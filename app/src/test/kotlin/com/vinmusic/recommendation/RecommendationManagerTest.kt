package com.vinmusic.recommendation

import com.vinmusic.innertube.VideoItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendationManagerTest {

    // --- normalizeTitle ---

    @Test
    fun `normalizeTitle removes brackets punctuation and stop words`() {
        assertEquals("tum hi ho", RecommendationManager.normalizeTitle("Tum Hi Ho (Official Video) [HD]"))
    }

    @Test
    fun `normalizeTitle removes lyrics and audio stop words`() {
        assertEquals("kesariya", RecommendationManager.normalizeTitle("Kesariya - Official Audio | Lyrics"))
    }

    @Test
    fun `normalizeTitle collapses whitespace and lowercases`() {
        assertEquals("channa mereya", RecommendationManager.normalizeTitle("  CHANNA   MEREYA  "))
    }

    @Test
    fun `normalizeTitle keeps stop word substrings inside other words`() {
        // "liver" contains no stop word as a whole word
        assertEquals("believer", RecommendationManager.normalizeTitle("Believer"))
    }

    // --- normalizeArtistName ---

    @Test
    fun `normalizeArtistName strips topic suffix`() {
        assertEquals("arijit singh", RecommendationManager.normalizeArtistName("Arijit Singh - Topic"))
    }

    @Test
    fun `normalizeArtistName strips standalone vevo`() {
        assertEquals("eminem", RecommendationManager.normalizeArtistName("Eminem VEVO"))
    }

    @Test
    fun `normalizeArtistName removes special characters`() {
        assertEquals("apdhillon", RecommendationManager.normalizeArtistName("A.P.Dhillon!"))
    }

    // --- isSimilarArtist ---

    @Test
    fun `isSimilarArtist matches identical artists after normalization`() {
        assertTrue(RecommendationManager.isSimilarArtist("Drake", "drake"))
        assertTrue(RecommendationManager.isSimilarArtist("Arijit Singh - Topic", "Arijit Singh"))
    }

    @Test
    fun `isSimilarArtist matches known similar artists in both directions`() {
        assertTrue(RecommendationManager.isSimilarArtist("Drake", "Kendrick Lamar"))
        assertTrue(RecommendationManager.isSimilarArtist("Kendrick Lamar", "Drake"))
    }

    @Test
    fun `isSimilarArtist rejects unrelated artists`() {
        assertFalse(RecommendationManager.isSimilarArtist("Drake", "Adele"))
    }

    // --- getLevenshteinDistance ---

    @Test
    fun `levenshtein distance of identical strings is zero`() {
        assertEquals(0, RecommendationManager.getLevenshteinDistance("abc", "abc"))
    }

    @Test
    fun `levenshtein distance against empty string is the other length`() {
        assertEquals(3, RecommendationManager.getLevenshteinDistance("", "abc"))
        assertEquals(3, RecommendationManager.getLevenshteinDistance("abc", ""))
    }

    @Test
    fun `levenshtein distance kitten to sitting is three`() {
        assertEquals(3, RecommendationManager.getLevenshteinDistance("kitten", "sitting"))
    }

    // --- isTooSimilar ---

    @Test
    fun `isTooSimilar detects same song with different suffixes`() {
        assertTrue(RecommendationManager.isTooSimilar("Tum Hi Ho", "Tum Hi Ho (Lyrics)"))
        assertTrue(RecommendationManager.isTooSimilar("Kesariya", "Kesariya Official Video"))
    }

    @Test
    fun `isTooSimilar rejects clearly different songs`() {
        assertFalse(RecommendationManager.isTooSimilar("Believer", "Channa Mereya"))
    }

    // --- isNonMusicVideo ---

    @Test
    fun `isNonMusicVideo flags reaction and explainer content`() {
        assertTrue(RecommendationManager.isNonMusicVideo("Song Meaning Explained", "Some Channel"))
        assertTrue(RecommendationManager.isNonMusicVideo("My Reaction To New Album", "Random Guy"))
    }

    @Test
    fun `isNonMusicVideo flags blacklisted channel keywords`() {
        assertTrue(RecommendationManager.isNonMusicVideo("New Single", "Tech Burner"))
    }

    @Test
    fun `isNonMusicVideo allows normal songs`() {
        assertFalse(RecommendationManager.isNonMusicVideo("Tum Hi Ho", "Arijit Singh"))
    }

    // --- isCompilationTitle / isCompilationTrack ---

    @Test
    fun `isCompilationTitle flags jukebox and best-of titles`() {
        assertTrue(RecommendationManager.isCompilationTitle("Best of Arijit Singh Jukebox"))
        assertTrue(RecommendationManager.isCompilationTitle("Top 10 Punjabi Songs"))
    }

    @Test
    fun `isCompilationTitle allows single track titles`() {
        assertFalse(RecommendationManager.isCompilationTitle("Kesariya"))
    }

    @Test
    fun `isCompilationTrack flags tracks longer than an hour`() {
        assertTrue(RecommendationManager.isCompilationTrack("Some Song", "1:02:33"))
    }

    @Test
    fun `isCompilationTrack flags tracks of fifteen minutes or more`() {
        assertTrue(RecommendationManager.isCompilationTrack("Some Song", "16:20"))
    }

    @Test
    fun `isCompilationTrack allows normal length tracks`() {
        assertFalse(RecommendationManager.isCompilationTrack("Some Song", "3:45"))
    }

    // --- channel classification ---

    @Test
    fun `isCorporateOrDistributorChannel recognizes major labels`() {
        assertTrue(RecommendationManager.isCorporateOrDistributorChannel("T-Series"))
        assertTrue(RecommendationManager.isCorporateOrDistributorChannel("Zee Music Company"))
    }

    @Test
    fun `isCorporateOrDistributorChannel rejects independent artists`() {
        assertFalse(RecommendationManager.isCorporateOrDistributorChannel("Anuv Jain"))
    }

    @Test
    fun `isUnofficialContent flags slowed reverb uploads`() {
        assertTrue(RecommendationManager.isUnofficialContent("Kesariya (Slowed + Reverb)", "Lofi Bois"))
    }

    @Test
    fun `isUnofficialContent trusts corporate channels even for remix titles`() {
        assertFalse(RecommendationManager.isUnofficialContent("Kesariya Remix", "T-Series"))
    }

    @Test
    fun `isOfficialArtistChannel accepts topic and corporate channels`() {
        assertTrue(RecommendationManager.isOfficialArtistChannel("Song", "Arijit Singh - Topic"))
        assertTrue(RecommendationManager.isOfficialArtistChannel("Song", "T-Series"))
    }

    @Test
    fun `isOfficialArtistChannel rejects known unofficial uploaders`() {
        assertFalse(RecommendationManager.isOfficialArtistChannel("Song", "SongWeed"))
    }

    // --- inferMetadata ---

    @Test
    fun `inferMetadata detects hindi romantic bollywood track`() {
        val item = VideoItem("v1", "Tum Hi Ho", "Arijit Singh", "4:22")
        val meta = RecommendationManager.inferMetadata(item)

        assertEquals("Hindi", meta.language)
        assertEquals("Bollywood", meta.genre)
        assertEquals("Romantic", meta.mood)
        assertTrue(meta.isOfficial)
    }

    @Test
    fun `inferMetadata detects punjabi folk track`() {
        val item = VideoItem("v2", "Jatt Da Muqabala", "Sidhu Moose Wala", "3:30")
        val meta = RecommendationManager.inferMetadata(item)

        assertEquals("Punjabi", meta.language)
        assertEquals("Punjabi Folk", meta.genre)
    }

    @Test
    fun `inferMetadata keeps energy and tempo within valid bounds`() {
        val items = listOf(
            VideoItem("a1", "Lofi Chill Beats", "Chill Vibes", "3:00"),
            VideoItem("b2", "Gym Workout Rap", "MC Stan", "2:50"),
            VideoItem("c3", "Tum Hi Ho", "Arijit Singh", "4:22")
        )
        for (item in items) {
            val meta = RecommendationManager.inferMetadata(item)
            assertTrue("energy in range for ${item.title}", meta.energy in 0.1..0.99)
            assertTrue("tempo in range for ${item.title}", meta.tempo in 60..180)
        }
    }

    @Test
    fun `inferMetadata extracts year from title when present`() {
        val item = VideoItem("v3", "Old Classic Song 1998", "Some Artist", "4:00")
        val meta = RecommendationManager.inferMetadata(item)
        assertEquals(1998, meta.year)
    }
}
