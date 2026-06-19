# Lyrics Providers Fix — Dead Sources, Spacing Bug, New YTM Provider

**Date:** 2026-06-19
**Status:** Design — ready for implementation
**Owner:** Vinrawa

## Problem

User reports only Genius (plain, poorly synced) and BetterLyrics (broken spacing —
words joined together like `Ifoundalove`) ever surface. Other providers never
return results. The auto chain jumps straight to Genius.

## Root Cause (verified, Phase 1 complete — all evidence-based)

| # | Bug | Evidence | Impact |
|---|-----|----------|--------|
| 1 | **LrcLib timeouts** — server takes ~10s, app `readTimeout=4s` | `curl time_starttransfer=10.85s`, connect only 0.3s | LrcLib (best synced source today) always fails |
| 2 | **SimpMusic dead** — HTTP 000, connect succeeds but no response | DNS resolves (216.150.1.193) but server silent (0.77s timeout) | SimpMusic never works |
| 3 | **Paxsenix dead** — DNS fails entirely | `[System.Net.Dns]::GetHostAddresses` returns nothing | Paxsenix never works |
| 4 | **BetterLyrics spacing bug** — space lives *between* `<span>`s, not inside | real TTML: `<span>I</span> <span>found</span>` → `span.textContent="I"` has no trailing space → `hasTrailingSpace=false` → `words.joinToString` produces `Ifoundalove` | BL lyrics render without word spacing |

**Chain:** YTM(?) → BetterLyrics(spacing broken) → LrcLib(timeout) → KuGou(English empty by design) → SimpMusic(dead) → Paxsenix(dead) → Genius(plain). Only Genius + broken-BL survive = exactly the symptoms.

KuGou is Chinese-focused (returns `candidates:[]` for English) — by design, not a bug. Kept for regional tracks.

## Goal

1. Add **YouTube Music** as the **#1 priority** lyrics source (perfect sync — same source as the playing song).
2. Fix BetterLyrics spacing bug.
3. Raise LrcLib timeout to 8s.
4. Remove dead SimpMusic + Paxsenix.
5. New priority order: `YTM → BetterLyrics → LrcLib → KuGou → Genius`.

## Why YTM as #1

- **Perfect sync guaranteed** — lyrics come from the same source as the song. Timestamps match exactly, no drift.
- **Official/Musixmatch-backed** — YTM has officially licensed synced lyrics.
- **No third-party dependency** — won't die like SimpMusic/Paxsenix.
- **Exact match** — uses the playing `videoId`, no fuzzy title/artist search.
- Trade-off: YTM doesn't have lyrics for *every* song, hence fallback chain.

## Design

### A. New InnerTube lyrics methods — `InnerTube.kt`

Two new public methods (mirrors Metrolist/InnerTune/RiMusic standard flow):

```kotlin
// 1. Get the lyrics browseId from a videoId via the /next endpoint.
fun getLyricsBrowseId(videoId: String): String?

// 2. Fetch synced (LRC) + plain lyrics from the browseId.
fun getLyrics(browseId: String): Pair<String?, String?>  // (synced, plain)
```

Flow:
1. POST to `$BASE/next` with `{ videoId, context: WEB_REMIX }`.
2. Parse the response `engagementPanels` (or `watchNextPlaylistPanel`) — find the
   one whose title is `"LYRICS"` / whose content contains a
   `musicDescriptions`/`navigationEndpoint/browseEndpoint/browseId` starting with
   `MPLYT_` (YTM lyrics browseId prefix).
3. POST to `$BASE/browse` with `{ browseId, context: WEB_REMIX }`.
4. Parse `musicDescriptionShelfRenderer/runs` — timed lines come back as
   `<00:12.34>word` style runs OR as `startTimeMs`/`endTimeMs` attached
   `timedLyricRender`. Convert to a standard LRC string (or directly to
   `List<LyricsLine>`).

