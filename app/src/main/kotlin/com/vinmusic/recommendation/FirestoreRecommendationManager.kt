package com.vinmusic.recommendation

import android.util.Log
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirestoreRecommendationManager @Inject constructor() {
    private var _firestore: FirebaseFirestore? = null
    internal var firestore: FirebaseFirestore
        get() {
            if (_firestore == null) {
                _firestore = FirebaseFirestore.getInstance()
            }
            return _firestore ?: throw IllegalStateException("Firestore not initialized")
        }
        set(value) {
            _firestore = value
        }

    companion object {
        private const val CLAIM_STALENESS_MS = 5 * 60_000L // 5 minutes
        private const val TAG = "FirestoreRecMgr"
    }

    /**
     * Checks if the song metadata exists in Firestore.
     * Returns the document data if status is "ready", or null otherwise.
     */
    suspend fun getSongMetadata(songKey: String): Map<String, Any>? {
        return try {
            val doc = firestore.collection("songs").document(songKey).get().await()
            if (doc.exists() && doc.getString("status") == "ready") {
                doc.data
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get song metadata for $songKey", e)
            null
        }
    }

    /** Call before starting local audio analysis. Returns true if this client should proceed. */
    suspend fun tryClaimForAnalysis(songKey: String): Boolean {
        val docRef = firestore.collection("songs").document(songKey)
        return try {
            firestore.runTransaction { txn ->
                val snap = txn.get(docRef)
                if (!snap.exists()) {
                    txn.set(docRef, mapOf(
                        "status" to "analyzing",
                        "claimedAt" to FieldValue.serverTimestamp()
                    ))
                    return@runTransaction true
                }
                when (snap.getString("status")) {
                    "ready" -> false
                    "analyzing" -> {
                        val claimedAtMs = snap.getTimestamp("claimedAt")?.toDate()?.time ?: 0L
                        val stale = System.currentTimeMillis() - claimedAtMs > CLAIM_STALENESS_MS
                        if (stale) {
                            txn.update(docRef, mapOf(
                                "status" to "analyzing",
                                "claimedAt" to FieldValue.serverTimestamp()
                            ))
                            true
                        } else false
                    }
                    else -> false
                }
            }.await()
        } catch (e: Exception) {
            Log.w(TAG, "Claim transaction failed for $songKey, skipping analysis", e)
            false
        }
    }

    /** 
     * Call after BOTH TarsosDSP and Last.fm tags finish successfully.
     * This atomic write commits all properties and flips status to 'ready'.
     */
    suspend fun completeAnalysis(
        songKey: String, 
        bpm: Float?, 
        energy: Float, 
        genreTags: List<String>, 
        moodTags: List<String>,
        title: String,
        artist: String
    ) {
        try {
            val data = mutableMapOf<String, Any>(
                "status" to "ready", 
                "energyReal" to energy,
                "genreTags" to genreTags,
                "moodTags" to moodTags,
                "title" to title,
                "artist" to artist
            )
            if (bpm != null && bpm in 40f..250f) {
                data["bpmReal"] = bpm
            }
            firestore.collection("songs").document(songKey).set(data, SetOptions.merge()).await()
            Log.d(TAG, "Successfully completed analysis cache for $songKey")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to complete analysis cache for $songKey", e)
        }
    }

    /**
     * Fetches top tags from Last.fm for the track and returns them mapped to standard genres and moods.
     */
    suspend fun fetchLastFmTags(artist: String, title: String): Map<String, List<String>>? {
        val apiKey = com.vinmusic.config.RemoteConfigHelper.getLastFmApiKey()
        if (apiKey.isBlank()) return null
        val urlString = "https://ws.audioscrobbler.com/2.0/?method=track.gettoptags" +
                "&artist=${java.net.URLEncoder.encode(artist, "UTF-8")}" +
                "&track=${java.net.URLEncoder.encode(title, "UTF-8")}" +
                "&api_key=$apiKey" +
                "&format=json"
        
        try {
            val url = java.net.URL(urlString)
            val connection = url.openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            
            if (connection.responseCode == 200) {
                val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                val responseJson = com.google.gson.JsonParser.parseString(responseText).asJsonObject
                val toptags = responseJson.getAsJsonObject("toptags")
                if (toptags != null) {
                    val tagArray = toptags.getAsJsonArray("tag")
                    if (tagArray != null) {
                        val rawTags = tagArray.map { it.asJsonObject.get("name").asString }
                        val result = normalizeTags(rawTags)
                        if (result["genres"]?.isNotEmpty() == true || result["moods"]?.isNotEmpty() == true) {
                            return result
                        }
                    }
                }
            } else {
                Log.w(TAG, "Last.fm API returned response code ${connection.responseCode}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch tags from Last.fm: ${e.message}")
        }
        
        return null
    }

    /**
     * Fetches similar artists from Last.fm for discovery.
     */
    suspend fun fetchSimilarArtists(artist: String): List<String> {
        val apiKey = com.vinmusic.config.RemoteConfigHelper.getLastFmApiKey()
        if (apiKey.isBlank()) return emptyList()
        val urlString = "https://ws.audioscrobbler.com/2.0/?method=artist.getsimilar" +
                "&artist=${java.net.URLEncoder.encode(artist, "UTF-8")}" +
                "&api_key=$apiKey" +
                "&format=json"
        
        try {
            val url = java.net.URL(urlString)
            val connection = url.openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            
            if (connection.responseCode == 200) {
                val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                val responseJson = com.google.gson.JsonParser.parseString(responseText).asJsonObject
                val similarartists = responseJson.getAsJsonObject("similarartists")
                if (similarartists != null) {
                    val artistArray = similarartists.getAsJsonArray("artist")
                    if (artistArray != null) {
                        return artistArray.map { it.asJsonObject.get("name").asString }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch similar artists from Last.fm: ${e.message}")
        }
        return emptyList()
    }

    /**
     * Fetches similar tracks from Last.fm for queue recommendations.
     * Returns list of (artist, title) pairs.
     */
    suspend fun fetchSimilarTracks(artist: String, title: String): List<Pair<String, String>> {
        val apiKey = com.vinmusic.config.RemoteConfigHelper.getLastFmApiKey()
        if (apiKey.isBlank()) return emptyList()
        val urlString = "https://ws.audioscrobbler.com/2.0/?method=track.getsimilar" +
                "&artist=${java.net.URLEncoder.encode(artist, "UTF-8")}" +
                "&track=${java.net.URLEncoder.encode(title, "UTF-8")}" +
                "&api_key=$apiKey" +
                "&format=json"

        try {
            val url = java.net.URL(urlString)
            val connection = url.openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000

            if (connection.responseCode == 200) {
                val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                val responseJson = com.google.gson.JsonParser.parseString(responseText).asJsonObject
                val similartracks = responseJson.getAsJsonObject("similartracks")
                if (similartracks != null) {
                    val trackArray = similartracks.getAsJsonArray("track")
                    if (trackArray != null) {
                        return trackArray.mapNotNull { track ->
                            val trackObj = track.asJsonObject
                            val trackName = trackObj.get("name")?.asString
                            val artistObj = trackObj.getAsJsonObject("artist")
                            val artistName = artistObj?.get("name")?.asString
                            if (trackName != null && artistName != null) {
                                artistName to trackName
                            } else null
                        }.take(20)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch similar tracks from Last.fm: ${e.message}")
        }
        return emptyList()
    }


    internal fun normalizeTags(rawTags: List<String>): Map<String, List<String>> {
        val genres = mutableSetOf<String>()
        val moods = mutableSetOf<String>()
        
        val genreMappings = mapOf(
            "lofi" to "Lofi", "lo-fi" to "Lofi",
            "rap" to "Rap/Hip-Hop", "hip hop" to "Rap/Hip-Hop", "hip-hop" to "Rap/Hip-Hop", "hiphop" to "Rap/Hip-Hop", "trap" to "Rap/Hip-Hop",
            "bollywood" to "Bollywood", "hindi" to "Bollywood",
            "punjabi" to "Punjabi Folk", "bhangra" to "Punjabi Folk",
            "pop" to "Pop", "dance" to "Pop",
            "indie" to "Indie", "acoustic" to "Indie", "singer-songwriter" to "Indie",
            "rock" to "Rock", "metal" to "Rock", "grunge" to "Rock", "alternative rock" to "Rock"
        )
        
        val moodMappings = mapOf(
            "chill" to "Chill/Relaxed", "relaxed" to "Chill/Relaxed", "relaxing" to "Chill/Relaxed", "mellow" to "Chill/Relaxed", "calm" to "Chill/Relaxed",
            "romantic" to "Romantic", "love" to "Romantic",
            "sad" to "Sad", "melancholy" to "Sad", "depression" to "Sad", "emotional" to "Sad",
            "energetic" to "Energetic", "energy" to "Energetic", "party" to "Energetic", "workout" to "Energetic", "gym" to "Energetic", "hype" to "Energetic",
            "happy" to "Happy", "cheerful" to "Happy", "fun" to "Happy", "upbeat" to "Happy",
            "dark" to "Dark", "heavy" to "Dark", "gothic" to "Dark"
        )
        
        for (tag in rawTags) {
            val cleanTag = tag.lowercase().trim()
            genreMappings.entries.forEach { (keyword, target) ->
                if (cleanTag.contains(keyword)) genres.add(target)
            }
            moodMappings.entries.forEach { (keyword, target) ->
                if (cleanTag.contains(keyword)) moods.add(target)
            }
        }
        
        return mapOf(
            "genres" to genres.take(10).toList(),
            "moods" to moods.take(10).toList()
        )
    }
}
