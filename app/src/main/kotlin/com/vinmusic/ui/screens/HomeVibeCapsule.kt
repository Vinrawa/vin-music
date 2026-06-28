package com.vinmusic.ui.screens

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vinmusic.ui.theme.VinColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val AESTHETIC_LYRICS_QUOTES = listOf(
    "Kho gaye hum kahan, rangon ke is jahaan mein..." to "Kho Gaye Hum Kahan - Prateek Kuhad",
    "Teri baaton mein hai jo nasha, dil mera behak sa gaya..." to "Husn - Anuv Jain",
    "Tum jo paas ho, toh har lamha haseen sa lagta hai..." to "Baarishein - Anuv Jain",
    "Kuch toh hai jo hum hai keh nahi paaye, kuch toh hai jo tum ho samajh rahe..." to "Tu Jo Mila - Pritam",
    "Dil se dil ki baatein, suno na tum humari..." to "Kasoor - Prateek Kuhad",
    "Kaise mujhe tum mil gayi, kismat pe yaqeen ban gaya..." to "Kaise Mujhe - A.R. Rahman",
    "Hold on to the memories, they will hold on to you..." to "New Year's Day - Taylor Swift",
    "Main tenu samjhawan ki, na tere bina lagda jee..." to "Samjhawan - Arijit Singh",
    "Jeene ke liye socha hi nahi, dard sambhalne honge..." to "Tujhse Naraz Nahi Zindagi - Gulzar",
    "Main jahaan rahoon, main kahin bhi hoon, teri yaad sath hai..." to "Namastey London - Himesh Reshammiya",
    "Ek pyaar ka nagma hai, maujon ki rawaani hai..." to "Ek Pyaar Ka Nagma - Laxmikant-Pyarelal",
    "Aise kyun hai yeh jahaan, hum jo mile hain yahan..." to "Aise Kyun - Anurag Saikia",
)

