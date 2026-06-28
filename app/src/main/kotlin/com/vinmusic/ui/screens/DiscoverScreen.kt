package com.vinmusic.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.vinmusic.innertube.InnerTube
import com.vinmusic.innertube.VideoItem
import com.vinmusic.player.PlayerViewModel
import com.vinmusic.recommendation.RecommendationManager
import com.vinmusic.ui.theme.VinColors
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Deferred
import kotlin.math.roundToInt
import kotlin.random.Random

// Diverse premium discovery query templates
private val DISCOVER_QUERIES = listOf(
    "trending viral hindi songs 2025",
    "best punjabi hits 2025",
    "top english pop hits 2025",
    "new bollywood songs 2025",
    "top tamil hits 2025",
    "trending k-pop songs 2025",
    "new indie songs 2025",
    "best rap hip hop 2025",
    "romantic songs 2025"
)

private data class DiscoverGenreFilter(
    val label: String,
    val query: String,
    val matchTerms: List<String>
)

private val DISCOVER_GENRE_FILTERS = listOf(
    DiscoverGenreFilter("Hip-Hop", "best hip hop rap songs trending", listOf("hip hop", "hip-hop", "rap", "trap", "drill")),
    DiscoverGenreFilter("Bollywood", "bollywood hit songs trending", listOf("bollywood", "hindi", "filmi")),
    DiscoverGenreFilter("Punjabi", "punjabi hit songs trending", listOf("punjabi", "sidhu", "karan aujla", "diljit")),
    DiscoverGenreFilter("Pop", "english pop hits trending", listOf("pop", "english", "billboard")),
    DiscoverGenreFilter("Indie", "indie pop songs trending", listOf("indie", "alternative", "acoustic")),
    DiscoverGenreFilter("R&B", "r&b soul songs trending", listOf("r&b", "rnb", "soul", "weeknd", "sza")),
    DiscoverGenreFilter("Rock", "rock songs trending", listOf("rock", "alt rock", "band")),
    DiscoverGenreFilter("Romantic", "romantic songs trending", listOf("romantic", "romance", "love"))
)

// Data class to wrap song with dynamic recommendation metadata
data class DiscoverSong(
    val videoItem: VideoItem,
    val recommendationReason: String,
    val vibeScore: Int
)

/**
 * Filter search results to ensure only official audio/video released by the artist is added.
 * Excludes fan-made content, covers, karaokes, reaction videos, and loops.
 */
fun isOfficialRelease(song: VideoItem): Boolean {
    val titleLower = song.title.lowercase()
    
    // Ignore common non-official/amateur patterns
    val ignoreKeywords = listOf(
        "cover", "karaoke", "instrumental", "tribute", "reaction", 
        "mashup", "reverb", "slowed", "1 hour", "2 hour", "3 hour", "nonstop", 
        "non-stop", "loop", "bgm", "clean audio", "lyrics video", "lyrics only",
        "parody", "fanmade", "fan-made", "synthesia", "piano tutorial",
        "remix", "sped up", "speed up", "speedup", "nightcore", "8d audio",
        "lofi version", "lo-fi version", "slowed reverb", "edit audio",
        "tiktok version", "tik tok version", "extended mix", "club mix"
    )
    
    return ignoreKeywords.none { titleLower.contains(it) }
}

private fun parseDiscoverDurationMs(durationText: String): Long? {
    val parts = durationText.split(":")
        .map { it.trim().toLongOrNull() ?: return null }
    if (parts.size !in 2..3) return null
    return parts.fold(0L) { total, part -> total * 60L + part } * 1_000L
}

private fun discoverPreviewStartMs(durationText: String): Long {
    val duration = parseDiscoverDurationMs(durationText) ?: return 42_000L
    if (duration <= 70_000L) return (duration * 0.28f).toLong().coerceAtLeast(8_000L)
    val target = (duration * 0.38f).toLong()
    val latest = (duration - 38_000L).coerceAtLeast(12_000L)
    return target.coerceIn(22_000L.coerceAtMost(latest), latest)
}

