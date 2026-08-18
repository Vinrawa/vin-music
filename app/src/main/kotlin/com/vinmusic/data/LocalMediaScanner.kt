package com.vinmusic.data

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import com.vinmusic.innertube.VideoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class LocalFolder(
    val name: String,
    val path: String,
    val songs: List<VideoItem>
)

data class LocalScanResult(
    val allSongs: List<VideoItem> = emptyList(),
    val folders: List<LocalFolder> = emptyList()
)

object LocalMediaScanner {
    private const val TAG = "LOCAL_SCANNER"

    private fun formatDuration(durationMs: Long): String {
        val totalSecs = (durationMs / 1000).coerceAtLeast(0)
        val mins = totalSecs / 60
        val secs = totalSecs % 60
        return "%d:%02d".format(mins, secs)
    }

    suspend fun scanLocalAudio(context: Context): LocalScanResult = withContext(Dispatchers.IO) {
        val songsList = mutableListOf<Pair<VideoItem, String>>()
        
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        val projection = mutableListOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DATA
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.Audio.Media.RELATIVE_PATH)
            }
        }.toTypedArray()

        // Filter out short notification/ringtone sounds (< 10s)
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} >= 10000"
        val sortOrder = "${MediaStore.Audio.Media.DATE_ADDED} DESC"

        try {
            context.contentResolver.query(
                collection,
                projection,
                selection,
                null,
                sortOrder
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val dataCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
                val relativePathCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    cursor.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
                } else -1

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val rawTitle = cursor.getString(titleCol) ?: "Unknown Track"
                    val rawArtist = cursor.getString(artistCol)?.takeIf { it != "<unknown>" && it.isNotBlank() } ?: "Unknown Artist"
                    val duration = cursor.getLong(durationCol)
                    val albumId = cursor.getLong(albumIdCol)
                    val dataPath = if (dataCol != -1) cursor.getString(dataCol) else null
                    val relPath = if (relativePathCol != -1) cursor.getString(relativePathCol) else null

                    val contentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id).toString()
                    val albumArtUri = "content://media/external/audio/albumart/$albumId"

                    // Clean folder extraction
                    val folderName = when {
                        !relPath.isNullOrBlank() -> {
                            val parts = relPath.trim('/', '\\').split('/', '\\').filter { it.isNotBlank() }
                            parts.lastOrNull() ?: "Internal Storage"
                        }
                        !dataPath.isNullOrBlank() -> {
                            try {
                                val parent = File(dataPath).parentFile
                                parent?.name?.takeIf { it.isNotBlank() } ?: "Music"
                            } catch (_: Exception) { "Music" }
                        }
                        else -> "Music"
                    }

                    val videoItem = VideoItem(
                        videoId = "local_$id",
                        title = rawTitle,
                        author = rawArtist,
                        durationText = formatDuration(duration),
                        customThumbnailUrl = albumArtUri,
                        localUriString = contentUri
                    )

                    songsList.add(Pair(videoItem, folderName))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query MediaStore for local audio: ${e.message}", e)
        }

        val allSongs = songsList.map { it.first }
        val folders = songsList
            .groupBy { it.second }
            .map { (folderName, pairs) ->
                LocalFolder(
                    name = folderName,
                    path = folderName,
                    songs = pairs.map { it.first }
                )
            }
            .sortedByDescending { it.songs.size }

        Log.d(TAG, "Local audio scan complete. Found ${allSongs.size} tracks in ${folders.size} folders.")
        LocalScanResult(allSongs = allSongs, folders = folders)
    }
}
