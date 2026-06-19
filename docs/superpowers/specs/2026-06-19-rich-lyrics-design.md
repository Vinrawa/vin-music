# Rich Lyrics — Word Karaoke, Source Picker & Smart Cache

**Date:** 2026-06-19
**Status:** Design — approved, ready for implementation plan
**Owner:** Vinrawa

## Goal

Make the lyrics experience genuinely premium and correct, without depending on the
protected BetterLyrics API. Three concrete improvements:

1. **Word-by-word karaoke fill** — animate word-level highlighting using the
   word timings we already capture from rich LRC (BetterLyrics TTML, Paxsenix,
   KuGou) and the LRC word tags (`<00:12.34>word`).
2. **Inline source picker** — when lyrics are wrong/mis-synced, let the user
   pick another result from the same song's candidate list, inline in the
   existing Lyrics panel (Metrolist-style, no new screen).
3. **Smart cache freshness** — stop stale/bad cached lyrics from sticking
   forever. Auto-refresh in the background; pin user edits/manual picks so
   they're never overwritten.

## Non-Goals (deferred)

- BetterLyrics hosted backend / Turnstile+JWT auth (hook stays as fail-fast fallback).
- Full-screen lyrics search screen.
- Genius + timeline merge improvements.
- Plain-lyrics word karaoke (plain estimates have no real word timings).
- Synced lyrics editing UX changes (existing edit dialog stays).

## Architecture

Three independent layers, each with one responsibility.

### A. Lyrics search layer — `LyricsHelper.kt`

New public API alongside the existing sequential `fetch()`:

```kotlin
data class LyricsCandidate(
    val source: String,            // "LrcLib", "KuGou", "Genius"…
    val result: LyricsResult,      // Synced | Plain
    val preview: String            // first ~2 non-empty lines, for the picker UI
)

fun fetchCandidates(
    title: String,
    artist: String,
    videoId: String,
    durationMs: Long
): List<LyricsCandidate>
```

Behavior:
- Hits **all** providers **in parallel** (`coroutineScope { async {} }` per
  provider), reusing the existing private `tryLrcLibGet/tryLrcLibSearch/
  tryKugou/trySimpMusic/tryPaxsenix/tryGenius/tryBetterLyrics` helpers.
- Respects the existing per-provider cooldown (`blacklistedProviders`) and the
  BetterLyrics fail-fast `disabledUntilMs`.
- 4s per-provider timeout (matches existing client config).
- Dedupes by `(source, lineCount, firstLineText)` so the same LRC from two
  endpoints collapses to one row.
- Returns `List<LyricsCandidate>` sorted: Synced first, then Plain; within
  each group by provider priority order.
- Empty list if every provider fails.

Existing `fetch()` (the Auto fast-path used by `loadLyrics`) is **unchanged**.
`fetchCandidates()` is only invoked when the user opens the source picker.

### B. Cache layer — `VinDatabase.kt` + `PlayerViewModel.kt`

`CachedLyricsEntity` gains two columns:

```kotlin
@Entity(tableName = "cached_lyrics")
data class CachedLyricsEntity(
    @PrimaryKey val videoId: String,
    val lyricsType: String,    // "synced", "plain", "not_found"
    val content: String,
    val fetchedAt: Long = System.currentTimeMillis(),
    val pinned: Boolean = false
)
```

DB version bumped `13 → 14` with `MIGRATION_13_14`:
```sql
ALTER TABLE cached_lyrics ADD COLUMN fetchedAt INTEGER NOT NULL DEFAULT 0;
ALTER TABLE cached_lyrics ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0;
```
Existing rows get `fetchedAt=0` (treated as immediately stale, so they
refresh on next play — desirable). Added to `.addMigrations(...)`.

Staleness rule (in `loadLyrics`, cache-hit branch):
- If `cached.lyricsType != "not_found"` and `cached.pinned == false` and
  `now - fetchedAt > 7 days` → **show cached immediately** (no flicker),
  then launch a background refresh via `fetch()`; if the fresh result is also
  not `NotFound`, swap `lyricsResult` and overwrite the cache row
  (preserving `videoId`, updating `fetchedAt`).
