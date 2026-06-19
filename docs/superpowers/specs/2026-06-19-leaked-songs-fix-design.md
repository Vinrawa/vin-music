# Leaked/Unreleased Artist Songs — Surface Them

**Date:** 2026-06-19
**Status:** Design — ready for implementation
**Owner:** Vinrawa

## Problem

Songs like Kendrick Lamar's unreleased "prayer", or J Cole's "4 Your Eyez Only"
(album not on his own channel but uploaded by other users), exist on YouTube but
**don't appear** in the app's search or artist pages. The user wants them visible
and playable.

## Root Cause (verified, Phase 1 complete)

Three independent gates block these songs:

1. **`InnerTube.isLooseArtistMusicVideo`** (`InnerTube.kt:625-654`) — the filter
   applied to `searchYouTubeArtistUploads` results drops:
   - Videos with no `durationText` (line 629) — common on unofficial uploads.
   - Videos shorter than 60s or longer than **900s (15 min)** (line 640).
   - Videos whose title/author match junk terms incl. `"full album"`,
     `"compilation"`, `"playlist"` (lines 643-652) — album-named tracks get
     dropped here.
2. **`InnerTube.searchYouTubeArtistUploads`** (`InnerTube.kt:770-775`) — queries
   are literal phrases (`"$artist leaked song"`, `"$artist unreleased song"`…).
   YouTube matches the exact phrase, so a video titled just "Kendrick Lamar -
   prayer (unreleased)" won't surface under `"kendrick lamar leaked song"`.
3. **`ArtistProfileScreen`** (`ArtistProfileScreen.kt:60-100`) — **by design**
   filters out any title containing `unreleased/leak/leaked/demo/rare/loosie/
   snippet/teaser/preview/unofficial/vibe/cdq leak` from Top Songs. The code
   comment explicitly says these stay reachable "only through an explicit user
   search on the Search screen."

So today: leaked songs are filtered out of BOTH the artist profile AND (due to
#1/#2) the search that's supposed to reach them.

## Goal

Make leaked/unreleased/rare/unofficial artist uploads discoverable AND keep the
Top Songs section clean:

- **Artist page** gains a new **"More from Artist"** section below Top Songs
  containing the rare/unreleased/demo/leaked videos (the ones currently filtered
  out by `isLeak`).
- **Search** returns leaked songs as normal results (relaxed filtering + broader
  queries) so a direct search like "kendrick lamar prayer" works.

## Non-Goals

- No change to playback/stream-URL fetching — these videos already play fine via
  the existing racing fallbacks when their stream URL resolves. (Verified: user
  confirmed songs are NOT stopping on the current build; only missing from
  discovery.)
- No pre-validation of playable URLs in search results (too slow).
- No separate "leaks" tab/global library — discovery is via artist page + search.

## Design

### A. Relax the upload filter — `InnerTube.kt`

`isLooseArtistMusicVideo` (lines 625-654) changes:
- **Duration cap**: `900` → `1500` (25 min). Covers long unreleased tracks /
  full-album uploads of a single artist.
- **Allow blank `durationText`**: drop the early-return at line 629; instead
  keep the video but give it a slightly lower score (see below) so duration-less
  items don't outrank clean official cuts.
- **Narrow the junk-term block** (lines 643-652): keep blocking genuine spam
  (`reaction`, `reacts`, `review`, `shorts`, `tiktok`, `reels`, `vlog`,
  `gaming`, `gameplay`, `prank`, `standup`, `tutorial`, `how to`, `meme`,
  `parody`, `roast`, `unboxing`, `1 hour`, `1hour`, `1hr`, `loop`, `looped`).
  **Remove** from the block list: `full album`, `greatest hits`, `best of`,
  `top 10`, `playlist`, `compilation`, `cover`, `karaoke`, `instrumental`,
  `behind the scenes`, `teaser`, `promo`, `interview`, `podcast`,
  `documentary`, `essay`. These can legitimately describe rare/unofficial
  uploads worth surfacing.

### B. Broaden the upload queries — `InnerTube.kt`

`searchYouTubeArtistUploads` (lines 770-775) queries become (mix of broad +
targeted, no exact-phrase-only matching):
```kotlin
val queries = listOf(
    "$cleanArtist",
    "$cleanArtist song",
    "$cleanArtist audio",
    "$cleanArtist unreleased",
    "$cleanArtist rare track",
    "$cleanArtist demo"
)
```
Drops the literal `"leaked song"` / `"unofficial audio"` phrases. Dedup +
score + cap (`take(40)`) stay.

### C. Score tweak — `InnerTube.artistUploadScore` (lines 656-669)

- Keep existing bonuses.
- Add: `if (seconds == null) score -= 5` (blank-duration items rank slightly
  lower, but still surface).
- The existing `+12` for `unreleased/leak/leaked/demo/rare/loosie` stays, so
  these rank *up* inside "More from Artist".

### D. New InnerTube entrypoints

Add two focused methods so the artist page can fetch the rare bucket cleanly
without re-parsing:

```kotlin
// Returns unofficial/rare/unreleased uploads for an artist.
fun getArtistRareUploads(artistName: String): List<VideoItem>
```
Reuses `searchYouTubeArtistUploads` (now broadened) and then **keeps only**
items whose title matches the rare-terms set (`unreleased/leak/leaked/demo/
rare/loosie/snippet/cdq`), OR whose author isn't an official music channel
(no `topic`/`vevo` and artist name not in author) — i.e. the stuff Top Songs
currently throws away. Caps at 20.

`getArtistTopSongs` (line 1572) stays as-is (official + uploads merged/ranked
for the recommendation engine); the artist UI calls the new method directly.

### E. Artist page UI — `ArtistProfileScreen.kt`

1. New state: `var rareSongs by remember { mutableStateOf<List<VideoItem>>(emptyList()) }`
   + `var rareLoading by remember { mutableStateOf(true) }`.
2. In the existing `LaunchedEffect(artist.name)` (lines 63-105), after computing
   `officialSongs`, also compute `rareSongs` from the same fetch + an
   `InnerTube.getArtistRareUploads(artist.name)` call, merged & deduped against
   `topSongs` (rare bucket excludes anything already in Top Songs). Keep the
   existing `isLeak` split logic — it already partitions perfectly.
3. New **LazyColumn item** between "Top Songs" and "Albums" sections:
   - Header: `"More from Artist"` (matches existing section header style, 20sp
     ExtraBold) with a small subtitle `"$N rare & unreleased tracks"` when >0.
   - Only renders when `rareSongs.isNotEmpty()`.
   - Reuses `ArtSongRow(index = i+1, ...)` with a 1-based index scoped to the
     rare bucket (not continuing from Top Songs — simpler, no cross-section
     counting). Tapping calls `onSongClick(song, rareSongs)` so the queue is
     the rare bucket.
   - Collapsible "See all" once >5 (reuse `showAllSongs` pattern with a separate
     `showAllRare` flag).

### F. Search results — already covered by A + B

`InnerTube.search` itself (lines 686-763) queries **YouTube Music** (official
catalog) and is NOT the leak source — that's `searchYouTubeArtistUploads`.
After A+B relax the upload filter/queries, `SearchScreen`'s artist-flow
(`SearchScreen.kt:156-201`) which calls `searchAll` → artist → eventually
top-songs now surfaces broader content, and a direct query like
`"kendrick lamar prayer"` resolves via `search()`'s normal YTM path (prayer,
if indexed as audio, appears; if not, the broadened `searchYouTubeArtistUploads`
catches it). No SearchScreen code change required.

