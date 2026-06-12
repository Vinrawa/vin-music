package com.vinmusic.player

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.room.withTransaction
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import com.vinmusic.data.db.VinDatabase

import com.vinmusic.innertube.InnerTube
import com.vinmusic.innertube.VideoItem
import kotlinx.coroutines.*

@UnstableApi
object PlayerCacheManager {
    private const val TAG = "VIN_PLAYER_CACHE"

    data class CacheCheckResult(
        val isDownloadCacheValid: Boolean,
        val isPlayerCached: Boolean,
        val isCachedComplete: Boolean,
        val totalCachedBytes: Long,
        val isDeviceOnline: Boolean
    )

    fun isOnline(ctx: Context): Boolean {
        return try {
            val connectivityManager = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            false
        }
    }

    suspend fun checkCacheStatus(ctx: Context, database: VinDatabase, videoId: String): CacheCheckResult = withContext(Dispatchers.IO) {
        val localDownload = database.downloadDao().get(videoId)
        val isDownloadCompleted = localDownload?.status == "completed"
        val dlCache = if (isDownloadCompleted) PlayerSingleton.getDownloadCache(ctx) else null
        val dlCacheBytes = dlCache?.getCachedBytes(videoId, 0, -1) ?: 0L
        
        // Healing Mechanism: If DB says downloaded but actual cached bytes are missing, heal DB state!
        if (isDownloadCompleted && dlCacheBytes < 100_000L) {
            Log.w(TAG, "Download DB says completed, but cached bytes are missing (\$dlCacheBytes). Healing DB.")
            try {
                database.withTransaction {
                    database.downloadDao().delete(videoId)
                    val sig = database.interactionSignalDao().get(videoId)
                    if (sig != null) {
                        sig.isDownloaded = false
                        database.interactionSignalDao().insert(sig)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to heal database for song \$videoId: \${e.message}")
            }
        }

        val isDownloadCacheValid = isDownloadCompleted && dlCacheBytes > 100_000L
        val pCache = PlayerSingleton.getCache(ctx)
        val pCacheBytes = pCache?.getCachedBytes(videoId, 0, -1) ?: 0L
        val isPlayerCached = pCacheBytes > 1_000_000L
        val isCachedComplete = isDownloadCacheValid || isPlayerCached
        val totalCachedBytes = if (isDownloadCacheValid) dlCacheBytes else if (isPlayerCached) pCacheBytes else 0L
        val onlineState = isOnline(ctx)

        CacheCheckResult(
            isDownloadCacheValid = isDownloadCacheValid,
            isPlayerCached = isPlayerCached,
            isCachedComplete = isCachedComplete,
            totalCachedBytes = totalCachedBytes,
            isDeviceOnline = onlineState
        )
    }

    fun prefetchNextSongs(
        ctx: Context,
        scope: CoroutineScope,
        db: VinDatabase?,
        quality: String,
        nextSongs: List<VideoItem>,
        nextStreamUrlDeferred: Pair<String, Deferred<String?>>?
    ): Job {
        return scope.launch(Dispatchers.IO) {
            try {
                // Wait a bit to let the active song start playing smoothly
                delay(3000)
                
                for ((offset, nextSong) in nextSongs.withIndex()) {
                    // Prefetch thumbnail images using Coil in parallel
                    launch(Dispatchers.IO) {
                        try {
                            val loader = SingletonImageLoader.get(ctx)
                            val req1 = ImageRequest.Builder(ctx)
                                .data(nextSong.thumbnail)
                                .build()
                            loader.enqueue(req1)
                            
                            val req2 = ImageRequest.Builder(ctx)
                                .data(nextSong.thumbnailHd)
                                .build()
                            loader.enqueue(req2)
                            Log.d(TAG, "Prefetched thumbnails for next song: \${nextSong.title}")
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to prefetch thumbnail: \${e.message}")
                        }
                    }
                    
                    // Verify actual cached bytes exist in the download cache before skipping prefetch
                    val localDownload = db?.downloadDao()?.get(nextSong.videoId)
                    val isComplete = localDownload?.status == "completed"
                    val dlCache = if (isComplete) ctx.let { PlayerSingleton.getDownloadCache(it) } else null
                    val dlCacheBytes = dlCache?.getCachedBytes(nextSong.videoId, 0, -1) ?: 0L
                    if (isComplete && dlCacheBytes > 100_000L) {
                        Log.d(TAG, "prefetchNextSongs: Song \${nextSong.title} (offset \$offset) is downloaded offline. Skipping.")
                        continue
                    }
                    
                    // Check if player cache already has enough bytes
                    val pCache = PlayerSingleton.getCache(ctx)
                    val pCacheBytes = pCache?.getCachedBytes(nextSong.videoId, 0, -1) ?: 0L
                    if (pCacheBytes > 1_500_000L) {
                        Log.d(TAG, "prefetchNextSongs: Song \${nextSong.title} (offset \$offset) already cached. Skipping.")
                        continue
                    }
                    
                    // Get stream URL (either by waiting for nextStreamUrlDeferred or fetching it)
                    var streamUrl: String? = null
                    val deferredPair = nextStreamUrlDeferred
                    if (offset == 0 && deferredPair != null && deferredPair.first == nextSong.videoId) {
                        Log.d(TAG, "prefetchNextSongs: Found active stream URL prefetch deferred. Waiting...")
                        streamUrl = deferredPair.second.await()
                    } else {
                        Log.d(TAG, "prefetchNextSongs: Fetching stream URL for prefetch offset=\$offset...")
                        streamUrl = InnerTube.getStreamUrl(nextSong.videoId, quality)
                    }
                    
                    if (streamUrl.isNullOrBlank()) {
                        Log.d(TAG, "prefetchNextSongs: Song stream URL is empty for offset=\$offset. Skipping.")
                        continue
                    }
                    
                    val cache = PlayerSingleton.getCache(ctx) ?: continue
                    Log.d(TAG, "prefetchNextSongs: Starting prefetch of 2.5MB for song \${nextSong.title} (offset \$offset, videoId=\${nextSong.videoId})")
                    
                    val httpFactory = DefaultHttpDataSource.Factory()
                        .setUserAgent("com.google.android.apps.youtube.vr.oculus/1.60.19 (Linux; U; Android 12; GB) gzip")
                        .setConnectTimeoutMs(30_000)
                        .setReadTimeoutMs(30_000)
                        .setAllowCrossProtocolRedirects(true)
                        .setDefaultRequestProperties(mapOf(
                            "Origin"  to "https://www.youtube.com",
                            "Referer" to "https://www.youtube.com/"
                        ))
                    
                    val cacheDataSource = androidx.media3.datasource.cache.CacheDataSource.Factory()
                        .setCache(cache)
                        .setUpstreamDataSourceFactory(httpFactory)
                        .setFlags(androidx.media3.datasource.cache.CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                        .createDataSource()
                    
                    val dataSpec = androidx.media3.datasource.DataSpec.Builder()
                        .setUri(android.net.Uri.parse(streamUrl))
                        .setPosition(0)
                        .setLength(2_500_000L) // 2.5MB (Approx. 2 mins of audio)
                        .setKey(nextSong.videoId)
                        .build()
                    
                    val cacheWriter = androidx.media3.datasource.cache.CacheWriter(
                        cacheDataSource,
                        dataSpec,
                        null,
                        null
                    )
                    
                    cacheWriter.cache()
                    Log.d(TAG, "prefetchNextSongs: Successfully completed prefetch of 2.5MB for \${nextSong.title} (offset \$offset)")
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "prefetchNextSongs cancelled.")
            } catch (e: Exception) {
                Log.e(TAG, "prefetchNextSongs failed: \${e.message}", e)
            }
        }
    }
}