private fun formatPreviewTime(ms: Long): String {
    val totalSeconds = (ms / 1_000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "%d:%02d".format(minutes, seconds)
}

private fun isDiscoverCandidate(song: VideoItem): Boolean {
    val duration = parseDiscoverDurationMs(song.durationText) ?: return false
    if (duration !in 45_000L..720_000L) return false
    if (!isOfficialRelease(song)) return false
    if (RecommendationManager.isCompilationTrack(song.title, song.durationText)) return false
    if (RecommendationManager.isNonMusicVideo(song.title, song.author)) return false
    if (RecommendationManager.isUnofficialContent(song.title, song.author)) return false
    return true
}

private fun discoverMatchesGenreFilter(song: VideoItem, filter: DiscoverGenreFilter?): Boolean {
    if (filter == null) return true
    val meta = RecommendationManager.inferMetadata(song)
    val haystack = listOf(song.title, song.author, meta.genre, meta.mood, meta.language)
        .joinToString(" ")
        .lowercase()
    return filter.matchTerms.any { term -> haystack.contains(term.lowercase()) }
}

private fun buildDiscoverDeck(pool: List<DiscoverSong>, filter: DiscoverGenreFilter? = null): List<DiscoverSong> {
    return pool
        .filter { isDiscoverCandidate(it.videoItem) && discoverMatchesGenreFilter(it.videoItem, filter) }
        .distinctBy { discoverSongKey(it.videoItem) }
        .sortedByDescending { it.vibeScore + Random.nextInt(0, 7) }
        .fold(mutableListOf<DiscoverSong>()) { acc, song ->
            val artistKey = song.videoItem.author.lowercase().trim()
            val artistCount = acc.count { it.videoItem.author.lowercase().trim() == artistKey }
            if (artistCount < 1) acc.apply { add(song) } else acc
        }
        .take(36)
}

private fun discoverSongKey(song: VideoItem): String {
    val title = song.title
        .lowercase()
        .replace(Regex("""\([^)]*\)|\[[^]]*]"""), " ")
        .replace(Regex("""(?i)\bofficial\b|\bmusic video\b|\baudio\b|\blyrics?\b|\bfull song\b|\bvisualizer\b|\bhd\b|\b4k\b"""), " ")
        .replace(Regex("""(?i)\bfeat\.?\b.*|\bft\.?\b.*"""), " ")
        .replace(Regex("""[^a-z0-9\u0900-\u097F\u0A00-\u0A7F\s]"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()
    val artist = song.author
        .lowercase()
        .replace(" - topic", "")
        .replace(Regex("""[^a-z0-9\u0900-\u097F\u0A00-\u0A7F\s]"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()
    return "$title|$artist"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverScreen(
    vm: PlayerViewModel,
    onBack: () -> Unit,
    onSongClick: (VideoItem, List<VideoItem>) -> Unit
) {
    val scope = rememberCoroutineScope()
    var cards by remember { mutableStateOf<List<DiscoverSong>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    val swipeState = rememberSwipeState(scope)
    
    // Controls if the next card should auto-play its preview
    var autoPreviewEnabled by remember { mutableStateOf(true) }
    
    // Floating toast popup state
    var showToastMessage by remember { mutableStateOf<String?>(null) }

    var selectedGenreFilter by remember { mutableStateOf<DiscoverGenreFilter?>(null) }
    var selectedMix by remember { mutableStateOf<com.vinmusic.recommendation.SpotifyMix?>(null) }

    val ctx = LocalContext.current
    val db  = remember(ctx) { com.vinmusic.data.db.VinDatabase.getInstance(ctx) }

    // Automatically clear floating toast after 2.5 seconds
    LaunchedEffect(showToastMessage) {
        if (showToastMessage != null) {
            delay(2500L)
            showToastMessage = null
        }
    }

    // ── Load personalized smart discovery pool ──
    fun loadDiscoverSongs(genreFilter: DiscoverGenreFilter? = selectedGenreFilter) {
        isLoading = true
        scope.launch(Dispatchers.IO) {
            try {
                // 1. Fetch user interactions, library statistics, and local playlists
                val signals = try { db.interactionSignalDao().getAll() } catch (_: Exception) { emptyList() }
                val history = try { db.historyDao().getAllHistory() } catch (_: Exception) { emptyList() }
                val liked   = try { db.likedSongDao().getAll() } catch (_: Exception) { emptyList() }
                val playlistSongs = try { db.playlistDao().getAllPlaylistSongs() } catch (_: Exception) { emptyList() }

                val discoverPool = mutableListOf<DiscoverSong>()
                fun addCandidate(song: VideoItem, reason: String, score: Int) {
                    if (isDiscoverCandidate(song) && discoverMatchesGenreFilter(song, genreFilter)) {
                        discoverPool.add(DiscoverSong(song, reason, score.coerceIn(70, 100)))
                    }
                }

                liked.shuffled().take(8).forEach { song ->
                    addCandidate(
                        VideoItem(song.videoId, song.title, song.author, song.durationText),
                        "Because you liked this sound",
                        Random.nextInt(91, 99)
                    )
                }
                history.take(10).forEach { song ->
                    addCandidate(
                        VideoItem(song.videoId, song.title, song.author, song.durationText),
                        "From your recent listening lane",
                        Random.nextInt(86, 96)
                    )
                }
                playlistSongs.shuffled().take(8).forEach { song ->
                    addCandidate(
                        VideoItem(song.videoId, song.title, song.author, song.durationText),
                        "Playlist DNA match",
                        Random.nextInt(88, 97)
                    )
                }

                val instantDeck = buildDiscoverDeck(discoverPool, genreFilter)
                if (instantDeck.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        cards = instantDeck
                        isLoading = false
                        autoPreviewEnabled = true
                    }
                }

                // Gather top artists from signals, history, and custom playlists
                val topArtists = signals
                    .filter { it.playCount > 0 }
                    .sortedByDescending { it.playCount * 3 + it.completeCount - it.skipCount }
                    .map { it.author }
                    .distinct()

                val historyArtists = history
                    .groupBy { it.author }
                    .entries
                    .sortedByDescending { it.value.size }
                    .map { it.key }

                val playlistArtists = playlistSongs
                    .groupBy { it.author }
                    .entries
                    .sortedByDescending { it.value.size }
                    .map { it.key }

                val combinedArtists = (topArtists + historyArtists + playlistArtists).distinct()

                // OPTIMIZATION: Instead of 20 parallel search requests which throttle network/choke playback,
                // we choose exactly a few random seeds to query concurrently.
                val seedArtists = combinedArtists.shuffled().take(2)
                val seedLikes = liked.shuffled().take(1)
                val seedPlaylists = playlistSongs.shuffled().take(1)

                coroutineScope {
                    val deferreds = mutableListOf<Deferred<List<DiscoverSong>>>()

                    deferreds.add(async(Dispatchers.IO) {
                        try {
                            vm.recommendationRepository.getQuickPicks()
                                .filter { isDiscoverCandidate(it) && discoverMatchesGenreFilter(it, genreFilter) }
                                .take(12)
                                .map { song ->
                                    DiscoverSong(
                                        videoItem = song,
                                        recommendationReason = "Smart mix from your taste",
                                        vibeScore = Random.nextInt(90, 99)
                                    )
                                }
                        } catch (_: Exception) { emptyList() }
                    })

                    val radioSeeds = (liked.map { VideoItem(it.videoId, it.title, it.author, it.durationText) } +
                        history.map { VideoItem(it.videoId, it.title, it.author, it.durationText) } +
                        playlistSongs.map { VideoItem(it.videoId, it.title, it.author, it.durationText) })
                        .filter { isDiscoverCandidate(it) }
                        .distinctBy { it.videoId }
                        .shuffled()
                        .take(2)

                    radioSeeds.forEach { seed ->
                        deferreds.add(async(Dispatchers.IO) {
                            try {
                                vm.recommendationRepository.getSongRadio(seed.videoId, seed.title, seed.author)
                                    .filter { isDiscoverCandidate(it) && discoverMatchesGenreFilter(it, genreFilter) }
                                    .take(6)
                                    .map { song ->
                                        DiscoverSong(
                                            videoItem = song,
                                            recommendationReason = "Radio match from ${seed.title}",
                                            vibeScore = Random.nextInt(89, 99)
                                        )
                                    }
                            } catch (_: Exception) { emptyList() }
                        })
                    }

                    genreFilter?.let { filter ->
                        listOf(filter.query, "${filter.query} songs", "${filter.label} music mix").forEach { query ->
                            deferreds.add(async(Dispatchers.IO) {
                                try {
                                    InnerTube.search(query)
                                        .filter { isDiscoverCandidate(it) }
                                        .take(8)
                                        .map { song ->
                                            DiscoverSong(
                                                videoItem = song,
                                                recommendationReason = "${filter.label} lane",
                                                vibeScore = Random.nextInt(88, 99)
                                            )
                                        }
                                } catch (_: Exception) { emptyList() }
                            })
                        }
                    }

                    // 2. Fetch popular/new songs from seed artists concurrently
                    seedArtists.forEach { artist ->
                        deferreds.add(async(Dispatchers.IO) {
                            try {
                                InnerTube.search("$artist popular songs")
                                    .filter { isDiscoverCandidate(it) && discoverMatchesGenreFilter(it, genreFilter) }
                                    .take(3)
                                    .map { song ->
                                        DiscoverSong(
                                            videoItem = song,
                                            recommendationReason = "Based on your love for $artist",
                                            vibeScore = Random.nextInt(92, 100)
                                        )
                                    }
                            } catch (_: Exception) { emptyList() }
                        })

                        deferreds.add(async(Dispatchers.IO) {
                            try {
                                InnerTube.search("$artist new song")
                                    .filter { isDiscoverCandidate(it) && discoverMatchesGenreFilter(it, genreFilter) }
                                    .take(2)
                                    .map { song ->
                                        DiscoverSong(
                                            videoItem = song,
                                            recommendationReason = "New from $artist",
                                            vibeScore = Random.nextInt(94, 99)
                                        )
                                    }
                            } catch (_: Exception) { emptyList() }
                        })
                    }

                    // 3. Similar to Liked Songs seed concurrently
                    seedLikes.forEach { likedSong ->
                        deferreds.add(async(Dispatchers.IO) {
                            try {
                                InnerTube.search("songs like ${likedSong.title} ${likedSong.author}")
                                    .filter { isDiscoverCandidate(it) && discoverMatchesGenreFilter(it, genreFilter) }
                                    .take(4)
                                    .map { song ->
                                        DiscoverSong(
                                            videoItem = song,
                                            recommendationReason = "Vibe match with '${likedSong.title}'",
                                            vibeScore = Random.nextInt(88, 98)
                                        )
                                    }
                            } catch (_: Exception) { emptyList() }
                        })
                    }

                    // 4. Similar to Playlist Songs seed concurrently
                    seedPlaylists.forEach { plSong ->
                        deferreds.add(async(Dispatchers.IO) {
                            try {
                                InnerTube.search("songs like ${plSong.title} ${plSong.author}")
                                    .filter { isDiscoverCandidate(it) && discoverMatchesGenreFilter(it, genreFilter) }
                                    .take(4)
                                    .map { song ->
                                        DiscoverSong(
                                            videoItem = song,
                                            recommendationReason = "Inspired by your playlist track",
                                            vibeScore = Random.nextInt(89, 97)
                                        )
                                    }
                            } catch (_: Exception) { emptyList() }
                        })
                    }

                    // 5. Fresh serendipity query templates concurrently
                    DISCOVER_QUERIES.shuffled().take(2).forEach { freshQuery ->
                        deferreds.add(async(Dispatchers.IO) {
                            try {
                                InnerTube.search(freshQuery)
                                    .filter { isDiscoverCandidate(it) && discoverMatchesGenreFilter(it, genreFilter) }
                                    .take(5)
                                    .map { song ->
                                        DiscoverSong(
                                            videoItem = song,
                                            recommendationReason = "Fresh: ${freshQuery.replace("2025", "").trim()}",
                                            vibeScore = Random.nextInt(80, 94)
                                        )
                                    }
                            } catch (_: Exception) { emptyList() }
                        })
                    }

                    val results = deferreds.awaitAll().flatten()
                    discoverPool.addAll(results)
                }

                // 6. Deduplicate, score-sort, and keep artist diversity.
                val uniqueDiscover = buildDiscoverDeck(discoverPool, genreFilter)

                withContext(Dispatchers.Main) {
                    val visibleCard = cards.lastOrNull()
                    val visibleKey = visibleCard?.videoItem?.let { discoverSongKey(it) }
                    val stableDeck = if (visibleCard != null && visibleKey != null && uniqueDiscover.any { discoverSongKey(it.videoItem) == visibleKey }) {
                        uniqueDiscover.filterNot { discoverSongKey(it.videoItem) == visibleKey } + visibleCard
                    } else {
                        uniqueDiscover
                    }
                    cards = stableDeck
                    isLoading = false
                    autoPreviewEnabled = true
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { isLoading = false }
            }
        }
    }

    LaunchedEffect(Unit) {
        loadDiscoverSongs()
    }

    fun dropCurrentCard() {
        val remainingCards = cards.dropLast(1)
        cards = remainingCards
        if (remainingCards.size <= 4 && !isLoading) {
            loadDiscoverSongs(selectedGenreFilter)
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(VinColors.BgColor)) {
        if (isLoading && cards.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    CircularProgressIndicator(color = VinColors.Accent, modifier = Modifier.size(48.dp), strokeWidth = 3.dp)
                    Text("Loading", color = VinColors.Secondary, fontSize = 14.sp)
                }
            }
        } else if (cards.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("You've swiped them all!", color = VinColors.Primary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text("Ready for another customized batch?", color = VinColors.Secondary, fontSize = 14.sp)
                Spacer(Modifier.height(20.dp))
                Button(onClick = { loadDiscoverSongs(selectedGenreFilter) }, colors = ButtonDefaults.buttonColors(containerColor = VinColors.Accent)) {
                    Icon(Icons.Default.Refresh, null, tint = Color.White)
                    Spacer(Modifier.width(8.dp))
                    Text("Refresh Deck", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        } else {
            val currentCard = cards.last()

            // Ambient background with animated layers
            val infiniteTransition = rememberInfiniteTransition(label = "discover_bg")
            val bgRotation by infiniteTransition.animateFloat(
                initialValue = 0f, targetValue = 360f,
                animationSpec = infiniteRepeatable(tween(60000, easing = LinearEasing), RepeatMode.Restart),
                label = "bgRotation"
            )
            val pulseAlpha by infiniteTransition.animateFloat(
                initialValue = 0.3f, targetValue = 0.55f,
                animationSpec = infiniteRepeatable(tween(3000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
                label = "pulse"
            )

            // Solid dark gradient background
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(com.vinmusic.ui.theme.Vin.Gradients.background)
            )

            Box(
                modifier = Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0x99000000),
                            Color.Transparent,
                            Color.Transparent,
                            Color(0xDD000000)
                        )
                    )
                )
            )

            // Back button
            IconButton(
                onClick = onBack,
                modifier = Modifier.align(Alignment.TopStart).padding(16.dp)
                    .size(42.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.4f))
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
            }

            // Screen Header title
            Column(
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Discover Mix", fontWeight = FontWeight.ExtraBold, fontSize = 22.sp, color = Color.White)
                Text("${cards.size} songs • Smart matching enabled", fontSize = 11.sp, color = Color.White.copy(alpha = 0.6f))
            }

            // Stack & Control layout
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding()
                    .padding(top = 80.dp, bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                DiscoverGenreFilterRow(
                    selected = selectedGenreFilter,
                    onSelected = { filter ->
                        selectedGenreFilter = filter
                        cards = emptyList()
                        autoPreviewEnabled = true
                        loadDiscoverSongs(filter)
                    }
                )

                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (cards.size > 1) {
                        Card(
                            modifier = Modifier.fillMaxWidth(0.88f).fillMaxHeight(0.82f)
                                .offset(y = 14.dp).graphicsLayer { scaleX = 0.93f; scaleY = 0.93f; alpha = 0.6f },
                            shape = RoundedCornerShape(28.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.08f))
                        ) {}
                    }
                    if (cards.size > 2) {
                        Card(
                            modifier = Modifier.fillMaxWidth(0.82f).fillMaxHeight(0.76f)
                                .offset(y = 26.dp).graphicsLayer { scaleX = 0.87f; scaleY = 0.87f; alpha = 0.35f },
                            shape = RoundedCornerShape(28.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.05f))
                        ) {}
                    }

                    // Discover Card
                    DiscoverCard(
                        discoverSong = currentCard,
                        vm = vm,
                        swipeState = swipeState,
                        autoPreviewEnabled = autoPreviewEnabled,
                        onSwipedLeft = {
                            scope.launch {
                                swipeState.animateLeft { dropCurrentCard() }
                            }
                        },
                        onSwipedRight = {
                            scope.launch {
                                swipeState.animateRight {
                                    vm.toggleLike(currentCard.videoItem)
                                    showToastMessage = "Added to Liked Songs"
                                    dropCurrentCard()
                                }
                            }
                        }
                    )
                }

                // Swiper Controls
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 36.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Skip button
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        IconButton(
                            onClick = { scope.launch { swipeState.animateLeft { dropCurrentCard() } } },
                            modifier = Modifier.size(64.dp).clip(CircleShape)
                                .background(Color(0xFFFF4D4D).copy(alpha = 0.15f))
                                .border(1.5.dp, Color(0xFFFF4D4D).copy(alpha = 0.5f), CircleShape)
                        ) { Icon(Icons.Default.Close, "Skip", tint = Color(0xFFFF4D4D), modifier = Modifier.size(28.dp)) }
                        Text("Skip", fontSize = 11.sp, color = Color(0xFFFF4D4D).copy(alpha = 0.8f), fontWeight = FontWeight.Medium)
                    }

                    // Center play/preview button
                    val isCurrentPlaying = vm.currentSong?.videoId == currentCard.videoItem.videoId && vm.isPlaying
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        IconButton(
                            onClick = {
                                autoPreviewEnabled = true
                                if (vm.currentSong?.videoId == currentCard.videoItem.videoId) vm.togglePlay()
                                else vm.playSongPreview(
                                    currentCard.videoItem,
                                    discoverPreviewStartMs(currentCard.videoItem.durationText)
                                )
                            },
                            modifier = Modifier.size(80.dp).clip(CircleShape)
                                .background(Brush.linearGradient(listOf(Color(0xFFC5A880), Color(0xFFE5CBA3))))
                        ) {
                            Icon(
                                if (isCurrentPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                "Preview", tint = Color.White, modifier = Modifier.size(38.dp)
                            )
                        }
                        Text("Preview", fontSize = 11.sp, color = Color.White.copy(alpha = 0.7f), fontWeight = FontWeight.Medium)
                    }

                    // Like Button (Triggers Like & Toast)
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        IconButton(
                            onClick = {
                                scope.launch {
                                    swipeState.animateRight {
                                        vm.toggleLike(currentCard.videoItem)
                                        showToastMessage = "Added to Liked Songs"
                                        dropCurrentCard()
                                    }
                                }
                            },
                            modifier = Modifier.size(64.dp).clip(CircleShape)
                                .background(Color(0xFF10B981).copy(alpha = 0.15f))
                                .border(1.5.dp, Color(0xFF10B981).copy(alpha = 0.5f), CircleShape)
                        ) { Icon(Icons.Default.Favorite, "Like", tint = Color(0xFF10B981), modifier = Modifier.size(28.dp)) }
                        Text("Like", fontSize = 11.sp, color = Color(0xFF10B981).copy(alpha = 0.8f), fontWeight = FontWeight.Medium)
                    }
                }
            }
        }

        // Floating Toast Popup at the bottom (Song is added to your liked playlist)
        AnimatedVisibility(
            visible = showToastMessage != null,
            enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 2 }),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 120.dp)
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF10B981)),
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.CheckCircle, null, tint = Color.White, modifier = Modifier.size(16.dp))
                    Text(
                        text = showToastMessage ?: "",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        if (selectedMix != null) {
            val mix = selectedMix ?: return
            val startColor = remember(mix.gradientStartHex) {
                runCatching { Color(android.graphics.Color.parseColor(mix.gradientStartHex.replace("0x", "#"))) }.getOrElse { Color(0xFFC5A880) }
            }
            val endColor = remember(mix.gradientEndHex) {
                runCatching { Color(android.graphics.Color.parseColor(mix.gradientEndHex.replace("0x", "#"))) }.getOrElse { Color(0xFF1E1A14) }
            }

            ModalBottomSheet(
                onDismissRequest = { selectedMix = null },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                containerColor = VinColors.Surface2,
                dragHandle = { BottomSheetDefaults.DragHandle(color = VinColors.GlassBorder) }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Header section
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(80.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Brush.verticalGradient(colors = listOf(startColor, endColor)))
                        ) {
                            GenreMix2x2Grid(songs = mix.songs, modifier = Modifier.fillMaxSize())
                        }
                        
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = mix.title,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color.White,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = mix.description,
                                fontSize = 12.sp,
                                color = VinColors.Secondary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    Button(
                        onClick = {
                            if (mix.songs.isNotEmpty()) {
                                val tracks = mix.songs.map { it.videoItem }
                                onSongClick(tracks[0], tracks)
                                selectedMix = null
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = VinColors.Accent),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Play Mix", fontWeight = FontWeight.Bold)
                    }

                    Spacer(Modifier.height(16.dp))

                    HorizontalDivider(color = VinColors.GlassBorder.copy(alpha = 0.3f))

                    Spacer(Modifier.height(12.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 280.dp)
                    ) {
                        if (mix.songs.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(150.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("No tracks inside this mix.", color = VinColors.Secondary, fontSize = 13.sp)
                            }
                        } else {
                            LazyColumn(
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                itemsIndexed(mix.songs) { index, recSong ->
                                    val song = recSong.videoItem
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(VinColors.White10)
                                            .clickable {
                                                val tracks = mix.songs.map { it.videoItem }
                                                onSongClick(song, tracks)
                                                selectedMix = null
                                            }
                                            .padding(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(modifier = Modifier.size(36.dp).clip(RoundedCornerShape(6.dp))) {
                                            AsyncImage(
                                                model = song.thumbnail,
                                                contentDescription = null,
                                                modifier = Modifier.fillMaxSize(),
                                                contentScale = ContentScale.Crop
                                            )
                                        }
                                        Spacer(Modifier.width(10.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = song.title,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color.White,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = song.author,
                                                fontSize = 11.sp,
                                                color = VinColors.Secondary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}

@Composable
fun DiscoverCard(
    discoverSong: DiscoverSong,
    vm: PlayerViewModel,
    swipeState: SwipeState,
    autoPreviewEnabled: Boolean,
    onSwipedLeft: () -> Unit,
    onSwipedRight: () -> Unit
) {
    val song = discoverSong.videoItem
    val isCurrentSong = vm.currentSong?.videoId == song.videoId
    val isPlaying = isCurrentSong && vm.isPlaying && !vm.isLoading
    
    // Auto-preview from the hook/mid-song section instead of starting at 0:00.
    LaunchedEffect(song.videoId) {
        // Wait to settle transitions and debounce fast swipes
        delay(350L)
        if (autoPreviewEnabled) {
            if (vm.currentSong?.videoId != song.videoId) {
                vm.playSongPreview(song, discoverPreviewStartMs(song.durationText))
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.92f)
            .offset { IntOffset(swipeState.offsetX.value.roundToInt(), swipeState.offsetY.value.roundToInt()) }
            .graphicsLayer { rotationZ = (swipeState.offsetX.value / 18f).coerceIn(-12f, 12f) }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDrag = { change, drag -> change.consume(); swipeState.drag(drag.x, drag.y) },
                    onDragEnd = { swipeState.released(size.width * 0.32f, onSwipedLeft, onSwipedRight) }
                )
            }
            .clip(RoundedCornerShape(28.dp))
    ) {
        AsyncImage(
            model = song.thumbnailHd ?: song.thumbnail,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )

        // Scrim overlay
        Box(
            modifier = Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    colors = listOf(Color.Transparent, Color.Transparent, Color(0xCC000000), Color(0xEE000000))
                )
            )
        )

        // Top pills
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Vibe Match Pill
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFFC5A880), Color(0xFFE5CBA3))))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(Icons.Default.Analytics, null, tint = Color.White, modifier = Modifier.size(12.dp))
                Text(
                    "${discoverSong.vibeScore}% Vibe Match",
                    fontSize = 11.sp,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }

            if (isPlaying) {
                Icon(
                    Icons.Default.GraphicEq,
                    contentDescription = null,
                    tint = Color(0xFF10B981),
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.46f))
                        .padding(6.dp)
                )
            }
        }

        // Recommendation reason tag
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 56.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Color.Black.copy(alpha = 0.65f))
                .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(20.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text(
                text = discoverSong.recommendationReason,
                fontSize = 11.sp,
                color = Color.White.copy(alpha = 0.9f),
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Center artwork, no decorative disc behind it.
        Box(
            modifier = Modifier.align(Alignment.Center).offset(y = (-70).dp),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier.size(172.dp)
                    .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(18.dp)),
                shape = RoundedCornerShape(18.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 10.dp)
            ) {
                AsyncImage(
                    model = song.thumbnailHd ?: song.thumbnail,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
        }

        // Bottom column stays compact so card metadata never collides with the artwork.
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.Black.copy(alpha = 0.52f))
                    .border(0.5.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(18.dp))
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Icon(Icons.Default.GraphicEq, null, tint = VinColors.AccentLight, modifier = Modifier.size(15.dp))
                Text(
                    text = "Hook preview starts at ${formatPreviewTime(discoverPreviewStartMs(song.durationText))}",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White.copy(alpha = 0.92f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Song Info (Title & Author)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    song.title, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold,
                    color = Color.White, textAlign = TextAlign.Center,
                    maxLines = 2, overflow = TextOverflow.Ellipsis
                )
                Text(
                    song.author, fontSize = 14.sp, color = Color.White.copy(alpha = 0.75f),
                    textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }

            // Badges row
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.clip(RoundedCornerShape(20.dp))
                        .background(Color.White.copy(alpha = 0.15f))
                        .padding(horizontal = 12.dp, vertical = 5.dp)
                ) { Text(song.durationText, fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold) }
                if (isCurrentSong) {
                    Box(
                        modifier = Modifier.clip(RoundedCornerShape(20.dp))
                            .background(Color(0xFFC5A880).copy(alpha = 0.4f))
                            .border(1.dp, Color(0xFFC5A880), RoundedCornerShape(20.dp))
                            .padding(horizontal = 12.dp, vertical = 5.dp)
                    ) { Text("Previewing", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold) }
                }
            }
        }

        // Drag hints
        val dragRatio = (swipeState.offsetX.value / 280f).coerceIn(-1f, 1f)
        AnimatedVisibility(
            visible = dragRatio > 0.15f,
            enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopStart).padding(20.dp)
        ) {
            Box(
                modifier = Modifier.graphicsLayer { rotationZ = -12f }
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF10B981).copy(alpha = 0.9f))
                    .padding(horizontal = 18.dp, vertical = 8.dp)
            ) { Text("LIKE", fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, color = Color.White) }
        }
        AnimatedVisibility(
            visible = dragRatio < -0.15f,
            enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopEnd).padding(20.dp)
        ) {
            Box(
                modifier = Modifier.graphicsLayer { rotationZ = 12f }
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFFFF4D4D).copy(alpha = 0.9f))
                    .padding(horizontal = 18.dp, vertical = 8.dp)
            ) { Text("SKIP", fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, color = Color.White) }
        }
    }
}