Reuse existing `http` client + headers already used by other InnerTube methods.
Timeout: the YTM endpoints are part of the same infra as the playing song, so
they're reliably fast (unlike LrcLib).

### B. New YTM lyrics provider — `LyricsHelper.kt`

```kotlin
private fun tryYouTubeMusic(videoId: String): LyricsResult? {
    if (videoId.isBlank()) return null
    val browseId = InnerTube.getLyricsBrowseId(videoId) ?: return null
    val (synced, plain) = InnerTube.getLyrics(browseId)
    return when {
        !synced.isNullOrBlank() -> {
            val lines = parseLrc(synced)
            if (lines.isNotEmpty()) LyricsResult.Synced(lines, "YouTube Music") else null
        }
        !plain.isNullOrBlank() -> sanitizePlainLyrics(plain)
            .takeIf { it.isNotBlank() }
            ?.let { LyricsResult.Plain(it, "YouTube Music") }
        else -> null
    }
}
```

The `fetch()` signature already receives `videoId` — pass it through to this
provider.

### C. Fix BetterLyrics spacing — `BetterLyricsClient.kt`

The space lives in text nodes *between* `<span>` siblings inside the `<p>`, not
inside any span. Two fixes (do both — belt and suspenders):

1. **`collectTimedWords`** — when iterating a span, read the **trailing text node**
   (the `nextSibling` if it's a `Text` node) to determine `hasTrailingSpace`:
   ```kotlin
   val next = span.nextSibling
   val trailingSpace = next is org.w3c.dom.Text && next.textContent.any { it.isWhitespace() }
   WordTiming(... hasTrailingSpace = trailingSpace)
   ```
2. **`parse` line-text builder** (line 86-89) — fallback: if joining words yields a
   run-together string with no spaces but the `<p>`'s own `textContent` has spaces,
   prefer the `<p>` textContent. Safer: render words individually in the karaoke UI
   (planned for the rich-lyrics feature) and insert a space between each word
   explicitly in the join, since LRC/TTML words are space-delimited by convention.
   Simplest correct fix: **always join with `" "`** and `trim()`:
   ```kotlin
   words.joinToString(" ") { it.text }.trim()
   ```
   (TTML word spans never contain internal leading/trailing spaces that matter;
   space-separating is the standard convention.)

### D. Raise LrcLib timeout — `LyricsHelper.kt`

Change `http` builder:
```kotlin
connectTimeout(5, TimeUnit.SECONDS)  // was 4
readTimeout(8, TimeUnit.SECONDS)     // was 4
```
(Confirmed with user — 8s read timeout.)

### E. Remove dead providers — `LyricsHelper.kt` + `SettingsScreen.kt`

- Delete `trySimpMusic()` and `tryPaxsenix()` from `LyricsHelper.kt`.
- Remove from the auto `providers` list (lines 140-147).
- Remove from manual-provider `when` (lines 113-123).
- Remove `"SimpMusic Only"` + `"Paxsenix Only"` from `SettingsScreen.kt` dropdown
  options (line 718) and the corresponding `when` mapping (lines 720-726).
- Update the "Source Priority" info text (line 732):
  `YouTube Music -> BetterLyrics -> LrcLib -> KuGou -> Genius (when Auto)`.

### F. New auto priority order — `LyricsHelper.fetch()`

```kotlin
val providers: List<Pair<String, () -> LyricsResult?>> = listOf(
    "YouTube Music" to { tryYouTubeMusic(videoId) },
    "BetterLyrics"  to { tryBetterLyrics(title, artist, durationMs) },
    "LrcLib"        to { tryLrcLibGet(t, a, durationMs) ?: tryLrcLibSearch(t, a, durationMs) },
    "KuGou"         to { tryKugou(t, a) },
    "Genius"        to { tryGenius(t, a) }
)
```

`videoId` must be threaded into `fetch()` — it's already a parameter, just needs
to reach `tryYouTubeMusic`.

## Data Flow

```
Song plays (videoId known)
  → fetch(title, artist, videoId, provider, durationMs)
    Auto:
      → tryYouTubeMusic(videoId)   [NEW #1 — perfect sync]
        → InnerTube.getLyricsBrowseId(videoId) -> browseId
        → InnerTube.getLyrics(browseId) -> (synced, plain)
        → Synced? return. Else fall through.
      → tryBetterLyrics(...)       [FIXED spacing]
      → tryLrcLib(...)             [8s timeout now]
      → tryKuGou(...)              [Chinese/regional]
      → tryGenius(...)             [plain fallback]
```

## Error Handling

- All YTM calls wrapped in `try/catch` returning `null` (matches existing provider
  pattern). A song without YTM lyrics returns the browseId-less / empty-runs case
  → `null` → falls through.
- YTM endpoints failing (rare) doesn't affect playback — lyrics are independent.
- BetterLyrics spacing fix is purely additive (more correct text), no new failure
  mode.
- LrcLib still may fail at 8s if its server is genuinely slow that day — that's
  fine, chain continues.

## Testing (manual)

1. **Spacing fix**: play any song where BetterLyrics returns TTML (e.g. Ed Sheeran
   "Perfect") → lyrics render with spaces between words (`I found a love for me`).
2. **YTM provider**: play a popular song → "Source: YouTube Music", perfectly
   synced, no drift over a 3-min play.
3. **Chain**: play a rare song YTM has no lyrics for → falls to BetterLyrics →
   LrcLib → ... → Genius. Verify in the "Source:" label.
4. **Dead providers gone**: search any song → no 0.7s/0.004s wasted waits on
   SimpMusic/Paxsenix; auto-flow faster.
5. **Settings**: dropdown shows 5 options (Auto + 4 providers), no SimpMusic/
   Paxsenix.

## Risks & Mitigations

| Risk | Mitigation |
|------|------------|
| YTM lyrics response shape varies / changes | Pattern-match loosely (scan for `MPLYT_` browseId, multiple run structures); wrap in `runCatching`. Existing InnerTube parsing is already defensive. |
| 8s LrcLib timeout makes auto-flow feel slower when LrcLib is the only option | LrcLib is mid-chain; YTM (fast) + BetterLyrics usually return first. Dead Simp/Pax removal saves ~1s. Net faster. |
| "Always join with space" breaks a TTML variant with deliberate no-space words | Extremely rare; TTML word-spans are space-delimited by spec. If needed, fall back to reading inter-span text nodes (fix #1) which preserves original spacing exactly. |
| YTM requires auth/visitor token for lyrics | Reuse existing `ensureVisitorData()` already called in `getStreamUrl`. Same infra. |

## Files Touched

| File | Change |
|------|--------|
| `app/src/main/kotlin/com/vinmusic/innertube/InnerTube.kt` | Add `getLyricsBrowseId()` + `getLyrics()` methods. |
| `app/src/main/kotlin/com/vinmusic/lyrics/LyricsHelper.kt` | Add `tryYouTubeMusic()`; fix timeout 8s; remove `trySimpMusic`/`tryPaxsenix`; reorder providers; thread `videoId`. |
| `app/src/main/kotlin/com/vinmusic/lyrics/BetterLyricsClient.kt` | Fix spacing (read inter-span text nodes / always-join-with-space). |
| `app/src/main/kotlin/com/vinmusic/ui/screens/SettingsScreen.kt` | Remove SimpMusic/Paxsenix dropdown options; update priority info text. |

## Sources

- [LRCLIB](https://lrclib.net/) — confirmed slow (~10s server response)
- [Better Lyrics for YouTube Music (GitHub)](https://github.com/better-lyrics/better-lyrics) — YTM-first reference
- [netease-qq-music-api (crates.io)](https://crates.io/crates/netease-qq-music-api) — alternative provider research
- [Spotube Issue #1491](https://github.com/KRTirtho/spotube/issues/1491) — QQ/NetEase as lyric sources
