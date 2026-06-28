package com.vinmusic.player

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.compose.runtime.*
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.room.withTransaction
import com.vinmusic.data.db.*
import com.vinmusic.innertube.InnerTube
import com.vinmusic.innertube.VideoItem
import com.vinmusic.recommendation.RecommendationRepository
import kotlinx.coroutines.*
import java.io.File
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap

/**
 * Singleton that holds the ONE ExoPlayer instance shared between
 * PlayerViewModel and VinMusicService (for proper system notifications).
 * Also maintains play queue and track state to support background playback.
 */
@UnstableApi
object PlayerSingleton {
    private const val TAG = "VIN_PLAYER"



    @Volatile private var _player: ExoPlayer? = null

    /** The active MediaSession (set by VinMusicService) */
    @Volatile var mediaSession: MediaSession? = null
    @Volatile var notificationMediaItem: MediaItem? = null
    @Volatile var currentSongLikedForNotification: Boolean = false
    @Volatile var onNotificationActionsChanged: (() -> Unit)? = null

    val actionEvents = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 10)

    // ── Shared Playback States (Process-alive, UI observable) ────────────────
    var currentSong    by mutableStateOf<VideoItem?>(null)
    var isPlaying      by mutableStateOf(false)
    var isLoading      by mutableStateOf(false)
    var errorMessage   by mutableStateOf<String?>(null)
    var queue          by mutableStateOf<List<VideoItem>>(emptyList())
    var queueIndex     by mutableIntStateOf(-1)
    var repeat         by mutableStateOf(false)
    var shuffle        by mutableStateOf(false)
    private var _smartShuffle by mutableStateOf(false)
    val smartShuffle get() = _smartShuffle
    var smartAutoplayEnabled by mutableStateOf(true)
    var isAutoplayLoading by mutableStateOf(false)

    // Stored playback parameters — survive across song transitions
    var storedSpeed by mutableFloatStateOf(1.0f)
    var storedPitch by mutableFloatStateOf(1.0f)

    val eightDAudioProcessor = EightDAudioProcessor()
    val audioFeatureProcessor = AudioFeatureProcessor()
    val firestoreRecommendationManager = com.vinmusic.recommendation.FirestoreRecommendationManager()
    var is8dEnabled by mutableStateOf(false)
        private set

    private var virtualizer: android.media.audiofx.Virtualizer? = null
    private var presetReverb: android.media.audiofx.PresetReverb? = null

    var nextStreamUrlDeferred: Pair<String, Deferred<String?>>? = null

    private var db: VinDatabase? = null
    private var recommendationRepository: RecommendationRepository? = null
    private var prefs: android.content.SharedPreferences? = null
    internal var context: Context? = null

    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null
    private var releaseWakeLockJob: Job? = null

    private fun acquireWakeLocks(ctx: Context) {
        releaseWakeLockJob?.cancel()
        scope.launch(Dispatchers.IO) {
            try {
                if (wakeLock == null) {
                    val powerManager = ctx.applicationContext.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                    wakeLock = powerManager.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "VinMusic::TransitionWakeLock").apply {
                        setReferenceCounted(false)
                    }
                }
                wakeLock?.acquire(60 * 60 * 1000L) // 1 hour timeout (resilient background)
                Log.d(TAG, "WakeLock acquired")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to acquire WakeLock: ${e.message}")
            }

            try {
                if (wifiLock == null) {
                    val wifiManager = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
                    val lockType = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF
                    } else {
                        @Suppress("DEPRECATION")
                        android.net.wifi.WifiManager.WIFI_MODE_FULL
                    }
                    wifiLock = wifiManager.createWifiLock(lockType, "VinMusic::TransitionWifiLock").apply {
                        setReferenceCounted(false)
                    }
                }
                @Suppress("DEPRECATION")
                wifiLock?.acquire()
                Log.d(TAG, "WifiLock acquired")
                scope.launch(Dispatchers.IO) {
                    delay(30_000)
                    try {
                        if (wifiLock?.isHeld == true) {
                            wifiLock?.release()
                            Log.d(TAG, "WifiLock safety timeout released")
                        }
                    } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to acquire WifiLock: ${e.message}")
            }
        }
    }

    private fun releaseWakeLocks() {
        releaseWakeLockJob?.cancel()
        releaseWakeLockJob = scope.launch(Dispatchers.IO) {
            delay(5000) // 5 second delay to survive gapless transitions in background
            try {
                if (wakeLock?.isHeld == true) {
                    wakeLock?.release()
                    Log.d(TAG, "WakeLock released")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to release WakeLock: ${e.message}")
            }

            try {
                if (wifiLock?.isHeld == true) {
                    wifiLock?.release()
                    Log.d(TAG, "WifiLock released")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to release WifiLock: ${e.message}")
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var fetchJob: Job? = null
    private var errorRetryCount = 0
    private var prefetchCacheJob: Job? = null
    private var playStartTime: Long = 0L
    private var previousSongId: String? = null
    var hasLoggedCompleteForCurrent: Boolean = false

    var onSongEndedCallback: (() -> Boolean)? = null

    init {
        scope.launch {
            actionEvents.collect { action ->
                when (action) {
                    "NEXT" -> playNext()
                    "PREV" -> playPrev()
                    "LIKE" -> currentSong?.let { toggleLike(it) }
                    "REPEAT" -> repeat = !repeat
                }
            }
        }
    }

    fun getOrCreate(context: Context): ExoPlayer {
        return _player ?: synchronized(this) {
            _player ?: run {
                val ctx = context.applicationContext
                this.context = ctx
                val databaseInstance = VinDatabase.getInstance(ctx)
                db = databaseInstance
                val recDb = com.vinmusic.recommendation.RecommendationDatabase.getInstance(ctx)
                recommendationRepository = RecommendationRepository(ctx, databaseInstance, recDb, firestoreRecommendationManager)
                prefs = ctx.getSharedPreferences("vin_music_prefs", Context.MODE_PRIVATE)
                smartAutoplayEnabled = prefs?.getBoolean("smart_autoplay", true) ?: true
                _smartShuffle = prefs?.getBoolean("smart_shuffle", false) ?: false

                
                buildPlayer(ctx).also { playerInstance ->
                    _player = playerInstance
                    setupPlayerListener(playerInstance)
                    triggerOfflineSync()
                }
            }
        }
    }

    private fun setupPlayerListener(playerInstance: ExoPlayer) {
        playerInstance.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                Log.d(TAG, "isPlaying = $playing")
                if (playing) {
                    context?.let { acquireWakeLocks(it) }
                } else {
                    releaseWakeLocks()
                }
                context?.let { com.vinmusic.widget.MusicWidgetProvider.updateAllWidgets(it) }
            }
            override fun onPlaybackStateChanged(state: Int) {
                val stateStr = when(state) {
                    Player.STATE_IDLE      -> "IDLE"
                    Player.STATE_BUFFERING -> "BUFFERING"
                    Player.STATE_READY     -> "READY"
                    Player.STATE_ENDED     -> "ENDED"
                    else -> "UNKNOWN($state)"
                }
                Log.d(TAG, "PlaybackState = $stateStr")
                when (state) {
                    Player.STATE_READY     -> {
                        isLoading  = false
                        errorMessage = null
                        // Reset the per-error retry budget on a successful load so a
                        // couple of lifetime errors don't permanently disable retry.
                        errorRetryCount = 0
                        // Re-apply stored playback parameters after song loads
                        reapplyPlaybackParameters()
                        prefetchNextSong()
                    }
                    Player.STATE_BUFFERING -> isLoading = true
                    Player.STATE_ENDED     -> onSongEnded()
                    Player.STATE_IDLE      -> releaseWakeLocks()
                }
            }
                        override fun onPlayerError(error: PlaybackException) {
                val msg = error.message ?: "unknown"
                val cause = error.cause?.message ?: "no cause"
                Log.e(TAG, "PlayerError: $msg | cause: $cause", error)
                
                val song = currentSong
                if (errorRetryCount < 2 && song != null) {
                    val pos = playerInstance.currentPosition.coerceAtLeast(0)
                    Log.d(TAG, "Retrying playback due to Source error... (retryCount=$errorRetryCount, pos=$pos)")
                    errorRetryCount++
                    scope.launch {
                        nextStreamUrlDeferred = null
                        playSong(song, startPositionMs = pos)
                    }
                    return
                }
                
                isLoading    = false
                errorMessage = "Playback error. Tap a song to retry."
                playerInstance.stop()
                releaseWakeLocks()
            }
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                Log.d(TAG, "onAudioSessionIdChanged = $audioSessionId")
                if (is8dEnabled) {
                    setupAudioEffects(audioSessionId)
                }
            }
        })
    }

    private fun prefetchNextSong() {
        if (queue.isEmpty()) return
        
        scope.launch {
            try {
                // Determine next index and next song
                val nextIndex = if (shuffle) {
                    if (smartShuffle) getSmartShuffleNextIndex() else queue.indices.random()
                } else {
                    (queueIndex + 1) % queue.size
                }
                
                if (nextIndex !in queue.indices) return@launch
                var nextSong = queue[nextIndex]
                
                // If it's the last song in the queue and smart autoplay is enabled, prefetch recommendations
                if (queueIndex == queue.size - 1 && !repeat && smartAutoplayEnabled) {
                    val seedSong = currentSong ?: queue[queueIndex]
                    Log.d(TAG, "Prefetching autoplay recommendations for seed=${seedSong.videoId}")
                    val currentQueueCopy = queue.toList()
                    val recommended = withContext(Dispatchers.IO) {
                        recommendationRepository?.getSongRadio(seedSong.videoId, seedSong.title, seedSong.author, currentQueueCopy)
                    }
                    if (!recommended.isNullOrEmpty()) {
                        withContext(Dispatchers.Main) {
                            val newQueue = queue.toMutableList()
                            val existingIds = newQueue.map { it.videoId }.toSet()
                            val uniqueRecs = recommended.filter { it.videoId !in existingIds }
                            if (uniqueRecs.isNotEmpty()) {
                                newQueue.addAll(uniqueRecs)
                                queue = newQueue
                                Log.d(TAG, "Autoplay recommendations appended to queue during prefetch")
                                
                                // Re-evaluate next song with newly appended items
                                val newNextIndex = if (shuffle) {
                                    if (smartShuffle) getSmartShuffleNextIndex() else queue.indices.random()
                                } else {
                                    (queueIndex + 1) % queue.size
                                }
                                if (newNextIndex in queue.indices) {
                                    nextSong = queue[newNextIndex]
                                }
                            }
                        }
                    }
                }
                
                // If we already have a prefetch running or completed for this next song, skip
                if (nextStreamUrlDeferred?.first == nextSong.videoId) {
                    Log.d(TAG, "Prefetch already active or complete for next song videoId=${nextSong.videoId}")
                    return@launch
                }
                
                // Verify actual cached bytes exist before skipping network prefetching!
                val isDownloaded = withContext(Dispatchers.IO) {
                    val ctx = context ?: return@withContext false
                    val isComplete = db?.downloadDao()?.get(nextSong.videoId)?.status == "completed"
                    if (isComplete) {
                        val dlCache = getDownloadCache(ctx)
                        val dlCacheBytes = dlCache?.getCachedBytes(nextSong.videoId, 0, -1) ?: 0L
                        dlCacheBytes > 100_000L
                    } else {
                        false
                    }
                }
                if (isDownloaded) {
                    Log.d(TAG, "Next song ${nextSong.title} is downloaded and cache is valid. Skipping network prefetch.")
                    return@launch
                }
                
                Log.d(TAG, "Prefetching stream URL for next song: ${nextSong.title} (videoId=${nextSong.videoId})")
                val quality = prefs?.getString("streaming_quality", "High (256kbps)") ?: "High (256kbps)"
                
                val deferred = async(Dispatchers.IO) {
                    try {
                        InnerTube.getStreamUrl(nextSong.videoId, quality)
                    } catch (e: Exception) {
                        Log.e(TAG, "Prefetch failed for videoId=${nextSong.videoId}: ${e.message}")
                        null
                    }
                }
                
                nextStreamUrlDeferred = Pair(nextSong.videoId, deferred)
                Log.d(TAG, "Prefetch successfully scheduled for videoId=${nextSong.videoId}")
            } catch (e: Exception) {
                Log.e(TAG, "Error in prefetchNextSong: ${e.message}")
            }
        }
    }

    val player: ExoPlayer get() = requireNotNull(_player) { "Player not initialized — call getOrCreate first" }

    @Volatile private var _cache: androidx.media3.datasource.cache.SimpleCache? = null
    @Volatile private var _cacheFailed = false
    @Volatile private var _dbProvider: androidx.media3.database.DatabaseProvider? = null

    @Synchronized
    private fun getDatabaseProvider(context: Context): androidx.media3.database.DatabaseProvider {
        return _dbProvider ?: androidx.media3.database.StandaloneDatabaseProvider(context.applicationContext).also { _dbProvider = it }
    }

    @Synchronized
    fun getCache(context: Context): androidx.media3.datasource.cache.SimpleCache? {
        if (_cacheFailed) return null
        return _cache ?: run {
            try {
                val cacheDir = File(context.cacheDir, "player_cache")
                val evictor = androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor(1024L * 1024L * 1024L) // 1 GB Cache
                val dbProvider = getDatabaseProvider(context)
                androidx.media3.datasource.cache.SimpleCache(cacheDir, evictor, dbProvider).also { _cache = it }
            } catch (e: Exception) {
                Log.e(TAG, "SimpleCache init failed: ${e.message}. Dynamic cache disabled.", e)
                _cacheFailed = true
                null
            }
        }
    }

    @Volatile private var _downloadCache: androidx.media3.datasource.cache.SimpleCache? = null
    @Volatile private var _downloadCacheFailed = false

    @Synchronized
    fun getDownloadCache(context: Context): androidx.media3.datasource.cache.SimpleCache? {
        if (_downloadCacheFailed) return null
        return _downloadCache ?: run {
            try {
                val downloadCacheDir = File(context.filesDir, "downloads")
                val evictor = androidx.media3.datasource.cache.NoOpCacheEvictor()
                val dbProvider = getDatabaseProvider(context)
                androidx.media3.datasource.cache.SimpleCache(downloadCacheDir, evictor, dbProvider).also { _downloadCache = it }
            } catch (e: Exception) {
                Log.e(TAG, "SimpleCache downloads init failed: ${e.message}.", e)
                _downloadCacheFailed = true
                null
            }
        }
    }

    private fun createDynamicHttpDataSourceFactory(): androidx.media3.datasource.DataSource.Factory {
        return androidx.media3.datasource.DataSource.Factory {
            object : androidx.media3.datasource.DataSource {
                private var currentDataSource: androidx.media3.datasource.DataSource? = null
                override fun addTransferListener(transferListener: androidx.media3.datasource.TransferListener) {}
                override fun open(dataSpec: androidx.media3.datasource.DataSpec): Long {
                    val url = dataSpec.uri.toString()
                    val resolvedUa = com.vinmusic.innertube.InnerTube.getUserAgentForUrl(url)
                    val requestProps = buildMap<String, String> {
                        if (resolvedUa.startsWith("Mozilla")) {
                            put("Origin", "https://www.youtube.com")
                            put("Referer", "https://www.youtube.com/")
                        }
                    }
                    val source = androidx.media3.datasource.DefaultHttpDataSource.Factory()
                        .setUserAgent(resolvedUa)
                        .setConnectTimeoutMs(30_000)
                        .setReadTimeoutMs(30_000)
                        .setAllowCrossProtocolRedirects(true)
                        .setDefaultRequestProperties(requestProps)
                        .createDataSource()
                    currentDataSource = source
                    return source.open(dataSpec)
                }
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    return currentDataSource?.read(buffer, offset, length) ?: -1
                }
                override fun getUri(): android.net.Uri? = currentDataSource?.uri
                override fun close() {
                    currentDataSource?.close()
                }
            }
        }
    }

    private fun buildPlayer(ctx: Context): ExoPlayer {
        val dynamicHttpFactory = createDynamicHttpDataSourceFactory()
        val cacheObj = getCache(ctx)
        val dataSourceFactory = if (cacheObj != null) {
            Log.d(TAG, "ExoPlayer initialized with 1GB automatic cache")
            androidx.media3.datasource.cache.CacheDataSource.Factory()
                .setCache(cacheObj)
                .setUpstreamDataSourceFactory(dynamicHttpFactory)
                .setFlags(androidx.media3.datasource.cache.CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        } else {
            Log.d(TAG, "ExoPlayer initialized with standard direct streaming (no-cache fallback)")
            dynamicHttpFactory
        }

        val renderersFactory = object : androidx.media3.exoplayer.DefaultRenderersFactory(ctx) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): androidx.media3.exoplayer.audio.AudioSink? {
                return androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                    .setAudioProcessors(arrayOf(eightDAudioProcessor, audioFeatureProcessor))
                    .build()
            }
        }

        return ExoPlayer.Builder(ctx, renderersFactory)
            .setLooper(android.os.Looper.getMainLooper())
            .setMediaSourceFactory(DefaultMediaSourceFactory(ctx).setDataSourceFactory(dataSourceFactory))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(), true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK) // Keep CPU awake for streaming in background
            .build().apply {
                skipSilenceEnabled = true // Enable gapless/skip silence
            }
    }

    // ── Queue & Playback Functions (Service/Background Safe) ─────────────────

    private fun buildMediaMetadata(song: VideoItem, artBytes: ByteArray? = null): MediaMetadata {
        val builder = MediaMetadata.Builder()
            .setTitle(song.title)
            .setArtist(song.author)

        if (artBytes != null) {
            builder.setArtworkData(artBytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
        } else {
            val artUrl = song.thumbnailHd.takeIf { it.isNotBlank() } ?: song.thumbnail
            if (artUrl.isNotBlank()) {
                builder.setArtworkUri(android.net.Uri.parse(artUrl))
            }
        }

        return builder.build()
    }

    private fun buildMediaItem(song: VideoItem, url: String? = null, artBytes: ByteArray? = null): MediaItem {
        val builder = MediaItem.Builder()
            .setMediaId(song.videoId)
            .setMediaMetadata(buildMediaMetadata(song, artBytes))

        if (url != null) {
            builder
                .setUri(url)
                .setCustomCacheKey(song.videoId)
        }

        return builder.build()
    }

    // ── Artwork Helpers ──────────────────────────────────────────────────────────

    /**
     * Try to load artwork bytes from Coil's disk cache.
     * Coil caches thumbnails when they're loaded in the UI, so this avoids
     * a network round-trip for songs the user has already seen.
     */
    private suspend fun loadArtBytesFromCoilCache(ctx: Context, videoId: String, thumbnail: String, thumbnailHd: String): ByteArray? {
        return try {
            val loader = SingletonImageLoader.get(ctx)
            // Try HD first, then standard
            val urls = listOfNotNull(
                thumbnailHd.takeIf { it.isNotBlank() },
                thumbnail.takeIf { it.isNotBlank() },
                "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
            )
            for (url in urls) {
                val request = ImageRequest.Builder(ctx)
                    .data(url)
                    .allowHardware(false)
                    .build()
                val result = loader.execute(request)
                if (result is SuccessResult) {
                    val bitmap = result.image.toBitmap()
                    val stream = java.io.ByteArrayOutputStream()
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, stream)
                    return stream.toByteArray()
                }
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "Coil cache artwork load failed: ${e.message}")
            null
        }
    }

    /**
     * Save artwork bytes to local thumbnail file for future instant loading
     * (both online and offline playback).
     */
    private fun saveArtBytesToLocal(ctx: Context?, videoId: String, artBytes: ByteArray) {
        if (ctx == null || artBytes.isEmpty()) return
        try {
            val thumbnailDir = File(ctx.filesDir, "thumbnails")
            if (!thumbnailDir.exists()) thumbnailDir.mkdirs()
            val thumbnailFile = File(thumbnailDir, "$videoId.jpg")
            if (!thumbnailFile.exists() || thumbnailFile.length() == 0L) {
                thumbnailFile.writeBytes(artBytes)
                Log.d(TAG, "Saved artwork to local thumbnail file: ${thumbnailFile.absolutePath}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save artwork locally: ${e.message}")
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun playSong(song: VideoItem, startPositionMs: Long = 0L) {
        context?.let { acquireWakeLocks(it) }
        fetchJob?.cancel()
        val safeStartPositionMs = startPositionMs.coerceAtLeast(0L)

        // Log end of previous song
        recordEnd()

        currentSong   = song
        currentSongLikedForNotification = false
        onNotificationActionsChanged?.invoke()
        context?.let { com.vinmusic.widget.MusicWidgetProvider.updateAllWidgets(it) }
        isLoading     = true
        isPlaying     = false
        errorMessage  = null
        val prefetchedUrlDeferred = nextStreamUrlDeferred
        nextStreamUrlDeferred = null

        notificationMediaItem = buildMediaItem(song)



        // Reset tracking for new song
        hasLoggedCompleteForCurrent = false
        previousSongId = song.videoId
        playStartTime = System.currentTimeMillis()
        recordPlay(song)
        checkAndClaimFeatures(song)

        val database = db ?: return

        scope.launch(Dispatchers.IO) {
            val liked = database.likedSongDao().isLiked(song.videoId)
            withContext(Dispatchers.Main) {
                if (currentSong?.videoId == song.videoId) {
                    currentSongLikedForNotification = liked
                    onNotificationActionsChanged?.invoke()
                }
            }
        }

        // Save to history + Metrolist-style related cache for recommendations
        scope.launch(Dispatchers.IO) {
            database.historyDao().insert(HistoryEntry(song.videoId, song.title, song.author, null, song.durationText))
            recommendationRepository?.touchSongPlayMeta(song)
            recommendationRepository?.cacheRelatedForSong(song.videoId)
        }

        fetchJob = scope.launch {
            try {
                Log.d(TAG, "Fetching stream/download for videoId=${song.videoId}")

                // 1. Offload SimpleCache queries, DB checks, and network checks entirely to Dispatchers.IO to avoid blocking the Main UI Thread and causing stuttering!
                val cacheResult = withContext(Dispatchers.IO) {
                    val ctx = context ?: return@withContext PlayerCacheManager.CacheCheckResult(false, false, false, 0L, false)
                    PlayerCacheManager.checkCacheStatus(ctx, database, song.videoId)
                }

                val isDownloadCacheValid = cacheResult.isDownloadCacheValid
                val isPlayerCached = cacheResult.isPlayerCached
                var isCachedComplete = cacheResult.isCachedComplete
                var totalCachedBytes = cacheResult.totalCachedBytes
                val isDeviceOnline = cacheResult.isDeviceOnline

                Log.d(TAG, "Cache check: isDownloadCacheValid=$isDownloadCacheValid isPlayerCached=$isPlayerCached isCachedComplete=$isCachedComplete totalCachedBytes=$totalCachedBytes isDeviceOnline=$isDeviceOnline")

                var url: String
                var artBytes: ByteArray? = null
                var onlineAndCached = isCachedComplete && isDeviceOnline

                // If song is fully downloaded, play it instantly from local cache (offline style) even if online
                if (isDownloadCacheValid) {
                    Log.d(TAG, "Playing fully downloaded song instantly: videoId=${song.videoId}")
                    url = "https://music.youtube.com/cache/${song.videoId}"
                    onlineAndCached = false
                } else if (isCachedComplete && !onlineAndCached) {
                    Log.d(TAG, "Playing fully cached offline song (device is offline): videoId=${song.videoId}")
                    url = "https://music.youtube.com/cache/${song.videoId}"

                    val ctx = context
                    if (ctx != null) {
                        try {
                            val database = com.vinmusic.data.db.VinDatabase.getInstance(ctx)
                            val dl = database.downloadDao().get(song.videoId)
                            if (dl?.thumbnailPath != null) {
                                val file = java.io.File(dl.thumbnailPath)
                                if (file.exists() && file.length() > 0) artBytes = file.readBytes()
                            }
                            if (artBytes == null) {
                                val cachePath = java.io.File(ctx.filesDir, "thumbnails/${song.videoId}.jpg")
                                if (cachePath.exists() && cachePath.length() > 0) artBytes = cachePath.readBytes()
                            }
                            // Fallback: try Coil disk cache for offline songs too
                            if (artBytes == null) {
                                artBytes = loadArtBytesFromCoilCache(ctx, song.videoId, song.thumbnail, song.thumbnailHd)
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Offline artwork load failed: ${e.message}")
                        }
                    }
                } else {
                    errorMessage = null
                    // Fetch stream URL and artwork bytes in parallel
                    val urlDeferred = if (prefetchedUrlDeferred != null && prefetchedUrlDeferred.first == song.videoId) {
                        prefetchedUrlDeferred.second
                    } else {
                        val quality = prefs?.getString("streaming_quality", "High (256kbps)") ?: "High (256kbps)"
                        async(Dispatchers.IO) {
                            try {
                                InnerTube.getStreamUrl(song.videoId, quality)
                            } catch (e: Exception) {
                                null
                            }
                        }
                    }
                    
                    // 1. Immediately try to load local cached artwork for instant notification cover
                    val ctx = context
                    if (ctx != null) {
                        try {
                            val database = com.vinmusic.data.db.VinDatabase.getInstance(ctx)
                            val dl = database.downloadDao().get(song.videoId)
                            if (dl?.thumbnailPath != null) {
                                val file = java.io.File(dl.thumbnailPath)
                                if (file.exists() && file.length() > 0) artBytes = file.readBytes()
                            }
                            // Fallback: check standard filesDir directory
                            if (artBytes == null) {
                                val cachePath = java.io.File(ctx.filesDir, "thumbnails/${song.videoId}.jpg")
                                if (cachePath.exists() && cachePath.length() > 0) artBytes = cachePath.readBytes()
                            }
                            // Fallback: try Coil disk cache (populated from UI browsing)
                            if (artBytes == null) {
                                artBytes = loadArtBytesFromCoilCache(ctx, song.videoId, song.thumbnail, song.thumbnailHd)
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Artwork disk load failed: ${e.message}")
                        }
                    }

                    // 2. IMMEDIATELY update notification with artwork bytes (before stream URL fetch)
                    // This makes the cover appear instantly instead of waiting 2-5s for stream URL
                    if (artBytes != null) {
                        withContext(Dispatchers.Main) {
                            if (currentSong?.videoId == song.videoId) {
                                notificationMediaItem = buildMediaItem(song, null, artBytes)
                                // ForwardingPlayer reads notificationMediaItem automatically;
                                // the notification will pick up artwork bytes when it refreshes
                                // after the stream URL is set.
                            }
                        }
                        // Save to local thumbnail file for future offline use
                        saveArtBytesToLocal(ctx, song.videoId, artBytes)
                    }

                    var fetchedUrl = urlDeferred.await()
                    
                    // Graceful Fallback: If network stream fetch fails but song is cached, fall back to offline playback instead of failing!
                    if (fetchedUrl == null && isCachedComplete) {
                        Log.w(TAG, "Online stream fetch failed for cached song. Falling back to local offline playback.")
                        onlineAndCached = false
                        fetchedUrl = "https://music.youtube.com/cache/${song.videoId}"
                    }

                    if (fetchedUrl == null) {
                        isLoading    = false
                        errorMessage = "Couldn't load this track. Check your connection and try again."
                        Log.e(TAG, "Stream URL is NULL for ${song.videoId} | ${InnerTube.lastDebugMsg}")
                        return@launch
                    }
                    url = fetchedUrl
                }

                withContext(Dispatchers.Main) {
                    val ctx = context ?: return@withContext
                    // Ensure service is running
                    try {
                        val intent = android.content.Intent(ctx, VinMusicService::class.java)
                        ctx.startService(intent)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to start VinMusicService in playSong: ${e.message}")
                    }

                    val mediaItem = buildMediaItem(song, url, artBytes)
                    notificationMediaItem = mediaItem

                    Log.d(TAG, "Setting media item: ${url.take(80)}")
                    player.playWhenReady = true
                    
                    if (isCachedComplete) {
                        val cache = if (isDownloadCacheValid) getDownloadCache(ctx) else getCache(ctx)
                        if (cache != null) {
                            if (onlineAndCached) {
                                Log.d(TAG, "Online Cached playback: prioritizing local cache with network fallback for videoId=${song.videoId}")
                                val cacheFactory = androidx.media3.datasource.cache.CacheDataSource.Factory()
                                    .setCache(cache)
                                    .setUpstreamDataSourceFactory(createDynamicHttpDataSourceFactory()) // Stream if there's any gap!
                                    .setCacheKeyFactory { dataSpec ->
                                        dataSpec.key ?: song.videoId
                                    }
                                    .setFlags(androidx.media3.datasource.cache.CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                                val mediaSource = androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(cacheFactory)
                                    .createMediaSource(mediaItem)
                                player.setMediaSource(mediaSource, safeStartPositionMs)
                            } else {
                                Log.d(TAG, "Offline playback: using cache=${if (isDownloadCacheValid) "download" else "player"}, totalCachedBytes=$totalCachedBytes for videoId=${song.videoId}")
                                val cacheFactory = androidx.media3.datasource.cache.CacheDataSource.Factory()
                                    .setCache(cache)
                                    .setCacheKeyFactory { dataSpec ->
                                        dataSpec.key ?: song.videoId
                                    }
                                    .setUpstreamDataSourceFactory {
                                        // Fixed upstream that reports bytesRemaining correctly relative to requested position
                                        object : androidx.media3.datasource.DataSource {
                                            private var uri: android.net.Uri? = null
                                            private var bytesRemaining = 0L

                                            override fun addTransferListener(transferListener: androidx.media3.datasource.TransferListener) {}
                                            
                                            override fun open(dataSpec: androidx.media3.datasource.DataSpec): Long {
                                                uri = dataSpec.uri
                                                val position = dataSpec.position
                                                bytesRemaining = if (totalCachedBytes > position) {
                                                    totalCachedBytes - position
                                                } else {
                                                    0L
                                                }
                                                Log.d(TAG, "Offline upstream open: position=$position, totalCachedBytes=$totalCachedBytes, bytesRemaining=$bytesRemaining")
                                                return bytesRemaining
                                            }
                                            
                                            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                                                if (bytesRemaining <= 0) {
                                                    return androidx.media3.common.C.RESULT_END_OF_INPUT
                                                }
                                                Log.w(TAG, "Offline cache miss at remaining=$bytesRemaining, requesting length=$length")
                                                return androidx.media3.common.C.RESULT_END_OF_INPUT
                                            }
                                            
                                            override fun getUri(): android.net.Uri? = uri
                                            override fun close() {}
                                        }
                                    }
                                val mediaSource = androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(cacheFactory)
                                    .createMediaSource(mediaItem)
                                player.setMediaSource(mediaSource, safeStartPositionMs)
                            }
                        } else {
                            player.setMediaItem(mediaItem, safeStartPositionMs)
                        }
                    } else {
                        player.setMediaItem(mediaItem, safeStartPositionMs)
                    }
                    
                    player.prepare()
                    player.playWhenReady = true
                    errorMessage = null
                    prefetchNextSongs()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "Error in playSong fetch: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    isLoading = false
                    errorMessage = "Couldn't play this track. Check your connection and try again."
                    releaseWakeLocks()
                }
            }
        }
    }

    fun setQueue(songs: List<VideoItem>, startIndex: Int = 0) {
        queue      = songs
        queueIndex = startIndex.coerceIn(0, (songs.size - 1).coerceAtLeast(0))
        if (songs.isNotEmpty()) playSong(songs[queueIndex])
    }

    fun playNext() {
        if (queue.isEmpty()) return
        
        // Spotify Radio Mode / Smart Autoplay
        if (queueIndex == queue.size - 1 && !repeat && smartAutoplayEnabled) {
            val seedSong = currentSong ?: queue[queueIndex]
            isAutoplayLoading = true
            scope.launch {
                try {
                    val currentQueueCopy = queue.toList()
                    val recommended = recommendationRepository?.getSongRadio(seedSong.videoId, seedSong.title, seedSong.author, currentQueueCopy)
                    if (!recommended.isNullOrEmpty()) {
                        val newQueue = queue.toMutableList()
                        val existingIds = newQueue.map { it.videoId }.toSet()
                        val uniqueRecs = recommended.filter { it.videoId !in existingIds }
                        
                        if (uniqueRecs.isNotEmpty()) {
                            newQueue.addAll(uniqueRecs)
                        }
                        queue = newQueue
                        
                        queueIndex++
                        withContext(Dispatchers.Main) {
                            playSong(queue[queueIndex])
                        }
                    } else {
                        if (!repeat && queueIndex == queue.size - 1) {
                            withContext(Dispatchers.Main) { _player?.pause() }
                            return@launch
                        }
                        val next = if (shuffle) {
                            if (smartShuffle) getSmartShuffleNextIndex() else queue.indices.random()
                        } else 0
                        if (next < 0 || next >= queue.size) return@launch
                        queueIndex = next
                        withContext(Dispatchers.Main) { playSong(queue[next]) }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to fetch autoplay recommendations: ${e.message}")
                    if (!repeat && queueIndex == queue.size - 1) {
                        withContext(Dispatchers.Main) { _player?.pause() }
                        return@launch
                    }
                    val next = if (shuffle) {
                        if (smartShuffle) getSmartShuffleNextIndex() else queue.indices.random()
                    } else 0
                    if (next < 0 || next >= queue.size) return@launch
                    queueIndex = next
                    withContext(Dispatchers.Main) { playSong(queue[next]) }
                } finally {
                    isAutoplayLoading = false
                }
            }
            return
        }

        if (shuffle && smartShuffle) {
            scope.launch {
                val next = getSmartShuffleNextIndex()
                if (next < 0 || next >= queue.size) return@launch
                withContext(Dispatchers.Main) {
                    queueIndex = next
                    playSong(queue[next])
                }
            }
            return
        }

        val next = if (shuffle) queue.indices.random() else (queueIndex + 1) % queue.size
        if (next == queueIndex && queue.size == 1 && !repeat) {
            _player?.pause()
            return
        }
        queueIndex = next
        playSong(queue[next])
    }

    fun playPrev() {
        if (queue.isEmpty()) return
        val activePlayer = _player ?: return
        if (activePlayer.currentPosition > 3000) { activePlayer.seekTo(0); return }
        val prev = if (queueIndex > 0) queueIndex - 1 else queue.size - 1
        queueIndex = prev
        playSong(queue[prev])
    }

    fun togglePlay() {
        val activePlayer = _player ?: return
        if (activePlayer.isPlaying) activePlayer.pause() else activePlayer.play()
    }

    fun seekTo(fraction: Float) {
        val duration = player.duration
        if (duration > 0) player.seekTo((fraction * duration).toLong())
    }

    fun seekToMs(ms: Long) {
        player.seekTo(ms)
    }

    /** Re-apply stored speed/pitch after song transition so params don't reset */
    private fun reapplyPlaybackParameters() {
        if (storedSpeed == 1.0f && storedPitch == 1.0f) return // nothing to reapply
        try {
            player.playbackParameters = androidx.media3.common.PlaybackParameters(storedSpeed, storedPitch)
            Log.d(TAG, "Re-applied playback params: speed=$storedSpeed, pitch=$storedPitch")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reapply playback parameters: ${e.message}")
        }
    }

    fun setSmartAutoplay(enabled: Boolean) {
        smartAutoplayEnabled = enabled
        prefs?.edit()?.putBoolean("smart_autoplay", enabled)?.apply()
    }

    fun setSmartShuffle(enabled: Boolean) {
        _smartShuffle = enabled
        prefs?.edit()?.putBoolean("smart_shuffle", enabled)?.apply()
    }

    private suspend fun getSmartShuffleNextIndex(): Int {
        val dbObj = db ?: return queue.indices.random()
        val profile = try {
            com.vinmusic.recommendation.RecommendationManager.buildTasteProfile(dbObj)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to build taste profile for smart shuffle: ${e.message}")
            null
        }
        
        if (profile == null) {
            return queue.indices.random()
        }

        val dna = profile.tasteDNA
        
        val candidates = queue.indices.filter { it != queueIndex }
        if (candidates.isEmpty()) return if (repeat) queueIndex else -1

        val scoredCandidates = candidates.map { idx ->
            val item = queue[idx]
            val meta = com.vinmusic.recommendation.RecommendationManager.getCachedOrInferredMetadata(dbObj, item)
            val score = com.vinmusic.recommendation.RecommendationManager.calculateTasteSimilarity(meta, dna)
            idx to score
        }

        val sorted = scoredCandidates.sortedByDescending { it.second }
        
        // Pick randomly from the top 3 or top 30% of candidates (whichever is larger)
        val poolSize = maxOf(3, (sorted.size * 0.3).toInt()).coerceAtMost(sorted.size)
        val bestCandidates = sorted.take(poolSize)
        
        return bestCandidates.random().first
    }

    private fun prefetchNextSongs() {
        val ctx = context ?: return
        if (queue.isEmpty()) return
        
        prefetchCacheJob?.cancel()
        
        val nextSongs = mutableListOf<VideoItem>()
        for (offset in 1..2) {
            val nextIndex = if (shuffle) {
                (queueIndex + offset) % queue.size
            } else {
                (queueIndex + offset) % queue.size
            }
            if (nextIndex in queue.indices) {
                nextSongs.add(queue[nextIndex])
            }
        }
        if (nextSongs.isEmpty()) return
        
        prefetchCacheJob = PlayerCacheManager.prefetchNextSongs(
            ctx = ctx,
            scope = scope,
            db = db,
            quality = prefs?.getString("streaming_quality", "High (256kbps)") ?: "High (256kbps)",
            nextSongs = nextSongs,
            nextStreamUrlDeferred = nextStreamUrlDeferred
        )
    }



    private fun onSongEnded() {
        if (onSongEndedCallback?.invoke() == true) {
            return
        }
        when {
            repeat -> player.seekTo(0)
            else -> playNext()
        }
    }

    fun toggleLike(song: VideoItem) {
        val database = db ?: return
        scope.launch(Dispatchers.IO) {
            val isCurrentlyLiked = database.likedSongDao().isLiked(song.videoId)
            val nextLiked = if (isCurrentlyLiked) {
                database.likedSongDao().delete(song.videoId)
                false
            } else {
                database.likedSongDao().insert(
                    LikedSong(song.videoId, song.title, song.author, song.durationText)
                )
                true
            }
            
            // Update interaction signal
            val sig = database.interactionSignalDao().get(song.videoId)
            if (sig != null) {
                sig.isLiked = nextLiked
                database.interactionSignalDao().insert(sig)
            } else {
                database.interactionSignalDao().insert(
                    InteractionSignal(
                        videoId = song.videoId,
                        title = song.title,
                        author = song.author,
                        durationText = song.durationText,
                        isLiked = nextLiked
                    )
                )
            }
            val songKey = com.vinmusic.recommendation.RecommendationManager.generateSongKey(song.author, song.title)
            val realCache = database.songFeatureCacheDao().get(songKey)
            if (realCache != null) {
                val newLikedCount = (realCache.likedByCount + (if (nextLiked) 1 else -1)).coerceAtLeast(0)
                val updatedCache = realCache.copy(likedByCount = newLikedCount)
                database.songFeatureCacheDao().insert(updatedCache)
                Log.d(TAG, "Updated local SongFeatureCache likedByCount to $newLikedCount for $songKey")
            }
            
            context?.let { ctx ->
                if (PlayerCacheManager.isOnline(ctx)) {
                    try {
                        val docRef = com.google.firebase.firestore.FirebaseFirestore.getInstance().collection("songs").document(songKey)
                        val incrementVal = if (nextLiked) 1L else -1L
                        docRef.set(
                            mapOf("likedByCount" to com.google.firebase.firestore.FieldValue.increment(incrementVal)),
                            com.google.firebase.firestore.SetOptions.merge()
                        )
                        Log.d(TAG, "Incremented Firestore likedByCount by $incrementVal for $songKey")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to update likedByCount in Firestore: ${e.message}")
                    }
                }
            }

            withContext(Dispatchers.Main) {
                if (currentSong?.videoId == song.videoId) {
                    currentSongLikedForNotification = nextLiked
                    onNotificationActionsChanged?.invoke()
                    context?.let { com.vinmusic.widget.MusicWidgetProvider.updateAllWidgets(it) }
                }
            }
        }
    }

    private fun recordPlay(song: VideoItem) {
        val database = db ?: return
        scope.launch(Dispatchers.IO) {
            val signal = database.interactionSignalDao().get(song.videoId) ?: InteractionSignal(
                videoId = song.videoId,
                title = song.title,
                author = song.author,
                durationText = song.durationText
            )
            signal.playCount += 1
            signal.lastPlayedAt = System.currentTimeMillis()
            if (previousSongId == song.videoId) {
                signal.repeatCount += 1
            }
            database.interactionSignalDao().insert(signal)
            com.vinmusic.recommendation.RecommendationManager.invalidateTasteProfile()

            // Log song playback event to Firebase Analytics
            context?.let { ctx ->
                val isOffline = database.downloadDao().get(song.videoId)?.status == "completed"
                com.vinmusic.analytics.AnalyticsHelper.logSongPlaybackStarted(
                    context = ctx,
                    videoId = song.videoId,
                    title = song.title,
                    artist = song.author,
                    isOffline = isOffline
                )
            }
        }
    }

    private fun recordEnd() {
        val database = db ?: return
        val prevId = previousSongId ?: return
        val playDuration = System.currentTimeMillis() - playStartTime
        scope.launch(Dispatchers.IO) {
            val signal = database.interactionSignalDao().get(prevId) ?: return@launch
            if (playDuration < 30_000 && !hasLoggedCompleteForCurrent) {
                signal.skipCount += 1
                if (playDuration < 20_000) {
                    signal.skip20sCount += 1
                }
                database.interactionSignalDao().insert(signal)
                com.vinmusic.recommendation.RecommendationManager.invalidateTasteProfile()
            }
        }
    }

    private fun parseDurationText(durationText: String?): Long {
        if (durationText.isNullOrBlank()) return 0L
        return try {
            val parts = durationText.split(":")
            var seconds = 0L
            for (part in parts) {
                seconds = seconds * 60 + part.toLong()
            }
            seconds * 1000L
        } catch (e: Exception) {
            0L
        }
    }

    private suspend fun getLocalFeatures(database: VinDatabase, songKey: String): com.vinmusic.data.db.SongFeatureCache? {
        return try {
            database.songFeatureCacheDao().get(songKey)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get local features for $songKey", e)
            null
        }
    }

    fun onAnalysisCompleted(
        songKey: String,
        bpm: Float?,
        energy: Float,
        genreTags: List<String>,
        moodTags: List<String>,
        title: String,
        artist: String,
        hasRealTags: Boolean
    ) {
        val database = db ?: return
        val ctx = context ?: return
        
        scope.launch(Dispatchers.IO) {
            try {
                val gson = com.google.gson.Gson()
                val genreTagsJson = gson.toJson(genreTags)
                val moodTagsJson = gson.toJson(moodTags)
                
                val cacheEntry = com.vinmusic.data.db.SongFeatureCache(
                    songKey = songKey,
                    bpmReal = if (bpm != null && bpm in 40f..250f) bpm else 0f,
                    energyReal = energy,
                    genreTags = genreTagsJson,
                    moodTags = moodTagsJson,
                    title = title,
                    artist = artist,
                    synced = false
                )
                database.songFeatureCacheDao().insert(cacheEntry)
                Log.d(TAG, "Saved features locally to Room for $songKey (hasRealTags=$hasRealTags)")
                
                val isAuthenticated = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser != null
                if (hasRealTags && PlayerCacheManager.isOnline(ctx) && isAuthenticated) {
                    firestoreRecommendationManager.completeAnalysis(
                        songKey = songKey,
                        bpm = bpm,
                        energy = energy,
                        genreTags = genreTags,
                        moodTags = moodTags,
                        title = title,
                        artist = artist
                    )
                    database.songFeatureCacheDao().markSynced(songKey)
                    Log.d(TAG, "Synced features to Firestore and marked synced locally for $songKey")
                } else {
                    Log.d(TAG, "Local features saved (synced=false) for $songKey. Sync skipped (hasRealTags=$hasRealTags)")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to complete analysis write for $songKey", e)
            }
        }
    }

    fun triggerOfflineSync() {
        val database = db ?: return
        val ctx = context ?: return
        val isAuthenticated = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser != null
        if (!PlayerCacheManager.isOnline(ctx) || !isAuthenticated) return
        
        scope.launch(Dispatchers.IO) {
            try {
                val unsynced = database.songFeatureCacheDao().getUnsynced()
                if (unsynced.isEmpty()) return@launch
                
                Log.d(TAG, "Found ${unsynced.size} unsynced audio features. Starting sync...")
                val gson = com.google.gson.Gson()
                for (item in unsynced) {
                    val genreType = object : com.google.gson.reflect.TypeToken<List<String>>() {}.type
                    var genres: List<String> = gson.fromJson(item.genreTags, genreType) ?: emptyList()
                    var moods: List<String> = gson.fromJson(item.moodTags, genreType) ?: emptyList()
                    var hasRealTags = false
                    
                    // Re-fetch real tags from Last.fm since we are online now
                    try {
                        val realTags = firestoreRecommendationManager.fetchLastFmTags(item.artist, item.title)
                        if (realTags != null) {
                            val realGenres = realTags["genres"] ?: emptyList()
                            val realMoods = realTags["moods"] ?: emptyList()
                            if (realGenres.isNotEmpty() || realMoods.isNotEmpty()) {
                                genres = realGenres
                                moods = realMoods
                                hasRealTags = true
                                // Update local Room database with real tags
                                val updatedItem = item.copy(
                                    genreTags = gson.toJson(realGenres),
                                    moodTags = gson.toJson(realMoods)
                                )
                                database.songFeatureCacheDao().insert(updatedItem)
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to re-fetch Last.fm tags during sync for ${item.songKey}: ${e.message}")
                    }
                    
                    if (hasRealTags) {
                        firestoreRecommendationManager.completeAnalysis(
                            songKey = item.songKey,
                            bpm = if (item.bpmReal in 40f..250f) item.bpmReal else null,
                            energy = item.energyReal,
                            genreTags = genres,
                            moodTags = moods,
                            title = item.title,
                            artist = item.artist
                        )
                        database.songFeatureCacheDao().markSynced(item.songKey)
                        Log.d(TAG, "Synced offline feature cache entry for ${item.songKey}")
                    } else {
                        Log.d(TAG, "Sync skipped for ${item.songKey} because real tags could not be fetched from Last.fm (remains unsynced)")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Offline sync failed: ${e.message}")
            }
        }
    }

    private fun checkAndClaimFeatures(song: VideoItem) {
        val database = db ?: return
        val songKey = com.vinmusic.recommendation.RecommendationManager.generateSongKey(song.author, song.title)
        val durationMs = parseDurationText(song.durationText)
        val ctx = context ?: return

        scope.launch(Dispatchers.IO) {
            try {
                // 1. Check local cache
                val local = database.songFeatureCacheDao().get(songKey)
                if (local != null) {
                    Log.d(TAG, "Song features found in local Room cache for key: $songKey")
                    withContext(Dispatchers.Main) {
                        if (currentSong?.videoId == song.videoId) {
                            audioFeatureProcessor.resetForSong(songKey, song.title, song.author, durationMs, shouldAnalyze = false)
                        }
                    }
                    triggerOfflineSync()
                    return@launch
                }

                // 2. Check remote Firestore
                val isAuthenticated = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser != null
                if (PlayerCacheManager.isOnline(ctx) && isAuthenticated) {
                    val remote = firestoreRecommendationManager.getSongMetadata(songKey)
                    if (remote != null) {
                        Log.d(TAG, "Song features found in remote Firestore for key: $songKey")
                        val bpm = (remote["bpmReal"] as? Number)?.toFloat() ?: 0f
                        val energy = (remote["energyReal"] as? Number)?.toFloat() ?: 0f
                        val title = remote["title"] as? String ?: ""
                        val artist = remote["artist"] as? String ?: ""
                        val genres = remote["genreTags"] as? List<*> ?: emptyList<Any>()
                        val moods = remote["moodTags"] as? List<*> ?: emptyList<Any>()
                        val likedByCount = (remote["likedByCount"] as? Number)?.toInt() ?: 0
                        
                        val gson = com.google.gson.Gson()
                        val genreTagsJson = gson.toJson(genres)
                        val moodTagsJson = gson.toJson(moods)
                        
                        val cacheEntry = com.vinmusic.data.db.SongFeatureCache(
                            songKey = songKey,
                            bpmReal = bpm,
                            energyReal = energy,
                            genreTags = genreTagsJson,
                            moodTags = moodTagsJson,
                            title = title,
                            artist = artist,
                            synced = true,
                            likedByCount = likedByCount
                        )
                        database.songFeatureCacheDao().insert(cacheEntry)
                        
                        withContext(Dispatchers.Main) {
                            if (currentSong?.videoId == song.videoId) {
                                audioFeatureProcessor.resetForSong(songKey, song.title, song.author, durationMs, shouldAnalyze = false)
                            }
                        }
                        return@launch
                    }
                }

                // 3. Cache Miss: Try to claim for analysis
                val shouldAnalyze = if (PlayerCacheManager.isOnline(ctx) && isAuthenticated) {
                    firestoreRecommendationManager.tryClaimForAnalysis(songKey)
                } else {
                    true
                }
                
                Log.d(TAG, "Cache miss for key: $songKey, tryClaimForAnalysis returned: $shouldAnalyze")
                withContext(Dispatchers.Main) {
                    if (currentSong?.videoId == song.videoId) {
                        audioFeatureProcessor.resetForSong(songKey, song.title, song.author, durationMs, shouldAnalyze = shouldAnalyze)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed in checkAndClaimFeatures for $songKey", e)
                withContext(Dispatchers.Main) {
                    if (currentSong?.videoId == song.videoId) {
                        audioFeatureProcessor.resetForSong(songKey, song.title, song.author, durationMs, shouldAnalyze = false)
                    }
                }
            }
        }
    }

    fun setEightDEnabled(enabled: Boolean) {
        is8dEnabled = enabled
        eightDAudioProcessor.enabled = enabled
        if (enabled) {
            val sessionId = _player?.audioSessionId ?: 0
            if (sessionId != C.AUDIO_SESSION_ID_UNSET && sessionId != 0) {
                setupAudioEffects(sessionId)
            }
        } else {
            releaseAudioEffects()
        }
    }

    private fun setupAudioEffects(sessionId: Int) {
        releaseAudioEffects()
        try {
            _player?.setAuxEffectInfo(androidx.media3.common.AuxEffectInfo(0, 0.0f))
            Log.d(TAG, "8D custom processor active on session $sessionId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear aux audio effects: ${e.message}")
        }
    }

    private fun releaseAudioEffects() {
        try {
            virtualizer?.enabled = false
            virtualizer?.release()
        } catch (_: Exception) {}
        virtualizer = null
        try {
            presetReverb?.enabled = false
            presetReverb?.release()
        } catch (_: Exception) {}
        presetReverb = null
        try {
            _player?.setAuxEffectInfo(androidx.media3.common.AuxEffectInfo(0, 0.0f))
        } catch (_: Exception) {}
    }
}