## Data Flow

```
Artist page open
  → LaunchedEffect(artist.name)
    → InnerTube.search("X songs") + ("X best hits")   (official catalog)
    → InnerTube.getArtistRareUploads("X")              (broadened uploads)
    → partition: officialSongs (no leakTerms) | rareSongs (leakTerms OR unofficial author)
    → Top Songs renders officialSongs
    → "More from Artist" renders rareSongs  (NEW)

Search "kendrick lamar prayer"
  → InnerTube.searchAll → search (YTM catalog) — prayer if indexed
  → if artist tab opened → getArtistTopSongs → searchYouTubeArtistUploads (now broadened) catches it
```

## Error Handling

- All new fetches wrapped in existing `try/catch` with `runCatching`/empty-list
  fallback (matches the pattern already in `ArtistProfileScreen`).
- `getArtistRareUploads` returning empty → "More from Artist" section simply
  doesn't render (conditional item). No error state needed.
- Blank-duration videos still get a stream URL via the existing racing fallback
  in `getStreamUrl`; if they're truly unplayable, the existing error UX fires
  (out of scope here — user confirmed playback isn't the current problem).

## Testing (manual, JVM where possible)

1. **Artist: J Cole** → "More from Artist" shows rare/unreleased (e.g. 4 Your
   Eyez Only related unofficial uploads); Top Songs stays clean/official.
2. **Artist: Kendrick Lamar** → "More from Artist" includes "prayer" if
   uploaded; search "kendrick lamar prayer" returns it.
3. **Regression check**: an obviously-spammy artist upload (reaction video,
   1-hour loop, gaming) must still be filtered out — verify with a known spammy
   query.
4. **Performance**: opening an artist profile must not feel slower than today —
   the rare fetch runs in the existing parallel `LaunchedEffect` (IO), and the
   extra `getArtistRareUploads` call reuses the broadened
   `searchYouTubeArtistUploads` results already being fetched.

## Risks & Mitigations

| Risk | Mitigation |
|------|------------|
| Relaxed junk filter lets noise (interviews, "best of") into results | Keep the genuine-spam terms blocked (reactions, shorts, loops, gaming); only dropped ambiguous ones. Score still ranks official cuts first. |
| "More from Artist" shows genuinely-unplayable videos (privatized leaks) | Existing error UX handles null stream URLs; user confirmed playback isn't currently broken. Out of scope to pre-validate. |
| Broader queries = slower artist page | Reuse the same `searchYouTubeArtistUploads` batch (no extra round-trips for Top Songs); rare fetch is one additional merged call. |
| Leak-term classification too broad (false positives) | "More from Artist" is additive — Top Songs is untouched, so a misclassified official track in the rare bucket doesn't remove it from anywhere harmful. |

## Files Touched

| File | Change |
|------|--------|
| `app/src/main/kotlin/com/vinmusic/innertube/InnerTube.kt` | Relax `isLooseArtistMusicVideo` (duration 1500, allow blank duration, narrow junk terms); broaden `searchYouTubeArtistUploads` queries; tweak `artistUploadScore`; add `getArtistRareUploads()`. |
| `app/src/main/kotlin/com/vinmusic/ui/screens/ArtistProfileScreen.kt` | Add `rareSongs`/`rareLoading` state; populate in existing `LaunchedEffect`; add "More from Artist" LazyColumn section reusing `ArtSongRow`. |
