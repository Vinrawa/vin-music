package com.vinmusic.recommendation

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Fetches audio features from the Spotify Web API for songs not in the bundled 500K DB.
 *
 * Flow:
 * 1. Search track on Spotify → get Spotify ID
 * 2. GET /audio-features/{id} → energy, valence, danceability, acousticness, tempo
 * 3. Cache result in Room DB for future use
 *
 * Auth: Client Credentials (free, no user login needed)
 * Rate limit: 100 requests/min (more than enough)
 */
object SpotifyApiService {
    private const val TAG = "SpotifyAPI"

    // Client Credentials — free tier, no user login needed
    // Get yours at: https://developer.spotify.com/dashboard
    private const val CLIENT_ID = ""
    private const val CLIENT_SECRET = ""

    private var cachedToken: String? = null
    private var tokenExpiry: Long = 0L

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    data class AudioFeatures(
        val energy: Float,      // 0.0-1.0
        val valence: Float,     // 0.0-1.0 (happiness)
        val danceability: Float, // 0.0-1.0
        val acousticness: Float, // 0.0-1.0
        val tempo: Float,       // BPM
        val instrumentalness: Float, // 0.0-1.0
        val speechiness: Float, // 0.0-1.0
        val liveness: Float     // 0.0-1.0
    )

    /**
     * Get audio features for a track. Returns null if:
     * - No CLIENT_ID/CLIENT_SECRET configured
     * - Track not found on Spotify
     * - API error
     */
    suspend fun getAudioFeatures(title: String, artist: String): AudioFeatures? = withContext(Dispatchers.IO) {
        if (CLIENT_ID.isEmpty() || CLIENT_SECRET.isEmpty()) {
            Log.d(TAG, "No Spotify API credentials configured, skipping")
            return@withContext null
        }

        try {
            // Step 1: Get access token
            val token = getAccessToken() ?: return@withContext null

            // Step 2: Search for the track
            val spotifyId = searchTrack(title, artist, token) ?: return@withContext null

            // Step 3: Get audio features
            val features = fetchAudioFeatures(spotifyId, token)
            features
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get audio features for '$title' by '$artist': ${e.message}")
            null
        }
    }

    private fun getAccessToken(): String? {
        // Check cache
        if (cachedToken != null && System.currentTimeMillis() < tokenExpiry) {
            return cachedToken
        }

        try {
            val credentials = "$CLIENT_ID:$CLIENT_SECRET"
            val encoded = Base64.getEncoder().encodeToString(credentials.toByteArray())

            val request = Request.Builder()
                .url("https://accounts.spotify.com/api/token")
                .post(okhttp3.FormBody.Builder()
                    .add("grant_type", "client_credentials")
                    .build())
                .addHeader("Authorization", "Basic $encoded")
                .addHeader("Content-Type", "application/x-www-form-urlencoded")
                .build()

            val response = http.newCall(request).execute()
            val body = response.body?.string() ?: return null

            if (!response.isSuccessful) {
                Log.w(TAG, "Token request failed: ${response.code}")
                return null
            }

            val json = gson.fromJson(body, JsonObject::class.java)
            val token = json.get("access_token")?.asString ?: return null
            val expiresIn = json.get("expires_in")?.asLong ?: 3600L

            cachedToken = token
            tokenExpiry = System.currentTimeMillis() + (expiresIn * 1000) - 60000 // 1min buffer

            Log.d(TAG, "Got Spotify access token, expires in ${expiresIn}s")
            return token
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get access token: ${e.message}")
            return null
        }
    }

    private fun searchTrack(title: String, artist: String, token: String): String? {
        try {
            val cleanTitle = title.lowercase()
                .replace(Regex("\\([^)]*\\)"), "")
                .replace(Regex("\\[[^]]*\\]"), "")
                .replace(Regex("\\b(feat\\.|ft\\.|with|prod\\.|produced by)\\b.*", RegexOption.IGNORE_CASE), "")
                .trim()

            val cleanArtist = artist.lowercase()
                .replace(Regex("\\s*-\\s*topic$"), "")
                .replace(Regex("\\bvevo$"), "")
                .replace(Regex("[^a-zA-Z0-9\\s]"), "")
                .trim()

            val query = "track:$cleanTitle artist:$cleanArtist"
            val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")

            val request = Request.Builder()
                .url("https://api.spotify.com/v1/search?q=$encodedQuery&type=track&limit=1")
                .addHeader("Authorization", "Bearer $token")
                .build()

            val response = http.newCall(request).execute()
            val body = response.body?.string() ?: return null

            if (!response.isSuccessful) {
                Log.w(TAG, "Search failed: ${response.code}")
                return null
            }

            val json = gson.fromJson(body, JsonObject::class.java)
            val tracks = json.getAsJsonObject("tracks")
            val items = tracks?.getAsJsonArray("items")

            if (items == null || items.size() == 0) {
                Log.d(TAG, "No Spotify results for '$title' by '$artist'")
                return null
            }

            val spotifyId = items[0].asJsonObject.get("id")?.asString
            Log.d(TAG, "Found Spotify track: $spotifyId for '$title' by '$artist'")
            return spotifyId
        } catch (e: Exception) {
            Log.w(TAG, "Search error: ${e.message}")
            return null
        }
    }

    private fun fetchAudioFeatures(spotifyId: String, token: String): AudioFeatures? {
        try {
            val request = Request.Builder()
                .url("https://api.spotify.com/v1/audio-features/$spotifyId")
                .addHeader("Authorization", "Bearer $token")
                .build()

            val response = http.newCall(request).execute()
            val body = response.body?.string() ?: return null

            if (!response.isSuccessful) {
                Log.w(TAG, "Audio features request failed: ${response.code}")
                return null
            }

            val json = gson.fromJson(body, JsonObject::class.java)

            val features = AudioFeatures(
                energy = json.get("energy")?.asFloat ?: 0.5f,
                valence = json.get("valence")?.asFloat ?: 0.5f,
                danceability = json.get("danceability")?.asFloat ?: 0.5f,
                acousticness = json.get("acousticness")?.asFloat ?: 0.3f,
                tempo = json.get("tempo")?.asFloat ?: 120f,
                instrumentalness = json.get("instrumentalness")?.asFloat ?: 0f,
                speechiness = json.get("speechiness")?.asFloat ?: 0.1f,
                liveness = json.get("liveness")?.asFloat ?: 0.1f
            )

            Log.d(TAG, "Audio features: E=${features.energy} V=${features.valence} D=${features.danceability} A=${features.acousticness} T=${features.tempo}")
            return features
        } catch (e: Exception) {
            Log.w(TAG, "Audio features error: ${e.message}")
            return null
        }
    }
}