- If `pinned == true` → cache hit is final, never auto-refresh.
- `not_found` cache rows keep existing behavior (cleared on refetch).

Pinning rules — set `pinned = true` when:
- User saves a **custom edit** (`saveCustomLyrics`).
- User taps **Refetch** (`refetchLyrics`) — explicit refresh = trust this one.
- User **picks a candidate** from the source picker (`selectCandidate`).

### C. UI layer — `PlayerViewModel.kt` + `FullPlayerScreen.kt`

#### C.1 Word-fill state (PlayerViewModel)

New observable state:
```kotlin
var currentWordIndex by mutableIntStateOf(-1)    // index in current line's words, -1 if none
var wordFillFraction by mutableFloatStateOf(0f)  // 0f..1f progress within active word
```

Computed inside the existing `updateSyncedLyricIndex()` (runs every playback
tick). After determining `currentLyricIndex`:
- If the active line has non-empty `words`:
  - Find the word whose `[startMs, endMs]` contains `lyricAdjustedTimeMs()`.
    If no exact containment (between words), use the last word with
    `startMs <= adjustedTime`.
  - `currentWordIndex = that index`.
  - `wordFillFraction = ((adjustedTime - word.startMs) / (word.endMs - word.startMs)).coerceIn(0f, 1f)`.
    If `word.endMs <= word.startMs` (no end timing), fraction = 1f for past
    words, 0f for future.
- Else (no word timings on this line): `currentWordIndex = -1`.

Reset both to defaults in `resetLyricsState()`.

#### C.2 Source picker state (PlayerViewModel)

```kotlin
var lyricsCandidates by mutableStateOf<List<LyricsCandidate>>(emptyList())
var isCandidatesLoading by mutableStateOf(false)

fun fetchLyricsCandidates()        // IO: calls LyricsHelper.fetchCandidates
fun selectCandidate(c: LyricsCandidate)  // sets lyricsResult, writes cache pinned=true
```

#### C.3 Rendering (FullPlayerScreen — LyricsPanel)

Header gains a **search/list icon** button (next to existing Refetch/Edit).
Tapping toggles a candidate-list view that replaces the lyrics `LazyColumn`
(AnimatedContent slide).

Active line rendering change:
- When `line.words != null` **and** `idx == currentLyricIndex` **and**
  `currentWordIndex >= 0` → render as a `KaraokeLine` composable:
  - `FlowRow` of words. Each word is a `Box` wrapping two stacked `Text`s:
    a dim base (grey, always drawn) and a bright overlay (white/accent)
    clipped from the left to `fraction * wordWidth` via
    `Modifier.graphicsLayer { clip = true; shape = fractionClipShape(...) }`
    (or `drawWithContent` + `clipRect(right = size.width * fraction)` on the
    overlay only — single concrete technique, picked during planning).
    - Past words: fraction = 1f (full bright overlay).
    - Future words: fraction = 0f (dim base only, overlay invisible).
    - Active word: fraction = animated `wordFillFraction`.
  - Words use the same font size/weight as current active line (18sp ExtraBold).
  - Preserves the existing per-line scale/alpha/shadow animation.
- Else → existing single `Text` render (unchanged). **No regression** for
  plain lyrics or synced lyrics without word timings.

Non-active lines: unchanged dim `Text`.

Candidate list row:
```
◉  LrcLib          [SYNCED]
   Walking down the street tonight...
   hear the rhythm of the city...
```
- Source name (medium), badge (SYNCED = accent, PLAIN = grey), 2-line preview.
- Radio highlight on the row matching the currently-shown source (matched by
  `result.source` prefix; "Local Cache" highlights none).
- Tap → `vm.selectCandidate(c)`, collapse list, lyrics switch instantly.

Empty candidates → "No alternative sources found." message with a Retry.

## Data Flow

