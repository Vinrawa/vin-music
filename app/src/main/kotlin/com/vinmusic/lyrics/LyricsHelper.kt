package com.vinmusic.lyrics

import com.google.gson.Gson
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

data class LyricsLine(val timeMs: Long, val text: String)

sealed class LyricsResult {
    data class Synced(val lines: List<LyricsLine>, val source: String) : LyricsResult()
    data class Plain(val text: String, val source: String) : LyricsResult()
    object NotFound : LyricsResult()
}

object LyricsHelper {
    private val gson = Gson()
    
    private val http = OkHttpClient.Builder().apply {
        if (true) { // Bypass SSL for lyrics APIs to support older Android devices with outdated root certs
            try {
                val trustAllCerts = arrayOf<javax.net.ssl.TrustManager>(
                    object : javax.net.ssl.X509TrustManager {
                        override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                        override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                        override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
                    }
                )
                val sslContext = javax.net.ssl.SSLContext.getInstance("SSL")
                sslContext.init(null, trustAllCerts, java.security.SecureRandom())
                sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as javax.net.ssl.X509TrustManager)
                hostnameVerifier { _, _ -> true }
            } catch (e: Exception) {}
        }
        connectTimeout(4, TimeUnit.SECONDS)
        readTimeout(4, TimeUnit.SECONDS)
    }.build()

    // List of common Indian record labels and YouTube distribution channel keywords
    private val INDIAN_RECORD_LABELS = listOf(
        "t-series", "t series", "speed records", "yrf", "yash raj", "sony music", 
        "zee music", "aditya music", "lahari music", "tips", "saregama", 
        "geet mp3", "jass records", "white hill", "saga hits", "desi music factory", 
        "vyrl", "t-series apap", "t-series regional", "tseries", "zeemusic", 
        "sonymusic", "tips official"
    )

    private val blacklistedProviders = ConcurrentHashMap<String, Long>()
    private const val COOLDOWN_MS = 24 * 60 * 60 * 1000L

    private fun isBlacklisted(provider: String): Boolean {
        val lastFailed = blacklistedProviders[provider] ?: return false
        if (System.currentTimeMillis() - lastFailed > COOLDOWN_MS) {
            blacklistedProviders.remove(provider)
            return false
        }
        return true
    }

    private fun markFailed(provider: String) {
        blacklistedProviders[provider] = System.currentTimeMillis()
    }

    fun fetch(title: String, artist: String, videoId: String = "", provider: String = "Auto", durationMs: Long = 0L): LyricsResult {
        var t = cleanTitle(title)
        var a = cleanArtist(artist)

        // Fallback: If artist is empty or matched as a record label, try to extract the real singer from the title
        if (a.isEmpty() && (title.contains("-") || title.contains("|") || title.contains(":"))) {
            val separator = when {
                title.contains("-") -> "-"
                title.contains("|") -> "|"
                else -> ":"
            }
            val parts = title.split(separator)
            if (parts.size >= 2) {
                val firstPart = parts[0].trim()
                val secondPart = parts[1].trim()

                // Check if the first part is a potential artist (not a label)
                val cleanFirst = cleanArtist(firstPart)
                if (cleanFirst.isNotEmpty()) {
                    a = cleanFirst
                    t = cleanTitle(secondPart)
                } else {
                    // Try the second part
                    val cleanSecond = cleanArtist(secondPart)
                    if (cleanSecond.isNotEmpty()) {
                        a = cleanSecond
                        t = cleanTitle(firstPart)
                    }
                }
            }
        }

        if (provider != "Auto") {
            try {
                when (provider) {
                    "LrcLib" -> {
                        tryLrcLibGet(t, a, durationMs)?.let { return it }
                        tryLrcLibSearch(t, a, durationMs)?.let { return it }
                    }
                    "SimpMusic" -> { trySimpMusic(t, a)?.let { return it } }
                    "Paxsenix" -> { tryPaxsenix(t, a)?.let { return it } }
                    "KuGou" -> { tryKugou(t, a)?.let { return it } }
                    "Genius" -> { tryGenius(t, a)?.let { return it } }
                }
            } catch (e: Exception) {
                // If specific provider requested and fails, just ignore and return NotFound
            }
            return LyricsResult.NotFound
        }

        // Auto: sequential provider fallback — try each provider in order and
        // return the FIRST usable result. This restores the pre-regression
        // behavior that produced correctly-synced lyrics. The old "parallel
        // race with longest-synced tie-break" picked shifted/community LRC
        // variants, causing lyrics to lag in some parts and jump ahead in others.
        //
        // Order: LrcLib → Genius → SimpMusic → KuGou → Paxsenix
        // Synced results return immediately. A Plain result from an early
        // provider is remembered as a fallback while we keep trying later
        // providers for a Synced version.
        val providers: List<Pair<String, () -> LyricsResult?>> = listOf(
            "LrcLib"     to { tryLrcLibGet(t, a, durationMs) ?: tryLrcLibSearch(t, a, durationMs) },
            "Genius"     to { tryGenius(t, a) },
            "SimpMusic"  to { trySimpMusic(t, a) },
            "KuGou"      to { tryKugou(t, a) },
            "Paxsenix"   to { tryPaxsenix(t, a) }
        )

        var fallbackPlain: LyricsResult.Plain? = null
        for ((name, provider) in providers) {
            if (isBlacklisted(name)) continue
            val res = try {
                provider()
            } catch (e: Exception) {
                android.util.Log.e("LyricsHelper", "Provider $name failed: ${e.message}")
                if (e is javax.net.ssl.SSLException) markFailed(name)
                null
            }
            when (res) {
                is LyricsResult.Synced -> {
                    android.util.Log.d("LyricsHelper", "Lyrics found via $name (synced)")
                    return res
                }
                is LyricsResult.Plain -> {
                    android.util.Log.d("LyricsHelper", "Lyrics found via $name (plain) — keeping as fallback")
                    if (fallbackPlain == null) fallbackPlain = res
                }
                else -> { /* NotFound or null — try next provider */ }
            }
        }
        return fallbackPlain ?: LyricsResult.NotFound
    }

    private fun cleanTitle(title: String): String {
        return title
            .replace(Regex("\\[.*?]|\\(.*?\\)"), "") // Remove parentheses and brackets content
            .replace(Regex("(?i)official|music video|lyrical|video|audio|lyrics?|hd|4k|feat\\.?.*|ft\\.?.*|full song|full video|latest song.*|new song.*|punjabi song.*|hindi song.*"), "")
            .replace(" - Topic", "", ignoreCase = true)
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun cleanArtist(artist: String): String {
        var clean = artist.replace(" - Topic", "", ignoreCase = true).trim()

        val lower = clean.lowercase()
        if (INDIAN_RECORD_LABELS.any { lower == it || lower.contains(it) }) {
            return ""
        }

        val parts = clean.split(Regex(",|&|\\bfeat\\.?\\b|\\bft\\.?\\b|\\band\\b", RegexOption.IGNORE_CASE))
        if (parts.isNotEmpty()) {
            clean = parts[0].trim()
        }

        val cleanLower = clean.lowercase()
        if (INDIAN_RECORD_LABELS.any { cleanLower == it || cleanLower.contains(it) }) {
            return ""
        }

        return clean
    }

    // ── LrcLib direct GET ──────────────────────────────────────────────────────
    private fun tryLrcLibGet(title: String, artist: String, durationMs: Long = 0L): LyricsResult? {
        if (artist.isEmpty()) return null
        val durationParam = if (durationMs > 0L) "&duration=${durationMs / 1000L}" else ""
        val url = "https://lrclib.net/api/get?track_name=${enc(title)}&artist_name=${enc(artist)}$durationParam"
        val resp = get(url) ?: return null
        return parseLrcLibItem(resp, "LrcLib")
    }

    private fun tryLrcLibSearch(title: String, artist: String, durationMs: Long = 0L): LyricsResult? {
        val urls = mutableListOf<String>()
        if (artist.isNotEmpty()) {
            urls.add("https://lrclib.net/api/search?track_name=${enc(title)}&artist_name=${enc(artist)}")
            urls.add("https://lrclib.net/api/search?q=${enc("$title $artist".trim())}")
        }
        urls.add("https://lrclib.net/api/search?q=${enc(title)}")

        for (url in urls) {
            val resp = get(url) ?: continue
            try {
                val arr = gson.fromJson(resp, List::class.java) ?: continue
                if (arr.isEmpty()) continue
                // Pre-regression behavior: take the FIRST relevant result.
                // Do NOT rank by duration or by syncedLyrics length — that
                // picked shifted/community LRC variants and caused drift.
                for (item in arr) {
                    val itemMap = item as? Map<*, *> ?: continue
                    parseLrcLibItem(gson.toJson(itemMap), "LrcLib")?.let { return it }
                }
            } catch (_: Exception) { continue }
        }
        return null
    }

    private fun scoreLrcLibCandidate(item: Map<*, *>, title: String, artist: String, durationMs: Long): Double {
        val candidateTitle = (item["trackName"] as? String).orEmpty()
        val candidateArtist = (item["artistName"] as? String).orEmpty()
        val candidateDurationMs = ((item["duration"] as? Number)?.toLong() ?: 0L) * 1000L

        val titleScore = lyricTextSimilarity(title, candidateTitle)
        val artistScore = if (artist.isBlank() || candidateArtist.isBlank()) {
            0.35
        } else {
            lyricTextSimilarity(artist, candidateArtist)
        }
        val durationScore = if (durationMs > 0L && candidateDurationMs > 0L) {
            val diff = kotlin.math.abs(durationMs - candidateDurationMs)
            when {
                diff <= 2_500L -> 1.0
                diff <= 7_500L -> 0.75
                diff <= 15_000L -> 0.45
                else -> 0.0
            }
        } else {
            0.35
        }
        val syncedBonus = if (!(item["syncedLyrics"] as? String).isNullOrBlank()) 0.12 else 0.0
        return (titleScore * 0.48) + (artistScore * 0.28) + (durationScore * 0.24) + syncedBonus
    }

    private fun lyricTextSimilarity(left: String, right: String): Double {
        val a = normalizeForLyricsMatch(left)
        val b = normalizeForLyricsMatch(right)
        if (a.isBlank() || b.isBlank()) return 0.0
        if (a == b) return 1.0
        if (a.contains(b) || b.contains(a)) return 0.82
        val aTokens = a.split(" ").filter { it.length > 1 }.toSet()
        val bTokens = b.split(" ").filter { it.length > 1 }.toSet()
        if (aTokens.isEmpty() || bTokens.isEmpty()) return 0.0
        return aTokens.intersect(bTokens).size.toDouble() / aTokens.union(bTokens).size.toDouble()
    }

    private fun normalizeForLyricsMatch(text: String): String {
        return cleanTitle(text)
            .lowercase()
            .replace(Regex("""\b(feat|ft|with)\b.*"""), " ")
            .replace(Regex("""[^a-z0-9\u0900-\u097F\u0A00-\u0A7F\s]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun parseLrcLibItem(json: String, source: String): LyricsResult? {
        return try {
            val item = gson.fromJson(json, Map::class.java) ?: return null
            val lrc   = item["syncedLyrics"] as? String
            val plain = item["plainLyrics"] as? String
            when {
                !lrc.isNullOrBlank() -> {
                    val lines = parseLrc(lrc)
                    if (lines.isNotEmpty()) LyricsResult.Synced(lines, source) else null
                }
                !plain.isNullOrBlank() -> sanitizePlainLyrics(plain)
                    .takeIf { it.isNotBlank() }
                    ?.let { LyricsResult.Plain(it, source) }
                else                   -> null
            }
        } catch (_: Exception) { null }
    }

    // ── Paxsenix ───────────────────────────────────────────────────────────────
    private fun tryPaxsenix(title: String, artist: String): LyricsResult? {
        val queryUrl = if (artist.isNotEmpty()) {
            "https://paxsenix.skiddle.id/lyrics?title=${enc(title)}&artist=${enc(artist)}"
        } else {
            "https://paxsenix.skiddle.id/lyrics?title=${enc(title)}"
        }
        val resp = get(queryUrl) ?: return null
        return try {
            val json  = gson.fromJson(resp, Map::class.java) ?: return null
            val lrc   = json["syncedLyrics"] as? String
            val plain = json["plainLyrics"] as? String
            when {
                !lrc.isNullOrBlank() -> {
                    val lines = parseLrc(lrc)
                    if (lines.isNotEmpty()) LyricsResult.Synced(lines, "Paxsenix") else null
                }
                !plain.isNullOrBlank() -> sanitizePlainLyrics(plain)
                    .takeIf { it.isNotBlank() }
                    ?.let { LyricsResult.Plain(it, "Paxsenix") }
                else                   -> null
            }
        } catch (_: Exception) { null }
    }

    // ── LRC parser ─────────────────────────────────────────────────────────────
    fun parseLrc(lrc: String): List<LyricsLine> {
        val timestampRegex = Regex("""\[(\d{1,2}):(\d{2})[\.:](\d{1,3})]""")
        return lrc.lines()
            .flatMap { rawLine ->
                val line = rawLine.trim()
                val matches = timestampRegex.findAll(line).toList()
                if (matches.isEmpty()) return@flatMap emptyList()
                val text = line
                    .replace(timestampRegex, "")
                    .replace(Regex("""<\d{1,2}:\d{2}[\.:]\d{1,3}>"""), "")
                    .trim()
                if (text.isEmpty() || isNonLyricLine(text)) return@flatMap emptyList()
                matches.map { m ->
                    val ms = m.groupValues[1].toLong() * 60_000 +
                            m.groupValues[2].toLong() * 1_000 +
                            m.groupValues[3].padEnd(3, '0').take(3).toLong()
                    LyricsLine(ms, text)
                }
            }
            .distinctBy { "${it.timeMs}|${it.text}" }
            .sortedBy { it.timeMs }
    }

    private fun sanitizePlainLyrics(text: String): String {
        return text
            .replace("\u00A0", " ")
            .lines()
            .map { it.trim() }
            .filter { line -> line.isEmpty() || !isNonLyricLine(line) }
            .joinToString("\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    private fun isNonLyricLine(raw: String): Boolean {
        val text = raw.trim().trim('[', ']', '(', ')', '{', '}').trim()
        if (text.isBlank()) return false
        val lower = text.lowercase()

        if (Regex("""^\d+\s+contributors?$""", RegexOption.IGNORE_CASE).matches(text)) return true
        if (Regex("""^contributors?$""", RegexOption.IGNORE_CASE).matches(text)) return true
        if (Regex("""^\d+\s*(embed|translations?)$""", RegexOption.IGNORE_CASE).matches(text)) return true

        val sectionLine = Regex(
            """^(intro|outro|verse|chorus|pre[-\s]?chorus|post[-\s]?chorus|bridge|hook|refrain|interlude|instrumental|drop|break|spoken|sample|skit|part|segue)(\s+\d+|\s+[ivx]+)?(\s*[:.-].*)?$""",
            RegexOption.IGNORE_CASE
        )
        if (sectionLine.matches(text)) return true

        val junkPhrases = listOf(
            "you might also like",
            "embed",
            "read more",
            "see live",
            "get tickets",
            "track info",
            "produced by",
            "written by",
            "release date",
            "translations",
            "lyrics",
            "album",
            "contributors"
        )
        if (junkPhrases.any { lower == it || (text.length < 42 && lower.startsWith(it)) }) return true

        val languages = setOf(
            "english", "hindi", "punjabi", "urdu", "spanish", "espanol", "francais",
            "portugues", "deutsch", "italiano", "turkce", "japanese", "russian",
            "korean", "chinese", "arabic", "romanization", "translation"
        )
        if (text.length < 24 && lower in languages) return true

        return false
    }

    private fun mergeSyncedWithGeniusText(
        synced: LyricsResult.Synced,
        title: String,
        artist: String
    ): LyricsResult.Synced? {
        val genius = tryGenius(title, artist) as? LyricsResult.Plain ?: return null
        val geniusLines = sanitizePlainLyrics(genius.text)
            .lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
        val syncedLines = synced.lines
            .filter { it.text.isNotBlank() }
            .sortedBy { it.timeMs }

        if (geniusLines.size < 4 || syncedLines.size < 4) return null

        val averageSyncedWords = syncedLines
            .map { it.text.split(Regex("\\s+")).count { word -> word.isNotBlank() } }
            .average()
        if (averageSyncedWords <= 2.4 && syncedLines.size > geniusLines.size) {
            return LyricsResult.Synced(
                mapTextLinesToTimeline(geniusLines, syncedLines),
                "${synced.source} timeline + Genius"
            )
        }

        val merged = mutableListOf<LyricsLine>()
        var plainCursor = 0
        var matched = 0

        syncedLines.forEachIndexed { index, syncedLine ->
            val searchStart = plainCursor.coerceAtMost(geniusLines.lastIndex)
            val searchEnd = (plainCursor + 8).coerceAtMost(geniusLines.lastIndex)
            var bestIndex = -1
            var bestScore = 0.0

            for (candidateIndex in searchStart..searchEnd) {
                val score = lyricLineSimilarity(syncedLine.text, geniusLines[candidateIndex])
                if (score > bestScore) {
                    bestScore = score
                    bestIndex = candidateIndex
                }
            }

            if (bestIndex >= 0 && bestScore >= 0.46) {
                merged.add(LyricsLine(syncedLine.timeMs, geniusLines[bestIndex]))
                plainCursor = bestIndex + 1
                matched++
            } else {
                val ratioIndex = ((index.toDouble() / syncedLines.size.toDouble()) * geniusLines.size)
                    .toInt()
                    .coerceIn(0, geniusLines.lastIndex)
                val ratioScore = lyricLineSimilarity(syncedLine.text, geniusLines[ratioIndex])
                if (ratioScore >= 0.58) {
                    merged.add(LyricsLine(syncedLine.timeMs, geniusLines[ratioIndex]))
                    plainCursor = (ratioIndex + 1).coerceAtLeast(plainCursor)
                    matched++
                } else {
                    merged.add(syncedLine)
                }
            }
        }

        val confidence = matched.toDouble() / syncedLines.size.toDouble()
        if (confidence < 0.42) {
            if (geniusLines.size < 2) return null
            val lastSyncedIndex = (syncedLines.size - 1).coerceAtLeast(1)
            val lastGeniusIndex = (geniusLines.size - 1).coerceAtLeast(0)
            val orderMapped = syncedLines.mapIndexed { index, syncedLine ->
                val ratioIndex = ((index.toDouble() / lastSyncedIndex.toDouble()) * lastGeniusIndex.toDouble())
                    .toInt()
                    .coerceIn(0, geniusLines.lastIndex)
                LyricsLine(syncedLine.timeMs, geniusLines[ratioIndex])
            }.distinctBy { "${it.timeMs}|${it.text}" }
            return LyricsResult.Synced(orderMapped, "${synced.source} timeline + Genius")
        }
        return LyricsResult.Synced(merged, "${synced.source} + Genius")
    }

    private fun mapTextLinesToTimeline(
        textLines: List<String>,
        timeline: List<LyricsLine>
    ): List<LyricsLine> {
        if (textLines.isEmpty() || timeline.isEmpty()) return emptyList()
        val lastTextIndex = (textLines.size - 1).coerceAtLeast(1)
        val lastTimelineIndex = (timeline.size - 1).coerceAtLeast(0)
        return textLines.mapIndexed { index, text ->
            val timelineIndex = if (textLines.size == 1) {
                0
            } else {
                ((index.toDouble() / lastTextIndex.toDouble()) * lastTimelineIndex.toDouble())
                    .toInt()
                    .coerceIn(0, timeline.lastIndex)
            }
            LyricsLine(timeline[timelineIndex].timeMs, text)
        }.distinctBy { "${it.timeMs}|${it.text}" }
    }

    private fun lyricLineSimilarity(left: String, right: String): Double {
        val a = lyricTokens(left)
        val b = lyricTokens(right)
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val common = a.intersect(b).size.toDouble()
        val union = a.union(b).size.toDouble().coerceAtLeast(1.0)
        val jaccard = common / union
        val leftNorm = a.joinToString(" ")
        val rightNorm = b.joinToString(" ")
        val containsBoost = if (leftNorm.contains(rightNorm) || rightNorm.contains(leftNorm)) 0.22 else 0.0
        return (jaccard + containsBoost).coerceAtMost(1.0)
    }

    private fun lyricTokens(text: String): Set<String> {
        return text
            .lowercase()
            .replace(Regex("""\([^)]*\)|\[[^]]*]"""), " ")
            .replace(Regex("""[^a-z0-9\u0900-\u097F\u0A00-\u0A7F\s']"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .split(" ")
            .filter { it.length > 1 }
            .toSet()
    }

    fun get(url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "VinMusic/2.0 (https://github.com/vinmusic)")
            .build()
        val response = http.newCall(request).execute()
        if (!response.isSuccessful) return null
        return response.use { it.body?.string() }
    }

    // SimpMusic ───────────────────────────────────────────────────────────────
    private fun trySimpMusic(title: String, artist: String): LyricsResult? {
        val q = if (artist.isNotEmpty()) "$title $artist".trim() else title
        val searchUrl = "https://lyrics.simpmusic.org/api/v1/search?q=${enc(q)}"
        val resp = get(searchUrl) ?: return null
        try {
            val list = gson.fromJson(resp, List::class.java) ?: return null
            if (list.isEmpty()) return null
            val first = list[0] as? Map<*, *> ?: return null
            val id = first["id"] as? String ?: return null
            
            val lyricsUrl = "https://lyrics.simpmusic.org/api/v1/lyrics/$id"
            val lyricsResp = get(lyricsUrl) ?: return null
            val lyricsData = gson.fromJson(lyricsResp, Map::class.java) ?: return null
            
            val lrc = lyricsData["synced"] as? String
            val plain = lyricsData["plain"] as? String
            
            when {
                !lrc.isNullOrBlank() -> {
                    val lines = parseLrc(lrc)
                    if (lines.isNotEmpty()) return LyricsResult.Synced(lines, "SimpMusic")
                }
                !plain.isNullOrBlank() -> sanitizePlainLyrics(plain)
                    .takeIf { it.isNotBlank() }
                    ?.let { return LyricsResult.Plain(it, "SimpMusic") }
            }
        } catch (_: Exception) {}
        return null
    }

    // ── KuGou Music Scraper ────────────────────────────────────────────────────
    private fun tryKugou(title: String, artist: String): LyricsResult? {
        val query = if (artist.isNotEmpty()) "$title $artist".trim() else title
        val searchUrl = "http://lyrics.kugou.com/search?ver=1&man=yes&client=pc&keyword=${enc(query)}"
        val searchResp = get(searchUrl) ?: return null
        return try {
            val searchJson = gson.fromJson(searchResp, Map::class.java) ?: return null
            val candidates = searchJson["candidates"] as? List<*> ?: return null
            if (candidates.isEmpty()) return null

            val firstCand = candidates[0] as? Map<*, *> ?: return null
            val id = (firstCand["id"] as? Number)?.toLong() ?: return null
            val accesskey = firstCand["accesskey"] as? String ?: return null

            val downloadUrl = "http://lyrics.kugou.com/download?ver=1&client=pc&id=$id&accesskey=$accesskey&fmt=lrc&charset=utf8"
            val downloadResp = get(downloadUrl) ?: return null
            val downloadJson = gson.fromJson(downloadResp, Map::class.java) ?: return null
            val base64Content = downloadJson["content"] as? String ?: return null
            if (base64Content.isBlank()) return null

            val decodedBytes = android.util.Base64.decode(base64Content, android.util.Base64.DEFAULT)
            val lrcString = String(decodedBytes, Charsets.UTF_8)

            if (lrcString.isNotBlank()) {
                val lines = parseLrc(lrcString)
                if (lines.isNotEmpty()) {
                    LyricsResult.Synced(lines, "KuGou")
                } else {
                    sanitizePlainLyrics(lrcString)
                        .takeIf { it.isNotBlank() }
                        ?.let { LyricsResult.Plain(it, "KuGou") }
                }
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    fun transliterateToHinglish(text: String, lang: String = "pa"): String {
        var detectedLang = lang
        if (lang == "pa") {
            when {
                text.any { it in '\u0A00'..'\u0A7F' } -> detectedLang = "pa"
                text.any { it in '\u0900'..'\u097F' } -> detectedLang = "hi"
                else -> return text
            }
        } else {
            if (!text.any { it in '\u0A00'..'\u0A7F' || it in '\u0900'..'\u097F' }) return text
        }
        
        try {
            val body = okhttp3.FormBody.Builder().add("q", text).build()
            val request = Request.Builder()
                .url("https://translate.googleapis.com/translate_a/single?client=gtx&sl=$detectedLang&tl=en&dt=rm")
                .post(body)
                .header("User-Agent", "Mozilla/5.0")
                .build()
            
            val response = http.newCall(request).execute().use { it.body?.string() } ?: return text
            val jsonArray = gson.fromJson(response, List::class.java)
            if (jsonArray.isNullOrEmpty()) return text
            val firstOuter = jsonArray.firstOrNull() as? List<*> ?: return text
            val sb = java.lang.StringBuilder()
            for (item in firstOuter) {
                val chunk = item as? List<*> ?: continue
                if (chunk.size > 3) {
                    val romanized = chunk[3] as? String
                    if (romanized != null) {
                        sb.append(romanized)
                    }
                }
            }
            if (sb.isEmpty()) return text
            
            return sb.toString().replace("ā", "a").replace("ī", "i").replace("ū", "u")
                .replace("ḍ", "d").replace("ṭ", "t").replace("ṇ", "n")
                .replace("ś", "sh").replace("ṣ", "sh").replace("ṛ", "r")
                .replace("ṃ", "n").replace("ḥ", "h").replace("ñ", "n")
        } catch (e: Exception) {
            return text
        }
    }

    private fun tryGenius(title: String, artist: String): LyricsResult? {
        val q = if (artist.isNotEmpty()) "$title $artist".trim() else title
        val searchUrl = "https://genius.com/api/search/multi?q=${enc(q)}"
        val resp = get(searchUrl) ?: return null

        try {
            val json = gson.fromJson(resp, Map::class.java) ?: return null
            val responseMap = json["response"] as? Map<*, *> ?: return null
            val sections = responseMap["sections"] as? List<*> ?: return null
            var songUrl: String? = null

            for (sec in sections) {
                val secMap = sec as? Map<*, *> ?: continue
                if (secMap["type"] == "song") {
                    val hits = secMap["hits"] as? List<*> ?: continue
                    if (hits.isNotEmpty()) {
                        val hit = hits[0] as? Map<*, *> ?: continue
                        val result = hit["result"] as? Map<*, *> ?: continue
                        songUrl = result["url"] as? String
                        break
                    }
                }
            }

            if (songUrl.isNullOrBlank()) return null

            val html = get(songUrl) ?: return null
            val doc = org.jsoup.Jsoup.parse(html)

            val containers = doc.select("div[data-lyrics-container=true]")
            val lyricsText = if (containers.isEmpty()) {
                val legacy = doc.select(".lyrics")
                if (legacy.isNotEmpty()) getPlainText(legacy[0]) else ""
            } else {
                containers.joinToString("\n\n") { getPlainText(it) }
            }

            if (lyricsText.isNotBlank()) {
                val cleanedLines = mutableListOf<String>()
                val lines = lyricsText.lines()
                val langKeywords = setOf(
                    "translations", "türkçe", "日本語", "français", "português", "español", 
                    "svenska", "русский", "italiano", "deutsch", "english", "polski", 
                    "tiếng việt", "română", "nederlands", "עברית", "العربية", "فارسی", 
                    "한국어", "中文", "українська", "ελληνικά", "magyar", "suomi", 
                    "norsk", "dansk", "català", "hrvatski", "bahasa indonesia", "bahasa melayu",
                    "japanese", "russian"
                )
                
                for (i in lines.indices) {
                    val line = lines[i]
                    val trimmed = line.trim()
                    if (trimmed.isEmpty()) {
                        cleanedLines.add(line)
                        continue
                    }
                    
                    val lower = trimmed.lowercase()
                    val isJunk = lower == "translations" || 
                                 langKeywords.any { lower == it || (trimmed.length < 30 && (lower.contains(it) || lower.startsWith(it))) } || 
                                 trimmed.endsWith(" Lyrics", ignoreCase = true) ||
                                 (trimmed.contains("Lyrics", ignoreCase = true) && trimmed.length < title.length + 15) ||
                                 trimmed.contains("Read More", ignoreCase = true) ||
                                 trimmed.contains("credits to", ignoreCase = true) ||
                                 trimmed.contains("Feeding Time of KTT", ignoreCase = true)
                    
                    if (i < 35 && isJunk) {
                        continue
                    }
                    cleanedLines.add(line)
                }
                
                val cleanedLyrics = sanitizePlainLyrics(cleanedLines.joinToString("\n"))
                if (cleanedLyrics.isNotBlank()) {
                    return LyricsResult.Plain(cleanedLyrics, "Genius")
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("LyricsHelper", "Genius failed: ${e.message}")
        }
        return null
    }

    private fun getPlainText(element: org.jsoup.nodes.Node): String {
        val sb = StringBuilder()
        fun traverse(node: org.jsoup.nodes.Node) {
            if (node is org.jsoup.nodes.TextNode) {
                sb.append(node.text())
            } else if (node is org.jsoup.nodes.Element) {
                if (node.tagName() == "br") {
                    sb.append("\n")
                    return
                }
                val isBlock = node.isBlock
                if (isBlock && sb.isNotEmpty() && !sb.endsWith("\n")) {
                    sb.append("\n")
                }
                for (child in node.childNodes()) {
                    traverse(child)
                }
                if (isBlock && !sb.endsWith("\n")) {
                    sb.append("\n")
                }
            }
        }
        traverse(element)
        return sb.toString().trim()
    }
}