@Composable
fun VibeOfTheDayCapsule(
    context: Context,
    db: com.vinmusic.data.db.VinDatabase,
) {
    var quoteText by rememberSaveable { mutableStateOf("") }
    var quoteSource by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val cachedLyrics = try {
                db.cachedLyricsDao().getAll()
            } catch (_: Exception) {
                emptyList()
            }.filter { it.content.isNotBlank() && it.lyricsType != "not_found" }

            val lyricsById = cachedLyrics.associateBy { it.videoId }
            val topMeta = try { db.songCacheMetaDao().topPlayed(500) } catch (_: Exception) { emptyList() }
            val metaById = topMeta.associateBy { it.videoId }
            val liked = try { db.likedSongDao().getAll() } catch (_: Exception) { emptyList() }
            val history = try { db.historyDao().getAllHistory() } catch (_: Exception) { emptyList() }
            val signals = try { db.interactionSignalDao().getAll() } catch (_: Exception) { emptyList() }

            val priorityIds = (
                signals.sortedWith(
                    compareByDescending<com.vinmusic.data.db.InteractionSignal> { it.isLiked }
                        .thenByDescending { it.repeatCount }
                        .thenByDescending { it.playCount }
                        .thenByDescending { it.lastPlayedAt },
                ).map { it.videoId } +
                    liked.map { it.videoId } +
                    history.map { it.videoId } +
                    topMeta.map { it.videoId }
            ).distinct()

            val customQuotes = ArrayList<Pair<String, String>>()
            for (videoId in priorityIds) {
                val lyrics = lyricsById[videoId] ?: continue
                val lines = extractVibeLines(lyrics)
                if (lines.isEmpty()) continue

                val trackTitle = metaById[videoId]?.let { "${it.title} - ${it.author}" }
                    ?: liked.firstOrNull { it.videoId == videoId }?.let { "${it.title} - ${it.author}" }
                    ?: history.firstOrNull { it.videoId == videoId }?.let { "${it.title} - ${it.author}" }
                    ?: signals.firstOrNull { it.videoId == videoId }?.let { "${it.title} - ${it.author}" }
                    ?: "Your Library"
                val cleanTrackTitle = cleanVibeSourcePart(trackTitle).ifBlank { "Your Library" }

                lines.shuffled().take(2).forEach { line ->
                    customQuotes.add(line to cleanTrackTitle)
                }
            }

            val chosen = (customQuotes.ifEmpty { AESTHETIC_LYRICS_QUOTES }).random()
            withContext(Dispatchers.Main) {
                quoteText = chosen.first
                quoteSource = chosen.second
            }
        }
    }

    if (quoteText.isEmpty()) return

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = 0.09f),
                        Color.White.copy(alpha = 0.02f),
                    ),
                ),
            )
            .border(1.dp, VinColors.GlassBorder, RoundedCornerShape(24.dp))
            .padding(20.dp),
    ) {
        Icon(
            imageVector = Icons.Default.FormatQuote,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.06f),
            modifier = Modifier
                .size(96.dp)
                .align(Alignment.BottomEnd)
                .offset(x = 10.dp, y = 20.dp),
        )

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(VinColors.Accent.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.FormatQuote,
                        contentDescription = null,
                        tint = VinColors.Accent,
                        modifier = Modifier.size(14.dp),
                    )
                }
                Text(
                    text = "YOUR VIBE TODAY",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = VinColors.AccentLight,
                    letterSpacing = 1.5.sp,
                )
            }

            Text(
                text = "\"$quoteText\"",
                fontSize = 16.sp,
                fontStyle = FontStyle.Italic,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                lineHeight = 24.sp,
            )

            Text(
                text = quoteSource,
                fontSize = 11.sp,
                color = VinColors.Secondary,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

private fun extractVibeLines(lyrics: com.vinmusic.data.db.CachedLyricsEntity): List<String> {
    val rawLines = when (lyrics.lyricsType) {
        "synced" -> try {
            com.google.gson.Gson()
                .fromJson(lyrics.content, Array<com.vinmusic.lyrics.LyricsLine>::class.java)
                .toList()
                .map { it.text }
        } catch (_: Exception) {
            emptyList()
        }
        "plain" -> lyrics.content.split("\n\n", "\n")
        else -> emptyList()
    }

    return rawLines
        .mapNotNull { cleanVibeLine(it) }
        .distinct()
        .filter { it.length in 24..92 }
        .filterNot { it.count { ch -> ch.isLetter() } < 10 }
        .filter { isHindiEnglishVibeLine(it) }
        .take(24)
}

private fun cleanVibeLine(raw: String): String? {
    val text = raw
        .replace("\u00A0", " ")
        .replace(CURRENCY_SYMBOL_REGEX, "")
        .replace(Regex("""\s+"""), " ")
        .trim()
        .trim('"', '\'', '[', ']', '(', ')')
        .trim()

    if (text.isBlank()) return null
    val lower = text.lowercase()
    if (Regex("""^\d+\s+contributors?$""", RegexOption.IGNORE_CASE).matches(text)) return null
    if (Regex("""^(intro|outro|verse|chorus|pre[-\s]?chorus|post[-\s]?chorus|bridge|hook|refrain|interlude|instrumental|drop|break|spoken|sample|skit)(\s+\d+|\s+[ivx]+)?(\s*[:.-].*)?$""", RegexOption.IGNORE_CASE).matches(text)) return null

    val junk = listOf(
        "you might also like",
        "embed",
        "read more",
        "translations",
        "lyrics",
        "track info",
        "produced by",
        "written by",
        "release date",
    )
    if (junk.any { lower == it || (text.length < 42 && lower.startsWith(it)) }) return null
    return text
}

private fun isHindiEnglishVibeLine(text: String): Boolean {
    if (CURRENCY_SYMBOL_REGEX.containsMatchIn(text)) return false
    val unsupported = text.any { ch ->
        !(ch.isLetterOrDigit() ||
            ch.isWhitespace() ||
            ch in setOf('\'', '"', ',', '.', '?', '!', '-', ':', ';') ||
            ch in '\u0900'..'\u097F')
    }
    if (unsupported) return false
    return text.any { it in 'a'..'z' || it in 'A'..'Z' || it in '\u0900'..'\u097F' }
}

private fun cleanVibeSourcePart(raw: String): String {
    return raw
        .replace(CURRENCY_SYMBOL_REGEX, "")
        .replace(Regex("""[^\p{L}\p{N}\s.'\-]"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()
}

private val CURRENCY_SYMBOL_REGEX = Regex("""[\u20AC\u00A3\u00A5\u20B9\u00A2$]""")