```
Song plays
  → loadLyrics()
    → cache hit?
       ├─ pinned?            → show, done.
       ├─ stale (>7d, not pinned) → show old + background fetch() → silent swap
       └─ fresh              → show, done.
    → cache miss/not_found   → fetch() → show + write cache (fetchedAt=now)

Playback tick (existing)
  → updateSyncedLyricIndex()
    → currentLyricIndex  (existing)
    → currentWordIndex + wordFillFraction  (NEW)

User taps picker icon
  → fetchLyricsCandidates()  (parallel)
  → list shown
  → selectCandidate(c)
    → lyricsResult = c.result
    → cache insert pinned=true
    → list collapse
```

## Error Handling

- `fetchCandidates`: any provider exception caught per-provider (already the
  pattern in `fetch()`); does not abort the batch. SSL failures still call
  `markFailed()` (cooldown).
- BetterLyrics 401/403 → existing `disabledUntilMs` 12h cooldown, skipped.
- Background stale-refresh: if `fetch()` returns `NotFound`, keep the
  existing cached lyrics (don't blank the screen) and **don't** overwrite the
  cache row (so we don't lose it). `fetchedAt` left as-is so it retries next
  play.
- `selectCandidate` on a `Plain` candidate while timeline has word timings:
  handled — plain result rebuilds an estimated timeline (existing
  `currentLyricsTimeline()` path), word karaoke simply stays off.

## Testing

Unit-testable pure functions (no Android dependencies) — target JVM tests in
`app/src/test`:

1. **`LyricsHelper.fetchCandidates`** — fake the provider helpers (or feed a
   small fixture LRC) and assert: parallel results deduped, sorted Synced-
   before-Plain, empty when all fail.
2. **Word-index math** — extract the word/fraction computation into a pure
   helper (e.g. `computeWordProgress(line, adjustedMs): Pair<Int, Float>`)
   and test: word contained, between words, line with no word timings,
   single word with no endMs.
3. **LRC rich-word parser** — existing `parseLrc` already preserves
   `<mm:ss.xx>word`; add a test asserting `words` populated and `endMs`
   derived from the next word.

UI (manual verification, no instrumented tests in scope): word fill animates
on a song with rich LRC; picker lists and switches sources; stale cache
refreshes after `fetchedAt` artificially aged.

## Risks & Mitigations

| Risk | Mitigation |
|------|------------|
| Word-fill recompose cost on every playback tick (jank) | Only the **active line** recomposes; `wordFillFraction` is a single float state. Other lines are stable. Use `animateFloatAsState` only on the fill width. |
| Parallel `fetchCandidates` is heavier than `fetch()` | Only triggered on explicit user tap; 4s cap per provider; cooldown respected. |
| `clipRect` per word on long lines expensive | Cap karaoke to lines with word timings (rich LRC only); plain/estimated lines unaffected. |
| Stale-refresh race when song changes mid-refresh | Existing `currentSong?.videoId == fetchVideoId` guard already in `loadLyrics`; reuse the same guard before swapping. |
| Migration breaks existing installs | Two simple `ALTER TABLE ADD COLUMN` with defaults; existing rows valid (`fetchedAt=0`, `pinned=0`). |

## Files Touched

| File | Change |
|------|--------|
| `app/src/main/kotlin/com/vinmusic/lyrics/LyricsHelper.kt` | Add `LyricsCandidate`, `fetchCandidates()`, pure `computeWordProgress()` helper. |
| `app/src/main/kotlin/com/vinmusic/data/db/VinDatabase.kt` | Add `fetchedAt`, `pinned` to entity; `MIGRATION_13_14`; bump version 14. |
| `app/src/main/kotlin/com/vinmusic/player/PlayerViewModel.kt` | Word state + picker state; staleness logic in `loadLyrics`; `resetLyricsState` updates; pinning on edit/refetch/selectCandidate. |
| `app/src/main/kotlin/com/vinmusic/ui/screens/FullPlayerScreen.kt` | `KaraokeLine` composable; picker button + candidate list view in `LyricsPanel`. |
| `app/src/test/...` | New JVM tests for pure helpers. |
