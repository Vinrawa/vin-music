package com.vinmusic.recommendation

import android.content.Context
import android.util.Log
import com.vinmusic.data.db.InteractionSignal
import com.vinmusic.data.db.VinDatabase
import com.vinmusic.data.db.SongFeatureCache
import com.vinmusic.innertube.InnerTube
import com.vinmusic.innertube.VideoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.Locale
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

data class SongMetadata(
    val title: String,
    val artist: String,
    val genre: String,
    val mood: String,
    val language: String,
    val energy: Double, // 0.0 to 1.0
    val tempo: Int,     // BPM
    val year: Int,
    val isOfficial: Boolean,
    val sourceQuality: String
)

data class RecommendedSong(
    val videoItem: VideoItem,
    val score: Double,
    val source: String,
    val reason: String
)

data class CachedSection(
    val title: String,
    val songs: List<RecommendedSong>
)

data class SpotifyMix(
    val id: String,
    val title: String,
    val description: String,
    val songs: List<RecommendedSong>,
    val gradientStartHex: String,
    val gradientEndHex: String
)

object RecommendationManager {
    private const val TAG = "VIN_REC"
    
    // Cache recommendations for 15 minutes to prevent frequent network requests
    private const val CACHE_EXPIRY_MS = 15 * 60 * 1000L
    private const val CACHE_SCHEMA_VERSION = 3
    private var lastCacheTime: Long = 0L
    private val cachedSections = ArrayList<Pair<String, List<RecommendedSong>>>()
    
    private var lastMixCacheTime: Long = 0L
    private val cachedMixes = ArrayList<SpotifyMix>()
    
    private val gson = Gson()

    // Every Noise genre similarity graph — loaded lazily from assets
    private var genreSimilarMap: Map<String, List<String>>? = null
    private var genreGraphLoaded = false

    /**
     * Loads the Every Noise genre similarity graph from assets.
     * Maps each genre to its top 10 most similar genres.
     */
    fun loadGenreGraph(context: Context) {
        if (genreGraphLoaded) return
        try {
            val json = context.assets.open("genre_graph.json").bufferedReader().use { it.readText() }
            val data = gson.fromJson(json, com.google.gson.reflect.TypeToken.getParameterized(
                Map::class.java, String::class.java,
                com.google.gson.reflect.TypeToken.getParameterized(List::class.java, Any::class.java).type
            ).type) as? Map<*, *>
            val simData = data?.get("genre_similar") as? Map<*, *>
            if (simData != null) {
                val map = HashMap<String, List<String>>()
                for ((genre, sims) in simData) {
                    val genreStr = genre?.toString() ?: continue
                    val simsList = sims as? List<*> ?: continue
                    val topGenres = simsList.mapNotNull { (it as? Map<*, *>)?.get("genre")?.toString() }.take(10)
                    if (topGenres.isNotEmpty()) {
                        map[genreStr] = topGenres
                    }
                }
                genreSimilarMap = map
                Log.d(TAG, "Loaded Every Noise genre graph: ${map.size} genres")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load genre graph: ${e.message}")
        }
        genreGraphLoaded = true
    }

    data class GenreMixConfig(
        val description: String,
        val queries: List<String>,
        val gradientStartHex: String,
        val gradientEndHex: String,
        val targetMood: String
    )

    val GENRE_CONFIGS = mapOf(
        "Lofi" to GenreMixConfig(
            description = "Your personal sanctuary of calm. Lofi, acoustic indie, and soft chill melodies.",
            queries = listOf("hindi soft indie aesthetic", "acoustic lofi relax", "aesthetic bedtime chill"),
            gradientStartHex = "0xFFC5A880", // Light Brown
            gradientEndHex = "0xFF1E1A14",   // Charcoal
            targetMood = "Chill/Relaxed"
        ),
        "Rap/Hip-Hop" to GenreMixConfig(
            description = "Get moving with high-tempo rap, energetic workout tracks, and modern hip hop.",
            queries = listOf("energetic rap hits workout", "modern hip hop playlist popular", "trap music gym workout"),
            gradientStartHex = "0xFFB39873", // Goldish Light Brown
            gradientEndHex = "0xFF191612",   // Dark Charcoal
            targetMood = "Energetic"
        ),
        "Bollywood" to GenreMixConfig(
            description = "Melodious romantic soundtracks, Bollywood hits, and warm acoustic love songs.",
            queries = listOf("bollywood romantic hit tracks", "arijit singh sweet love audio", "hindi slow romantic ost"),
            gradientStartHex = "0xFFD6BE9C", // Warm Tan
            gradientEndHex = "0xFF2C251C",   // Deep Charcoal
            targetMood = "Romantic"
        ),
        "Punjabi Folk" to GenreMixConfig(
            description = "High-energy Punjabi beats, bhangra hits, and upbeat modern releases.",
            queries = listOf("upbeat punjabi dance bhangra", "karan aujla sidhu moose wala hits", "popular punjabi music charts"),
            gradientStartHex = "0xFFA38C6D", // Dull Gold
            gradientEndHex = "0xFF171411",   // Dark Charcoal
            targetMood = "Energetic"
        ),
        "Pop" to GenreMixConfig(
            description = "An upbeat collection of popular hits, dance anthems, and modern pop releases.",
            queries = listOf("popular pop hits charts", "dance pop anthems radio", "fresh upbeat pop music"),
            gradientStartHex = "0xFFC5A880", // Light Brown
            gradientEndHex = "0xFF251F17",   // Warm Charcoal
            targetMood = "Happy"
        ),
        "Indie" to GenreMixConfig(
            description = "Warm acoustic indie, singer-songwriter gems, and fresh independent sounds.",
            queries = listOf("hindi indie acoustic aesthetic", "indie folk playlist viral", "prateek kuhad anuv jain style"),
            gradientStartHex = "0xFFB39873", // Gold
            gradientEndHex = "0xFF15120E",   // Charcoal
            targetMood = "Chill/Relaxed"
        ),
        "Rock" to GenreMixConfig(
            description = "Heavy guitar solos, classic rock anthems, and high-voltage grunge energy.",
            queries = listOf("popular rock workout music", "heavy grunge rock classics", "linkin park style rock music"),
            gradientStartHex = "0xFF8C7355", // Dull Light Brown
            gradientEndHex = "0xFF100E0C",   // Dark Ash
            targetMood = "Energetic"
        )
    )


    private fun saveToDisk(ctx: Context, sections: List<Pair<String, List<RecommendedSong>>>) {
        try {
            val prefs = ctx.getSharedPreferences("vin_music_recommendation_cache", Context.MODE_PRIVATE)
            val list = sections.map { CachedSection(it.first, it.second) }
            val json = gson.toJson(list)
            prefs.edit()
                .putString("cached_sections", json)
                .putLong("cached_time", System.currentTimeMillis())
                .putInt("cache_schema_version", CACHE_SCHEMA_VERSION)
                .apply()
            Log.d(TAG, "Saved recommendations to disk cache.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save recommendations to disk: ${e.message}")
        }
    }

    private fun loadFromDisk(ctx: Context): List<Pair<String, List<RecommendedSong>>>? {
        try {
            val prefs = ctx.getSharedPreferences("vin_music_recommendation_cache", Context.MODE_PRIVATE)
            if (prefs.getInt("cache_schema_version", 0) != CACHE_SCHEMA_VERSION) {
                prefs.edit().remove("cached_sections").remove("cached_time").apply()
                return null
            }
            val json = prefs.getString("cached_sections", null) ?: return null
            val time = prefs.getLong("cached_time", 0L)
            
            val type = object : TypeToken<List<CachedSection>>() {}.type
            val list: List<CachedSection> = gson.fromJson(json, type) ?: return null
            
            if (list.isEmpty()) {
                Log.w(TAG, "Disk cache has 0 sections — treating as invalid.")
                prefs.edit().remove("cached_sections").remove("cached_time").apply()
                return null
            }

            val totalSongs = list.sumOf { it.songs.size }
            if (totalSongs == 0) {
                Log.w(TAG, "Disk cache has sections but 0 songs — treating as invalid.")
                prefs.edit().remove("cached_sections").remove("cached_time").apply()
                return null
            }
            
            lastCacheTime = time
            Log.d(TAG, "Loaded ${list.size} sections ($totalSongs songs) from disk cache.")
            return list.map { Pair(it.title, it.songs) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load recommendations from disk: ${e.message}")
            return null
        }
    }

    // User Taste Profile Representation containing acoustic DNA vector
    data class TasteDNA(
        val targetEnergy: Double,
        val targetTempo: Int,
        val preferredGenres: Map<String, Double>,
        val preferredMoods: Map<String, Double>,
        val preferredLanguages: Map<String, Double>,
        val preferredArtists: Map<String, Double> = emptyMap()
    )

    data class TasteProfile(
        val topArtists: List<Pair<String, Double>>, // Artist name to affinity score
        val topGenres: List<Pair<String, Double>>,   // Genre to affinity score
        val topMoods: List<Pair<String, Double>>,    // Mood to affinity score
        val topLanguages: List<Pair<String, Double>>, // Language to affinity score
        val favoriteTracks: Set<String>,            // Video IDs
        val skippedTracks: Set<String>,             // Video IDs
        val skippedArtists: Set<String>,           // Artist names
        val downloadedTracks: List<InteractionSignal>,
        val likedTracks: List<InteractionSignal>,
        val tasteDNA: TasteDNA
    )

    fun invalidateCache(ctx: Context? = null) {
        Log.d(TAG, "Invalidating recommendation cache.")
        lastCacheTime = 0L
        lastMixCacheTime = 0L
        synchronized(cachedSections) {
            cachedSections.clear()
        }
        synchronized(cachedMixes) {
            cachedMixes.clear()
        }
        ctx?.let {
            try {
                it.getSharedPreferences("vin_music_recommendation_cache", Context.MODE_PRIVATE)
                    .edit()
                    .clear()
                    .apply()
            } catch (_: Exception) {}
        }
    }

    /**
     * Normalizes a song title to clean up YouTube search fluff (brackets, punctuation, common stop-words).
     */
    fun normalizeTitle(title: String): String {
        var text = title.lowercase(Locale.ROOT)
        text = text.replace(Regex("\\([^)]*\\)"), "")
        text = text.replace(Regex("\\[[^]]*\\]"), "")
        text = text.replace(Regex("[^a-zA-Z0-9\\s]"), "")
        
        val stopWords = listOf(
            "official", "audio", "video", "lyrics", "lyric", "explained", "meaning", 
            "reaction", "remix", "cover", "instrumental", "karaoke", "slowed", 
            "reverb", "nightcore", "live", "interview", "story", "documentary",
            "hd", "4k", "genius", "unplugged", "acoustic"
        )
        for (word in stopWords) {
            text = text.replace(Regex("\\b$word\\b"), "")
        }
        return text.replace(Regex("\\s+"), " ").trim()
    }

    fun normalizeArtistName(name: String): String {
        var clean = name.lowercase(Locale.ROOT).trim()
        clean = clean.replace(Regex("- topic$"), "").trim()
        clean = clean.replace(Regex("\\bvevo\\b"), "").trim()
        clean = clean.replace(Regex("[^a-z0-9\\s]"), "")
        clean = clean.replace(Regex("\\s+"), " ").trim()
        return clean
    }

    /**
     * Generates a deterministic SHA-256 hash key for a normalized artist + title string.
     */
    fun generateSongKey(artist: String, title: String): String {
        val normArtist = normalizeArtistName(artist)
        val normTitle = normalizeTitle(title)
        val combined = "$normArtist::$normTitle"
        return try {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val hashBytes = digest.digest(combined.toByteArray(Charsets.UTF_8))
            hashBytes.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            combined.replace(Regex("[^a-zA-Z0-9_]"), "_")
        }
    }

    val SIMILAR_ARTISTS_MAP = mapOf(
        "j. cole" to listOf("kendrick lamar", "drake", "jid", "cordae", "joey bada\$\$", "kanye west"),
        "j cole" to listOf("kendrick lamar", "drake", "jid", "cordae", "joey bada\$\$", "kanye west"),
        "kendrick lamar" to listOf("j. cole", "drake", "travis scott", "21 savage", "baby keem", "a\$ap rocky"),
        "21 savage" to listOf("metro boomin", "future", "travis scott", "drake", "lil baby", "gunna"),
        "travis scott" to listOf("metro boomin", "don toliver", "kid cudi", "a\$ap rocky", "kanye west"),
        "drake" to listOf("j. cole", "kendrick lamar", "the weeknd", "future", "lil baby", "travis scott"),
        "arijit singh" to listOf("atif aslam", "jubin nautiyal", "shreya ghoshal", "pritam", "darshan raval"),
        "sidhu moose wala" to listOf("karan aujla", "diljit dosanjh", "shubh", "prem dhillon", "amrit maan"),
        "karan aujla" to listOf("sidhu moose wala", "diljit dosanjh", "shubh", "ap dhillon", "garry sandhu"),
        "diljit dosanjh" to listOf("karan aujla", "sidhu moose wala", "ap dhillon", "ammy virk", "shubh"),
        "shubh" to listOf("ap dhillon", "gurinder gill", "sidhu moose wala", "karan aujla", "diljit dosanjh"),
        "prateek kuhad" to listOf("anuv jain", "local train", "when chai met toast", "yellow diary"),
        "anuv jain" to listOf("prateek kuhad", "aditya rikhari", "mitraz", "local train", "osho jain"),
        "mitraz" to listOf("anuv jain", "aditya rikhari", "darshan raval", "zaeden", "prateek kuhad"),
        "the weeknd" to listOf("post malone", "khalid", "frank ocean", "sza", "brent faiyaz"),
        "taylor swift" to listOf("olivia rodrigo", "billie eilish", "sabrina carpenter", "ed sheeran"),
        "eminem" to listOf("dr. dre", "50 cent", "snoop dogg", "j. cole", "kendrick lamar"),
        "badshah" to listOf("raftaar", "yo yo honey singh", "king", "mc stan", "divine"),
        "divine" to listOf("naezy", "raftaar", "mc stan", "seedhe maut", "kr\$na"),
        "kr\$na" to listOf("raftaar", "seedhe maut", "divine", "emiway bantai", "young stunners")
    )

    fun isSimilarArtist(artist1: String, artist2: String): Boolean {
        val norm1 = normalizeArtistName(artist1)
        val norm2 = normalizeArtistName(artist2)
        if (norm1 == norm2) return true
        
        val list1 = SIMILAR_ARTISTS_MAP[norm1]
        if (list1 != null && list1.any { normalizeArtistName(it) == norm2 }) return true
        
        val list2 = SIMILAR_ARTISTS_MAP[norm2]
        if (list2 != null && list2.any { normalizeArtistName(it) == norm1 }) return true
        
        return false
    }

    fun getLevenshteinDistance(s1: String, s2: String): Int {
        val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }
        for (i in 0..s1.length) dp[i][0] = i
        for (j in 0..s2.length) dp[0][j] = j
        for (i in 1..s1.length) {
            for (j in 1..s2.length) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = minOf(dp[i - 1][j] + 1, dp[i][j - 1] + 1, dp[i - 1][j - 1] + cost)
            }
        }
        return dp[s1.length][s2.length]
    }

