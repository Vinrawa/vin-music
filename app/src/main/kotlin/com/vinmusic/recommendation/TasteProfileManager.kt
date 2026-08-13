package com.vinmusic.recommendation

import com.vinmusic.data.db.InteractionSignal
import com.vinmusic.data.db.InteractionSignalDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

/**
 * Spotify-feature-space taste profile (energy/valence/danceability/acousticness/tempo).
 * Used by MusicDnaScreen to display the user's audio feature preferences.
 *
 * NOTE: This is DIFFERENT from RecommendationManager.TasteProfile which contains
 * topArtists/topGenres/topMoods (the genre/mood/artist preference profile).
 * This class deals with raw audio features; that one deals with categorical preferences.
 */
data class AudioFeatureProfile(
    val energy: Int,
    val valence: Int,
    val danceability: Int,
    val acousticness: Int,
    val tempo: Int
)

@Singleton
class TasteProfileManager @Inject constructor(
    private val signalDao: InteractionSignalDao,
    private val spotifyDao: SpotifyTrackDao,
    private val featureCacheDao: com.vinmusic.data.db.SongFeatureCacheDao
) {

    /**
     * Calculates the user's overall TasteDNA profile based on their listening history.
     * Weights: Likes (+3), Completions (+1), Skips (-2 if < 20s).
     */
    suspend fun calculateTasteProfile(): AudioFeatureProfile = withContext(Dispatchers.IO) {
        val signals = signalDao.getAll()
        
        var totalWeight = 0.0
        var wEnergy = 0.0
        var wValence = 0.0
        var wDance = 0.0
        var wAcoustic = 0.0
        var wTempo = 0.0
        
        val updatedSignals = mutableListOf<InteractionSignal>()

        for (sig in signals) {
            // Skip signals with blank metadata — can't infer anything useful
            if (sig.title.isNullOrBlank() || sig.author.isNullOrBlank()) continue
            
            try {
                val songKey = RecommendationManager.generateSongKey(sig.author, sig.title)
                val realCache = try { featureCacheDao.get(songKey) } catch (_: Exception) { null }

                if (realCache != null) {
                    sig.energy = (realCache.energyReal * 100).toInt().coerceIn(0, 100)
                    if (realCache.bpmReal in 40f..250f) {
                        sig.tempo = realCache.bpmReal.toInt().coerceIn(0, 255)
                    }
                }

                // Fetch audio features from the local Spotify database if missing in InteractionSignal
                var features = sig
                var needsUpdate = false
                if (sig.energy == -1) {
                    // Using fuzzy DB search
                    val spotifyTrack = try { RecommendationManager.findSpotifyTrackFuzzy(spotifyDao, sig.title, sig.author) } catch (_: Exception) { null }
                    if (spotifyTrack != null) {
                        sig.energy = spotifyTrack.energy
                        sig.valence = spotifyTrack.valence
                        sig.danceability = spotifyTrack.dance
                        sig.acousticness = spotifyTrack.acoustic
                        sig.tempo = spotifyTrack.tempo
                        needsUpdate = true
                        features = sig
                    } else {
                        // Feature inference fallback!
                        val fakeItem = com.vinmusic.innertube.VideoItem(sig.videoId, sig.title, sig.author, sig.durationText)
                        val inferred = RecommendationManager.inferMetadata(fakeItem)
                        
                        sig.energy = (inferred.energy * 100).toInt().coerceIn(0, 100)
                        sig.valence = when (inferred.mood) {
                            "Sad" -> 20
                            "Chill/Relaxed" -> 45
                            "Romantic" -> 55
                            "Happy" -> 80
                            "Energetic" -> 70
                            "Dark" -> 25
                            else -> 50
                        }
                        sig.danceability = when (inferred.genre) {
                            "Rap/Hip-Hop" -> 80
                            "Punjabi Folk" -> 85
                            "Pop" -> 75
                            "Lofi" -> 30
                            "Sad", "Indie" -> 40
                            "Bollywood" -> if (inferred.mood == "Energetic") 75 else 50
                            else -> 50
                        }
                        sig.acousticness = when (inferred.genre) {
                            "Lofi" -> 70
                            "Indie" -> 65
                            "Sad" -> 55
                            "Bollywood" -> if (inferred.mood == "Romantic") 50 else 30
                            "Rap/Hip-Hop", "Rock" -> 10
                            else -> 30
                        }
                        sig.tempo = inferred.tempo.coerceIn(0, 255)
                        
                        needsUpdate = true
                        features = sig
                    }
                } else if (realCache != null) {
                    val originalEnergy = sig.energy
                    val originalTempo = sig.tempo
                    sig.energy = (realCache.energyReal * 100).toInt().coerceIn(0, 100)
                    if (realCache.bpmReal in 40f..250f) {
                        sig.tempo = realCache.bpmReal.toInt().coerceIn(0, 255)
                    }
                    if (originalEnergy != sig.energy || originalTempo != sig.tempo) {
                        needsUpdate = true
                    }
                    features = sig
                }
                
                if (needsUpdate) {
                    updatedSignals.add(sig)
                }
                
                if (features.energy == -1) continue // Skip if we couldn't match the song

                // Calculate weight based on interactions
                var weight = (features.playCount * 0.5) + features.completeCount
                if (features.isLiked) weight += 3.0
                if (features.isDownloaded) weight += 2.0
                weight -= (features.skip20sCount * 2.0)
                
                if (weight > 0) {
                    totalWeight += weight
                    wEnergy += features.energy * weight
                    wValence += features.valence * weight
                    wDance += features.danceability * weight
                    wAcoustic += features.acousticness * weight
                    wTempo += features.tempo * weight
                }
            } catch (e: Exception) {
                // Skip this signal and continue with others
                continue
            }
        }
        
        // Batch write all updated signals
        if (updatedSignals.isNotEmpty()) {
            try {
                for (sig in updatedSignals) {
                    signalDao.insert(sig)
                }
            } catch (_: Exception) { /* non-critical */ }
        }

        if (totalWeight <= 0) {
            // Fallback default profile if new user
            return@withContext AudioFeatureProfile(60, 50, 60, 20, 120)
        }

        return@withContext AudioFeatureProfile(
            energy = (wEnergy / totalWeight).toInt().coerceIn(0, 100),
            valence = (wValence / totalWeight).toInt().coerceIn(0, 100),
            danceability = (wDance / totalWeight).toInt().coerceIn(0, 100),
            acousticness = (wAcoustic / totalWeight).toInt().coerceIn(0, 100),
            tempo = (wTempo / totalWeight).toInt().coerceIn(0, 255)
        )
    }
}