@Composable
private fun DiscoverGenreFilterRow(
    selected: DiscoverGenreFilter?,
    onSelected: (DiscoverGenreFilter?) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = selected?.let { "${it.label} Deck" } ?: "Swipe by genre",
                fontSize = 18.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White
            )
            Text(
                text = if (selected == null) "All songs" else "Filtered",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = VinColors.AccentLight
            )
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            item {
                DiscoverGenreChip(
                    label = "All",
                    selected = selected == null,
                    onClick = { onSelected(null) }
                )
            }
            items(DISCOVER_GENRE_FILTERS, key = { it.label }) { filter ->
                DiscoverGenreChip(
                    label = filter.label,
                    selected = selected?.label == filter.label,
                    onClick = { onSelected(filter) }
                )
            }
        }
    }
}

@Composable
private fun DiscoverGenreChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(
                if (selected) {
                    Brush.horizontalGradient(listOf(Color(0xFFC5A880), Color(0xFF8C7355)))
                } else {
                    Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.12f), Color.White.copy(alpha = 0.04f)))
                }
            )
            .border(
                width = 1.dp,
                color = if (selected) Color.Transparent else Color.White.copy(alpha = 0.12f),
                shape = RoundedCornerShape(20.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 15.dp, vertical = 9.dp)
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = Color.White,
            fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun GenreMix2x2Grid(songs: List<com.vinmusic.recommendation.RecommendedSong>, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.05f))
    ) {
        val coverSongs = songs.take(4)
        if (coverSongs.size >= 4) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(modifier = Modifier.weight(1f)) {
                    AsyncImage(
                        model = coverSongs[0].videoItem.thumbnail,
                        contentDescription = null,
                        modifier = Modifier.weight(1f).fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                    AsyncImage(
                        model = coverSongs[1].videoItem.thumbnail,
                        contentDescription = null,
                        modifier = Modifier.weight(1f).fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
                Row(modifier = Modifier.weight(1f)) {
                    AsyncImage(
                        model = coverSongs[2].videoItem.thumbnail,
                        contentDescription = null,
                        modifier = Modifier.weight(1f).fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                    AsyncImage(
                        model = coverSongs[3].videoItem.thumbnail,
                        contentDescription = null,
                        modifier = Modifier.weight(1f).fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
            }
        } else {
            val singleCover = coverSongs.firstOrNull()?.videoItem?.thumbnail
            if (singleCover != null) {
                AsyncImage(
                    model = singleCover,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.MusicNote, null, tint = Color.White.copy(alpha = 0.5f))
                }
            }
        }
    }
}

@Composable
fun GenreMixCard(
    mix: com.vinmusic.recommendation.SpotifyMix,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "genre_mix_card_scale"
    )

    val startColor = remember(mix.gradientStartHex) {
        runCatching { Color(android.graphics.Color.parseColor(mix.gradientStartHex.replace("0x", "#"))) }.getOrElse { Color(0xFFC5A880) }
    }
    val endColor = remember(mix.gradientEndHex) {
        runCatching { Color(android.graphics.Color.parseColor(mix.gradientEndHex.replace("0x", "#"))) }.getOrElse { Color(0xFF1E1A14) }
    }

    Column(
        modifier = Modifier
            .width(130.dp)
            .graphicsLayer(scaleX = scale, scaleY = scale)
            .clickable(interactionSource = interactionSource, indication = null) { onClick() }
            .padding(bottom = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(130.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Brush.verticalGradient(colors = listOf(startColor, endColor)))
                .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(18.dp))
        ) {
            GenreMix2x2Grid(songs = mix.songs, modifier = Modifier.fillMaxSize())
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.5f))
                        )
                    )
            )
            // A premium glassmorphic tag indicating "MIX"
            Box(
                modifier = Modifier
                    .padding(6.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.5f))
                    .border(0.5.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 5.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "MIX",
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }

        Spacer(Modifier.height(6.dp))

        Text(
            text = mix.title,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp)
        )
        Text(
            text = mix.description,
            fontSize = 10.sp,
            color = Color.White.copy(alpha = 0.55f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp)
        )
    }
}

