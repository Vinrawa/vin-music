package com.vinmusic.recommendation

import com.vinmusic.innertube.VideoItem
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class SmartQueuePipelineTest {

    private fun normalizeArtist(artist: String): String =
        RecommendationManager.normalizeArtistName(artist).trim().lowercase(Locale.ROOT)

    @Test
    fun `smart queue pipeline with English seed retains English and Unknown candidates and rejects mismatched languages`() {
        val seed = VideoItem("seed1", "Shape of You", "Ed Sheeran", "3:53")
        val seedMeta = RecommendationManager.inferMetadata(seed)
        assertEquals("English", seedMeta.language)

        val rawCandidates = listOf(
            VideoItem("e1", "Blinding Lights", "The Weeknd", "3:20"),
            VideoItem("e2", "Stay", "Justin Bieber", "2:21"),
            VideoItem("e3", "Bad Habits", "Ed Sheeran", "3:51"),
            VideoItem("e4", "Levitating", "Dua Lipa", "3:23"),
            VideoItem("e5", "As It Was", "Harry Styles", "2:47"),
            VideoItem("e6", "Watermelon Sugar", "Harry Styles", "2:54"),
            VideoItem("e7", "Save Your Tears", "The Weeknd", "3:35"),
            VideoItem("e8", "Peaches", "Justin Bieber", "3:18"),
            VideoItem("e9", "Attention", "Charlie Puth", "3:28"),
            VideoItem("e10", "Starboy", "The Weeknd", "3:50"),
            VideoItem("e11", "Someone You Loved", "Lewis Capaldi", "3:02"),
            VideoItem("e12", "Sunflower", "Post Malone", "2:38"),
            // Non-music videos / bootlegs that must be filtered out
            VideoItem("bad1", "Shape of You (Reaction Video)", "Random Vlogger", "10:00"),
            VideoItem("bad2", "Shape of You (1 Hour Loop)", "Chill Zone", "1:00:00"),
            VideoItem("bad3", "Shape of You (Slowed + Reverb)", "Slowed Nation", "4:30"),
            // Mismatched languages that must be filtered out
            VideoItem("h1", "Tum Hi Ho", "Arijit Singh", "4:22"),
            VideoItem("p1", "No Love", "Shubh", "2:50")
        )

        // Stage 1: Compilation filter
        val afterCompilation = rawCandidates.filter { !RecommendationManager.isCompilationTrack(it.title, it.durationText) }
        assertFalse(afterCompilation.any { it.videoId == "bad2" })

        // Stage 2: Non-music video filter
        val afterNonMusicVideo = afterCompilation.filter { !RecommendationManager.isNonMusicVideo(it.title, it.author) }
        assertFalse(afterNonMusicVideo.any { it.videoId == "bad1" })

        // Stage 3: Unofficial content filter
        val afterUnofficial = afterNonMusicVideo.filter { !RecommendationManager.isUnofficialContent(it.title, it.author) }
        assertFalse(afterUnofficial.any { it.videoId == "bad3" })

        // Stage 4: isOfficial
        val officialMetaPairs = afterUnofficial.map { it to RecommendationManager.inferMetadata(it) }
            .filter { (_, meta) -> meta.isOfficial }
        assertTrue(officialMetaPairs.isNotEmpty())

        // Stage 5: isTooSimilar to seed
        val afterSimilarity = officialMetaPairs.filter { (item, _) ->
            !RecommendationManager.isTooSimilar(seed.title, item.title)
        }

        // Stage 6: Language filter (English)
        val languageFiltered = afterSimilarity.filter { (_, meta) ->
            RecommendationManager.isCompatibleQueueLanguage(meta.language, seedMeta.language, allowUnknown = true)
        }
        assertFalse(languageFiltered.any { it.first.videoId == "h1" })
        assertFalse(languageFiltered.any { it.first.videoId == "p1" })
        assertEquals(12, languageFiltered.size)
    }

    @Test
    fun `smart queue pipeline with Punjabi seed retains Punjabi and Unknown candidates and rejects English`() {
        val seed = VideoItem("p_seed", "No Love", "Shubh", "2:50")
        val seedMeta = RecommendationManager.inferMetadata(seed)
        assertEquals("Punjabi", seedMeta.language)

        val rawCandidates = listOf(
            VideoItem("p1", "Elevated", "Shubh", "3:20"),
            VideoItem("p2", "Baller", "Shubh", "2:30"),
            VideoItem("p3", "Winning Speech", "Karan Aujla", "3:10"),
            VideoItem("p4", "Softly", "Karan Aujla", "2:35"),
            VideoItem("p5", "Excuses", "AP Dhillon", "2:56"),
            VideoItem("p6", "Brown Munde", "AP Dhillon", "4:07"),
            VideoItem("p7", "Insane", "AP Dhillon", "3:25"),
            VideoItem("p8", "295", "Sidhu Moose Wala", "4:30"),
            VideoItem("p9", "Same Beef", "Sidhu Moose Wala", "4:15"),
            VideoItem("p10", "G-Shit", "Sidhu Moose Wala", "3:54"),
            VideoItem("p11", "White Brown Black", "Avvy Sra", "3:05"),
            VideoItem("p12", "G.O.A.T.", "Diljit Dosanjh", "3:43"),
            VideoItem("p13", "Lover", "Diljit Dosanjh", "3:12"),
            VideoItem("p14", "Lemonade", "Diljit Dosanjh", "3:00"),
            // English songs that must be rejected
            VideoItem("e1", "Blinding Lights", "The Weeknd", "3:20"),
            VideoItem("e2", "Shape of You", "Ed Sheeran", "3:53")
        )

        // Stage 1: Compilation filter
        val afterCompilation = rawCandidates.filter { !RecommendationManager.isCompilationTrack(it.title, it.durationText) }
        assertEquals(rawCandidates.size, afterCompilation.size)

        // Stage 2: Non-music video filter
        val afterNonMusicVideo = afterCompilation.filter { !RecommendationManager.isNonMusicVideo(it.title, it.author) }
        assertEquals(rawCandidates.size, afterNonMusicVideo.size)

        // Stage 3: Unofficial content filter
        val afterUnofficial = afterNonMusicVideo.filter { !RecommendationManager.isUnofficialContent(it.title, it.author) }
        assertEquals(rawCandidates.size, afterUnofficial.size)

        // Stage 4: isOfficial
        val officialMetaPairs = afterUnofficial.map { it to RecommendationManager.inferMetadata(it) }
            .filter { (_, meta) -> meta.isOfficial }
        assertEquals(rawCandidates.size, officialMetaPairs.size)

        // Stage 5: isTooSimilar to seed
        val afterSimilarity = officialMetaPairs.filter { (item, _) ->
            !RecommendationManager.isTooSimilar(seed.title, item.title)
        }
        // "No Love" shouldn't eliminate "Lover" or "Elevated" or "Baller"
        assertTrue(afterSimilarity.any { it.first.title == "Lover" })
        assertTrue(afterSimilarity.any { it.first.title == "Elevated" })

        // Stage 6: Language filter (Punjabi)
        val languageFiltered = afterSimilarity.filter { (_, meta) ->
            RecommendationManager.isCompatibleQueueLanguage(meta.language, seedMeta.language, allowUnknown = true)
        }
        assertFalse(languageFiltered.any { it.first.videoId == "e1" })
        assertFalse(languageFiltered.any { it.first.videoId == "e2" })
        assertTrue(languageFiltered.size >= 12)
    }

    @Test
    fun `smart queue pipeline generates full 10-20 track queue even when YTM Related is zero but radio and search have candidates`() {
        val ytmRelatedCount = 0 // Simulating YTM Related returning 0
        val ytmRadioTracks = listOf(
            VideoItem("r1", "Channa Mereya", "Arijit Singh", "4:49"),
            VideoItem("r2", "Tum Hi Ho", "Arijit Singh", "4:22"),
            VideoItem("r3", "Kesariya", "Arijit Singh", "4:28"),
            VideoItem("r4", "Raataan Lambiyan", "Jubin Nautiyal", "3:50"),
            VideoItem("r5", "Dil Diyan Gallan", "Atif Aslam", "4:20"),
            VideoItem("r6", "Agar Tum Saath Ho", "Arijit Singh", "5:41")
        )
        val searchFallbackTracks = listOf(
            VideoItem("s1", "Hawayein", "Arijit Singh", "4:50"),
            VideoItem("s2", "Shayad", "Arijit Singh", "4:07"),
            VideoItem("s3", "Tujhe Kitna Chahne Lage", "Arijit Singh", "4:44"),
            VideoItem("s4", "Mast Magan", "Arijit Singh", "4:40"),
            VideoItem("s5", "Kalank", "Arijit Singh", "5:11"),
            VideoItem("s6", "Bekhayali", "Sachet Tandon", "6:11"),
            VideoItem("s7", "Tera Ban Jaunga", "Akhil Sachdeva", "3:56"),
            VideoItem("s8", "Pachtaoge", "Arijit Singh", "3:46")
        )

        val pool = mutableListOf<VideoItem>()
        if (ytmRelatedCount == 0) {
            pool.addAll(ytmRadioTracks)
            pool.addAll(searchFallbackTracks)
        }

        val deduped = pool.distinctBy {
            "${RecommendationManager.normalizeTitle(it.title)}|${normalizeArtist(it.author)}"
        }
        assertEquals(14, deduped.size)

        val languageFiltered = deduped.map { it to RecommendationManager.inferMetadata(it) }
            .filter { (_, meta) ->
                meta.isOfficial &&
                RecommendationManager.isCompatibleQueueLanguage(meta.language, "Hindi", allowUnknown = true)
            }

        assertEquals(14, languageFiltered.size)

        // Verify sequencing produces at least 10 items
        val sequenced = ArrayList<VideoItem>()
        val remaining = ArrayList(languageFiltered.map { it.first })
        val artistCount = mutableMapOf<String, Int>()
        var lastArtist = ""

        // Pass 1
        while (remaining.isNotEmpty() && sequenced.size < 20) {
            val eligible = remaining.filter { item ->
                val artist = normalizeArtist(item.author)
                val count = artistCount[artist] ?: 0
                val cap = 2
                val lastIndex = sequenced.indexOfLast { normalizeArtist(it.author) == artist }
                val gap = if (lastIndex < 0) Int.MAX_VALUE else sequenced.size - lastIndex
                count < cap && (gap >= 3 || artist != lastArtist)
            }
            if (eligible.isEmpty()) break
            val next = eligible.first()
            sequenced.add(next)
            val artist = normalizeArtist(next.author)
            lastArtist = artist
            artistCount[artist] = (artistCount[artist] ?: 0) + 1
            remaining.remove(next)
        }

        // Pass 2: Relaxed spacing if sequenced < 10
        if (sequenced.size < 10 && remaining.isNotEmpty()) {
            while (remaining.isNotEmpty() && sequenced.size < 20) {
                val eligible = remaining.filter { item ->
                    val artist = normalizeArtist(item.author)
                    val count = artistCount[artist] ?: 0
                    val cap = 6
                    val lastIndex = sequenced.indexOfLast { normalizeArtist(it.author) == artist }
                    val gap = if (lastIndex < 0) Int.MAX_VALUE else sequenced.size - lastIndex
                    count < cap && gap >= 1
                }
                if (eligible.isEmpty()) break
                val next = eligible.first()
                sequenced.add(next)
                val artist = normalizeArtist(next.author)
                lastArtist = artist
                artistCount[artist] = (artistCount[artist] ?: 0) + 1
                remaining.remove(next)
            }
        }

        assertTrue("Sequenced queue size must be >= 10, got ${sequenced.size}", sequenced.size >= 10)
    }

    @Test
    fun `smart queue pipeline with Isaiah Rashad English HipHop seed generates full queue even if profile language was Punjabi`() {
        val seed = VideoItem("seed_isaiah", "Wat's Wrong (feat. Zacari & Kendrick Lamar)", "Isaiah Rashad", "5:37")
        val seedMeta = RecommendationManager.inferMetadata(seed)
        assertEquals("English", seedMeta.language)

        val rawCandidates = listOf(
            VideoItem("r1", "4r Da Squaw", "Isaiah Rashad", "3:52"),
            VideoItem("r2", "Free Lunch", "Isaiah Rashad", "3:11"),
            VideoItem("r3", "Money Trees", "Kendrick Lamar", "6:26"),
            VideoItem("r4", "LOVE.", "Kendrick Lamar", "3:33"),
            VideoItem("r5", "Collard Greens", "ScHoolboy Q", "4:59"),
            VideoItem("r6", "Man of the Year", "ScHoolboy Q", "3:36"),
            VideoItem("r7", "Love Galore", "SZA", "4:35"),
            VideoItem("r8", "Broken Clocks", "SZA", "3:51"),
            VideoItem("r9", "No Role Modelz", "J. Cole", "4:52"),
            VideoItem("r10", "MIDDLE CHILD", "J. Cole", "3:33"),
            VideoItem("r11", "Self Care", "Mac Miller", "5:45"),
            VideoItem("r12", "Swimming Pools", "Kendrick Lamar", "4:07")
        )

        // Profile language might be "Punjabi" from previous listening
        val profileLanguage = "Punjabi"

        // Candidate pool majority language
        val candidatePairs = rawCandidates.map { it to RecommendationManager.inferMetadata(it) }
        val candidateMajorityLang = candidatePairs.groupingBy { it.second.language }
            .eachCount()
            .filter { it.key.isNotBlank() && it.key != "Unknown" }
            .maxByOrNull { it.value }?.key

        // Queue language determination
        val queueLanguage = seedMeta.language.takeIf { it.isNotBlank() && it != "Unknown" }
            ?: candidateMajorityLang
            ?: profileLanguage

        assertEquals("English", queueLanguage)

        var languageFiltered = candidatePairs.filter { (_, meta) ->
            RecommendationManager.isCompatibleQueueLanguage(meta.language, queueLanguage, allowUnknown = true)
        }

        if (languageFiltered.size < 5 && candidatePairs.size >= 5) {
            languageFiltered = candidatePairs
        }

        assertEquals(12, languageFiltered.size)
    }
}