    fun isTooSimilar(title1: String, title2: String): Boolean {
        val n1 = normalizeTitle(title1)
        val n2 = normalizeTitle(title2)
        if (n1 == n2) return true
        if (n1.contains(n2) || n2.contains(n1)) return true
        
        val maxLen = maxOf(n1.length, n2.length)
        if (maxLen == 0) return true
        val dist = getLevenshteinDistance(n1, n2)
        val similarity = 1.0 - (dist.toDouble() / maxLen.toDouble())
        return similarity > 0.70
    }

    fun isNonMusicVideo(title: String, author: String): Boolean {
        val titleLow = title.lowercase(Locale.ROOT)
        val authorLow = author.lowercase(Locale.ROOT)
        
        val blacklistTerms = listOf(
            "explained", "meaning", "reaction", "review", "breakdown", "story", "stories",
            "genius", "interview", "podcast", "documentary", "behind the scenes", "tutorial",
            "lesson", "news", "hidden meaning", "analysis", "funny", "parody", "reaction video",
            "reviewing", "lyrics", "lyric", "lyric video", "behind the song", "teaser", "promo",
            "leak", "shorts", "be like", "when you", "pov", "tiktok", "tiktoks", "meme", "memes",
            "comedy", "comedian", "prank", "vlog", "vlogs", "gaming", "gameplay", "roast", "standup",
            "rant", "compilation", "fails", "challenge", "unboxing", "how to play", "tutorial",
            "guitar cover lesson", "piano lesson", "behind the track", "1 hour", "1hour", "loop",
            "looped", "deep dive", "important song", "best song", "worst song", "top song",
            "irl", "dropped", "unofficial", "timeline", "beef", "drama", "funny moments", "fails"
        )
        
        for (term in blacklistTerms) {
            if (titleLow.contains(term) || authorLow.contains(term)) {
                return true
            }
        }
        
        val blacklistedChannelKeywords = listOf(
            "news", "tv", "comedy", "vlog", "gaming", "cricket", "tech", "review",
            "fitness", "food", "travel", "lifestyle", "kids", "cartoon", "meme",
            "unboxing", "essay", "analysis", "genius", "vlogger", "react", "reaction",
            "podcast", "podcasts", "interview", "interviews", "talks", "show", "entertainment",
            "media", "gamer", "games", "prank", "pranks", "roast", "roasts", "clips", "fails"
        )
        if (blacklistedChannelKeywords.any { authorLow.contains(it) }) {
            return true
        }
        return false
    }

    fun isCompilationTitle(title: String): Boolean {
        val t = title.lowercase(Locale.ROOT)
        val compileKeywords = listOf(
            "top 10", "top 20", "top 30", "top 40", "top 50", "top songs", "best songs", 
            "best of", "greatest hits", "jukebox", "mashup", "compilation", "all songs", 
            "full album", "full songs", "collection", "nonstop", "non-stop", "playlist",
            "hits of", "hits mix", "best mix", "song collection"
        )
        return compileKeywords.any { t.contains(it) }
    }

    fun isCompilationTrack(title: String, durationText: String): Boolean {
        if (isCompilationTitle(title)) return true

        val t = title.lowercase(Locale.ROOT)
        if (t.contains("30 min") || t.contains("60 min") || t.contains("1 hour") || 
            t.contains("nonstop") || t.contains("jukebox") || t.contains("compilation") || 
            t.contains("full album") || t.contains("mashup") || t.contains("all songs") || 
            t.contains("mix video") || t.contains("dj mix") || t.contains("non stop")) {
            return true
        }
        
        val parts = durationText.split(":")
        if (parts.size >= 3) return true // Over an hour long
        if (parts.size == 2) {
            val mins = parts[0].toIntOrNull() ?: 0
            if (mins >= 15) return true // > 15 minutes is treated as a compile/mix
        }
        return false
    }

    fun isCorporateOrDistributorChannel(author: String): Boolean {
        val authorLow = author.lowercase(Locale.ROOT)
        val corporateLabels = listOf(
            "t-series", "tseries", "t series", "zee music", "zeemusic", "sony music", "sonymusic", 
            "yrf", "yash raj", "saregama", "tips official", "tips industries", "aditya music",
            "lahari music", "white hill", "geet mp3", "jass records", "desi music factory",
            "vyrl", "hombale", "think music", "single track studios", "mufasa music",
            "shemaroo", "venus", "dharma", "reliance entertainment", "eros", "speed records",
            "speedrecords", "t-series regional", "tseries regional", "lts music", "ltsmusic",
            "times music", "timesmusic", "t-series regional", "tseries regional", "t-series apna punjab",
            "tseries apna punjab", "t-series haryanvi", "wave music", "t-series bhakti sagar", "hmv",
            "ultra regional", "ultra bollywood", "ultra music", "saregama hum bhojpuri", "saregama ghazal",
            "saregama punjabi", "zee music south", "zee music classic", "desi melodies", "speed records punjabi",
            "goldmines", "b4u", "vintage", "venus movies", "mars", "dhruvan", "madura audio", "anand audio",
            "haripa music", "muzik 247", "manorama music", "satyam auditions", "millennium audits", "speed audio",
            "paattu"
        )
        return corporateLabels.any { authorLow.contains(it) }
    }

    fun isUnofficialContent(title: String, author: String): Boolean {
        if (isCorporateOrDistributorChannel(author)) return false
        
        val titleLow = title.lowercase(Locale.ROOT)
        val authorLow = author.lowercase(Locale.ROOT)
        val fullText = "$titleLow $authorLow"
        
        val unofficialKeywords = listOf(
            "remix", "slowed", "reverb", "live", "cover", "reaction", "meme", 
            "fan-made", "fanmade", "mashup", "instrumental", "karaoke", 
            "nightcore", "sped up", "speed up", "tribute", "parody", 
            "roast", "gaming", "unboxing", "1 hour", "1hour", "loop", "looped",
            "fan edit", "status video", "shorts", "reels", "tutorial", "bts",
            "behind the scenes", "leak", "unplugged", "reaction video",
            "lofi", "lo-fi", "type beat", "typebeat", "type-beat", "study music",
            "sleep music", "10 hours", "8d audio", "vaporwave", "bass boosted",
            "slowed reverb", "slowed + reverb"
        )
        return unofficialKeywords.any { fullText.contains(it) }
    }

    fun isOfficialArtistChannel(title: String, author: String): Boolean {
        val authorLow = author.lowercase(Locale.ROOT)
        if (isCorporateOrDistributorChannel(author)) return true
        if (authorLow.contains("- topic") || authorLow.contains("vevo")) return true
        
        // Stricter check: if the channel contains keywords associated with user uploaders/curators,
        // it cannot be an official artist channel unless it matched the Topic/Vevo/Corporate checks above.
        val uploaderKeywords = listOf(
            "lyrics", "lyric", "vibe", "vibes", "chill", "chilled", "chillout",
            "nation", "beats", "beat", "prod", "producer",
            "lofi", "lo-fi", "slowed", "reverb", "reverbed", "sped", "speed",
            "mix", "mashup", "cover", "remix", "tv", "fm", "radio", "edits", "edit",
            "fan", "fanz", "tribute", "karaoke", "sub", "subs", "subbed",
            "translation", "translations", "uploader", "uploads", "upload", "channel",
            "songweed", "mr. scrub", "lix", "grow music", "ridhi sound", "vdj royal",
            "biffin", "uproxx", "webworthy", "rdcworld", "longbeachgriffy"
        )
        if (uploaderKeywords.any { authorLow.contains(it) }) return false
        
        return true
    }

    suspend fun getCachedOrInferredMetadata(db: VinDatabase, item: VideoItem): SongMetadata {
        val songKey = generateSongKey(item.author, item.title)
        val cachedFeature = try { db.songFeatureCacheDao().get(songKey) } catch (_: Exception) { null }
        
        if (cachedFeature != null) {
            return getCachedOrInferredMetadata(item, cachedFeature)
        }
        
        // No cached features — use inference (context-free version)
        return inferMetadata(item)
    }

    /**
     * Enhanced version that uses FeatureEstimator for accurate audio features.
     * Call this when you have a Context available (from ViewModel, Activity, etc.)
     */
    suspend fun getCachedOrInferredMetadata(
        db: VinDatabase,
        item: VideoItem,
        context: android.content.Context
    ): SongMetadata {
        val songKey = generateSongKey(item.author, item.title)
        val cachedFeature = try { db.songFeatureCacheDao().get(songKey) } catch (_: Exception) { null }
        
        if (cachedFeature != null) {
            return getCachedOrInferredMetadata(item, cachedFeature)
        }
        
        // No cached features — estimate from 500K DB
        val inferredMeta = inferMetadata(item)
        val recDb = try { RecommendationDatabase.getInstance(context) } catch (_: Exception) { null }
        
        if (recDb != null) {
            val estimated = FeatureEstimator.estimateFeatures(
                recDb, item.title, item.author, inferredMeta.genre, inferredMeta.mood
            )
            if (estimated != null) {
                // Cache the estimated features
                try {
                    val cacheEntry = SongFeatureCache(
                        songKey = songKey,
                        energyReal = estimated.energy,
                        bpmReal = estimated.tempo,
                        genreTags = "[]",
                        moodTags = "[]",
                        title = item.title,
                        artist = item.author,
                        synced = false,
                        likedByCount = 0
                    )
                    db.songFeatureCacheDao().insert(cacheEntry)
                    Log.d(TAG, "Cached estimated features for '${item.title}' by '${item.author}'")
                } catch (_: Exception) {}
                
                return inferredMeta.copy(
                    energy = estimated.energy.toDouble(),
                    tempo = estimated.tempo.toInt().coerceIn(40, 250)
                )
            }
        }
        
        // Fallback to inference only
        return inferredMeta
    }

    fun getCachedOrInferredMetadata(item: VideoItem, cachedFeature: SongFeatureCache?): SongMetadata {
        var meta = inferMetadata(item)
        if (cachedFeature != null) {
            var realGenre = meta.genre
            var realMood = meta.mood
            try {
                val gson = Gson()
                val typeToken = object : TypeToken<List<String>>() {}.type
                val genres: List<String> = gson.fromJson(cachedFeature.genreTags, typeToken) ?: emptyList()
                val moods: List<String> = gson.fromJson(cachedFeature.moodTags, typeToken) ?: emptyList()
                if (genres.isNotEmpty()) realGenre = genres[0]
                if (moods.isNotEmpty()) realMood = moods[0]
            } catch (_: Exception) {}

            meta = meta.copy(
                energy = cachedFeature.energyReal.toDouble(),
                tempo = if (cachedFeature.bpmReal in 40f..250f) cachedFeature.bpmReal.toInt() else meta.tempo,
                genre = realGenre,
                mood = realMood
            )
        }
        return meta
    }