private fun strokeStyle() = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.2f)

class SwipeState(val scope: kotlinx.coroutines.CoroutineScope) {
    val offsetX = Animatable(0f)
    val offsetY = Animatable(0f)

    fun drag(x: Float, y: Float) {
        scope.launch {
            offsetX.snapTo(offsetX.value + x)
            offsetY.snapTo(offsetY.value + y)
        }
    }

    suspend fun animateLeft(onComplete: () -> Unit) {
        offsetX.animateTo(-1400f, tween(300, easing = FastOutSlowInEasing))
        onComplete()
        offsetX.snapTo(0f); offsetY.snapTo(0f)
    }

    suspend fun animateRight(onComplete: () -> Unit) {
        offsetX.animateTo(1400f, tween(300, easing = FastOutSlowInEasing))
        onComplete()
        offsetX.snapTo(0f); offsetY.snapTo(0f)
    }

    fun released(threshold: Float, onLeft: () -> Unit, onRight: () -> Unit) {
        scope.launch {
            when {
                offsetX.value > threshold -> onRight()
                offsetX.value < -threshold -> onLeft()
                else -> {
                    launch { offsetX.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow)) }
                    launch { offsetY.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow)) }
                }
            }
        }
    }
}

@Composable
fun rememberSwipeState(scope: kotlinx.coroutines.CoroutineScope = rememberCoroutineScope()): SwipeState =
    remember(scope) { SwipeState(scope) }
