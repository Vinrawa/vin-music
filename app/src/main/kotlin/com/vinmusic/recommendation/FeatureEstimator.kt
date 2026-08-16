package com.vinmusic.recommendation

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Estimates audio features for songs NOT in the bundled 500K DB.
 *
 * Strategy (completely free, no API needed):
 * 1. Find the artist's other tracks in the 500K DB → use average features
 * 2. If artist not found → use genre-based estimation from Every Noise + mood keywords
 * 3. Refine energy/tempo based on title mood keywords
 *
 * This runs locally on the device using the bundled recommendations.db.
 */
object FeatureEstimator {
    private const val TAG = "FeatureEstimator"

    private val estimatedFeatureCache = java.util.concurrent.ConcurrentHashMap<String, AudioFeatures>()

    data class AudioFeatures(
        val energy: Float,
        val valence: Float,
        val danceability: Float,
        val acousticness: Float,
        val tempo: Float
    )

    /**
     * Estimate audio features for a track. Uses memory cache first, then fast indexed B-Tree search in 500K DB.
     */
    suspend fun estimateFeatures(
        recDb: RecommendationDatabase,
        title: String,
        artist: String,
        genre: String,
        mood: String
    ): AudioFeatures? = withContext(Dispatchers.IO) {
        val cacheKey = "${artist.lowercase()}|${title.lowercase()}|${genre.lowercase()}|${mood.lowercase()}"
        estimatedFeatureCache[cacheKey]?.let { return@withContext it }

        try {
            // Strategy 1: Fast indexed artist lookup (Exact match first, then Prefix match)
            val cleanArtist = artist.trim()
            if (cleanArtist.isNotBlank()) {
                var artistTracks = recDb.trackDao().findTracksByArtistExact(cleanArtist)
                if (artistTracks.isEmpty() && cleanArtist.length >= 3) {
                    artistTracks = recDb.trackDao().findTracksByArtistPrefix(cleanArtist)
                }
                if (artistTracks.isNotEmpty()) {
                    val avgEnergy = artistTracks.map { it.energy }.average().toFloat()
                    val avgValence = artistTracks.map { it.valence }.average().toFloat()
                    val avgDance = artistTracks.map { it.dance }.average().toFloat()
                    val avgAcoustic = artistTracks.map { it.acoustic }.average().toFloat()
                    val avgTempo = artistTracks.map { it.tempo }.average().toFloat()

                    Log.d(TAG, "Indexed match: found ${artistTracks.size} tracks by '$artist' in DB")
                    val result = refineByMood(
                        AudioFeatures(avgEnergy / 100f, avgValence / 100f, avgDance / 100f, avgAcoustic / 100f, avgTempo),
                        title, mood
                    )
                    estimatedFeatureCache[cacheKey] = result
                    return@withContext result
                }
            }

            // Strategy 2: Fast indexed genre lookup (Exact match first, then Prefix match)
            val cleanGenre = genre.trim().lowercase()
            if (cleanGenre.isNotBlank()) {
                var genreTracks = recDb.trackDao().findTracksByGenreExact(cleanGenre)
                if (genreTracks.isEmpty() && cleanGenre.length >= 3) {
                    genreTracks = recDb.trackDao().findTracksByGenrePrefix(cleanGenre)
                }
                if (genreTracks.isNotEmpty()) {
                    val avgEnergy = genreTracks.map { it.energy }.average().toFloat()
                    val avgValence = genreTracks.map { it.valence }.average().toFloat()
                    val avgDance = genreTracks.map { it.dance }.average().toFloat()
                    val avgAcoustic = genreTracks.map { it.acoustic }.average().toFloat()
                    val avgTempo = genreTracks.map { it.tempo }.average().toFloat()

                    Log.d(TAG, "Indexed match: found ${genreTracks.size} tracks in genre '$genre' in DB")
                    val result = refineByMood(
                        AudioFeatures(avgEnergy / 100f, avgValence / 100f, avgDance / 100f, avgAcoustic / 100f, avgTempo),
                        title, mood
                    )
                    estimatedFeatureCache[cacheKey] = result
                    return@withContext result
                }
            }

            // Strategy 3: Use genre-based defaults
            Log.d(TAG, "No indexed tracks found for artist '$artist' or genre '$genre', using genre defaults")
            val fallback = getGenreDefaults(genre, mood)
            if (fallback != null) {
                estimatedFeatureCache[cacheKey] = fallback
            }
            return@withContext fallback
        } catch (e: Exception) {
            Log.w(TAG, "Feature estimation failed: ${e.message}")
            null
        }
    }