    fun inferMetadata(item: VideoItem): SongMetadata {
        val title = item.title.lowercase(Locale.ROOT)
        val author = item.author.lowercase(Locale.ROOT)
        val fullText = "$title $author"

        // 1. Language Detection
        var language = "English"
        val punjabiKeywords = listOf(
            "punjabi", "jatt", "jatta", "munde", "munda", "kudi", "kudia", "patiala", 
            "punjab", "sidhu", "moose", "moosewala", "moose wala", "dhillon", "ap dhillon", 
            "aujla", "karan aujla", "dosanjh", "diljit", "shubh", "garry", "bhangra", 
            "kaur", "gaddi", "gabru", "nach", "suit", "daaru", "peg", "kahlon", "ammy", "virk"
        )
        val hindiKeywords = listOf(
            "hindi", "bollywood", "arijit", "singh", "kakkar", "nautiyal", "jubin", 
            "aslam", "atif", "sonu nigam", "shreya", "ghoshal", "alkas", "udit", 
            "kumars", "pritam", "ar rahman", "t-series", "zee music", "yrf", 
            "dil", "pyar", "pyaar", "tujhe", "yaar", "tere", "teri", "ishq", 
            "mohabbat", "kiya", "meri", "mera", "channa", "mereya", "raataan", 
            "lambiyan", "sajna", "sajan", "duniya", "zindagi", "sanam", "jaan", 
            "jaana", "naina", "ankhein", "saans", "humsafar", "dua", "khuda", 
            "seedhe maut", "krsna", "kr\$na", "divine", "emiway", "mc stan", "raftaar", "badshah"
        )
        val tamilKeywords = listOf("tamil", "anirudh", "arrahman", "ilayaraja", "kadhal", "kadhala", "kollywood", "yuvan", "srinivas", "vijay", "ajith", "kamal", "rajini")
        val koreanKeywords = listOf("k-pop", "bts", "blackpink", "twice", "korean", "newjeans", "stray kids", "exo", "jungkook", "jimin", "seventeen")

        // Known Hindi artists for more reliable detection
        val knownHindiArtists = listOf(
            "arijit singh", "atif aslam", "jubin nautiyal", "shreya ghoshal", "pritam",
            "sonu nigam", "alka yagnik", "udit narayan", "kumar sanu", "kishore kumar",
            "lata mangeshkar", "mohit chauhan", "shaan", "sunidhi chauh", "neha kakkar",
            "honey singh", "badshah", "raftaar", "divine", "emiway", "mc stan", "seedhe maut",
            "kr\$na", "king", "darshan raval", "stebin ben", "vaibhav gupta"
        )
        val knownPunjabiArtists = listOf(
            "sidhu moose wala", "diljit dosanjh", "ap dhillon", "karan aujla", "shubh",
            "gurinder gill", "ammy virk", "garry sandhu", "prem dhillon", "amrit maan",
            "karan aujla", "bohemia", "lehmber hussainpuri", "sukshinder shinda"
        )

        // Check artist name against known artists first (most reliable)
        val authorLower = author.lowercase(Locale.ROOT)
        if (knownPunjabiArtists.any { authorLower.contains(it) }) {
            language = "Punjabi"
        } else if (knownHindiArtists.any { authorLower.contains(it) }) {
            language = "Hindi"
        } else if (punjabiKeywords.any { fullText.contains(it) }) {
            language = "Punjabi"
        } else if (hindiKeywords.any { fullText.contains(it) }) {
            language = "Hindi"
        } else if (tamilKeywords.any { fullText.contains(it) }) {
            language = "Tamil"
        } else if (koreanKeywords.any { fullText.contains(it) }) {
            language = "Korean"
        }

        // 2. Genre Detection
        var genre = "Pop"
        val lofiKeywords = listOf("lofi", "lo-fi", "chill", "slowed", "reverb", "aesthetic", "bedtime", "relax", "meditate", "sleep", "study", "ambient", "peaceful", "calm")
        val rapKeywords = listOf("rap", "hip hop", "hiphop", "hip-hop", "cypher", "freestyle", "beat", "diss", "badshah", "raftaar", "kr\$na", "emiway", "mc stan", "divine", "drake", "eminem", "shubh", "kendrick", "lamar", "durk", "cole", "travis", "future", "lil baby", "savage", "boomin", "playboi", "carti", "kanye", "thug", "young stunners")
        val indieKeywords = listOf("indie", "prateek kuhad", "anuv jain", "local train", "yellow diary", "independent", "mitraz", "aditya rikhari", "darshan raval", "taba chake", "kuhad", "anuv", "local train", "chai met toast", "osho jain")
        val rockKeywords = listOf("rock", "metal", "grunge", "nirvana", "linkin park", "metallica", "guitar solo", "hard rock", "heavy metal", "punk")
        val bollywoodKeywords = listOf("bollywood", "t-series", "zee music", "yrf", "soundtrack", "ost", "arijit", "pritam", "saregama", "shreya", "kakkar", "nautiyal", "atif", "aslam", "sonu", "nigam", "udit", "rahman")

        if (lofiKeywords.any { fullText.contains(it) }) {
            genre = "Lofi"
        } else if (rapKeywords.any { fullText.contains(it) }) {
            genre = "Rap/Hip-Hop"
        } else if (indieKeywords.any { fullText.contains(it) }) {
            genre = "Indie"
        } else if (rockKeywords.any { fullText.contains(it) }) {
            genre = "Rock"
        } else if (bollywoodKeywords.any { fullText.contains(it) } && language == "Hindi") {
            genre = "Bollywood"
        } else if (punjabiKeywords.any { fullText.contains(it) } && language == "Punjabi") {
            genre = "Punjabi Folk"
        }

        // 3. Mood Detection
        var mood = "Chill/Relaxed"
        val romanticKeywords = listOf("love", "pyar", "dil", "ishq", "romantic", "mohabat", "humsafar", "tum", "tujhe", "shreya ghoshal", "sweetheart", "kiss", "valentine", "sanam")
        val sadKeywords = listOf("sad", "breakup", "broken", "dard", "gam", "alone", "crying", "judaa", "tanha", "tears", "lonely", "hurt")
        val energeticKeywords = listOf("remix", "edm", "party", "club", "dance", "gym", "workout", "dj", "punjabi", "badshah", "upbeat", "bhangra", "bass boosted", "trap")
        val happyKeywords = listOf("happy", "smile", "fun", "celebration", "summer", "good vibes", "cheerful", "sunny")
        val darkKeywords = listOf("dark", "heavy", "metal", "evil", "ghost", "shadow", "rage")

        if (sadKeywords.any { fullText.contains(it) }) {
            mood = "Sad"
        } else if (romanticKeywords.any { fullText.contains(it) }) {
            mood = "Romantic"
        } else if (energeticKeywords.any { fullText.contains(it) }) {
            mood = "Energetic"
        } else if (happyKeywords.any { fullText.contains(it) }) {
            mood = "Happy"
        } else if (darkKeywords.any { fullText.contains(it) }) {
            mood = "Dark"
        }

        // 4. Energy & Tempo
        var energy = 0.5
        var tempo = 100
        when (genre) {
            "Lofi" -> { energy = 0.25; tempo = 74 }
            "Sad" -> { energy = 0.3; tempo = 82 }
            "Rap/Hip-Hop" -> { energy = 0.82; tempo = 136 }
            "Rock" -> { energy = 0.88; tempo = 128 }
            "Punjabi Folk" -> { energy = 0.85; tempo = 124 }
            "Bollywood" -> {
                if (mood == "Romantic" || mood == "Sad") {
                    energy = 0.45; tempo = 88
                } else {
                    energy = 0.65; tempo = 110
                }
            }
            else -> {
                when (mood) {
                    "Energetic" -> { energy = 0.85; tempo = 128 }
                    "Sad" -> { energy = 0.35; tempo = 80 }
                    "Romantic" -> { energy = 0.5; tempo = 92 }
                    "Happy" -> { energy = 0.7; tempo = 115 }
                    "Dark" -> { energy = 0.8; tempo = 120 }
                    else -> { energy = 0.55; tempo = 96 }
                }
            }
        }
        
        val hash = item.videoId.hashCode()
        energy = (energy + (hash % 10) / 100.0).coerceIn(0.1, 0.99)
        tempo = (tempo + (hash % 15) - 7).coerceIn(60, 180)

        // 5. Year
        var year = 2025
        val yearRegex = Regex("\\b(19\\d\\d|20[0-2]\\d)\\b")
        val matchResult = yearRegex.find(item.title)
        if (matchResult != null) {
            year = matchResult.value.toIntOrNull() ?: 2025
        } else {
            if (fullText.contains("retro") || fullText.contains("classic") || fullText.contains("90s") || fullText.contains("80s") || fullText.contains("kishore") || fullText.contains("lata")) {
                year = 1995 + (hash % 15)
            } else {
                year = 2021 + (hash % 5)
            }
        }
        
        val isOfficial = isOfficialArtistChannel(item.title, item.author) && 
                         !isUnofficialContent(item.title, item.author)
        val sourceQuality = if (isOfficial) "Ultra HD (320kbps)" else "Standard Quality (128kbps)"

        return SongMetadata(
            title = item.title,
            artist = item.author,
            genre = genre,
            mood = mood,
            language = language,
            energy = energy,
            tempo = tempo,
            year = year,
            isOfficial = isOfficial,
            sourceQuality = sourceQuality
        )
    }

    // Cache for TasteProfile to prevent massive DB reads and CPU scans on every song change
    @Volatile
    private var cachedProfile: Pair<Long, TasteProfile>? = null

    fun invalidateTasteProfile() {
        cachedProfile = null
    }

    suspend fun buildTasteProfile(db: VinDatabase): TasteProfile = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val cached = cachedProfile
        if (cached != null && (now - cached.first) < 60000) { // 60-second cache TTL
            return@withContext cached.second
        }

        val signals = db.interactionSignalDao().getAll()
        val imports = db.playlistDao().getAllPlaylistSongs()
        val history = db.historyDao().getAllHistory()
        
        val cachedFeaturesMap = try {
            db.songFeatureCacheDao().getAll().associateBy { it.songKey }
        } catch (_: Exception) {
            emptyMap<String, SongFeatureCache>()
        }
        
        val artistScores = HashMap<String, Double>()
        val genreScores = HashMap<String, Double>()
        val moodScores = HashMap<String, Double>()
        val langScores = HashMap<String, Double>()
        
        val favoriteTracks = HashSet<String>()
        val skippedTracks = HashSet<String>()
        val skippedArtists = HashSet<String>()
        val downloadedTracks = ArrayList<InteractionSignal>()
        val likedTracks = ArrayList<InteractionSignal>()

        var weightedEnergy = 0.0
        var weightedTempo = 0.0
        var totalWeight = 0.0

        // 1. Process Interaction Signals (Play, Likes, Skips)
        for (sig in signals) {
            val author = sig.author.trim()
            if (author.isBlank() || author.lowercase() == "unknown") continue

            var score = 0.0
            score += sig.completeCount * 5.0
            score += sig.repeatCount * 6.0
            if (sig.isLiked) {
                score += 10.0
                likedTracks.add(sig)
            }
            if (sig.isDownloaded) {
                score += 8.0
                downloadedTracks.add(sig)
            }
            score += sig.searchClickCount * 3.0
            score -= sig.skip20sCount * 6.0

            if (sig.skipCount > 0 && sig.skip20sCount == 0) {
                score -= sig.skipCount * 3.0
            }

            if (score > 6.0) {
                favoriteTracks.add(sig.videoId)
            }

            if (sig.skip20sCount >= 2 || sig.skipCount >= 4) {
                skippedTracks.add(sig.videoId)
                if (sig.skipCount > sig.playCount) {
                    skippedArtists.add(author.lowercase(Locale.ROOT))
                }
            }

            artistScores[author] = (artistScores[author] ?: 0.0) + score

            val fakeItem = VideoItem(sig.videoId, sig.title, sig.author, sig.durationText)
            val songKey = generateSongKey(sig.author, sig.title)
            val cachedFeature = cachedFeaturesMap[songKey]
            val meta = getCachedOrInferredMetadata(fakeItem, cachedFeature)
            if (score > 0) {
                genreScores[meta.genre] = (genreScores[meta.genre] ?: 0.0) + score
                moodScores[meta.mood] = (moodScores[meta.mood] ?: 0.0) + score
                langScores[meta.language] = (langScores[meta.language] ?: 0.0) + score
            }

            // Continuous vector TasteDNA profile calculation merged in the same pass
            val vectorScore = sig.completeCount * 5.0 + sig.repeatCount * 6.0 + 
                              (if (sig.isLiked) 10.0 else 0.0) + (if (sig.isDownloaded) 8.0 else 0.0)
            if (vectorScore > 2.0) {
                weightedEnergy += meta.energy * vectorScore
                weightedTempo += meta.tempo * vectorScore
                totalWeight += vectorScore
            }
        }

        // 2. Process Imported Playlists (highly trains TasteDNA on cold/warm starts!)
        for (imp in imports) {
            val author = imp.author.trim()
            if (author.isBlank() || author.lowercase() == "unknown") continue
            
            // Give each imported song a dynamic base score weight of +3.0
            artistScores[author] = (artistScores[author] ?: 0.0) + 3.0
            
            val fakeItem = VideoItem(imp.videoId, imp.title, imp.author, imp.durationText)
            val songKey = generateSongKey(imp.author, imp.title)
            val cachedFeature = cachedFeaturesMap[songKey]
            val meta = getCachedOrInferredMetadata(fakeItem, cachedFeature)
            genreScores[meta.genre] = (genreScores[meta.genre] ?: 0.0) + 3.0
            moodScores[meta.mood] = (moodScores[meta.mood] ?: 0.0) + 3.0
            langScores[meta.language] = (langScores[meta.language] ?: 0.0) + 3.0
        }

        // 3. Process History Entries (Listen back logs)
        for (h in history) {
            val author = h.author.trim()
            if (author.isBlank() || author.lowercase() == "unknown") continue
            
            // Base weight for simple play history
            artistScores[author] = (artistScores[author] ?: 0.0) + 1.0
            
            val fakeItem = VideoItem(h.videoId, h.title, h.author, h.durationText)
            val songKey = generateSongKey(h.author, h.title)
            val cachedFeature = cachedFeaturesMap[songKey]
            val meta = getCachedOrInferredMetadata(fakeItem, cachedFeature)
            genreScores[meta.genre] = (genreScores[meta.genre] ?: 0.0) + 1.0
            moodScores[meta.mood] = (moodScores[meta.mood] ?: 0.0) + 1.0
            langScores[meta.language] = (langScores[meta.language] ?: 0.0) + 1.0
        }

        val sortedArtists = artistScores.toList()
            .filter { it.second > 0.0 }
            .sortedByDescending { it.second }
            .take(8)

        val sortedGenres = genreScores.toList().sortedByDescending { it.second }
        val sortedMoods = moodScores.toList().sortedByDescending { it.second }
        val sortedLangs = langScores.toList().sortedByDescending { it.second }
        
        // Add imports to vector profile calculation
        for (imp in imports.take(15)) {
            val fake = VideoItem(imp.videoId, imp.title, imp.author, imp.durationText)
            val songKey = generateSongKey(imp.author, imp.title)
            val cachedFeature = cachedFeaturesMap[songKey]
            val meta = getCachedOrInferredMetadata(fake, cachedFeature)
            weightedEnergy += meta.energy * 3.0
            weightedTempo += meta.tempo * 3.0
            totalWeight += 3.0
        }

        val targetEnergy = if (totalWeight > 0) (weightedEnergy / totalWeight) else 0.58
        val targetTempo = if (totalWeight > 0) (weightedTempo / totalWeight).toInt() else 105

        val tasteDNA = TasteDNA(
            targetEnergy = targetEnergy,
            targetTempo = targetTempo,
            preferredGenres = genreScores,
            preferredMoods = moodScores,
            preferredLanguages = langScores,
            preferredArtists = artistScores
        )

