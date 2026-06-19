package com.vinmusic.lyrics

import com.google.gson.Gson
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

object BetterLyricsClient {
    private val gson = Gson()
    @Volatile private var disabledUntilMs: Long = 0L
    private val http = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .build()

    fun fetch(title: String, artist: String, durationMs: Long, album: String? = null): LyricsResult? {
        if (System.currentTimeMillis() < disabledUntilMs) return null

        val urlBuilder = HttpUrl.Builder()
            .scheme("https")
            .host("lyrics-api.boidu.dev")
            .addPathSegment("getLyrics")
            .addQueryParameter("s", title)
            .addQueryParameter("a", artist)

        if (durationMs > 0L) {
            urlBuilder.addQueryParameter("d", (durationMs / 1000L).toString())
        }
        if (!album.isNullOrBlank()) {
            urlBuilder.addQueryParameter("al", album)
        }

        val request = Request.Builder()
            .url(urlBuilder.build())
            .header("User-Agent", "VinMusic/2.0")
            .header("Accept", "application/json")
            .build()

        val body = http.newCall(request).execute().use { response ->
            if (response.code == 401 || response.code == 403) {
                disabledUntilMs = System.currentTimeMillis() + TimeUnit.HOURS.toMillis(12)
                return null
            }
            if (!response.isSuccessful) return null
            response.body?.string()
        } ?: return null

        val json = gson.fromJson(body, Map::class.java) ?: return null
        val ttml = (json["ttml"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val lines = BetterLyricsTtmlParser.parse(ttml)
        return if (lines.isNotEmpty()) LyricsResult.Synced(lines, "BetterLyrics") else null
    }
}

private object BetterLyricsTtmlParser {
    fun parse(ttml: String): List<LyricsLine> {
        return runCatching {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
                runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
                runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
                runCatching { isExpandEntityReferences = false }
            }
            val doc = factory.newDocumentBuilder().parse(ttml.byteInputStream())
            val paragraphs = mutableListOf<Element>()
            collectElements(doc.documentElement, "p", paragraphs)

            paragraphs.mapNotNull { p ->
                val words = collectTimedWords(p)
                val lineStart = parseTime(attr(p, "begin"))
                    ?: words.minOfOrNull { it.startMs }
                    ?: firstSpanStart(p)
                    ?: return@mapNotNull null
                val explicitEnd = parseTime(attr(p, "end"))
                val duration = parseTime(attr(p, "dur"))
                val lineEnd = explicitEnd
                    ?: duration?.let { lineStart + it }
                    ?: words.maxOfOrNull { it.endMs }
                    ?: 0L

                val text = if (words.isNotEmpty()) {
                    // TTML word spans are space-delimited by spec: the space lives in
                    // text nodes *between* <span> siblings, not inside any span. So
                    // span.textContent ("I") has no trailing space inside it, which is
                    // why the old joinToString("") produced "Ifoundalove". Joining with
                    // a single space is the TTML-correct rendering. (hasTrailingSpace
                    // is still detected from inter-span text nodes below so it stays
                    // accurate for any karaoke UI that needs it.)
                    words.joinToString(" ") { it.text }.trim()
                } else {
                    p.textContent?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
                }

                if (text.isBlank() || LyricsHelper.isNonLyricLinePublic(text)) {
                    null
                } else {
                    LyricsLine(
                        timeMs = lineStart,
                        text = text,
                        endTimeMs = lineEnd.takeIf { it > lineStart } ?: 0L,
                        words = words
                    )
                }
            }
                .distinctBy { "${it.timeMs}|${it.text}" }
                .sortedBy { it.timeMs }
        }.getOrElse { emptyList() }
    }

    private fun collectTimedWords(root: Element): List<WordTiming> {
        val spans = mutableListOf<Element>()
        collectElements(root, "span", spans)
        return spans.mapNotNull { span ->
            val start = parseTime(attr(span, "begin")) ?: return@mapNotNull null
            val end = parseTime(attr(span, "end"))
                ?: parseTime(attr(span, "dur"))?.let { start + it }
                ?: 0L
            val raw = span.textContent ?: return@mapNotNull null
            val collapsed = raw.replace(Regex("\\s+"), " ")
            val text = collapsed.trim()
            if (text.isBlank()) return@mapNotNull null
            // Detect whether a whitespace text node follows this span inside the
            // parent <p> — that's where TTML stores inter-word spaces (e.g.
            // "<span>I</span> <span>found</span>"). Reading the sibling keeps
            // hasTrailingSpace accurate even though span.textContent itself has none.
            val next = span.nextSibling
            val trailingSpace = next is org.w3c.dom.Text &&
                next.textContent.any { it.isWhitespace() }
            WordTiming(
                text = text,
                startMs = start,
                endMs = end.takeIf { it > start } ?: 0L,
                hasTrailingSpace = trailingSpace
            )
        }.sortedBy { it.startMs }
    }

    private fun firstSpanStart(root: Element): Long? {
        val spans = mutableListOf<Element>()
        collectElements(root, "span", spans)
        return spans.mapNotNull { parseTime(attr(it, "begin")) }.minOrNull()
    }

    private fun collectElements(node: Node?, localName: String, out: MutableList<Element>) {
        if (node == null) return
        if (node is Element && node.local() == localName) out += node
        var child = node.firstChild
        while (child != null) {
            collectElements(child, localName, out)
            child = child.nextSibling
        }
    }

    private fun Element.local(): String = localName ?: nodeName.substringAfterLast(':')

    private fun attr(element: Element, name: String): String {
        val direct = element.getAttribute(name)
        if (direct.isNotBlank()) return direct
        val prefixed = element.getAttribute("tts:$name")
        if (prefixed.isNotBlank()) return prefixed
        val param = element.getAttribute("ttp:$name")
        if (param.isNotBlank()) return param
        return ""
    }

    private fun parseTime(raw: String?): Long? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (value.endsWith("ms")) return value.removeSuffix("ms").toDoubleOrNull()?.toLong()
        if (value.endsWith("s")) return value.removeSuffix("s").toDoubleOrNull()?.let { (it * 1000).toLong() }

        val parts = value.split(":")
        val seconds = when (parts.size) {
            3 -> {
                val hours = parts[0].toDoubleOrNull() ?: return null
                val minutes = parts[1].toDoubleOrNull() ?: return null
                val secs = parts[2].toDoubleOrNull() ?: return null
                hours * 3600 + minutes * 60 + secs
            }
            2 -> {
                val minutes = parts[0].toDoubleOrNull() ?: return null
                val secs = parts[1].toDoubleOrNull() ?: return null
                minutes * 60 + secs
            }
            1 -> parts[0].toDoubleOrNull() ?: return null
            else -> return null
        }
        return (seconds * 1000).toLong()
    }
}
