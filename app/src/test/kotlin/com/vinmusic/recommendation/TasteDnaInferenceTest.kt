package com.vinmusic.recommendation

import com.vinmusic.innertube.VideoItem
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for the TasteDNA input quality rules in [RecommendationManager.inferMetadata]:
 * stable acoustic baselines (no per-video hash jitter) and conservative language
 * detection, so the taste profile is trained on signal instead of noise.
 */
class TasteDnaInferenceTest {

    private fun item(id: String, title: String, author: String) =
        VideoItem(videoId = id, title = title, author = author)

    // ═══════════════════════════════════════════════════════════════════════
    // No hash jitter — identical metadata ⇒ identical features
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `same genre tracks get identical energy and tempo regardless of videoId`() {
        val a = RecommendationManager.inferMetadata(item("videoAAA", "Chill Lofi Study Beats", "Lofi Girl"))
        val b = RecommendationManager.inferMetadata(item("videoXYZ", "Chill Lofi Study Beats", "Lofi Girl"))
        assertEquals(a.energy, b.energy, 0.0)
        assertEquals(a.tempo, b.tempo)
        assertEquals(a.year, b.year)
    }

    @Test
    fun `different videos with same inferred genre share baseline features`() {
        val a = RecommendationManager.inferMetadata(item("id1", "Some Rap Track", "Unknown Artist"))
        val b = RecommendationManager.inferMetadata(item("id2", "Another Rap Song", "Someone Else"))
        assertEquals(a.energy, b.energy, 0.0)
        assertEquals(a.tempo, b.tempo)
    }

    @Test
    fun `genre detection is marked confident only with evidence`() {
        val evidenced = RecommendationManager.inferMetadata(item("i1", "Energetic Rap Cypher", "MC"))
        assertTrue(evidenced.genreConfident)

        val fallback = RecommendationManager.inferMetadata(item("i2", "Untitled Upload", "Random Channel"))
        assertFalse(fallback.genreConfident)
        assertFalse(fallback.moodConfident)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Conservative language detection
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `single generic english word does not set language to English`() {
        // "love" alone is weak evidence (appears in Hinglish titles constantly)
        val meta = RecommendationManager.inferMetadata(item("i1", "Love Story Gaana", "Bhojpuri Singer"))
        assertNotEquals("English", meta.language)
    }

    @Test
    fun `two or more generic english words set English`() {
        val meta = RecommendationManager.inferMetadata(item("i1", "Never Let You Go", "Indie Band"))
        assertEquals("English", meta.language)
    }

    @Test
    fun `known artists still dominate language detection`() {
        val punjabi = RecommendationManager.inferMetadata(item("i1", "New Track", "Karan Aujla"))
        assertEquals("Punjabi", punjabi.language)

        val hindi = RecommendationManager.inferMetadata(item("i2", "Song", "Arijit Singh"))
        assertEquals("Hindi", hindi.language)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Profile math sanity via calculateTasteSimilarity
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `low confidence genre still scores against dna map without error`() {
        val dna = RecommendationManager.TasteDNA(
            targetEnergy = 0.6,
            targetTempo = 110,
            preferredGenres = mapOf("Rap/Hip-Hop" to 50.0),
            preferredMoods = emptyMap(),
            preferredLanguages = mapOf("English" to 40.0),
            preferredArtists = emptyMap()
        )
        val fallbackMeta = RecommendationManager.inferMetadata(item("i1", "Untitled Upload", "Channel X"))
        val score = RecommendationManager.calculateTasteSimilarity(fallbackMeta, dna)
        assertTrue(score in 0.0..1.0)
    }
}