        val profile = TasteProfile(
            topArtists = sortedArtists,
            topGenres = sortedGenres,
            topMoods = sortedMoods,
            topLanguages = sortedLangs,
            favoriteTracks = favoriteTracks,
            skippedTracks = skippedTracks,
            skippedArtists = skippedArtists,
            downloadedTracks = downloadedTracks,
            likedTracks = likedTracks,
            tasteDNA = tasteDNA
        )
        cachedProfile = Pair(System.currentTimeMillis(), profile)
        profile
    }

    /**
     * Mathematically calculates cosine/vector similarity between candidate metadata and TasteDNA profile
     */
    fun calculateTasteSimilarity(meta: SongMetadata, dna: TasteDNA): Double {
        val maxGenreVal = dna.preferredGenres.values.maxOrNull() ?: 1.0
        val genreWeight = dna.preferredGenres[meta.genre] ?: 0.1
        val genreScore = (genreWeight / maxGenreVal).coerceIn(0.1, 1.0)

        val maxMoodVal = dna.preferredMoods.values.maxOrNull() ?: 1.0
        val moodWeight = dna.preferredMoods[meta.mood] ?: 0.1
        val moodScore = (moodWeight / maxMoodVal).coerceIn(0.1, 1.0)

        val maxLangVal = dna.preferredLanguages.values.maxOrNull() ?: 1.0
        val langWeight = dna.preferredLanguages[meta.language] ?: 0.1
        val langScore = (langWeight / maxLangVal).coerceIn(0.1, 1.0)

        // Artist score: check exact artist affinity or similar artist affinity
        val normMetaArtist = normalizeArtistName(meta.artist)
        var artistScore = 0.0
        val maxArtistVal = dna.preferredArtists.values.maxOrNull() ?: 1.0
        
        val artistWeight = dna.preferredArtists.entries.firstOrNull { 
            normalizeArtistName(it.key) == normMetaArtist 
        }?.value ?: 0.0
        
        if (artistWeight > 0.0) {
            artistScore = (artistWeight / maxArtistVal).coerceIn(0.0, 1.0)
        } else {
            val similarArtistMatch = dna.preferredArtists.entries.firstOrNull { (prefArtist, _) ->
                isSimilarArtist(prefArtist, meta.artist)
            }
            if (similarArtistMatch != null) {
                artistScore = (similarArtistMatch.value / maxArtistVal * 0.6).coerceIn(0.0, 1.0)
            }
        }

        // Energy similarity delta
        val energyDelta = Math.abs(meta.energy - dna.targetEnergy)
        val energyScore = (1.0 - energyDelta).coerceIn(0.0, 1.0)

        // Tempo similarity — octave equivalence: 90 BPM ≈ 180 BPM (half/double time)
        val bpm1 = meta.tempo.toDouble()
        val bpm2 = dna.targetTempo.toDouble()
        val effectiveTempoDelta = minOf(
            Math.abs(bpm1 - bpm2),
            Math.abs(bpm1 - bpm2 * 2.0),
            Math.abs(bpm1 * 2.0 - bpm2),
            Math.abs(bpm1 / 2.0 - bpm2),
            Math.abs(bpm1 - bpm2 / 2.0)
        )
        val tempoScore = Math.cos((effectiveTempoDelta / 60.0 * Math.PI).coerceIn(0.0, Math.PI)) / 2.0 + 0.5

        return (genreScore * 0.20) + (artistScore * 0.15) + (moodScore * 0.20) + (langScore * 0.15) + (energyScore * 0.15) + (tempoScore * 0.15)
    }

    /**
     * Builds 3 distinct parallel search queries for a seed track's radio queue.
     */
    fun buildQueriesForSeed(seedMeta: SongMetadata, similarArtists: List<String>): List<String> {
        val genreTerm = when (seedMeta.genre) {
            "Rap/Hip-Hop" -> "Rap Hip Hop"
            "Punjabi Folk" -> "Punjabi"
            else -> seedMeta.genre
        }
        val moodTerm = when (seedMeta.mood) {
            "Chill/Relaxed" -> "chill"
            "Energetic" -> "workout energetic"
            "Sad" -> "sad emotional"
            "Romantic" -> "romantic love"
            "Dark" -> "dark metal"
            "Happy" -> "happy upbeat"
            else -> "popular"
        }
        val langTerm = if (seedMeta.language != "English") seedMeta.language.lowercase(Locale.ROOT) else ""
        
        val queries = mutableListOf<String>()
        
        // Query 1: Genre + Mood + Language hits
        queries.add("$genreTerm $moodTerm $langTerm popular hits".trim().replace(Regex("\\s+"), " "))
        
        // Query 2: Similar Artist
        if (similarArtists.isNotEmpty()) {
            val pickedArtist = similarArtists.first()
            queries.add("artists like $pickedArtist popular songs")
        } else {
            queries.add("${seedMeta.artist} similar music")
        }
        
        // Query 3: Acoustic Vibe Blend
        queries.add("$genreTerm $moodTerm similar to ${seedMeta.artist}".trim().replace(Regex("\\s+"), " "))
        
        return queries
    }


    private suspend fun fetchCandidatesFromQueries(queries: List<String>): List<VideoItem> = coroutineScope {
        val deferredResults = queries.map { query ->
            async(Dispatchers.IO) {
                try {
                    InnerTube.search(query)
                } catch (e: Exception) {
                    Log.e(TAG, "Search query failed '$query': ${e.message}")
                    emptyList<VideoItem>()
                }
            }
        }
        deferredResults.awaitAll().flatten().distinctBy { it.videoId }
    }

    // ── SPOTIFY DYNAMIC DAILY MIXES CURATION ──────────────────────────────────────────

    suspend fun getSpotifyMixes(ctx: Context, forceRefresh: Boolean = false): List<SpotifyMix> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        
        synchronized(cachedMixes) {
            if (!forceRefresh && cachedMixes.isNotEmpty()) {
                return@withContext ArrayList(cachedMixes)
            }
        }

        Log.d(TAG, "Curating Spotify-Style Daily Mixes concurrently...")
        val db = VinDatabase.getInstance(ctx)
        val profile = buildTasteProfile(db)
        val dna = profile.tasteDNA

        val mixes = ArrayList<SpotifyMix>()
        val globalShownIds = HashSet<String>()

        // Dynamically compute the top 3 genre clusters from TasteDNA profile, fallback to defaults
        val topGenres = profile.topGenres.map { it.first }.filter { it.isNotBlank() }.toMutableList()
        val defaultFallbacks = listOf("Lofi", "Rap/Hip-Hop", "Bollywood")
        for (fallback in defaultFallbacks) {
            if (topGenres.size >= 3) break
            if (!topGenres.contains(fallback)) {
                topGenres.add(fallback)
            }
        }
        val selectedGenres = topGenres.take(3)

        // Generate Daily Mix 1, 2, and 3 based on these top clusters
        for (i in 0..2) {
            val genre = selectedGenres.getOrNull(i) ?: defaultFallbacks[i]
            val config = GENRE_CONFIGS[genre] ?: GenreMixConfig(
                description = "Your personal compilation of $genre tracks curated matching your TasteDNA.",
                queries = listOf("$genre official popular music", "$genre hit tracks playlist"),
                gradientStartHex = when (i) {
                    0 -> "0xFFC5A880"
                    1 -> "0xFFB39873"
                    else -> "0xFFD6BE9C"
                },
                gradientEndHex = when (i) {
                    0 -> "0xFF1E1A14"
                    1 -> "0xFF191612"
                    else -> "0xFF2C251C"
                },
                targetMood = "Chill/Relaxed"
            )

            val pool = fetchCandidatesFromQueries(config.queries)
            val songs = curateMixSongs(db, pool, profile, genre, genre, config.targetMood, globalShownIds)
            
            globalShownIds.addAll(songs.map { it.videoItem.videoId })

            mixes.add(SpotifyMix(
                id = "daily_mix_${i + 1}",
                title = "Daily Mix ${i + 1}",
                description = config.description,
                songs = songs,
                gradientStartHex = config.gradientStartHex,
                gradientEndHex = config.gradientEndHex
            ))
        }

        // 4. Discover Weekly Mix
        val dwQueries = listOf("underrated fresh acoustic gems", "new independent music releases 2026", "indie folk playlist viral")
        val dwPool = fetchCandidatesFromQueries(dwQueries)
        val dwSongs = curateDiscoverWeekly(db, dwPool, profile, globalShownIds, 8)
        globalShownIds.addAll(dwSongs.map { it.videoItem.videoId })
        mixes.add(SpotifyMix(
            id = "discover_weekly",
            title = "Discover Weekly",
            description = "Fresh discoveries matching your TasteDNA. Underrated official tracks you haven't heard yet.",
            songs = dwSongs,
            gradientStartHex = "0xFFC5A880", // Light Brown
            gradientEndHex = "0xFF1E1A14"   // Charcoal
        ))

        // 5. Release Radar Mix
        val rrQueries = listOf("new music release 2026", "latest official hits charts 2026")
        val rrPool = fetchCandidatesFromQueries(rrQueries)
        val rrSongs = curateReleaseRadar(db, rrPool, profile, globalShownIds, 8)
        globalShownIds.addAll(rrSongs.map { it.videoItem.videoId })
        mixes.add(SpotifyMix(
            id = "release_radar",
            title = "Release Radar",
            description = "The latest official releases from your preferred artists and matching genres.",
            songs = rrSongs,
            gradientStartHex = "0xFFB39873", // Gold
            gradientEndHex = "0xFF191612"   // Dark Charcoal
        ))

        // 6. Repeat Rewind Mix
        val rewindSongs = curateRepeatRewind(db, profile, globalShownIds, 8)
        mixes.add(SpotifyMix(
            id = "repeat_rewind",
            title = "Repeat Rewind",
            description = "Rewind your favorites. The songs you have played on repeat and liked the most.",
            songs = rewindSongs,
            gradientStartHex = "0xFFA38C6D", // Dull Gold
            gradientEndHex = "0xFF171411"   // Dark Charcoal
        ))


        synchronized(cachedMixes) {
            cachedMixes.clear()
            cachedMixes.addAll(mixes)
            lastMixCacheTime = System.currentTimeMillis()
        }
        mixes
    }

    /**
     * Applies the capped social proof boost to a taste score:
     * final_score = taste_score * (1 + 0.1 * min(likedByCount, 3))
     *
     * TODO: Sybil mitigation (trusted user list / account-age weighting) deferred until user base grows past current 5–10 scale.
     */
    private fun applySocialProofBoost(tasteScore: Double, likedByCount: Int): Double {
        val boostMultiplier = 1.0 + 0.1 * kotlin.math.min(likedByCount, 3)
        return tasteScore * boostMultiplier
    }

    private suspend fun curateMixSongs(
        db: VinDatabase,
        candidates: List<VideoItem>,
        profile: TasteProfile,
        targetGenre: String,
        fallbackGenre: String,
        targetMood: String,
        globalShownIds: HashSet<String> = HashSet(),
        limit: Int = 8
    ): List<RecommendedSong> {
        val scored = ArrayList<RecommendedSong>()
        val localArtists = HashMap<String, Int>()

        for (item in candidates) {
            if (globalShownIds.contains(item.videoId)) continue

            val title = item.title
            val author = item.author.trim()
            val normAuthor = author.lowercase(Locale.ROOT)
            val normTitle = normalizeTitle(title)

            if (isCompilationTrack(title, item.durationText)) continue
            if (!isOfficialArtistChannel(title, author) || isUnofficialContent(title, author)) continue
            if (profile.skippedTracks.contains(item.videoId) || profile.skippedArtists.contains(normAuthor)) continue

            val songKey = generateSongKey(author, title)
            val cachedFeature = try { db.songFeatureCacheDao().get(songKey) } catch (_: Exception) { null }
            
            var meta = inferMetadata(item)
            if (cachedFeature != null) {
                var realGenre = meta.genre
                var realMood = meta.mood
                try {
                    val gson = Gson()
                    val typeToken = object : TypeToken<List<String>>() {}.type
                    val genres: List<String> = gson.fromJson(cachedFeature.genreTags, typeToken) ?: emptyList()
                    val moods: List<String> = gson.fromJson(cachedFeature.moodTags, typeToken) ?: emptyList()
                    if (genres.isNotEmpty()) realGenre = genres[0]
                    if (moods.isNotEmpty()) realMood = moods[0]
                } catch (_: Exception) {}

                meta = meta.copy(
                    energy = cachedFeature.energyReal.toDouble(),
                    tempo = if (cachedFeature.bpmReal in 40f..250f) cachedFeature.bpmReal.toInt() else meta.tempo,
                    genre = realGenre,
                    mood = realMood
                )
            }

            val isGenreMatch = meta.genre == targetGenre || meta.genre == fallbackGenre
            val isMoodMatch = meta.mood == targetMood
            
            if (!isGenreMatch && !isMoodMatch) continue

            val userHistoryMatch = calculateTasteSimilarity(meta, profile.tasteDNA)
            
            // Boost exact matches slightly
            val baseScore = userHistoryMatch * 70.0 + (if (meta.isOfficial) 20.0 else 0.0) + (if (isGenreMatch) 10.0 else 0.0)
            val likedByCount = cachedFeature?.likedByCount ?: 0
            val finalScore = applySocialProofBoost(baseScore, likedByCount)
            scored.add(RecommendedSong(item, finalScore, "daily_mix", "Personal Mix match"))
        }

        val distinct = scored.distinctBy { it.videoItem.videoId }
            .distinctBy { "${normalizeTitle(it.videoItem.title)}|${it.videoItem.author.lowercase(Locale.ROOT)}" }
            .sortedByDescending { it.score }

        val selected = ArrayList<RecommendedSong>()
        for (rec in distinct) {
            if (selected.size >= limit) break
            val artLow = rec.videoItem.author.lowercase(Locale.ROOT)
            val currentCount = localArtists[artLow] ?: 0
            if (currentCount < 2) {
                selected.add(rec)
                localArtists[artLow] = currentCount + 1
            }
        }
        return selected
    }

    private suspend fun curateDiscoverWeekly(
        db: VinDatabase,
        candidates: List<VideoItem>,
        profile: TasteProfile,
        globalShownIds: HashSet<String> = HashSet(),
        limit: Int = 8
    ): List<RecommendedSong> {
        val scored = ArrayList<RecommendedSong>()
        val localArtists = HashMap<String, Int>()

        val mainstreamArtists = listOf(
            "arijit singh", "diljit dosanjh", "karan aujla", "sidhu moose wala", "badshah", 
            "drake", "eminem", "taylor swift", "ed sheeran", "the weeknd", "travis scott", 
            "post malone", "kendrick lamar", "21 savage", "j cole", "justin bieber", 
            "billie eilish", "neha kakkar", "jubin nautiyal", "king", "mc stan", "divine"
        )

        for (item in candidates) {
            if (globalShownIds.contains(item.videoId)) continue

            // Must not be in user favorites or high play history to ensure actual "Discovery"
            if (profile.favoriteTracks.contains(item.videoId)) continue

            val title = item.title
            val author = item.author.trim()
            val normAuthor = author.lowercase(Locale.ROOT)

            if (isCompilationTrack(title, item.durationText)) continue
            if (!isOfficialArtistChannel(title, author) || isUnofficialContent(title, author)) continue
            if (profile.skippedTracks.contains(item.videoId) || profile.skippedArtists.contains(normAuthor)) continue

            val songKey = generateSongKey(author, title)
            val cachedFeature = try { db.songFeatureCacheDao().get(songKey) } catch (_: Exception) { null }
            
            var meta = inferMetadata(item)
            if (cachedFeature != null) {
                var realGenre = meta.genre
                var realMood = meta.mood
                try {
                    val gson = Gson()
                    val typeToken = object : TypeToken<List<String>>() {}.type
                    val genres: List<String> = gson.fromJson(cachedFeature.genreTags, typeToken) ?: emptyList()
                    val moods: List<String> = gson.fromJson(cachedFeature.moodTags, typeToken) ?: emptyList()
                    if (genres.isNotEmpty()) realGenre = genres[0]
                    if (moods.isNotEmpty()) realMood = moods[0]
                } catch (_: Exception) {}

                meta = meta.copy(
                    energy = cachedFeature.energyReal.toDouble(),
                    tempo = if (cachedFeature.bpmReal in 40f..250f) cachedFeature.bpmReal.toInt() else meta.tempo,
                    genre = realGenre,
                    mood = realMood
                )
            }

            val similarity = calculateTasteSimilarity(meta, profile.tasteDNA)
            
            val isMainstream = mainstreamArtists.contains(normAuthor) || isCorporateOrDistributorChannel(author)
            val mainstreamPenalty = if (isMainstream) 15.0 else 0.0
            
            val isIndependent = !isMainstream && (meta.genre == "Indie" || meta.genre == "Lofi" || 
                                title.lowercase(Locale.ROOT).contains("indie") || 
                                author.lowercase(Locale.ROOT).contains("indie") ||
                                title.lowercase(Locale.ROOT).contains("independent"))
            val independentBoost = if (isIndependent) 10.0 else 0.0

            // Boost official quality, penalize if artist is already super famous to promote true discover weekly gems!
            val baseScore = similarity * 80.0 + (if (meta.isOfficial) 20.0 else 0.0) - mainstreamPenalty + independentBoost
            val likedByCount = cachedFeature?.likedByCount ?: 0
            val finalScore = applySocialProofBoost(baseScore, likedByCount)
            scored.add(RecommendedSong(item, finalScore, "discover_weekly", "Fresh new track match"))
        }

        val distinct = scored.distinctBy { it.videoItem.videoId }
            .distinctBy { "${normalizeTitle(it.videoItem.title)}|${it.videoItem.author.lowercase(Locale.ROOT)}" }
            .sortedByDescending { it.score }

        val selected = ArrayList<RecommendedSong>()
        for (rec in distinct) {
            if (selected.size >= limit) break
            val artLow = rec.videoItem.author.lowercase(Locale.ROOT)
            val currentCount = localArtists[artLow] ?: 0
            if (currentCount < 2) {
                selected.add(rec)
                localArtists[artLow] = currentCount + 1
            }
        }
        return selected
    }

    private suspend fun curateReleaseRadar(
        db: VinDatabase,
        candidates: List<VideoItem>,
        profile: TasteProfile,
        globalShownIds: HashSet<String> = HashSet(),
        limit: Int = 8
    ): List<RecommendedSong> {
        val scored = ArrayList<RecommendedSong>()
        val localArtists = HashMap<String, Int>()

        for (item in candidates) {
            if (globalShownIds.contains(item.videoId)) continue

            val title = item.title
            val author = item.author.trim()
            val normAuthor = author.lowercase(Locale.ROOT)

            if (isCompilationTrack(title, item.durationText)) continue
            if (!isOfficialArtistChannel(title, author) || isUnofficialContent(title, author)) continue
            if (profile.skippedTracks.contains(item.videoId) || profile.skippedArtists.contains(normAuthor)) continue

            val songKey = generateSongKey(author, title)
            val cachedFeature = try { db.songFeatureCacheDao().get(songKey) } catch (_: Exception) { null }
            
            var meta = inferMetadata(item)
            if (cachedFeature != null) {
                var realGenre = meta.genre
                var realMood = meta.mood
                try {
                    val gson = Gson()
                    val typeToken = object : TypeToken<List<String>>() {}.type
                    val genres: List<String> = gson.fromJson(cachedFeature.genreTags, typeToken) ?: emptyList()
                    val moods: List<String> = gson.fromJson(cachedFeature.moodTags, typeToken) ?: emptyList()
                    if (genres.isNotEmpty()) realGenre = genres[0]
                    if (moods.isNotEmpty()) realMood = moods[0]
                } catch (_: Exception) {}

                meta = meta.copy(
                    energy = cachedFeature.energyReal.toDouble(),
                    tempo = if (cachedFeature.bpmReal in 40f..250f) cachedFeature.bpmReal.toInt() else meta.tempo,
                    genre = realGenre,
                    mood = realMood
                )
            }

            // Release radar focuses heavily on fresh 2025/2026 releases!
            if (meta.year < 2024) continue

            val similarity = calculateTasteSimilarity(meta, profile.tasteDNA)
            var artistBoost = 0.0
            if (profile.topArtists.any { it.first.lowercase(Locale.ROOT) == normAuthor }) {
                artistBoost = 20.0
            }

            val baseScore = similarity * 60.0 + artistBoost + 20.0 // 20.0 official release bonus
            val likedByCount = cachedFeature?.likedByCount ?: 0
            val finalScore = applySocialProofBoost(baseScore, likedByCount)
            scored.add(RecommendedSong(item, finalScore, "release_radar", "New release match"))
        }

        val distinct = scored.distinctBy { it.videoItem.videoId }
            .distinctBy { "${normalizeTitle(it.videoItem.title)}|${it.videoItem.author.lowercase(Locale.ROOT)}" }
            .sortedByDescending { it.score }

        val selected = ArrayList<RecommendedSong>()
        for (rec in distinct) {
            if (selected.size >= limit) break
            val artLow = rec.videoItem.author.lowercase(Locale.ROOT)
            val currentCount = localArtists[artLow] ?: 0
            if (currentCount < 2) {
                selected.add(rec)
                localArtists[artLow] = currentCount + 1
            }
        }
        return selected
    }

    private suspend fun curateRepeatRewind(
        db: VinDatabase,
        profile: TasteProfile,
        globalShownIds: HashSet<String> = HashSet(),
        limit: Int = 8
    ): List<RecommendedSong> = withContext(Dispatchers.IO) {
        val historyList = db.historyDao().getAllHistory()
        val scored = ArrayList<RecommendedSong>()
        val globalVideoIds = HashSet<String>()

        // Gather all highly interacted songs (likes, downloads, or high play counts)
        val signals = db.interactionSignalDao().getAll()

        for (sig in signals) {
            if (globalVideoIds.contains(sig.videoId) || globalShownIds.contains(sig.videoId)) continue
            
            val item = VideoItem(sig.videoId, sig.title, sig.author, sig.durationText)
            if (isCompilationTrack(sig.title, sig.durationText)) continue
            
            // Calculate mathematical exponential time decay using lastPlayedAt to favor recent obsessions
            val ageDays = if (sig.lastPlayedAt > 0) {
                (System.currentTimeMillis() - sig.lastPlayedAt).toDouble() / (1000.0 * 60.0 * 60.0 * 24.0)
            } else {
                30.0 // Default to 30 days if not set
            }
            val decayFactor = Math.exp(-ageDays / 14.0) // 14-day exponential decay half-life

            val baseScore = sig.playCount * 5.0 + sig.repeatCount * 6.0 + 
                            (if (sig.isLiked) 10.0 else 0.0) + (if (sig.isDownloaded) 8.0 else 0.0)
            val score = baseScore * decayFactor
            
            if (score >= 1.0) {
                scored.add(RecommendedSong(item, score, "repeat_rewind", "Your highly played favorite"))
                globalVideoIds.add(sig.videoId)
            }
        }

        // Add plain history entries if we need more songs
        for (h in historyList) {
            if (scored.size >= limit) break
            if (globalVideoIds.contains(h.videoId) || globalShownIds.contains(h.videoId)) continue
            if (isCompilationTrack(h.title, h.durationText)) continue
            
            val item = VideoItem(h.videoId, h.title, h.author, h.durationText)
            scored.add(RecommendedSong(item, 1.0, "repeat_rewind", "From your history"))
            globalVideoIds.add(h.videoId)
        }

        scored.sortedByDescending { it.score }.take(limit)
    }

    // ── SPOTIFY-STYLE RADIO AUTOPLAY SIMILARITY MATCHING ──────────────────────────

    suspend fun getAutoplayRecommendations(ctx: Context, seedSong: VideoItem): List<VideoItem> = withContext(Dispatchers.IO) {
        Log.d(TAG, "Generating YTM-first autoplay for '${seedSong.title}'...")
        
        val pool = mutableListOf<VideoItem>()
        
        // 1. PRIMARY: YTM Related (fresh, algorithm-curated)
        try {
            val ytmNext = com.vinmusic.innertube.YTMusicApi.getNextRelated(
                seedSong.videoId, 
                playlistId = "RDAMVM${seedSong.videoId}"
            )
            val browse = ytmNext.relatedBrowse
            if (browse != null) {
                val related = com.vinmusic.innertube.YTMusicApi.getRelatedSongs(browse.browseId, browse.params)
                if (related.isNotEmpty()) {
                    Log.d(TAG, "YTM Related: ${related.size} tracks")
                    pool.addAll(related)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "YTM Related failed: ${e.message}")
        }

        // 2. SECONDARY: YTM Radio (RDAMVM radio playlist)
        if (pool.size < 10) {
            try {
                val radio = InnerTube.getWatchNextRadio(seedSong.videoId)
                if (radio.isNotEmpty()) {
                    Log.d(TAG, "YTM Radio: ${radio.size} tracks")
                    for (track in radio) {
                        if (pool.none { it.videoId == track.videoId }) {
                            pool.add(track)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "YTM Radio failed: ${e.message}")
            }
        }

        // 3. FALLBACK: Simple search (only if pool is small)
        if (pool.size < 5) {
            try {
                val seedMeta = inferMetadata(seedSong)
                val yr = java.time.LocalDate.now().year
                val queries = listOf(
                    "${seedMeta.genre.lowercase(Locale.ROOT)} official hits $yr",
                    "${seedSong.author} similar artists"
                )
                for (query in queries) {
                    val results = InnerTube.search(query).take(5)
                    for (item in results) {
                        if (pool.none { it.videoId == item.videoId } &&
                            !isCompilationTrack(item.title, item.durationText) &&
                            !isUnofficialContent(item.title, item.author)) {
                            pool.add(item)
                        }
                    }
                    if (pool.size >= 10) break
                }
            } catch (e: Exception) {
                Log.w(TAG, "Search fallback failed: ${e.message}")
            }
        }

        // Simple filtering + diversity
        pool.filter { it.videoId != seedSong.videoId }
            .distinctBy { it.videoId }
            .distinctBy { "${normalizeTitle(it.title)}|${it.author.lowercase(Locale.ROOT)}" }
            .take(10)
    }

    // ── GENERAL PERSONALIZED SHELVES (DIVERSITY & CAP GUARANTEE) ──────────────────

    suspend fun getRecommendations(ctx: Context, forceRefresh: Boolean = false): List<Pair<String, List<RecommendedSong>>> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        
        // 1. Resolve current biological hour and expected time-of-day section key
        val calendar = java.util.Calendar.getInstance()
        val hour = calendar.get(java.util.Calendar.HOUR_OF_DAY)
        val expectedTimeSectionKey = when {
            hour in 5..11 -> "Morning Acoustic Sunshine"
            hour in 12..20 -> "Midday Chill & Focus"
            else -> "Midnight Sanctuary"
        }

        // 2. Memory cache is stale-first. Only forceRefresh should replace it.
        synchronized(cachedSections) {
            if (!forceRefresh && cachedSections.isNotEmpty()) {
                val totalSongs = cachedSections.sumOf { it.second.size }
                if (totalSongs > 0) {
                    Log.d(TAG, "Returning memory cached personalized sections.")
                    return@withContext ArrayList(cachedSections)
                } else {
                    cachedSections.clear()
                    lastCacheTime = 0L
                }
            }
        }

        // 3. Disk cache is also stale-first; manual pull refresh clears/replaces it.
        if (!forceRefresh) {
            val disk = loadFromDisk(ctx)
            if (disk != null && disk.isNotEmpty()) {
                synchronized(cachedSections) {
                    cachedSections.clear()
                    cachedSections.addAll(disk)
                }
                return@withContext disk
            }
        }

        Log.d(TAG, "Generating advanced personalized recommendation shelves concurrently...")
            val db = VinDatabase.getInstance(ctx)
        val profile = buildTasteProfile(db)
        val dna = profile.tasteDNA

        data class CurationTask(
            val sectionKey: String,
            val queries: List<String>,
            val seedItem: VideoItem?,
            val sourceType: String
        )

        val tasks = ArrayList<CurationTask>()

        // 0. Your Taste Mix — genre-based discovery via Every Noise, NOT artist-name echo
        val topGenresForMix = profile.topGenres.map { it.first }.filter { it.isNotBlank() }.take(3)
        val mixQueries = mutableListOf<String>()
        for (genre in topGenresForMix) {
            val genreLower = genre.lowercase(Locale.ROOT).replace("rap/hip-hop", "rap hip hop").replace("punjabi folk", "punjabi")
            mixQueries.add("$genreLower official hits 2026")
            mixQueries.add("$genreLower underrated songs")
            // Add Every Noise similar genres for cross-genre discovery
            val similarGenres = genreSimilarMap?.get(genreLower)?.take(1) ?: emptyList()
            for (sg in similarGenres) {
                mixQueries.add("$sg official popular songs")
            }
        }
        // Add mood + language blends instead of artist names
        val topMoodForMix = profile.topMoods.firstOrNull()?.first?.lowercase(Locale.ROOT) ?: "chill"
        val topLangForMix = profile.topLanguages.firstOrNull()?.first?.lowercase(Locale.ROOT) ?: "english"
        mixQueries.add("$topMoodForMix $topLangForMix music official")
        if (mixQueries.isEmpty()) {
            mixQueries.addAll(listOf("popular hits music 2026", "hindi english punjabi songs hits"))
        }
        tasks.add(CurationTask(
            sectionKey = "Your Taste Mix",
            queries = mixQueries,
            seedItem = null,
            sourceType = "your_taste_mix"
        ))

        // 1. More from [ArtistName] — one shelf per top artist (up to 3)
        val historyList = try { db.historyDao().getAllHistory() } catch (_: Exception) { emptyList() }
        val interactionSignals = try { db.interactionSignalDao().getAll() } catch (_: Exception) { emptyList() }
        val listenedArtists = (historyList.map { it.author.trim() } + interactionSignals.map { it.author.trim() })
            .filter { it.isNotBlank() && it.lowercase() != "unknown" && !isCorporateOrDistributorChannel(it) }
        val cleanListenedCount = listenedArtists.map { normalizeArtistName(it) }.distinct().size

        val topArtistsList = profile.topArtists.map { it.first }.filter { it.isNotBlank() }

        // Only show "More from artist" for artists the user has actually listened to
        // For cold start (< 5 artists), use their top played artists instead of hardcoded fallback
        val moreFromArtists = if (cleanListenedCount >= 5) {
            topArtistsList.take(5)
        } else if (cleanListenedCount > 0) {
            // Use top played artists from interaction signals
            interactionSignals.sortedByDescending { it.playCount }
                .map { it.author.trim() }
                .filter { it.isNotBlank() && !isCorporateOrDistributorChannel(it) }
                .distinct()
                .take(3)
        } else {
            emptyList()
        }
        
        // Add a separate CurationTask per artist
        for (artistName in moreFromArtists) {
            tasks.add(CurationTask(
                sectionKey = "More from $artistName",
                queries = listOf(
                    "$artistName official audio popular",
                    "$artistName hit songs",
                    "$artistName deep cuts official",
                    "$artistName live session acoustic"
                ),
                seedItem = null,
                sourceType = "more_from_artist"
            ))
        }

        // 2. Rewind: Listen Back
        // Fetches from your history and highly played list - entirely offline seed!
        val rewindQueries = emptyList<String>() // Rewind is handled offline from local DB candidates!
        tasks.add(CurationTask("Rewind: Listen Back", rewindQueries, null, "rewind_listen_back"))

        // 4. Similar songs (dynamically seeded by most representative track)
        // Uses weighted scoring: play count + recency + likes to find the best seed
        val recentPlayed = historyList.firstOrNull()
        val topPlayedList = interactionSignals.sortedByDescending { it.playCount }

        val similarSeed = if (cleanListenedCount >= 5) {
            // Find the most representative seed: combine play count, recency, and likes
            val scoredTracks = interactionSignals
                .filter { it.playCount > 0 }
                .map { sig ->
                    val recencyScore = if (sig.lastPlayedAt > 0) {
                        val hoursAgo = (System.currentTimeMillis() - sig.lastPlayedAt) / (1000 * 60 * 60)
                        when {
                            hoursAgo < 24 -> 30.0    // Played today
                            hoursAgo < 72 -> 20.0    // Played in last 3 days
                            hoursAgo < 168 -> 10.0   // Played in last week
                            else -> 5.0
                        }
                    } else 0.0
                    val playScore = (sig.playCount * 2.0).coerceAtMost(20.0)
                    val likeScore = if (sig.isLiked) 15.0 else 0.0
                    val completeScore = (sig.completeCount * 1.5).coerceAtMost(10.0)
                    sig to (playScore + recencyScore + likeScore + completeScore)
                }
                .sortedByDescending { it.second }

            scoredTracks.firstOrNull()?.first
        } else {
            null
        }
        
        val knownArtists = profile.topArtists.map { it.first }.toSet() +
            profile.skippedArtists
        val recDbInstance = try { RecommendationDatabase.getInstance(ctx) } catch (_: Exception) { null }

        val similarQueries = if (similarSeed != null && recDbInstance != null) {
            val seedMetaForQuery = getCachedOrInferredMetadata(
                VideoItem(similarSeed.videoId, similarSeed.title, similarSeed.author, similarSeed.durationText),
                null
            )
            try {
                buildAcousticQueriesForSeed(recDbInstance, seedMetaForQuery, knownArtists, db)
            } catch (_: Exception) {
                val genreLower = seedMetaForQuery.genre.lowercase(Locale.ROOT).replace("rap/hip-hop", "rap hip hop").replace("punjabi folk", "punjabi")
                val similarGenres = genreSimilarMap?.get(genreLower)?.take(1) ?: emptyList()
                val genreQueries = similarGenres.map { "$it official popular songs" }
                genreQueries + listOf(
                    "$genreLower similar vibes official hits",
                    "$genreLower underrated artists songs"
                )
            }
        } else if (similarSeed != null) {
            val seedMetaFallback = inferMetadata(VideoItem(similarSeed.videoId, similarSeed.title, similarSeed.author, similarSeed.durationText))
            val genreLower = seedMetaFallback.genre.lowercase(Locale.ROOT).replace("rap/hip-hop", "rap hip hop").replace("punjabi folk", "punjabi")
            val artistName = similarSeed.author.trim()

            // Build queries based on seed track's genre, mood, and artist
            val queries = mutableListOf<String>()

            // Primary: genre + mood queries
            queries.add("$genreLower similar vibes official hits")
            queries.add("$genreLower ${seedMetaFallback.mood.lowercase()} songs official")

            // Secondary: artist similarity queries
            val similarGenres = genreSimilarMap?.get(genreLower)?.take(2) ?: emptyList()
            for (sg in similarGenres) {
                val sgDisplay = sg.replace(Regex("(?<=[a-z])(?=[A-Z])"), " ").lowercase()
                queries.add("$sgDisplay official popular songs")
            }

            // Tertiary: energy-based queries
            val energyTerm = when {
                seedMetaFallback.energy < 0.35 -> "chill relaxed"
                seedMetaFallback.energy > 0.75 -> "energetic upbeat"
                else -> "mid tempo"
            }
            queries.add("$genreLower $energyTerm official audio")

            queries.take(5)
        } else {
            // Cold start: no listening history — serve diverse genre discovery
            val coldStartGenres = listOf("hip hop", "pop", "indie", "r&b", "bollywood", "electronic")
            val selectedGenres = coldStartGenres.shuffled().take(3)
            selectedGenres.flatMap { g -> listOf("$g official hits 2026", "$g underrated songs") }
        }
        val seedVideo = similarSeed?.let { VideoItem(it.videoId, it.title, it.author, it.durationText) }
        tasks.add(CurationTask("Similar songs", similarQueries, seedVideo, "similar_songs"))

        val topGenresForShelves = profile.topGenres.map { it.first }.filter { it.isNotBlank() }.take(4)
        for (genre in topGenresForShelves) {
            val genreLower = genre.lowercase(Locale.ROOT).replace("rap/hip-hop", "rap hip hop").replace("punjabi folk", "punjabi")
            val similarGenres = genreSimilarMap?.get(genreLower)?.take(1) ?: emptyList()
            val similarGenreQuery = similarGenres.firstOrNull()?.let { " $it" } ?: ""
            
            tasks.add(CurationTask(
                sectionKey = "$genre for you",
                queries = listOf(
                    "$genreLower official hits 2026",
                    "$genreLower fresh releases",
                    "$genreLower underrated songs",
                    "$genreLower$similarGenreQuery playlist 2026"
                ),
                seedItem = null,
                sourceType = "genre_for_you"
            ))
        }

        val topMoodsForShelves = profile.topMoods.map { it.first }.filter { it.isNotBlank() }.take(3)
        for (mood in topMoodsForShelves) {
            val moodLower = mood.lowercase(Locale.ROOT)
            tasks.add(CurationTask(
                sectionKey = "$mood mood",
                queries = listOf(
                    "$moodLower music official songs",
                    "$moodLower playlist popular",
                    "$moodLower songs hindi english punjabi",
                    "$moodLower indie pop music"
                ),
                seedItem = null,
                sourceType = "mood_for_you"
            ))
        }

        val topLanguagesForShelves = profile.topLanguages.map { it.first }.filter { it.isNotBlank() }.take(3)
        for (language in topLanguagesForShelves) {
            val langLower = language.lowercase(Locale.ROOT)
            tasks.add(CurationTask(
                sectionKey = "$language picks",
                queries = listOf(
                    "$langLower songs latest hits 2026",
                    "$langLower music official audio",
                    "$langLower indie pop playlist",
                    "$langLower romantic energetic songs"
                ),
                seedItem = null,
                sourceType = "language_for_you"
            ))
        }

        // "Fans also like" — discover adjacent artists via Every Noise genre graph, not hardcoded map
        val adjacentGenres = topGenresForShelves.flatMap { genre ->
            val gLower = genre.lowercase(Locale.ROOT).replace("rap/hip-hop", "rap hip hop")
            genreSimilarMap?.get(gLower)?.take(2) ?: emptyList()
        }.distinct().take(6)
        
        val adjacentArtistsFromGenres = adjacentGenres.flatMap { adjGenre ->
            val adjLower = adjGenre.lowercase(Locale.ROOT)
            // Find a few artists in this genre from the Spotify DB
            try {
                val recDbForAdjacent = recDbInstance ?: RecommendationDatabase.getInstance(ctx)
                recDbForAdjacent.trackDao().getSimilarTracksInCluster(
                    targetCluster = 0, targetEnergy = 50, targetValence = 50,
                    targetDance = 50, targetAcoustic = 50, targetTempo = 120, limit = 5
                ).filter { it.genre.lowercase(Locale.ROOT).contains(adjLower) || adjLower.contains(it.genre.lowercase(Locale.ROOT)) }
                 .map { it.artist }.distinct().take(2)
            } catch (_: Exception) { emptyList() }
        }.distinct().filter { adj ->
            moreFromArtists.none { normalizeArtistName(it) == normalizeArtistName(adj) }
        }.take(6)
        
        if (adjacentArtistsFromGenres.isNotEmpty()) {
            tasks.add(CurationTask(
                sectionKey = "Fans also like",
                queries = adjacentArtistsFromGenres.flatMap { artist -> listOf("$artist official songs", "$artist popular tracks") },
                seedItem = null,
                sourceType = "artists_like"
            ))
        }

        tasks.add(CurationTask(
            sectionKey = "Fresh finds",
            queries = listOf(
                "new music releases 2026 official audio",
                "fresh indie pop songs 2026",
                "underrated artists songs 2026",
                "new hindi punjabi english songs"
            ),
            seedItem = null,
            sourceType = "fresh_finds"
        ))

        tasks.add(CurationTask(
            sectionKey = "Deep cuts & hidden gems",
            queries = listOf(
                "underrated indie songs official audio",
                "lesser known artist hits official",
                "overlooked tracks worth listening",
                "underrated rap bollywood punjabi songs official"
            ),
            seedItem = null,
            sourceType = "hidden_gems"
        ))

        // 5. Dynamic Time-of-Day Curation Task — uses genre graph, NOT hardcoded artists
        val topGenresForTime = profile.topGenres.map { it.first }.filter { it.isNotBlank() }.take(2)
        val timeCuration = when (expectedTimeSectionKey) {
            "Morning Acoustic Sunshine" -> {
                val genreTerms = topGenresForTime.ifEmpty { listOf("acoustic", "indie folk") }
                CurationTask(
                    sectionKey = expectedTimeSectionKey,
                    queries = genreTerms.flatMap { g ->
                        val gLower = g.lowercase(Locale.ROOT)
                        listOf("$gLower acoustic morning hits", "$gLower soft chill official")
                    } + listOf("acoustic pop hits official", "fresh morning songs official audio"),
                    seedItem = null,
                    sourceType = "morning_vibe"
                )
            }
            "Midday Chill & Focus" -> {
                val genreTerms = topGenresForTime.ifEmpty { listOf("lofi", "indie") }
                CurationTask(
                    sectionKey = expectedTimeSectionKey,
                    queries = genreTerms.flatMap { g ->
                        val gLower = g.lowercase(Locale.ROOT).replace("rap/hip-hop", "rap hip hop")
                        listOf("$gLower chill focus official", "$gLower soft popular songs")
                    } + listOf("chill ambient focus music official", "soft instrumental study songs"),
                    seedItem = null,
                    sourceType = "afternoon_vibe"
                )
            }
            else -> {
                val genreTerms = topGenresForTime.ifEmpty { listOf("indie", "r&b") }
                CurationTask(
                    sectionKey = expectedTimeSectionKey,
                    queries = genreTerms.flatMap { g ->
                        val gLower = g.lowercase(Locale.ROOT).replace("rap/hip-hop", "rap hip hop")
                        listOf("$gLower romantic slow songs official", "$gLower sad emotional hits")
                    } + listOf("late night chill emotional songs official", "romantic aesthetic night music"),
                    seedItem = null,
                    sourceType = "night_vibe"
                )
            }
        }
        tasks.add(timeCuration)

        val newSections = ArrayList<Pair<String, List<RecommendedSong>>>()
        val compilationSongs = java.util.Collections.synchronizedList(ArrayList<RecommendedSong>())

        
        // GLOBAL ARTIST & DUPES DE-CLUSTERING FILTER: Cap at 2 tracks per artist globally on the Home Screen!
        val globalArtistCounts = HashMap<String, Int>()
        val globalShownVideoIds = HashSet<String>()
        val globalShownTitlesAndArtists = HashSet<String>()

        // Global exploration flag: 10% chance across ALL shelves (not per-shelf)
        val shouldExplore = Math.random() < 0.10
        var explorationUsed = false

        coroutineScope {
            val deferredResults = tasks.map { task ->
                async(Dispatchers.IO) {
                    val taskCandidates = ArrayList<VideoItem>()
                    
                    if (task.sourceType == "rewind_listen_back") {
                        // Offline Rewind: Gather from history and interaction signals
                        val historyList = db.historyDao().getAllHistory()
                        val signalsList = db.interactionSignalDao().getAll()
                        
                        val offlinePool = (historyList.map { VideoItem(it.videoId, it.title, it.author, it.durationText) } +
                                          signalsList.map { VideoItem(it.videoId, it.title, it.author, it.durationText) })
                                          .distinctBy { it.videoId }
                        taskCandidates.addAll(offlinePool)
                    } else if (task.sourceType == "your_taste_mix") {
                        // Blend random history/likes (up to 8)
                        val historyList = db.historyDao().getAllHistory()
                        val signalsList = db.interactionSignalDao().getAll()
                        val offlinePool = (historyList.map { VideoItem(it.videoId, it.title, it.author, it.durationText) } +
                                          signalsList.map { VideoItem(it.videoId, it.title, it.author, it.durationText) })
                                          .distinctBy { it.videoId }
                        val randomHistory = offlinePool.shuffled().take(8)
                        taskCandidates.addAll(randomHistory)

                        // And add search query results
                        val queryJobs = task.queries.map { query ->
                            async(Dispatchers.IO) {
                                try {
                                    InnerTube.search(query)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Search query failed '$query': ${e.message}")
                                    emptyList<VideoItem>()
                                }
                            }
                        }
                        val queryResults = queryJobs.awaitAll()
                        for (res in queryResults) {
                            taskCandidates.addAll(res)
                        }
                    } else {
                        val queryJobs = task.queries.map { query ->
                            async(Dispatchers.IO) {
                                try {
                                    InnerTube.search(query)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Search query failed '$query': ${e.message}")
                                    emptyList<VideoItem>()
                                }
                            }
                        }
                        val queryResults = queryJobs.awaitAll()
                        for (res in queryResults) {
                            taskCandidates.addAll(res)
                        }
                    }
                    task to taskCandidates
                }
            }

            val taskResults = deferredResults.awaitAll()

            for ((task, candidates) in taskResults) {
                val filteredScored = ArrayList<RecommendedSong>()
                
                // Shuffle candidates dynamically on every call to avoid fixed/static playlist grids and promote fresh discoveries
                val shuffledCandidates = candidates.shuffled()
                
                val candidateVideoIds = shuffledCandidates.map { it.videoId }
                val signalsMap = db.interactionSignalDao().getAll()
                    .filter { it.videoId in candidateVideoIds }
                    .associateBy { it.videoId }

                // Batch-fetch all song features to avoid N+1 DB queries
                val featureCacheMap = try {
                    db.songFeatureCacheDao().getAll().associateBy { it.songKey }
                } catch (_: Exception) { emptyMap() }

                for (item in shuffledCandidates) {
                    val author = item.author.trim()
                    val title = item.title.trim()
                    val normAuthor = author.lowercase(Locale.ROOT)
                    val normTitle = normalizeTitle(title)

                    val songKey = generateSongKey(author, title)
                    val cachedFeature = featureCacheMap[songKey]
                    val meta = getCachedOrInferredMetadata(item, cachedFeature)

                    // Strict filter rules: compilation and unofficial streams blocked from main shelves!
                    val isCompile = isCompilationTrack(title, item.durationText) || isCompilationTitle(title)
                    if (isCompile) {
                        val similarity = calculateTasteSimilarity(meta, dna)
                        val score = similarity * 50.0 + (if (meta.isOfficial) 10.0 else 0.0)
                        compilationSongs.add(RecommendedSong(item, score, "compilation", "Jukebox / Compilation mix"))
                        continue
                    }
                    if (isNonMusicVideo(title, author)) continue
                    if (isUnofficialContent(title, author)) continue

                    // Filter out songs that are literally named "deep cut" or "hidden gem"
                    if (task.sourceType == "hidden_gems") {
                        val titleLower = title.lowercase(Locale.ROOT)
                        if (titleLower.contains("deep cut") || titleLower.contains("hidden gem") ||
                            titleLower.contains("underrated songs") || titleLower.contains("best of")) {
                            continue
                        }
                    }

                    if (profile.skippedTracks.contains(item.videoId) || 
                        profile.skippedArtists.contains(normAuthor)) {
                        continue
                    }

                    if (task.seedItem != null) {
                        if (task.sourceType == "similar_songs" && normalizeArtistName(author) == normalizeArtistName(task.seedItem.author)) {
                            continue
                        }
                        if (isTooSimilar(title, task.seedItem.title)) continue
                    }

                    val normKey = "$normTitle|$normAuthor"
                    if (globalShownVideoIds.contains(item.videoId) || globalShownTitlesAndArtists.contains(normKey)) {
                        continue
                    }

                    val sig = signalsMap[item.videoId]
                    
                    // Score with vector profile matching TasteDNA similarity
                    val similarity = calculateTasteSimilarity(meta, dna)
                    
                    var historyScore = 0.0
                    if (sig != null) {
                        val raw = sig.completeCount * 5.0 + sig.repeatCount * 6.0 + 
                                  (if (sig.isLiked) 10.0 else 0.0) + (if (sig.isDownloaded) 8.0 else 0.0) + 
                                  sig.searchClickCount * 3.0 - sig.skip20sCount * 6.0
                        historyScore = (raw / 40.0).coerceIn(0.0, 1.0)
                    }

                    // Dynamic vibe alignment scoring bonus matching current bio hour
                    var vibeBonus = 0.0
                    if (task.sourceType == "morning_vibe") {
                        if (meta.energy >= 0.55 || meta.mood in listOf("Happy", "Energetic", "Chill")) {
                            vibeBonus = 15.0
                        }
                    } else if (task.sourceType == "afternoon_vibe") {
                        if (meta.energy <= 0.55 || meta.mood in listOf("Chill", "Happy") || meta.genre == "Lofi") {
                            vibeBonus = 15.0
                        }
                    } else if (task.sourceType == "evening_vibe") {
                        if (meta.energy >= 0.60 || meta.mood in listOf("Energetic", "Happy", "Dark")) {
                            vibeBonus = 15.0
                        }
                    } else if (task.sourceType == "night_vibe") {
                        if (meta.energy <= 0.45 || meta.mood in listOf("Sad", "Romantic", "Chill", "Dark")) {
                            vibeBonus = 15.0
                        }
                    }

                    // Dynamic random entropy factor (+/- 12 points) to guarantee varied selections on refresh
                    val randomEntropyFactor = Math.random() * 12.0
                    val baseScore = (similarity * 60.0) + (historyScore * 20.0) + (if (meta.isOfficial) 20.0 else 0.0) + vibeBonus + randomEntropyFactor
                    
                    val likedByCount = cachedFeature?.likedByCount ?: 0
                    val finalScore = applySocialProofBoost(baseScore, likedByCount)

                    val reason = when (task.sourceType) {
                        "more_from_artist" -> "Official track from your top artist"
                        "rewind_listen_back" -> "Familiar song from your history"
                        "artists_like"   -> "Artist similarity matches TasteDNA"
                        "similar_songs"  -> "Acoustically matches your top song"
                        "genre_for_you"  -> "Matches your strongest genre"
                        "mood_for_you"   -> "Fits your recent mood"
                        "language_for_you" -> "Matches your listening language"
                        "fresh_finds"    -> "Fresh discovery for your taste"
                        "trending_songs" -> "Trending official hit"
                        "hidden_gems"    -> "Acoustically matches underrated gem"
                        "morning_vibe"   -> "Perfect acoustic start for your morning"
                        "afternoon_vibe" -> "Chill focus beats for your afternoon"
                        "evening_vibe"   -> "Energetic vibes for your evening"
                        "night_vibe"     -> "Soothing deep melodies for your night"
                        "your_taste_mix" -> "Blended for your unique taste"
                        else             -> "Curated recommendations"
                    }

                    filteredScored.add(RecommendedSong(item, finalScore, task.sourceType, reason))
                }

                val distinctScored = filteredScored.distinctBy { it.videoItem.videoId }
                    .distinctBy { "${normalizeTitle(it.videoItem.title)}|${it.videoItem.author.lowercase(Locale.ROOT)}" }
                    .sortedByDescending { it.score }

                val selected = ArrayList<RecommendedSong>()
                
                if (task.sourceType == "more_from_artist") {
                    // For artist-specific shelves, bypass the global capping and select a randomized subset of the top 15 matches
                    // to guarantee highly relevant yet completely fresh/different tracks on reload!
                    val topCandidates = distinctScored.take(24).shuffled()
                    for (rec in topCandidates) {
                        if (selected.size >= 12) break
                        selected.add(rec)
                    }
                } else {
                    // Incorporate GLOBAL ARTIST CAPPING: max 2 songs per artist combined for general/time-of-day mixes.
                    for (rec in distinctScored) {
                        if (selected.size >= 12) break
                        val artLow = rec.videoItem.author.lowercase(Locale.ROOT)
                        val globalCount = globalArtistCounts[artLow] ?: 0
                        
                        if (globalCount < 2) {
                            selected.add(rec)
                            globalArtistCounts[artLow] = globalCount + 1
                        }
                    }
                }

                // Epsilon-greedy exploration: global 10% chance, used at most once across all shelves
                if (shouldExplore && !explorationUsed && selected.size >= 3) {
                    val userGenres = dna.preferredGenres.keys.map { it.lowercase(Locale.ROOT) }
                    val explorationCandidate = distinctScored.lastOrNull { rec ->
                        val recGenre = inferMetadata(rec.videoItem).genre.lowercase(Locale.ROOT)
                        recGenre !in userGenres && rec.videoItem.videoId !in globalShownVideoIds
                    }
                    if (explorationCandidate != null && selected.size >= 2) {
                        val replaceIdx = selected.size - 1
                        selected[replaceIdx] = RecommendedSong(
                            explorationCandidate.videoItem,
                            explorationCandidate.score * 0.5,
                            "exploration",
                            "Discovering something new for you"
                        )
                        explorationUsed = true
                    }
                }

                if (selected.size >= 3) {
                    newSections.add(task.sectionKey to selected)
                    for (sel in selected) {
                        globalShownVideoIds.add(sel.videoItem.videoId)
                        val normKey = "${normalizeTitle(sel.videoItem.title)}|${sel.videoItem.author.lowercase(Locale.ROOT)}"
                        globalShownTitlesAndArtists.add(normKey)
                    }
                    Log.d(TAG, "Shelf '${task.sectionKey}' curated with ${selected.size} tracks successfully.")
                }
            }
        }

        if (newSections.isEmpty()) {
            Log.w(TAG, "Advanced curation resulted in empty shelves, generating premium default charts.")
            try {
                val fallbackQueries = listOf(
                    "Discover Weekly" to "acoustic warm indie pop songs",
                    "Fresh Hindi & Punjabi" to "new hindi punjabi songs official audio",
                    "Global Pop Radar" to "global pop hits official music",
                    "Rap Rotation" to "hip hop rap official songs trending",
                    "Late Night Chill" to "late night chill lofi indie songs"
                )
                for ((title, query) in fallbackQueries) {
                    val results = InnerTube.search(query)
                    val songs = results
                        .filter { !isCompilationTrack(it.title, it.durationText) }
                        .filter { !isNonMusicVideo(it.title, it.author) }
                        .filter { !isUnofficialContent(it.title, it.author) }
                        .take(12).map { item ->
                            RecommendedSong(item, 50.0, "curated", "Discover Weekly hit")
                        }
                    if (songs.size >= 3) {
                        newSections.add(title to songs)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load fallbacks: ${e.message}")
            }
        }

        // Append distinct dynamic compilation songs as a separate premium shelf!
        if (compilationSongs.isNotEmpty()) {
            val distinctCompilations = compilationSongs.distinctBy { it.videoItem.videoId }
                .distinctBy { "${normalizeTitle(it.videoItem.title)}|${it.videoItem.author.lowercase(Locale.ROOT)}" }
                .sortedByDescending { it.score }
                .take(12)
            if (distinctCompilations.size >= 3) {
                newSections.add("Jukebox & Compilations" to distinctCompilations)
            }
        }

        synchronized(cachedSections) {
            cachedSections.clear()
            cachedSections.addAll(newSections)
            lastCacheTime = System.currentTimeMillis()
        }
        saveToDisk(ctx, newSections)
        ArrayList(newSections)
    }

    suspend fun findSpotifyTrackFuzzy(dao: SpotifyTrackDao, title: String, artist: String): SpotifyTrack? {
        val cleanTitle = title.lowercase(Locale.ROOT)
            .replace(Regex("\\([^)]*\\)"), "")
            .replace(Regex("\\[[^]]*\\]"), "")
            .replace(Regex("\\b(feat\\.|ft\\.|with|prod\\.|produced by)\\b.*", RegexOption.IGNORE_CASE), "")
            .trim()
        
        if (cleanTitle.isEmpty()) return null
        
        val candidates = try {
            dao.findTracksByTitlePrefix(cleanTitle)
        } catch (_: Exception) {
            emptyList()
        }
        
        val normQueryArtist = cleanArtistForMatching(artist)
        for (cand in candidates) {
            val normCandArtist = cleanArtistForMatching(cand.artist)
            if (normQueryArtist.isNotEmpty() && normCandArtist.isNotEmpty() &&
                (normQueryArtist.contains(normCandArtist) || normCandArtist.contains(normQueryArtist))) {
                return cand
            }
        }
        return null
    }

    /**
     * Finds similar tracks from the user's analyzed song history (SongFeatureCache).
     * These are songs the user has actually played — features were extracted by
     * TarsosDSP (BPM/energy) and Last.fm (genre/mood tags).
     *
     * This is the "lazy enrichment" path: songs not in the bundled DB still get
     * matched by their real audio features.
     */
    fun findSimilarFromCache(
        featureCache: List<SongFeatureCache>,
        seedMeta: SongMetadata,
        excludeArtists: Set<String>,
        limit: Int = 10
    ): List<SongMetadata> {
        val seedGenre = seedMeta.genre.lowercase(Locale.ROOT)
        val normalizedExclusions = excludeArtists.map { it.lowercase(Locale.ROOT).trim() }.toSet()

        return featureCache
            .map { cache ->
                // Convert cached features to SongMetadata for comparison
                var genre = seedMeta.genre // default
                var mood = seedMeta.mood
                try {
                    val genres: List<String> = gson.fromJson(cache.genreTags, object : com.google.gson.reflect.TypeToken<List<String>>() {}.type) ?: emptyList()
                    val moods: List<String> = gson.fromJson(cache.moodTags, object : com.google.gson.reflect.TypeToken<List<String>>() {}.type) ?: emptyList()
                    if (genres.isNotEmpty()) genre = genres[0]
                    if (moods.isNotEmpty()) mood = moods[0]
                } catch (_: Exception) {}

                SongMetadata(
                    title = cache.title,
                    artist = cache.artist,
                    genre = genre,
                    mood = mood,
                    language = "", // not stored in cache
                    energy = cache.energyReal.toDouble().coerceIn(0.0, 1.0),
                    tempo = if (cache.bpmReal in 40f..250f) cache.bpmReal.toInt() else 100,
                    year = 2024,
                    isOfficial = true,
                    sourceQuality = "Analyzed"
                )
            }
            .filter { meta ->
                val artistLow = meta.artist.lowercase(Locale.ROOT).trim()
                // Exclude seed artist and known artists
                artistLow != seedMeta.artist.lowercase(Locale.ROOT).trim() &&
                    normalizedExclusions.none { it.isNotEmpty() && artistLow.contains(it) }
            }
            // Genre filter using Every Noise graph
            .filter { meta ->
                if (seedGenre.isEmpty()) return@filter true
                areGenresSimilar(seedGenre, meta.genre)
            }
            .map { meta ->
                val similarity = calculateTasteSimilarity(meta, TasteDNA(
                    targetEnergy = seedMeta.energy,
                    targetTempo = seedMeta.tempo,
                    preferredGenres = mapOf(seedMeta.genre to 100.0),
                    preferredMoods = mapOf(seedMeta.mood to 100.0),
                    preferredLanguages = emptyMap(),
                    preferredArtists = emptyMap()
                ))
                meta to similarity
            }
            .sortedByDescending { it.second }
            .distinctBy { it.first.artist.lowercase(Locale.ROOT) }
            .take(limit)
            .map { it.first }
    }

    /**
     * Finds acoustically similar tracks from the bundled Spotify dataset,
     * excluding the seed artist and the user's top known artists.
     * This breaks the artist echo chamber by discovering new artists
     * in the same acoustic neighborhood.
     */
    suspend fun findAcousticallySimilarTracks(
        recDb: RecommendationDatabase,
        seedTitle: String,
        seedArtist: String,
        excludeArtists: Set<String>,
        limit: Int = 10
    ): List<SpotifyTrack> = withContext(Dispatchers.IO) {
        val cleanTitle = seedTitle.lowercase(Locale.ROOT)
            .replace(Regex("\\([^)]*\\)"), "")
            .replace(Regex("\\[[^]]*\\]"), "")
            .replace(Regex("\\b(feat\\.|ft\\.|with|prod\\.|produced by)\\b.*", RegexOption.IGNORE_CASE), "")
            .trim()

        if (cleanTitle.isEmpty()) return@withContext emptyList()

        val seedTrack = try {
            recDb.trackDao().findTracksByTitlePrefix(cleanTitle).firstOrNull { cand ->
                val normCand = cleanArtistForMatching(cand.artist)
                val normSeed = cleanArtistForMatching(seedArtist)
                normCand.isNotEmpty() && normSeed.isNotEmpty() &&
                    (normCand.contains(normSeed) || normSeed.contains(normCand))
            }
        } catch (_: Exception) { null } ?: return@withContext emptyList()

        val normalizedExclusions = excludeArtists.map { cleanArtistForMatching(it) }.toSet()

        // Get seed genre for filtering
        val seedGenre = seedTrack.genre.lowercase(Locale.ROOT)

        // Use genre-filtered query if genre is available (much better results)
        val candidates = try {
            if (seedGenre.isNotEmpty()) {
                recDb.trackDao().getClusterNeighborsByGenre(
                    targetCluster = seedTrack.cluster_id,
                    genre = seedTrack.genre,
                    targetEnergy = seedTrack.energy,
                    targetValence = seedTrack.valence,
                    targetDance = seedTrack.dance,
                    targetAcoustic = seedTrack.acoustic,
                    targetTempo = seedTrack.tempo,
                    limit = 60
                )
            } else {
                recDb.trackDao().getClusterNeighborsCushioned(
                    targetCluster = seedTrack.cluster_id,
                    targetEnergy = seedTrack.energy,
                    targetValence = seedTrack.valence,
                    targetDance = seedTrack.dance,
                    targetAcoustic = seedTrack.acoustic,
                    targetTempo = seedTrack.tempo,
                    limit = 60
                )
            }
        } catch (_: Exception) { emptyList() }

        val seedNorm = cleanArtistForMatching(seedArtist)
        candidates
            .filter { track ->
                val trackNorm = cleanArtistForMatching(track.artist)
                // Exclude seed artist and known artists
                trackNorm != seedNorm && normalizedExclusions.none { excl ->
                    excl.isNotEmpty() && trackNorm.isNotEmpty() && (excl.contains(trackNorm) || trackNorm.contains(excl))
                }
            }
            // Genre filter: use Every Noise similarity graph for precise matching
            .filter { track ->
                if (seedGenre.isEmpty()) return@filter true
                val trackGenre = track.genre.lowercase(Locale.ROOT)
                areGenresSimilar(seedGenre, trackGenre)
            }
            .distinctBy { it.artist.lowercase(Locale.ROOT) }
            .take(limit)
    }

    /**
     * Maps specific Every Noise genres to broader families for cross-track matching.
     * Prevents Katy Perry showing up for Lupe Fiasco.
     *
     * Boundary case rule: check the PRIMARY genre first. In "rap rock", rock is primary.
     * In "jazz rap", rap is primary. Order of checks matters.
     *
     * Returns "" for unknown families — means "don't filter, allow any genre".
     */
    private fun getGenreFamily(genre: String): String {
        if (genre.isEmpty()) return ""
        val g = genre.lowercase(Locale.ROOT)

        // --- Hiphop family (check boundary cases BEFORE pure hiphop) ---
        // "jazz rap" → hiphop (rap is primary), "desi hip hop" → hiphop (hip hop is primary)
        if (g.contains("hiphop") || g.contains("hip hop") ||
            g.contains("rap") && !g.contains("rock") && !g.contains("metal") ||
            g.contains("drill") || g.contains("trap") && !g.contains("metal") ||
            g.contains("conscious") || g.contains("gangsta") || g.contains("boom bap") ||
            g.contains("underground") && (g.contains("hip") || g.contains("rap")) ||
            g.contains("desi") && (g.contains("hip") || g.contains("rap"))
        ) return "hiphop"

        // --- Rock family (check boundary cases BEFORE pure rock) ---
        // "rap rock" → rock, "trap metal" → rock, "pop punk" → rock, "pop rock" → rock
        if (g.contains("rock") || g.contains("metal") || g.contains("punk") ||
            g.contains("grunge") || g.contains("emo") && !g.contains("rap") ||
            g.contains("alternative") && (g.contains("rock") || g.contains("metal"))
        ) return "rock"

        // --- Pop family (after hiphop/rock boundary checks) ---
        // "indie pop" → pop, "dance pop" → pop
        if (g.contains("pop")) return "pop"

        // --- R&B family ---
        if (g.contains("r&b") || g.contains("soul") || g.contains("neo soul")) return "rnb"

        // --- Indian family ---
        if (g.contains("bollywood") || g.contains("filmi") || g.contains("desi") ||
            g.contains("hindi") || g.contains("punjabi") || g.contains("indian") ||
            g.contains("bhangra") || g.contains("ghazal") || g.contains("bhajan") ||
            g.contains("tamil") || g.contains("telugu") || g.contains("kannada") ||
            g.contains("malayalam") || g.contains("tollywood") || g.contains("kollywood")
        ) return "indian"

        // --- Electronic family ---
        if (g.contains("electronic") || g.contains("edm") || g.contains("house") ||
            g.contains("techno") || g.contains("trance") || g.contains("dubstep") ||
            g.contains("dnb") || g.contains("drum and bass") || g.contains("garage") ||
            g.contains("ambient") || g.contains("downtempo") || g.contains("synth") ||
            g.contains("disco") || g.contains("dance") && !g.contains("pop")
        ) return "electronic"

        // --- Latin family ---
        if (g.contains("reggaeton") || g.contains("latin") || g.contains("salsa") ||
            g.contains("bachata") || g.contains("cumbia") || g.contains("merengue") ||
            g.contains("corrido") || g.contains("banda") || g.contains("norteno") ||
            g.contains("urbano") || g.contains("dembow") || g.contains("perreo") ||
            g.contains("kuduro") || g.contains("funk carioca")
        ) return "latin"

        // --- Country family ---
        if (g.contains("country") || g.contains("americana") || g.contains("honky tonk") ||
            g.contains("outlaw country") || g.contains("red dirt")
        ) return "country"

        // --- Jazz family ---
        if (g.contains("jazz") || g.contains("blues") || g.contains("bebop") ||
            g.contains("swing") || g.contains("smooth jazz")
        ) return "jazz"

        // --- Folk family ---
        if (g.contains("folk") || g.contains("singer-songwriter") || g.contains("acoustic") ||
            g.contains("singer songwriter")
        ) return "folk"

        // --- Reggae family ---
        if (g.contains("reggae") || g.contains("ska") || g.contains("dancehall") ||
            g.contains("dub")
        ) return "reggae"

        // --- Classical family ---
        if (g.contains("classical") || g.contains("orchestral") || g.contains("baroque") ||
            g.contains("romantic era") || g.contains("opera") || g.contains("chamber")
        ) return "classical"

        // Unknown — don't filter
        return ""
    }

    /**
     * Checks if two Every Noise genres are similar using the genre similarity graph.
     * "chicagorap" and "conscioushiphop" → true (they're neighbors in the graph)
     * "chicagorap" and "dancepop" → false (far apart in the graph)
     *
     * Returns true if genres are similar OR if either genre is unknown (don't filter).
     */
    fun areGenresSimilar(genre1: String, genre2: String, context: Context? = null): Boolean {
        if (genre1.isEmpty() || genre2.isEmpty()) return true
        val g1 = genre1.lowercase(Locale.ROOT).replace(" ", "")
        val g2 = genre2.lowercase(Locale.ROOT).replace(" ", "")
        if (g1 == g2) return true

        // Try loading genre graph if not loaded yet
        if (context != null && !genreGraphLoaded) {
            loadGenreGraph(context)
        }

        val graph = genreSimilarMap
        if (graph.isNullOrEmpty()) {
            // Fallback to family-based comparison
            val family1 = getGenreFamily(genre1)
            val family2 = getGenreFamily(genre2)
            if (family1.isEmpty() || family2.isEmpty()) return true
            return family1 == family2
        }

        // Check if g2 is in g1's similar genres, or vice versa
        val similar1 = graph[g1]
        if (similar1 != null && similar1.any { it.replace(" ", "") == g2 }) return true

        val similar2 = graph[g2]
        if (similar2 != null && similar2.any { it.replace(" ", "") == g1 }) return true

        // Check if they share similar genres (2-hop similarity)
        if (similar1 != null && similar2 != null) {
            val shared = similar1.intersect(similar2.toSet())
            if (shared.size >= 2) return true
        }

        return false
    }

    /**
     * Detects the song's style/vibe from title keywords, mood, energy, and tempo.
     * "Prayer" → storytelling/introspective, "Hustle" → motivational/grind, "Flex" → confident/boastful.
     * This lets the query builder match SONG characteristics, not just artist genre.
     */
    fun detectSongStyle(title: String, mood: String, energy: Double, tempo: Int): List<String> {
        val titleLower = title.lowercase(Locale.ROOT)
        val styles = mutableListOf<String>()

        // Title keyword → style mapping
        val storytellingKeywords = listOf("prayer", "story", "letter", "dear", "memoir", "confession",
            "journal", "diary", "testimony", "parable", "fable", "legend", "chronicle", "tribute",
            "ode", "eulogy", "sermon", "parable")
        val introspectiveKeywords = listOf("prayer", "god", "soul", "mind", "deep", "think", "alone",
            "silence", "peace", "faith", "hope", "dream", "inside", "within", "reflect",
            "adoration", "magi", "worship", "sacred", "holy", "spiritual", "divine",
            "blessing", "grace", "meditation", "zen", "karma", "dharma", "chakra",
            "philosophy", "wisdom", "truth", "meaning", "existence", "void", "infinite")
        val motivationalKeywords = listOf("hustle", "grind", "rise", "work", "money", "success",
            "champion", "winner", "grateful", "blessed", "king", "queen", "throne", "empire")
        val partyKeywords = listOf("party", "club", "dance", "turnt", "lit", "flex", "bottle",
            "wave", "vibe", "bounce", "drop", "anthem", "hype", "fire", "heat")
        val loveKeywords = listOf("love", "heart", "kiss", "baby", "babe", "forever", "always",
            "miss", "need", "want", "desire", "romance", "wedding", "ring", "devotion")
        val sadKeywords = listOf("cry", "tears", "pain", "hurt", "gone", "lost", "broken", "empty",
            "lonely", "miss", "goodbye", "leave", "end", "fade", "drown")
        val aggressiveKeywords = listOf("kill", "war", "battle", "fight", "destroy", "rip",
            "beast", "monster", "savage", "brutal", "hardest", "real", "street")
        val chillKeywords = listOf("rain", "late night", "midnight", "moon", "stars", "cloud",
            "breeze", "calm", "gentle", "soft", "slow", "easy", "float", "drift")

        if (storytellingKeywords.any { titleLower.contains(it) }) styles.add("storytelling")
        if (introspectiveKeywords.any { titleLower.contains(it) }) styles.add("introspective")
        if (motivationalKeywords.any { titleLower.contains(it) }) styles.add("motivational")
        if (partyKeywords.any { titleLower.contains(it) }) styles.add("party")
        if (loveKeywords.any { titleLower.contains(it) }) styles.add("love")
        if (sadKeywords.any { titleLower.contains(it) }) styles.add("sad")
        if (aggressiveKeywords.any { titleLower.contains(it) }) styles.add("aggressive")
        if (chillKeywords.any { titleLower.contains(it) }) styles.add("chill")

        // Mood → style
        when (mood) {
            "Sad" -> if (!styles.contains("sad")) styles.add("emotional")
            "Energetic" -> if (!styles.contains("party")) styles.add("energetic")
            "Chill/Relaxed" -> if (!styles.contains("chill")) styles.add("chill")
            "Romantic" -> if (!styles.contains("love")) styles.add("romantic")
            "Dark" -> if (!styles.contains("aggressive")) styles.add("dark")
            "Happy" -> if (!styles.contains("party")) styles.add("upbeat")
        }

        // Energy + Tempo → style refinement
        if (energy < 0.35 && tempo < 90) {
            if (!styles.contains("chill")) styles.add("slow")
            if (!styles.contains("introspective")) styles.add("deep")
        }
        if (energy > 0.75 && tempo > 130) {
            if (!styles.contains("aggressive")) styles.add("hard")
            if (!styles.contains("party")) styles.add("hype")
        }

        return styles.distinct().take(3)
    }

    /**
     * Builds search queries from acoustically similar tracks instead of the seed artist name.
     * This is the key function that breaks the echo chamber: instead of searching
     * "J. Cole similar music", we search for tracks by new artists in the same acoustic space.
     *
     * Now also considers SONG-LEVEL style (storytelling, introspective, party, etc.)
     * not just artist genre.
     */
    suspend fun buildAcousticQueriesForSeed(
        recDb: RecommendationDatabase,
        seedMeta: SongMetadata,
        excludeArtists: Set<String>,
        vinDb: com.vinmusic.data.db.VinDatabase? = null
    ): List<String> = withContext(Dispatchers.IO) {
        val similarTracks = findAcousticallySimilarTracks(
            recDb, seedMeta.title, seedMeta.artist, excludeArtists, limit = 6
        )

        // Also search user's analyzed song history for additional matches
        val cacheMatches = if (vinDb != null) {
            try {
                val allCached = vinDb.songFeatureCacheDao().getAll()
                findSimilarFromCache(allCached, seedMeta, excludeArtists, limit = 4)
            } catch (_: Exception) { emptyList() }
        } else emptyList()

        // Combine bundled DB results + cache results
        val allSimilar = similarTracks.map { SpotifyTrack(0, it.title, it.artist, 0, 0, 0, 0, 0, 0, it.genre) } +
            cacheMatches.map { SpotifyTrack(0, it.title, it.artist, 0, 0, 0, 0, 0, 0, it.genre) }

        if (allSimilar.isEmpty()) {
            // Seed track not in bundled DB AND not in cache.
            // Use Every Noise genre graph + AUDIO FEATURES (mood/energy/tempo) for queries.
            // NOTE: We deliberately do NOT use title keywords here — they're unreliable
            // (e.g., "Rich N****z" is introspective, not a flex song).
            // Instead we use: artist genre + mood + energy/tempo band.
            val seedGenre = seedMeta.genre.lowercase(Locale.ROOT)
            val everyNoiseGenres = genreSimilarMap?.get(seedGenre)
            
            val queries = mutableListOf<String>()
            
            // Determine energy/tempo band for query precision
            val energyBand = when {
                seedMeta.energy < 0.35 -> "chill"
                seedMeta.energy > 0.75 -> "energetic"
                else -> ""
            }
            val tempoBand = when {
                seedMeta.tempo < 85 -> "slow"
                seedMeta.tempo > 130 -> "fast"
                else -> ""
            }
            val moodTerm = when (seedMeta.mood) {
                "Chill/Relaxed" -> "chill"
                "Energetic" -> "energetic"
                "Sad" -> "sad"
                "Romantic" -> "romantic"
                "Dark" -> "dark"
                "Happy" -> "happy"
                else -> "popular"
            }
            
            if (everyNoiseGenres != null && everyNoiseGenres.isNotEmpty()) {
                // Use specific Every Noise genres for precise queries
                val topGenres = everyNoiseGenres.take(2)
                for (g in topGenres) {
                    val genreDisplay = g.replace(Regex("(?<=[a-z])(?=[A-Z])"), " ")
                    val qualifiers = listOf(energyBand, tempoBand, moodTerm).filter { it.isNotEmpty() }.joinToString(" ")
                    queries.add("$genreDisplay $qualifiers official popular songs".trim().replace(Regex("\\s+"), " "))
                }
                // Add a mood+genre blend
                queries.add("${topGenres[0]} $moodTerm official audio")
            } else {
                // Fallback to inferred genre + audio features
                val genreTerm = when (seedMeta.genre) {
                    "Rap/Hip-Hop" -> "rap hip hop"
                    "Punjabi Folk" -> "punjabi"
                    else -> seedMeta.genre.lowercase(Locale.ROOT)
                }
                val qualifiers = listOf(energyBand, tempoBand, moodTerm).filter { it.isNotEmpty() }.joinToString(" ")
                queries.add("$genreTerm $qualifiers official hits".trim().replace(Regex("\\s+"), " "))
                queries.add("$genreTerm $moodTerm similar vibes".trim().replace(Regex("\\s+"), " "))
            }
            
            return@withContext queries
        }

        val queries = mutableListOf<String>()

        // Query 1-2: Search for specific similar tracks by new artists
        val uniqueSimilar = allSimilar.distinctBy { it.artist.lowercase(Locale.ROOT) }
        for (track in uniqueSimilar.take(2)) {
            queries.add("${track.title} ${track.artist} official")
        }

        // Query 3: Genre + mood + AUDIO FEATURES blend (no artist name, no title keywords)
        val genreTerm = when (seedMeta.genre) {
            "Rap/Hip-Hop" -> "rap hip hop"
            "Punjabi Folk" -> "punjabi"
            else -> seedMeta.genre.lowercase(Locale.ROOT)
        }
        val moodTerm = when (seedMeta.mood) {
            "Chill/Relaxed" -> "chill"
            "Energetic" -> "energetic workout"
            "Sad" -> "sad emotional"
            "Romantic" -> "romantic love"
            "Dark" -> "dark"
            "Happy" -> "happy upbeat"
            else -> "popular"
        }
        // Use energy/tempo band for precision instead of title keywords
        val energyBand = when {
            seedMeta.energy < 0.35 -> "chill"
            seedMeta.energy > 0.75 -> "energetic"
            else -> ""
        }
        val tempoBand = when {
            seedMeta.tempo < 85 -> "slow"
            seedMeta.tempo > 130 -> "fast"
            else -> ""
        }
        val langTerm = if (seedMeta.language != "English") seedMeta.language.lowercase(Locale.ROOT) else ""
        val qualifiers = listOf(energyBand, tempoBand).filter { it.isNotEmpty() }.joinToString(" ")
        queries.add("$genreTerm $moodTerm $qualifiers similar vibes $langTerm official hits".trim().replace(Regex("\\s+"), " "))

        // Query 4: Energy/tempo band search for precision
        if (qualifiers.isNotEmpty()) {
            queries.add("$qualifiers $genreTerm $langTerm official audio".trim().replace(Regex("\\s+"), " "))
        }

        // Query 5: Artists similar to the similar tracks (one hop further)
        val diverseArtist = similarTracks.getOrNull(2)?.artist ?: similarTracks[0].artist
        queries.add("$diverseArtist $genreTerm popular songs")

        queries
    }

    private fun cleanArtistForMatching(name: String): String {
        val temp = java.text.Normalizer.normalize(name.lowercase(Locale.ROOT), java.text.Normalizer.Form.NFD)
        val clean = temp.replace(Regex("\\p{M}+"), "")
        return normalizeArtistName(clean)
    }
}