    /**
     * Refine features based on mood keywords in the title.
     * "Rich N****z" → mood=Chill/Relaxed but title suggests introspective → lower energy
     */
    private fun refineByMood(features: AudioFeatures, title: String, mood: String): AudioFeatures {
        val titleLower = title.lowercase()
        var energy = features.energy
        var valence = features.valence
        var tempo = features.tempo

        // Introspective/spiritual titles → lower energy, lower valence
        val introspectiveKeywords = listOf("prayer", "god", "soul", "mind", "deep", "think", "alone",
            "silence", "peace", "faith", "hope", "dream", "inside", "reflect", "adoration", "magi",
            "worship", "sacred", "holy", "spiritual", "divine", "truth", "meaning", "story")
        if (introspectiveKeywords.any { titleLower.contains(it) }) {
            energy = (energy * 0.7f).coerceIn(0.15f, 0.55f)
            valence = (valence * 0.8f).coerceIn(0.1f, 0.5f)
            tempo = (tempo * 0.9f).coerceIn(70f, 100f)
        }

        // Party/hype titles → higher energy, higher valence
        val partyKeywords = listOf("party", "club", "banger", "hype", "turnt", "lit", "flex", "drop")
        if (partyKeywords.any { titleLower.contains(it) }) {
            energy = (energy * 1.3f).coerceIn(0.6f, 0.95f)
            valence = (valence * 1.2f).coerceIn(0.5f, 0.9f)
            tempo = (tempo * 1.1f).coerceIn(120f, 160f)
        }

        // Sad/heartbreak titles → lower energy, lower valence
        val sadKeywords = listOf("cry", "tears", "pain", "hurt", "gone", "lost", "broken", "empty", "lonely")
        if (sadKeywords.any { titleLower.contains(it) }) {
            energy = (energy * 0.75f).coerceIn(0.15f, 0.5f)
            valence = (valence * 0.6f).coerceIn(0.05f, 0.4f)
        }

        // Mood-based refinement
        when (mood) {
            "Sad" -> {
                energy = (energy * 0.8f).coerceIn(0.15f, 0.55f)
                valence = (valence * 0.7f).coerceIn(0.05f, 0.45f)
            }
            "Energetic" -> {
                energy = (energy * 1.2f).coerceIn(0.5f, 0.95f)
                tempo = (tempo * 1.1f).coerceIn(110f, 160f)
            }
            "Chill/Relaxed" -> {
                energy = (energy * 0.85f).coerceIn(0.2f, 0.6f)
                tempo = (tempo * 0.9f).coerceIn(70f, 110f)
            }
            "Romantic" -> {
                energy = (energy * 0.8f).coerceIn(0.2f, 0.55f)
                valence = (valence * 1.1f).coerceIn(0.4f, 0.7f)
                tempo = (tempo * 0.85f).coerceIn(70f, 100f)
            }
            "Dark" -> {
                energy = (energy * 0.9f).coerceIn(0.3f, 0.7f)
                valence = (valence * 0.6f).coerceIn(0.05f, 0.4f)
            }
        }

        return AudioFeatures(energy, valence, features.danceability, features.acousticness, tempo)
    }

    /**
     * Genre-based default features when no tracks found in DB.
     */
    private fun getGenreDefaults(genre: String, mood: String): AudioFeatures {
        val genreLower = genre.lowercase()

        val baseFeatures = when {
            genreLower.contains("rap") || genreLower.contains("hiphop") || genreLower.contains("hip-hop") -> {
                AudioFeatures(energy = 0.55f, valence = 0.45f, danceability = 0.6f, acousticness = 0.2f, tempo = 90f)
            }
            genreLower.contains("r&b") || genreLower.contains("soul") -> {
                AudioFeatures(energy = 0.45f, valence = 0.5f, danceability = 0.55f, acousticness = 0.35f, tempo = 85f)
            }
            genreLower.contains("pop") -> {
                AudioFeatures(energy = 0.65f, valence = 0.6f, danceability = 0.7f, acousticness = 0.25f, tempo = 110f)
            }
            genreLower.contains("rock") || genreLower.contains("metal") -> {
                AudioFeatures(energy = 0.75f, valence = 0.45f, danceability = 0.5f, acousticness = 0.15f, tempo = 120f)
            }
            genreLower.contains("indie") || genreLower.contains("folk") -> {
                AudioFeatures(energy = 0.4f, valence = 0.5f, danceability = 0.45f, acousticness = 0.65f, tempo = 95f)
            }
            genreLower.contains("lofi") || genreLower.contains("chill") -> {
                AudioFeatures(energy = 0.25f, valence = 0.45f, danceability = 0.5f, acousticness = 0.6f, tempo = 80f)
            }
            genreLower.contains("punjabi") || genreLower.contains("bhangra") -> {
                AudioFeatures(energy = 0.7f, valence = 0.65f, danceability = 0.75f, acousticness = 0.15f, tempo = 100f)
            }
            genreLower.contains("bollywood") -> {
                AudioFeatures(energy = 0.55f, valence = 0.55f, danceability = 0.6f, acousticness = 0.35f, tempo = 100f)
            }
            genreLower.contains("electronic") || genreLower.contains("edm") -> {
                AudioFeatures(energy = 0.8f, valence = 0.55f, danceability = 0.75f, acousticness = 0.05f, tempo = 128f)
            }
            else -> {
                AudioFeatures(energy = 0.5f, valence = 0.5f, danceability = 0.5f, acousticness = 0.3f, tempo = 100f)
            }
        }

        // Apply mood refinement
        return refineByMood(baseFeatures, "", mood)
    }
}
