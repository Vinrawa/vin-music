package com.vinmusic.recommendation

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.vinmusic.data.db.VinDatabase
import com.vinmusic.innertube.InnerTube
import com.vinmusic.innertube.VideoItem
import com.vinmusic.innertube.ArtistItem
import com.vinmusic.innertube.YTMusicApi
import com.vinmusic.innertube.YTMusicHomeSection
import com.vinmusic.data.db.RelatedSongMap
import com.vinmusic.data.db.SongCacheMeta
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RecommendationRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: VinDatabase,
    private val recDb: RecommendationDatabase,
    private val firestoreRecommendationManager: FirestoreRecommendationManager
) {
    private val TAG = "VIN_REC_REP"

    private fun isOnline(): Boolean {
        return try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            false
        }
    }
    private val prefs = context.getSharedPreferences("vin_music_repository_cache", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val CACHE_EXPIRY_MS = 15 * 60 * 1000L // 15 minutes
    /**
     * Smart Queue requests can be triggered by both the player and the UI at the
     * same time.  A single lock makes the second caller reuse the cache written by
     * the first caller instead of starting another set of network searches.
     */
    private val smartQueueMutex = Mutex()

    private fun normalizeQueueArtist(artist: String): String =
        RecommendationManager.normalizeArtistName(artist).trim().lowercase(Locale.ROOT)

    private fun sameQueueLanguage(first: String?, second: String?): Boolean =
        !first.isNullOrBlank() && !second.isNullOrBlank() &&
            first.trim().equals(second.trim(), ignoreCase = true)

    // ── Local Disk Cache Helpers ──────────────────────────────────────────────

    private fun saveCacheStr(key: String, json: String) {
        try {
            prefs.edit()
                .putString(key, json)
                .putLong("${key}_time", System.currentTimeMillis())
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write disk cache for key '$key': ${e.message}")
        }
    }

    private fun loadCacheStr(key: String, allowStale: Boolean = false): String? {
        try {
            val time = prefs.getLong("${key}_time", 0L)
            if (!allowStale && System.currentTimeMillis() - time > CACHE_EXPIRY_MS) {
                prefs.edit().remove(key).remove("${key}_time").apply()
                return null
            }
            return prefs.getString(key, null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load disk cache for key '$key': ${e.message}")
            return null
        }
    }

    private fun saveVideoItems(key: String, list: List<VideoItem>) {
        saveCacheStr(key, gson.toJson(list))
    }

    private fun loadVideoItems(key: String, allowStale: Boolean = true): List<VideoItem>? {
        val json = loadCacheStr(key, allowStale = allowStale) ?: return null
        val type = object : TypeToken<List<VideoItem>>() {}.type
        return gson.fromJson(json, type)
    }

    /**
     * Clears the cached Quick Picks so the next [getQuickPicks] call regenerates
     * a fresh list. Used by the "refresh Quick Picks after every 2 songs" hook.
     */
    fun invalidateQuickPicksCache() {
        try {
            prefs.edit().remove("quick_picks_v2").remove("quick_picks_v2_time").apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to invalidate Quick Picks cache: ${e.message}")
        }
    }

    private fun saveArtistItems(key: String, list: List<ArtistItem>) {
        saveCacheStr(key, gson.toJson(list))
    }

    private fun loadArtistItems(key: String, allowStale: Boolean = true): List<ArtistItem>? {
        val json = loadCacheStr(key, allowStale = allowStale) ?: return null
        val type = object : TypeToken<List<ArtistItem>>() {}.type
        return gson.fromJson(json, type)
    }

    // ── Primary Metrolist Curation Functions ───────────────────────────────────

    /**
     * Metrolist-style Quick Picks: local related_song_map + forgotten favorites + YouTube related().
     * Falls back to TasteDNA search when cache is empty.
     */
    suspend fun getQuickPicks(): List<VideoItem> = withContext(Dispatchers.IO) {
        val cacheKey = "quick_picks_v2"
        val cached = loadVideoItems(cacheKey)
        if (cached != null && cached.isNotEmpty()) {
            Log.d(TAG, "Loaded getQuickPicks from disk cache.")
            return@withContext cached
        }

        Log.d(TAG, "Generating Metrolist-style Quick Picks...")
        val profile = RecommendationManager.buildTasteProfile(db)
        val combined = LinkedHashMap<String, VideoItem>()

        // 1) Cached related songs from recent seeds
        db.relatedSongDao().quickPickVideos(30).forEach { row ->
            addFilteredQuickPick(combined, VideoItem(row.videoId, row.title, row.author, row.durationText), profile)
        }

        // 2) Forgotten favorites — played before but not in last 14 days
        val twoWeeksAgo = System.currentTimeMillis() - 86400000L * 14
        db.songCacheMetaDao().forgottenFavorites(twoWeeksAgo, 8).forEach { meta ->
            addFilteredQuickPick(
                combined,
                VideoItem(meta.videoId, meta.title, meta.author, meta.durationText),
                profile,
            )
        }

        // 3) YouTube Music related() for blended seeds (top 3 recently played + 2 random liked tracks)
        val recentHistory = db.historyDao().getAllHistory().take(3)
        val likedSongs = db.likedSongDao().getAll().shuffled().take(2)
        val seeds = (recentHistory.map { it.videoId } + likedSongs.map { it.videoId }).distinct()
        
        if (seeds.isNotEmpty()) {
            coroutineScope {
                val deferreds = seeds.map { seedId ->
                    async(Dispatchers.IO) {
                        try {
                            fetchYtRelatedForSeed(seedId)
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to fetch related for seed $seedId: ${e.message}")
                            null
                        }
                    }
                }
                deferreds.awaitAll().filterNotNull().flatten().forEach { item ->
                    addFilteredQuickPick(combined, item, profile)
                }
            }
        }

        var result = combined.values.take(20)
        if (result.size < 6) {
            Log.d(TAG, "Quick picks sparse (${result.size}), TasteDNA fallback...")
            result = getQuickPicksTasteDnaFallback(profile).take(20)
        }

        if (result.isNotEmpty()) saveVideoItems(cacheKey, result)
        result
    }

    /** Official YouTube Music home shelves (FEmusic_home) — requires cookie for best results. */
    suspend fun getYouTubeMusicHomeSections(): List<YTMusicHomeSection> = withContext(Dispatchers.IO) {
        val cacheKey = "yt_home_sections"
        val cachedJson = loadCacheStr(cacheKey, allowStale = true)
        if (cachedJson != null) {
            try {
                val type = object : TypeToken<List<YTMusicHomeSectionCache>>() {}.type
                val list: List<YTMusicHomeSectionCache> = gson.fromJson(cachedJson, type)
                if (list.isNotEmpty()) {
                    return@withContext list.map { it.toSection() }
                }
            } catch (_: Exception) { }
        }

        val profile = RecommendationManager.buildTasteProfile(db)
        val sections = ArrayList<YTMusicHomeSection>()
        var page = YTMusicApi.getHomePage()
        sections.addAll(filterHomeSections(page.sections, profile))
        var guard = 0
        while (!page.continuation.isNullOrBlank() && sections.size < 12 && guard < 3) {
            page = YTMusicApi.getHomePage(continuation = page.continuation)
            sections.addAll(filterHomeSections(page.sections, profile))
            guard++
        }

        if (sections.isNotEmpty()) {
            saveCacheStr(cacheKey, gson.toJson(sections.map { YTMusicHomeSectionCache.from(it) }))
        }
        sections
    }

    /** Official user library playlists (FEmusic_liked_playlists) — requires cookie. */
    suspend fun getLibraryPlaylists(): List<com.vinmusic.innertube.AlbumItem> = withContext(Dispatchers.IO) {
        val cacheKey = "library_playlists"
        val cached = loadCacheStr(cacheKey, allowStale = true)
        if (cached != null) {
            try {
                val type = object : TypeToken<List<com.vinmusic.innertube.AlbumItem>>() {}.type
                val list: List<com.vinmusic.innertube.AlbumItem> = gson.fromJson(cached, type)
                if (list.isNotEmpty()) return@withContext list
            } catch (_: Exception) {}
        }
        val result = YTMusicApi.getLibraryPlaylists()
        if (result.isNotEmpty()) {
            saveCacheStr(cacheKey, gson.toJson(result))
        }
        result
    }

    /** Cache related tracks after playback (feeds quick picks over time). */
    suspend fun cacheRelatedForSong(videoId: String) = withContext(Dispatchers.IO) {
        if (db.relatedSongDao().hasRelated(videoId)) return@withContext
        val related = fetchYtRelatedForSeed(videoId) ?: return@withContext
        if (related.isEmpty()) return@withContext
        db.relatedSongDao().deleteForSong(videoId)
        val rows = related.take(25).map {
            RelatedSongMap(videoId, it.videoId, it.title, it.author, it.durationText)
        }
        db.relatedSongDao().insertAll(rows)
        Log.d(TAG, "Cached ${rows.size} related songs for $videoId")
    }

    suspend fun touchSongPlayMeta(song: VideoItem) = withContext(Dispatchers.IO) {
        val existing = db.songCacheMetaDao().topPlayed(500).find { it.videoId == song.videoId }
        val playTime = existing?.totalPlayTime?.plus(30_000L) ?: 30_000L
        db.songCacheMetaDao().upsert(
            SongCacheMeta(
                videoId = song.videoId,
                title = song.title,
                author = song.author,
                durationText = song.durationText,
                lastPlayedAt = System.currentTimeMillis(),
                totalPlayTime = playTime,
            )
        )
    }

    private data class YTMusicHomeSectionCache(
        val title: String,
        val songs: List<VideoItem>,
        val browseId: String?,
        val params: String?,
    ) {
        fun toSection() = YTMusicHomeSection(title, songs, browseId, params)
        companion object {
            fun from(s: YTMusicHomeSection) = YTMusicHomeSectionCache(s.title, s.songs, s.browseId, s.params)
        }
    }

    private fun addFilteredQuickPick(
        out: LinkedHashMap<String, VideoItem>,
        item: VideoItem,
        profile: RecommendationManager.TasteProfile,
    ) {
        val author = item.author.trim().lowercase(Locale.ROOT)
        if (RecommendationManager.isCompilationTrack(item.title, item.durationText)) return
        if (RecommendationManager.isNonMusicVideo(item.title, item.author)) return
        if (RecommendationManager.isUnofficialContent(item.title, item.author)) return
        if (profile.skippedTracks.contains(item.videoId) || profile.skippedArtists.contains(author)) return
        val meta = RecommendationManager.inferMetadata(item)
        if (!meta.isOfficial) return
        val topLang = profile.topLanguages.firstOrNull()?.first
        if (topLang != null && meta.language != topLang) return
        out.putIfAbsent(item.videoId, item)
    }

    private suspend fun fetchYtRelatedForSeed(videoId: String): List<VideoItem>? {
        val next = YTMusicApi.getNextRelated(videoId, playlistId = "RDAMVM$videoId")
        val browse = next.relatedBrowse ?: return null
        val raw = YTMusicApi.getRelatedSongs(browse.browseId, browse.params)
        return if (raw.isNotEmpty()) raw else null
    }

    private suspend fun getQuickPicksTasteDnaFallback(
        profile: RecommendationManager.TasteProfile,
    ): List<VideoItem> {
        val dna = profile.tasteDNA
        val yr = java.time.LocalDate.now().year
        val queries = ArrayList<String>()
        
        val topArtists = profile.topArtists.take(3).map { it.first }
        val topGenres = profile.topGenres.take(2).map { it.first }
        
        // Add dynamic artist + genre blend queries
        if (topArtists.isNotEmpty() && topGenres.isNotEmpty()) {
            queries.add("${topArtists[0]} ${topGenres[0]} official songs")
        }
        
        // Dynamic search terms matching DNA profile parameters
        val energyTerm = when {
            dna.targetEnergy > 0.75 -> "energetic upbeat dance"
            dna.targetEnergy < 0.40 -> "acoustic soft chill lofi"
            else -> "popular"
        }
        
        val tempoTerm = when {
            dna.targetTempo > 125 -> "fast tempo workout beats"
            dna.targetTempo < 90 -> "slow relaxing mood"
            else -> "hits"
        }
        
        // Generate queries based on top artists
        for (artist in topArtists) {
            queries.add("$artist $energyTerm official music")
            queries.add("$artist popular tracks")
        }
        
        // Generate queries based on top genres
        for (genre in topGenres) {
            queries.add("$genre $tempoTerm $energyTerm hits $yr")
            queries.add("$genre trending music official")
        }
        
        // Fallbacks
        queries.add("trending official music hits $yr")
        if (queries.size > 8) {
            val uniqueQueries = queries.distinct().shuffled().take(6)
            queries.clear()
            queries.addAll(uniqueQueries)
        }
        val candidates = fetchCandidatesFromQueries(queries)
        val topLang = profile.topLanguages.firstOrNull()?.first
        val scored = ArrayList<Pair<VideoItem, Double>>()
        for (item in candidates) {
            if (RecommendationManager.isCompilationTrack(item.title, item.durationText)) continue
            if (RecommendationManager.isNonMusicVideo(item.title, item.author)) continue
            if (RecommendationManager.isUnofficialContent(item.title, item.author)) continue
            val meta = RecommendationManager.getCachedOrInferredMetadata(db, item)
            if (!meta.isOfficial) continue
            if (topLang != null && meta.language != topLang) continue
            val similarity = RecommendationManager.calculateTasteSimilarity(meta, dna)
            val officialBonus = if (meta.isOfficial) 25.0 else 0.0
            scored.add(item to (similarity * 75.0 + officialBonus))
        }
        val selected = ArrayList<VideoItem>()
        val artistCounts = HashMap<String, Int>()
        for (item in scored.sortedByDescending { it.second }.map { it.first }) {
            if (selected.size >= 12) break
            val author = item.author.lowercase(Locale.ROOT)
            val count = artistCounts[author] ?: 0
            if (count < 3) {
                selected.add(item)
                artistCounts[author] = count + 1
            }
        }
        return selected
    }

    private fun filterHomeSections(
        sections: List<YTMusicHomeSection>,
        profile: RecommendationManager.TasteProfile,
    ): List<YTMusicHomeSection> {
        val topLang = profile.topLanguages.firstOrNull()?.first
        return sections.mapNotNull { section ->
            val filtered = section.songs.filter { item ->
                !RecommendationManager.isCompilationTrack(item.title, item.durationText) &&
                    !RecommendationManager.isNonMusicVideo(item.title, item.author) &&
                    !RecommendationManager.isUnofficialContent(item.title, item.author) &&
                    !profile.skippedTracks.contains(item.videoId)
            }.mapNotNull { item ->
                val meta = RecommendationManager.inferMetadata(item)
                if (!meta.isOfficial) return@mapNotNull null
                if (topLang != null && meta.language != topLang) return@mapNotNull null
                item
            }.distinctBy { it.videoId }
            if (filtered.size >= 3) section.copy(songs = filtered.take(12)) else null
        }
    }

    /**
     * 2. getRelatedSongs(videoId)
     * Returns contextual similar songs for a target track.
     */
    suspend fun getRelatedSongs(videoId: String): List<VideoItem> = withContext(Dispatchers.IO) {
        val cacheKey = "related_songs_v2_$videoId"
        val cached = loadVideoItems(cacheKey)
        if (cached != null && cached.isNotEmpty()) {
            return@withContext cached
        }

        Log.d(TAG, "Generating live related songs for $videoId...")

        // 1. Metrolist: next → related browse (official YT Music related tab)
        var pool = fetchYtRelatedForSeed(videoId).orEmpty()

        // 2. InnerTube watch-next radio
        if (pool.isEmpty()) pool = InnerTube.getWatchNextRadio(videoId)

        // 3. TasteDNA query search fallback
        val historyEntry = db.historyDao().getAllHistory().firstOrNull { it.videoId == videoId }
        val likedEntry = db.likedSongDao().getAll().firstOrNull { it.videoId == videoId }
        val signalEntry = db.interactionSignalDao().get(videoId)

        val seedTitle = historyEntry?.title ?: likedEntry?.title ?: signalEntry?.title ?: ""
        val seedAuthor = historyEntry?.author ?: likedEntry?.author ?: signalEntry?.author ?: ""

        val seedMeta = if (seedTitle.isNotEmpty()) {
            val seedItem = VideoItem(videoId, seedTitle, seedAuthor)
            RecommendationManager.getCachedOrInferredMetadata(db, seedItem)
        } else null

        var profileCache: RecommendationManager.TasteProfile? = null
        suspend fun getProfile(): RecommendationManager.TasteProfile {
            return profileCache ?: RecommendationManager.buildTasteProfile(db).also { profileCache = it }
        }

        if (pool.isEmpty()) {
            Log.d(TAG, "getWatchNextRadio empty for $videoId, using TasteDNA fallback queries...")
            if (seedMeta != null && seedTitle.isNotEmpty()) {
                val profile = getProfile()
                val knownArtists = profile.topArtists.map { it.first }.toSet() + profile.skippedArtists + seedAuthor
                val yr = java.time.LocalDate.now().year

                val queries = try {
                    RecommendationManager.buildAcousticQueriesForSeed(recDb, seedMeta, knownArtists, db)
                } catch (_: Exception) {
                    listOf(
                        "$seedAuthor official popular",
                        "${seedMeta.genre} similar hits $yr",
                        "$seedTitle similar music"
                    )
                }
                pool = fetchCandidatesFromQueries(queries)
            } else {
                Log.w(TAG, "No metadata available for fallback seed videoId '$videoId'. Doing raw search fallback.")
                pool = InnerTube.search(videoId)
            }
        }

        // Apply scoring and similarity filters
        val scored = ArrayList<Pair<VideoItem, Double>>()
        val profile = getProfile()

        for (item in pool) {
            if (item.videoId == videoId) continue
            if (RecommendationManager.isCompilationTrack(item.title, item.durationText)) continue
            if (RecommendationManager.isNonMusicVideo(item.title, item.author)) continue
            if (RecommendationManager.isUnofficialContent(item.title, item.author)) continue
            if (profile.skippedTracks.contains(item.videoId) || profile.skippedArtists.contains(item.author.lowercase(Locale.ROOT))) continue

            val meta = RecommendationManager.getCachedOrInferredMetadata(db, item)

            if (!meta.isOfficial) continue
            if (seedMeta != null && meta.language != seedMeta.language) continue

            var totalSimilarity = 0.5
            if (seedMeta != null) {
                val genreScore = if (meta.genre == seedMeta.genre) 1.0 else 0.0
                val artistScore = if (meta.artist.lowercase() == seedMeta.artist.lowercase()) {
                    1.0
                } else if (RecommendationManager.isSimilarArtist(meta.artist, seedMeta.artist)) {
                    0.6
                } else {
                    0.0
                }
                val moodScore = if (meta.mood == seedMeta.mood) 1.0 else 0.2
                val langScore = if (meta.language == seedMeta.language) 1.0 else 0.0

                val energyDelta = Math.abs(meta.energy - seedMeta.energy)
                val energyScore = (1.0 - energyDelta).coerceIn(0.0, 1.0)

                val bpm1 = meta.tempo.toDouble()
                val bpm2 = seedMeta.tempo.toDouble()
                val effectiveTempoDelta = minOf(
                    Math.abs(bpm1 - bpm2),
                    Math.abs(bpm1 - bpm2 * 2.0),
                    Math.abs(bpm1 * 2.0 - bpm2),
                    Math.abs(bpm1 / 2.0 - bpm2),
                    Math.abs(bpm1 - bpm2 / 2.0)
                )
                val tempoScore = Math.cos((effectiveTempoDelta / 60.0 * Math.PI).coerceIn(0.0, Math.PI)) / 2.0 + 0.5

                totalSimilarity = genreScore * 0.25 + artistScore * 0.20 + moodScore * 0.15 + langScore * 0.15 + energyScore * 0.125 + tempoScore * 0.125
            } else {
                totalSimilarity = RecommendationManager.calculateTasteSimilarity(meta, profile.tasteDNA)
            }

            val officialBonus = if (meta.isOfficial) 0.15 else 0.0
            scored.add(item to (totalSimilarity + officialBonus))
        }

        val sorted = scored.sortedByDescending { it.second }.map { it.first }

        val selected = ArrayList<VideoItem>()
        val artistCounts = HashMap<String, Int>()
        val seedAuthorLower = seedAuthor.lowercase(Locale.ROOT)
        for (item in sorted) {
            if (selected.size >= 12) break
            val author = item.author.lowercase(Locale.ROOT)
            val count = artistCounts[author] ?: 0
            val cap = if (author == seedAuthorLower) 1 else 3
            if (count < cap) {
                selected.add(item)
                artistCounts[author] = count + 1
            }
        }

        if (selected.isNotEmpty()) {
            saveVideoItems(cacheKey, selected)
        }
        selected
    }

    private data class SmartQueueCandidate(
        val item: VideoItem,
        val meta: SongMetadata,
        val seedSimilarity: Double,
        val tasteScore: Double,
        val behaviorScore: Double,
        val recentPenalty: Double,
        val baseScore: Double
    )

    suspend fun getSongRadio(videoId: String, fallbackTitle: String = "", fallbackAuthor: String = "", currentQueue: List<VideoItem> = emptyList()): List<VideoItem> = withContext(Dispatchers.IO) {
        smartQueueMutex.withLock {
            getSongRadioInternal(videoId, fallbackTitle, fallbackAuthor, currentQueue)
        }
    }

    private suspend fun getSongRadioInternal(videoId: String, fallbackTitle: String, fallbackAuthor: String, currentQueue: List<VideoItem>): List<VideoItem> {
        // v3 invalidates the old random/stale queue and enforces the repository's
        // 15-minute TTL for Smart Queue only. Home recommendation caches are untouched.
        val cacheKey = "song_radio_v3_$videoId"
        val cached = loadVideoItems(cacheKey, allowStale = false)
        if (cached != null && cached.isNotEmpty()) {
            Log.d(TAG, "Smart Queue cache hit for seed=$videoId size=${cached.size}")
            return cached
        }

        Log.d(TAG, "Generating Smart Queue for seed track $videoId...")

        val pool = mutableListOf<VideoItem>()
        
        val historyEntry = db.historyDao().getAllHistory().firstOrNull { it.videoId == videoId }
        val likedEntry = db.likedSongDao().getAll().firstOrNull { it.videoId == videoId }
        val signalEntry = db.interactionSignalDao().get(videoId)

        val seedTitle = historyEntry?.title ?: likedEntry?.title ?: signalEntry?.title ?: fallbackTitle
        val seedAuthor = historyEntry?.author ?: likedEntry?.author ?: signalEntry?.author ?: fallbackAuthor

        val profile = RecommendationManager.buildTasteProfile(db)
        val seedMeta = if (seedTitle.isNotEmpty()) {
            // Use real cached/estimated audio features whenever available. The
            // title-based estimator remains the fallback for unknown tracks.
            RecommendationManager.getCachedOrInferredMetadata(
                db,
                VideoItem(videoId, seedTitle, seedAuthor),
                context
            )
        } else null
        val profileLanguage = profile.topLanguages.firstOrNull()?.first?.takeIf { it.isNotBlank() }

        val online = isOnline()

        if (online) {
            Log.d(TAG, "Device is ONLINE. Using YTM Related → YTM Radio → Search fallback.")

            // 1. PRIMARY: YouTube Music Related (fresh, algorithm-curated) - SAME LANGUAGE ONLY
            val ytRelated = fetchYtRelatedForSeed(videoId).orEmpty()
            if (ytRelated.isNotEmpty()) {
                Log.d(TAG, "YTM Related returned ${ytRelated.size} tracks")
                for (track in ytRelated) {
                    val trackMeta = RecommendationManager.inferMetadata(track)
                    // Only add if same language as seed
                    if (seedMeta == null || trackMeta.language == seedMeta.language) {
                        pool.add(track)
                    }
                }
            }

            // 2. SECONDARY: YouTube Music Radio (RDAMVM radio playlist) - SAME LANGUAGE ONLY
            if (pool.size < 15) {
                val radioTracks = InnerTube.getWatchNextRadio(videoId)
                if (radioTracks.isNotEmpty()) {
                    Log.d(TAG, "YTM Radio returned ${radioTracks.size} tracks")
                    for (track in radioTracks) {
                        if (pool.none { it.videoId == track.videoId }) {
                            val trackMeta = RecommendationManager.inferMetadata(track)
                            // Only add if same language as seed
                            if (seedMeta == null || trackMeta.language == seedMeta.language) {
                                pool.add(track)
                            }
                        }
                    }
                }
            }

            // 2.5 TERTIARY: Last.fm Similar Tracks (genre/mood matched) - SAME LANGUAGE ONLY
            if (pool.size < 15 && seedAuthor.isNotEmpty() && seedTitle.isNotEmpty()) {
                try {
                    val lastFmSimilar = firestoreRecommendationManager.fetchSimilarTracks(seedAuthor, seedTitle)
                    if (lastFmSimilar.isNotEmpty()) {
                        Log.d(TAG, "Last.fm Similar returned ${lastFmSimilar.size} tracks")
                        for ((artist, title) in lastFmSimilar) {
                            if (pool.none { it.title.lowercase(Locale.ROOT) == title.lowercase(Locale.ROOT) &&
                                            it.author.lowercase(Locale.ROOT) == artist.lowercase(Locale.ROOT) }) {
                                // Search YTM for this track to get videoId
                                try {
                                    val results = InnerTube.search("$artist $title").take(3)
                                    val match = results.firstOrNull { result ->
                                        result.author.lowercase(Locale.ROOT).contains(artist.lowercase(Locale.ROOT)) &&
                                        result.title.lowercase(Locale.ROOT).contains(title.lowercase(Locale.ROOT).take(8))
                                    }
                                    if (match != null) {
                                        val matchMeta = RecommendationManager.inferMetadata(match)
                                        if (seedMeta == null || matchMeta.language == seedMeta.language) {
                                            pool.add(match)
                                        }
                                    }
                                } catch (_: Exception) {}
                            }
                            if (pool.size >= 20) break
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Last.fm similar tracks failed: ${e.message}")
                }
            }

            // 3. Cross-genre search to diversify artist pool (SAME LANGUAGE ONLY)
            val poolArtists = pool.map { it.author.lowercase(Locale.ROOT) }.toSet()
            if (poolArtists.size < 10 && seedMeta != null) {
                Log.d(TAG, "Pool has only ${poolArtists.size} unique artists, adding cross-genre search...")
                val genreLower = seedMeta.genre.lowercase(Locale.ROOT).replace("rap/hip-hop", "rap hip hop")
                val langLower = seedMeta.language.lowercase(Locale.ROOT)
                val yr = java.time.LocalDate.now().year

                // Only search in the same language as the seed track
                val langPrefix = when (seedMeta.language) {
                    "Hindi" -> "hindi"
                    "Punjabi" -> "punjabi"
                    "Tamil" -> "tamil"
                    "Korean" -> "korean"
                    else -> "" // English - no prefix needed
                }

                // Keep query order deterministic. Exploration is controlled by
                // the later ranking stage instead of randomising the candidate pool.
                val crossGenres = listOf("rap hip hop", "pop", "r&b", "indie", "electronic", "rock", "lofi")
                    .filter { !genreLower.contains(it.take(3)) }
                    .sortedBy { it }
                    .take(3)

                val searchQueries = mutableListOf<String>()
                // Same language + genre queries
                if (langPrefix.isNotEmpty()) {
                    searchQueries.add("$langPrefix $genreLower official hits $yr")
                    searchQueries.add("$langPrefix ${seedMeta.mood.lowercase()} songs")
                } else {
                    searchQueries.add("$genreLower official hits $yr")
                    searchQueries.add("${seedMeta.mood.lowercase()} $genreLower songs")
                }
                for (cg in crossGenres) {
                    if (langPrefix.isNotEmpty()) {
                        searchQueries.add("$langPrefix $cg official $yr")
                    } else {
                        searchQueries.add("$cg official $yr")
                    }
                }
                for (query in searchQueries) {
                    try {
                        val results = InnerTube.search(query).take(5)
                        for (item in results) {
                            val itemMeta = RecommendationManager.inferMetadata(item)
                            // Only add if same language and not already in pool
                            if (pool.none { it.videoId == item.videoId } &&
                                itemMeta.language == seedMeta.language &&
                                !RecommendationManager.isCompilationTrack(item.title, item.durationText) &&
                                !RecommendationManager.isNonMusicVideo(item.title, item.author) &&
                                !RecommendationManager.isUnofficialContent(item.title, item.author)) {
                                pool.add(item)
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Search fallback failed for '$query': ${e.message}")
                    }
                    if (pool.size >= 20) break
                }
            }
        } else {
            Log.d(TAG, "Device is OFFLINE. Using downloaded songs for radio.")
            val downloads = db.downloadDao().getByStatus("completed")
            val downloadedItems = downloads.map { 
                VideoItem(it.videoId, it.title, it.author, it.durationText) 
            }
            val available = downloadedItems
                .filter { it.videoId != videoId }
                .sortedWith(compareBy<VideoItem>({ it.author.lowercase(Locale.ROOT) }, { it.title.lowercase(Locale.ROOT) }, { it.videoId }))
            pool.addAll(available)
        }

        val history = db.historyDao().getAllHistory()
        val recentlyPlayedIds = history.take(20).map { it.videoId }.toSet()
        val recentlyPlayedTitles = history.take(20).map { RecommendationManager.normalizeTitle(it.title) }.toSet()
        val signals = db.interactionSignalDao().getAll().associateBy { it.videoId }
        val currentQueueArtists = currentQueue.map { normalizeQueueArtist(it.author) }.toSet()

        // De-duplicate before ranking. A related track can arrive from YTM,
        // radio, Last.fm and search under different result objects.
        val dedupedPool = pool.distinctBy {
            "${RecommendationManager.normalizeTitle(it.title)}|${normalizeQueueArtist(it.author)}"
        }
        val duplicateRemoved = pool.size - dedupedPool.size

        var recentlyPlayedRemoved = 0
        val candidatePairs = dedupedPool.mapNotNull { item ->
            if (item.videoId == videoId ||
                RecommendationManager.isCompilationTrack(item.title, item.durationText) ||
                RecommendationManager.isNonMusicVideo(item.title, item.author) ||
                RecommendationManager.isUnofficialContent(item.title, item.author) ||
                profile.skippedTracks.contains(item.videoId) ||
                profile.skippedArtists.any { skipped -> normalizeQueueArtist(skipped) == normalizeQueueArtist(item.author) } ||
                (seedTitle.isNotEmpty() && RecommendationManager.isTooSimilar(seedTitle, item.title))
            ) {
                return@mapNotNull null
            }

            val signal = signals[item.videoId]
            val recentlyPlayed = item.videoId in recentlyPlayedIds ||
                RecommendationManager.normalizeTitle(item.title) in recentlyPlayedTitles
            val strongRepeatIntent = signal?.isLiked == true ||
                (signal?.repeatCount ?: 0) > 0 ||
                (signal?.completeCount ?: 0) >= 2
            if (recentlyPlayed && !strongRepeatIntent) {
                recentlyPlayedRemoved++
                return@mapNotNull null
            }

            val meta = RecommendationManager.getCachedOrInferredMetadata(db, item, context)
            if (!meta.isOfficial) return@mapNotNull null
            item to meta
        }

        // Language is a Smart Queue hard constraint. This is deliberately kept
        // inside getSongRadio; Home's normal recommendation sections are not
        // routed through this filter and remain mixed-language.
        val queueLanguage = (seedMeta?.language?.takeIf { it.isNotBlank() } ?: profileLanguage)
            ?: candidatePairs.groupingBy { it.second.language }.eachCount().maxByOrNull { it.value }?.key
        val languageFilteredPairs = if (queueLanguage != null) {
            candidatePairs.filter { sameQueueLanguage(it.second.language, queueLanguage) }
        } else {
            candidatePairs
        }
        val languageRemoved = candidatePairs.size - languageFilteredPairs.size

        val now = System.currentTimeMillis()
        val recentArtistCounts = history.take(20)
            .groupingBy { normalizeQueueArtist(it.author) }
            .eachCount()
        fun behaviorScore(item: VideoItem): Double {
            val signal = signals[item.videoId]
            val ageMs = (now - (signal?.lastPlayedAt ?: 0L)).coerceAtLeast(0L)
            val decay = if (signal == null || signal.lastPlayedAt <= 0L) 0.0
            else Math.exp(-ageMs.toDouble() / (14.0 * 24.0 * 60.0 * 60.0 * 1000.0))
            val positive = (signal?.completeCount ?: 0) * 0.20 +
                (signal?.repeatCount ?: 0) * 0.35 +
                (if (signal?.isLiked == true) 0.55 else 0.0) +
                (if (signal?.isDownloaded == true) 0.10 else 0.0)
            val negative = (signal?.skip20sCount ?: 0) * 0.55 +
                (signal?.skipCount ?: 0) * 0.20
            val recentArtistAffinity = (recentArtistCounts[normalizeQueueArtist(item.author)] ?: 0) * 0.04
            return ((positive - negative) * decay + recentArtistAffinity).coerceIn(-1.0, 1.0)
        }

        fun tempoSimilarity(first: Int, second: Int): Double {
            val bpm1 = first.toDouble()
            val bpm2 = second.toDouble()
            val effectiveDelta = minOf(
                Math.abs(bpm1 - bpm2), Math.abs(bpm1 - bpm2 * 2.0),
                Math.abs(bpm1 * 2.0 - bpm2), Math.abs(bpm1 / 2.0 - bpm2),
                Math.abs(bpm1 - bpm2 / 2.0)
            )
            return Math.cos((effectiveDelta / 60.0 * Math.PI).coerceIn(0.0, Math.PI)) / 2.0 + 0.5
        }

        val scoredCandidates = languageFilteredPairs.map { (item, meta) ->
            val seedSimilarity = if (seedMeta != null) {
                val genre = if (meta.genre.equals(seedMeta.genre, ignoreCase = true)) 1.0 else 0.0
                val mood = if (meta.mood.equals(seedMeta.mood, ignoreCase = true)) 1.0 else 0.25
                val energy = (1.0 - Math.abs(meta.energy - seedMeta.energy)).coerceIn(0.0, 1.0)
                val tempo = tempoSimilarity(meta.tempo, seedMeta.tempo)
                val artist = when {
                    normalizeQueueArtist(item.author) == normalizeQueueArtist(seedAuthor) -> 1.0
                    RecommendationManager.isSimilarArtist(item.author, seedAuthor) -> 0.82
                    else -> 0.35
                }
                genre * 0.24 + mood * 0.14 + energy * 0.24 + tempo * 0.23 + artist * 0.15
            } else {
                0.35
            }
            val tasteScore = RecommendationManager.calculateTasteSimilarity(meta, profile.tasteDNA)
            val behavior = behaviorScore(item)
            val recentPenalty = if (item.videoId in recentlyPlayedIds) -0.18 else 0.0
            val currentQueuePenalty = if (normalizeQueueArtist(item.author) in currentQueueArtists) -0.12 else 0.0
            val officialBonus = if (meta.isOfficial) 0.05 else 0.0
            val baseScore = seedSimilarity * 0.52 + tasteScore * 0.30 + behavior * 0.13 +
                recentPenalty + currentQueuePenalty + officialBonus
            SmartQueueCandidate(item, meta, seedSimilarity, tasteScore, behavior, recentPenalty, baseScore)
        }

        val sequenced = ArrayList<VideoItem>()
        val remaining = ArrayList(scoredCandidates)
        val artistCount = mutableMapOf<String, Int>()
        var lastArtist = normalizeQueueArtist(seedAuthor)
        val seedAuthorLower = normalizeQueueArtist(seedAuthor)
        var artistCapRemoved = 0

        fun positionScore(candidate: SmartQueueCandidate, position: Int): Double {
            // Close continuity first, then gradually hand more weight to the
            // user's taste and controlled discovery.
            val seedWeight = when {
                position < 3 -> 0.64
                position < 7 -> 0.48
                else -> 0.34
            }
            val tasteWeight = when {
                position < 3 -> 0.20
                position < 7 -> 0.32
                else -> 0.44
            }
            val discoveryBonus = if (position >= 7 &&
                !RecommendationManager.isSimilarArtist(seedAuthor, candidate.item.author) &&
                normalizeQueueArtist(candidate.item.author) != seedAuthorLower) 0.035 else 0.0
            return candidate.seedSimilarity * seedWeight +
                candidate.tasteScore * tasteWeight +
                candidate.behaviorScore * 0.12 +
                candidate.baseScore * 0.08 + discoveryBonus
        }

        while (remaining.isNotEmpty() && sequenced.size < 20) {
            val position = sequenced.size
            val eligible = remaining.filter { candidate ->
                val artist = normalizeQueueArtist(candidate.item.author)
                val count = artistCount[artist] ?: 0
                val cap = if (artist == seedAuthorLower) 1 else 2
                val lastIndex = sequenced.indexOfLast { normalizeQueueArtist(it.author) == artist }
                val gap = if (lastIndex < 0) Int.MAX_VALUE else sequenced.size - lastIndex
                count < cap &&
                    (gap >= 3 || artist != lastArtist) &&
                    sequenced.none { RecommendationManager.isTooSimilar(it.title, candidate.item.title) }
            }
            if (eligible.isEmpty()) {
                artistCapRemoved += remaining.size
                break
            }

            val next = eligible.sortedWith(
                compareByDescending<SmartQueueCandidate> { positionScore(it, position) }
                    .thenBy { RecommendationManager.normalizeTitle(it.item.title) }
                    .thenBy { normalizeQueueArtist(it.item.author) }
                    .thenBy { it.item.videoId }
            ).first()

            Log.d(
                TAG,
                "Smart Queue pick=${position + 1} title='${next.item.title}' artist='${next.item.author}' " +
                    "seed=${"%.3f".format(Locale.ROOT, next.seedSimilarity)} " +
                    "taste=${"%.3f".format(Locale.ROOT, next.tasteScore)} " +
                    "behavior=${"%.3f".format(Locale.ROOT, next.behaviorScore)} " +
                    "final=${"%.3f".format(Locale.ROOT, positionScore(next, position))}"
            )

            sequenced.add(next.item)
            val artist = normalizeQueueArtist(next.item.author)
            lastArtist = artist
            artistCount[artist] = (artistCount[artist] ?: 0) + 1
            remaining.remove(next)
            remaining.removeAll {
                RecommendationManager.isTooSimilar(next.item.title, it.item.title)
            }
        }

        // Fallback: if the online/offline pool is empty, use only history/liked
        // songs that still satisfy the Smart Queue language constraint.
        if (sequenced.isEmpty()) {
            Log.d(TAG, "Pool empty, falling back to history/liked songs.")
            val fallback = mutableListOf<VideoItem>()
            fallback.addAll(history.take(30).map { VideoItem(it.videoId, it.title, it.author, it.durationText) })
            fallback.addAll(db.likedSongDao().getAll().take(30).map { VideoItem(it.videoId, it.title, it.author, it.durationText) })
            val filtered = fallback
                .filter { it.videoId != videoId }
                .distinctBy { "${RecommendationManager.normalizeTitle(it.title)}|${normalizeQueueArtist(it.author)}" }
                .map { it to RecommendationManager.getCachedOrInferredMetadata(db, it, context) }
                .filter { (_, meta) -> meta.isOfficial && (queueLanguage == null || sameQueueLanguage(meta.language, queueLanguage)) }
                .sortedWith(compareByDescending<Pair<VideoItem, SongMetadata>> {
                    RecommendationManager.calculateTasteSimilarity(it.second, profile.tasteDNA)
                }.thenBy { RecommendationManager.normalizeTitle(it.first.title) })
                .map { it.first }
            sequenced.addAll(filtered.take(15))
        }

        Log.d(
            TAG,
            "Smart Queue seed=$videoId language=${queueLanguage ?: "unknown"} " +
                "raw=${pool.size} unique=${dedupedPool.size} candidates=${candidatePairs.size} " +
                "removedLanguage=$languageRemoved removedDuplicates=$duplicateRemoved " +
                "removedRecent=$recentlyPlayedRemoved removedArtistCap=$artistCapRemoved " +
                "final=${sequenced.size}"
        )
        Log.d(
            TAG,
            "Smart Queue selected=" + sequenced.joinToString(" | ") {
                "${it.title} — ${it.author}"
            }
        )

        if (sequenced.isNotEmpty()) {
            saveVideoItems(cacheKey, sequenced)
        }
        return sequenced
    }

    /**
     * 4. getArtistSongs(artistId)
     * Retrieves top/popular official songs for artist profile page (using channelId).
     */
    suspend fun getArtistSongs(artistId: String): List<VideoItem> = withContext(Dispatchers.IO) {
        val cacheKey = "artist_songs_$artistId"
        val cached = loadVideoItems(cacheKey)
        if (cached != null && cached.isNotEmpty()) {
            return@withContext cached
        }

        Log.d(TAG, "Fetching popular/official songs for artist channel ID $artistId...")
        val artistData = InnerTube.fetchChannelData(artistId)
        val artistName = artistData.title.ifBlank {
            // Fallback: try to search for the ID or scrape name from metadata
            ""
        }

        val songs = ArrayList<VideoItem>()
        if (artistName.isNotEmpty()) {
            // Fetch top songs by artist name
            val topSongs = InnerTube.getArtistTopSongs(artistName)
            songs.addAll(topSongs)

            // Scrape album and singles to ingest high-fidelity official tracks
            try {
                val (albums, singles) = InnerTube.getArtistAlbumsAndSingles(artistId, artistName)
                val allCollections = (albums.take(2) + singles.take(2)).distinctBy { it.playlistId }
                for (col in allCollections) {
                    val albumSongs = InnerTube.getAlbumSongs(col.playlistId)
                    songs.addAll(albumSongs)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to scrape albums for artist '$artistName': ${e.message}")
            }
        } else {
            // Direct ID search fallback
            val searchRes = InnerTube.search(artistId)
            songs.addAll(searchRes)
        }

        // Apply strict curation filters
        val filtered = songs.distinctBy { it.videoId }
            .filter { !RecommendationManager.isCompilationTrack(it.title, it.durationText) }
            .filter { !RecommendationManager.isNonMusicVideo(it.title, it.author) }
            .filter { !RecommendationManager.isUnofficialContent(it.title, it.author) }
            .take(25)

        if (filtered.isNotEmpty()) {
            saveVideoItems(cacheKey, filtered)
        }
        filtered
    }

    /**
     * 5. getArtistRelatedArtists(artistId)
     * Returns similar artists for profile discovery.
     */
    suspend fun getArtistRelatedArtists(artistId: String): List<ArtistItem> = withContext(Dispatchers.IO) {
        val cacheKey = "related_artists_$artistId"
        val cached = loadArtistItems(cacheKey)
        if (cached != null && cached.isNotEmpty()) {
            return@withContext cached
        }

        Log.d(TAG, "Fetching related artists for channel ID $artistId...")
        val artistData = InnerTube.fetchChannelData(artistId)
        val artistName = artistData.title

        val related = ArrayList<ArtistItem>()
        if (artistName.isNotEmpty()) {
            val searchResult = InnerTube.searchAll("artists like $artistName music")
            related.addAll(searchResult.artists)
        }

        val finalRelated = related.distinctBy { it.channelId }.take(8)
        if (finalRelated.isNotEmpty()) {
            saveArtistItems(cacheKey, finalRelated)
        }
        finalRelated
    }

    /**
     * 6. getPlaylistSongs(playlistId)
     * High-fidelity playlist resolver layered with strict filters and cache.
     */
    suspend fun getPlaylistSongs(playlistId: String): List<VideoItem> = withContext(Dispatchers.IO) {
        val cacheKey = "playlist_songs_$playlistId"
        val cached = loadVideoItems(cacheKey)
        if (cached != null && cached.isNotEmpty()) {
            return@withContext cached
        }

        Log.d(TAG, "Resolving playlist songs for $playlistId...")
        val (_, songs) = InnerTube.getPlaylistSongs(playlistId)

        // Strict Curation filters applied to loaded playlists
        val filtered = songs.filter { !RecommendationManager.isCompilationTrack(it.title, it.durationText) }
            .filter { !RecommendationManager.isNonMusicVideo(it.title, it.author) }
            .distinctBy { it.videoId }

        if (filtered.isNotEmpty()) {
            saveVideoItems(cacheKey, filtered)
        }
        filtered
    }

    /**
     * Curates and returns genre-based mixes for Lofi, Rap/Hip-Hop, Bollywood, Punjabi Folk, Pop, Indie, and Rock
     * with caching, similarity scoring, target mood filtering, and artist diversity.
     */
    suspend fun getGenreMixes(): List<SpotifyMix> = withContext(Dispatchers.IO) {
        val cacheKey = "genre_mixes_v3"
        val cachedJson = loadCacheStr(cacheKey, allowStale = true)
        if (cachedJson != null) {
            try {
                val type = object : TypeToken<List<SpotifyMix>>() {}.type
                val list: List<SpotifyMix> = gson.fromJson(cachedJson, type)
                if (list.isNotEmpty()) {
                    Log.d(TAG, "Loaded getGenreMixes from disk cache.")
                    return@withContext list
                }
            } catch (_: Exception) {}
        }

        Log.d(TAG, "Generating Genre-based smart mixes...")
        val profile = RecommendationManager.buildTasteProfile(db)
        val tasteDNA = profile.tasteDNA

        val mixes = ArrayList<SpotifyMix>()

        coroutineScope {
            val deferreds = RecommendationManager.GENRE_CONFIGS.map { (genreName, config) ->
                async(Dispatchers.IO) {
                    val genreCandidates = try {
                        config.queries.map { query ->
                            async(Dispatchers.IO) {
                                try {
                                    InnerTube.search(query)
                                } catch (e: Exception) {
                                    emptyList<VideoItem>()
                                }
                            }
                        }.awaitAll().flatten().distinctBy { it.videoId }
                    } catch (e: Exception) {
                        emptyList<VideoItem>()
                    }

                    // Content quality filters
                    val filteredCandidates = genreCandidates.filter { item ->
                        !RecommendationManager.isCompilationTrack(item.title, item.durationText) &&
                        !RecommendationManager.isNonMusicVideo(item.title, item.author) &&
                        !RecommendationManager.isUnofficialContent(item.title, item.author) &&
                        !profile.skippedTracks.contains(item.videoId) &&
                        !profile.skippedArtists.contains(item.author.trim().lowercase(Locale.ROOT))
                    }

                    // Score candidates
                    val scored = filteredCandidates.mapNotNull { item ->
                        val meta = RecommendationManager.inferMetadata(item)
                        val isGenreMatch = meta.genre.lowercase(Locale.ROOT) == genreName.lowercase(Locale.ROOT)
                        val isMoodMatch = meta.mood.lowercase(Locale.ROOT) == config.targetMood.lowercase(Locale.ROOT)
                        
                        if (isGenreMatch || isMoodMatch) {
                            val similarity = RecommendationManager.calculateTasteSimilarity(meta, tasteDNA)
                            val officialBonus = if (meta.isOfficial) 0.15 else 0.0
                            val finalScore = similarity + officialBonus
                            RecommendedSong(item, finalScore, "genre_mix", "Matches your genre preference")
                        } else {
                            null
                        }
                    }

                    // Artist diversity
                    val sorted = scored.distinctBy { it.videoItem.videoId }
                        .distinctBy { "${RecommendationManager.normalizeTitle(it.videoItem.title)}|${it.videoItem.author.lowercase(Locale.ROOT)}" }
                        .sortedByDescending { it.score }

                    val selected = ArrayList<RecommendedSong>()
                    val artistCounts = HashMap<String, Int>()

                    for (rec in sorted) {
                        if (selected.size >= 12) break
                        val normArtist = RecommendationManager.normalizeArtistName(rec.videoItem.author)
                        val count = artistCounts[normArtist] ?: 0
                        if (count < 2) {
                            selected.add(rec)
                            artistCounts[normArtist] = count + 1
                        }
                    }

                    if (selected.size >= 3) {
                        SpotifyMix(
                            id = "genre_mix_${genreName.lowercase(Locale.ROOT).replace("/", "_")}",
                            title = "$genreName Mix",
                            description = config.description,
                            songs = selected,
                            gradientStartHex = config.gradientStartHex,
                            gradientEndHex = config.gradientEndHex
                        )
                    } else {
                        null
                    }
                }
            }
            
            val resolvedMixes = deferreds.awaitAll().filterNotNull()
            mixes.addAll(resolvedMixes)
        }

        if (mixes.isNotEmpty()) {
            saveCacheStr(cacheKey, gson.toJson(mixes))
        }
        mixes
    }

    suspend fun getMoodCategoryPage(browseId: String, params: String): List<Pair<String, List<Any>>> = withContext(Dispatchers.IO) {
        InnerTube.getMoodCategoryPage(browseId, params)
    }

    /** Retrieves the user's top artists to inject into dynamic search queries for hyper-personalization. */
    suspend fun getTopArtistsForPersonalization(limit: Int = 2): List<String> = withContext(Dispatchers.IO) {
        try {
            val profile = RecommendationManager.buildTasteProfile(db)
            profile.topArtists.take(limit).map { it.first }
        } catch (e: Exception) {
            emptyList()
        }
    }
 
     // ── Internal Helpers ──────────────────────────────────────────────────────

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

    private suspend fun fetchArtistName(channelId: String): String = withContext(Dispatchers.IO) {
        try {
            val data = InnerTube.fetchChannelData(channelId)
            return@withContext data.title
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve artist title for channel $channelId: ${e.message}")
            return@withContext ""
        }
    }
}
