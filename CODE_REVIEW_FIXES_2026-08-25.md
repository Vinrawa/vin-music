# Vin Music v2 — Review Verification & Fixes

**Date:** 2026-08-25 · Companion to `CODE_REVIEW_2026-08-25.md`
**Method:** Every high-severity claim re-verified against source (first-party + 4 independent deep-dives covering backend/CI, InnerTube/lyrics, playback/download, data/recommendation). Then fixes applied for everything actionable in code.
**Build verification:** ✅ `:app:compileDebugKotlin` (includes KSP/Room/Hilt processing) and `:app:processDebugResources` (manifest merger + XML linking) both **BUILD SUCCESSFUL** after all edits — only pre-existing deprecation warnings. Runtime behavior still needs a device smoke-test before release.

---

## Part 1 — Verification verdicts on the original review

### Escalated (worse than reported)
| Original | Reality |
|---|---|
| S2: "keystore present in tree, gitignored, weak password" | **`app/release-keystore.jks` was tracked in git AND publicly downloadable** at `https://vinrawa.github.io/Vin-music-v2/app/release-keystore.jks` (HTTP 200, verified live). Cause: `.github/workflows/pages.yml` uploaded the entire checkout (`path: '.'`). Commit `ea21900` added it under the message "secure keystore". The `.gitignore` rule `**/*.jks` cannot untrack an already-committed file. Store/key passwords are identical, 11-char dictionary-grade → **treat the signing key as compromised.** |
| S8: PII in debug logs | Amplified: release builds never stripped logs (`proguard-rules.pro` had no `-assumenosideeffects`), so account emails ([AuthViewModel.kt:123](app/src/main/kotlin/com/vinmusic/player/AuthViewModel.kt:123), [AuthScreen.kt:133](app/src/main/kotlin/com/vinmusic/ui/screens/AuthScreen.kt:133)), visitor tokens ([InnerTube.kt:106,154](app/src/main/kotlin/com/vinmusic/innertube/InnerTube.kt)) and a 300-char auth response dump ([YTMusicApi.kt:436](app/src/main/kotlin/com/vinmusic/innertube/YTMusicApi.kt)) shipped to logcat in production. |

### Confirmed (with corrected line numbers)
S1 update flow unverified/unvalidated; S3 debug-signing fallback ([build.gradle.kts:50](app/build.gradle.kts:50)); S4 plaintext cookie + no backup rules; S5 cleartext; widget cluster B1–B4 all real (plus the per-refresh `ImageLoader` is also never shut down); B5 `isExtendingQueue`; B6 detached analysis coroutine (+~22 MB buffers retained per analysis); A6 two live `RecommendationRepository`s (AppModule.kt:51 vs PlayerSingleton.kt:145); A4 `HomeScreen.kt.tmp` tracked (4351 lines); §4 `getGenreMixes` orphaned (~105 dead lines) while `tasks.md` claims 0%.

### Refuted / corrected
- **B7 (`updateInfo!!`) is NOT a reachable crash** — dialog gated non-null; latent smell only (now fixed anyway via smart-capture).
- InnerTube.kt:40 logs token *length*, not the token.
- PlayerViewModel/PlayerSingleton duplicated state fields are **dead code**, not an active race.
- VinDatabase migrations 8→15 verified correct against entities (incl. Room 2.6.1 default-value semantics).

### Highest-value NEW findings (from deep-dives)
- **LyricsHelper leaked an HTTP connection on every non-2xx** — 404s are the normal miss path ([LyricsHelper.get]) — *fixed*.
- **Cancelled downloads resurrected as ghost "failed" rows** referencing deleted files — *fixed*.
- **Every Activity recreation leaked a full PlayerViewModel** into immortal `PlayerSingleton` (listener never removed, service connection never unbound, `player.release()` has zero call sites) — *fixed*.
- **`cleanTitle` mangled any title containing "ft"** ("Gift of Love" → "Gi"), poisoning every lyrics-provider lookup — *fixed*.
- Cloud Function `generateDecode`: fully open endpoint feeding client text verbatim into paid Groq calls; rate limiter per-instance and never evicted — *clamped + evicted; App Check still recommended*.
- **Firestore `songs` rules contradicted the app's writes**: rules demand full metadata fields, app writes only `{likedByCount: increment}` via merge → shared like-counts silently `PERMISSION_DENIED` for new songs — *rules rewritten to match reality*.
- Background video picker compared resolutions lexicographically ("1080p" < "360p") — *fixed*.
- Sleep timer cancelled mid-fade left volume faded permanently — *fixed*.
- Crossfade fired `playNext()` ignoring repeat-one — *fixed*.
- Playlist deletion orphaned `playlist_songs` rows forever (no FK cascade) — *fixed with transactional delete*.
- Share card PNG encode ran on Main (hundreds of ms freeze) — *moved to IO*.
- Battery-optimization prompt used an intent requiring a manifest permission the app never declared — *permission added*.
- MainActivity's MediaController was never released (service-binding leak per recreation) — *fixed*.
- Also noted, unfixed below: hardcoded `gl:"IN"` region vs "international compatibility" claim; frozen Dec-2023 WEB_REMIX version strings; `RecommendationDatabase` destructive-fallback + `createFromAsset` trap; FirebaseSyncManager 1 MiB doc ceiling & non-transactional restore.

---

## Part 2 — Fixes applied (this session)

### Security
1. **Keystore exposure** — `git rm --cached app/release-keystore.jks` (file kept on disk for signing) + `HomeScreen.kt.tmp` untracked. [pages.yml](.github/workflows/pages.yml) now stages **only** `index.html`, `latest_version.json`, `vinmusic.apk` into a `site/` dir before upload.
2. **Release log stripping** — `-assumenosideeffects` for `Log.v/d/i` in [proguard-rules.pro](app/proguard-rules.pro).
3. **Self-update hardening** ([UpdateManager.kt](app/src/main/kotlin/com/vinmusic/update/UpdateManager.kt)): optional `sha256` field verified (streaming hash, off-main) before install; HTTPS + host allowlist enforced pre- and post-redirect; APK now downloaded to **app-private external storage** and installed via FileProvider (no more public `/Downloads` TOCTOU); receiver always unregistered; download status checked; null-safe manifest parsing. [latest_version.json](latest_version.json) now publishes the SHA-256 of the current v2.2.2 APK; [file_paths.xml](app/src/main/res/xml/file_paths.xml) gained the `updates/` path.
4. **Session cookie encryption** ([YTMusicSession.kt](app/src/main/kotlin/com/vinmusic/innertube/YTMusicSession.kt)): EncryptedSharedPreferences with one-time migration from legacy prefs, reset-on-corruption retry, plaintext fallback if keystore unusable; plaintext copy always cleared. Backup/d2d exclusion rules added ([backup_rules.xml](app/src/main/res/xml/backup_rules.xml), [data_extraction_rules.xml](app/src/main/res/xml/data_extraction_rules.xml), wired in [AndroidManifest.xml](app/src/main/AndroidManifest.xml)). New dep: `androidx.security:security-crypto:1.1.0-alpha06`.

### Crash / correctness
5. **Widget** ([MusicWidgetProvider.kt](app/src/main/kotlin/com/vinmusic/widget/MusicWidgetProvider.kt)): SupervisorJob + CoroutineExceptionHandler scope; `goAsync()` in `onUpdate`/`onReceive`; bitmap capped at 512px; every `updateAppWidget` wrapped; singleton Coil loader; stale-art guard (videoId checked after async load); immediate text paint.
6. **DownloadService**: no ghost row on cancellation; foreground teardown moved into queue-drain branch so queued work keeps foreground protection; notification ordering fixed.
7. **PlayerViewModel**: player listener removed + callback nulled + service connection unbound in `onCleared`; sleep-timer fade restores volume in `finally`; crossfade respects repeat-one.
8. **PlayerSingleton**: `isExtendingQueue` reset in `finally`; `clearPlayerCache` now suspends on IO instead of disk-looping on Main during 403 recovery.
9. **AudioFeatureProcessor**: analysis jobs tracked & cancelled in `onReset`; capture buffers freed right after copy.
10. **Data**: transactional `deletePlaylistWithSongs` (both call sites); `AnalyticsHelper` DCL field now `@Volatile`.
11. **UI**: share-card PNG write moved off Main; SettingsScreen update dialog de-`!!`-ed (nullable-safe).
12. **MainActivity**: MediaController future stored & released in `onDestroy`.
13. **Networking/lyrics**: `LyricsHelper.get` closes responses on all paths; `cleanTitle` regex anchored (feat/ft/featuring word-boundary strip, junk tokens as standalone words); background video resolution parsed numerically.
14. **Backend**: input clamps (per-field caps, list filters) before the Groq prompt; rate-limiter map eviction. [firestore.rules](firestore.rules): `songs` create/update restricted to `likedByCount`-only increments within bounds.
15. **Manifest**: `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` declared (prompt previously couldn't legally appear).

### Second safe-fix batch (follow-up pass, also compile-verified)
16. **Local tracks in queue fallback**: history/signals store bare `local_<id>` ids; fallback rebuilt them without a URI → guaranteed playback failure. Now reconstructs the MediaStore `content://` URI at both rebuild ([PlayerSingleton.buildFallbackItem]) and play time.
17. **Stuck loading spinner**: `playSong` early-return when DB not ready left `isLoading = true` forever — now resets with an error message.
18. **Remaining unclosed responses**: InnerTube player hot path, ExperimentalResolver, NewPipeDownloader now `.use{}` (LyricsHelper was fixed in batch 1).
19. **Visitor-data thundering herd**: token refresh serialized; concurrent LOGIN_REQUIRED storms no longer fire ~6 parallel scrapes racing one prefs file.
20. **Fake "verified artist" heuristic removed** — subscriber text containing "K"/"M" marked virtually every channel verified and poisoned the global cache; badge styles only now.
21. **streamUrlCache eviction** — entries previously lived forever; TTL sweep + 200-entry cap on insert.
22. **TTML parser hardening** — DOCTYPE disallowed (blocks billion-laughs), secure-processing on, Error-swallowing `runCatching` → `catch (Exception)`.
23. **Non-Latin artist banner collapse** — Punjabi/Hindi names normalized to "" and shared one `.jpg`; stable hash fallback now.
24. Cross-thread visibility: `nextStreamUrlDeferred`, `lastDebugMsg` now `@Volatile`.
25. Cloud Functions runtime Node 18 (EOL) → Node 20.

---

## Part 3 — Requires YOUR action / decision
1. **Commit & push** the staged deletions + workflow fix, then confirm `…/app/release-keystore.jks` returns 404 on Pages and raw.githubusercontent.
2. **Purge history** (`git filter-repo`) and **rotate the signing key** — rotation breaks silent updates for existing installs; plan a transition (old cert as secondary signer or an in-app migration path). Until rotated, treat all signature-based trust as compromised.
3. **Firebase App Check** on `generateDecode` (console setup) — clamps don't stop scripted abuse of the free endpoint.
4. **Process change:** publish `sha256` in `latest_version.json` with **every** future release (the updater warns-skips when absent).
5. Deliberately deferred (risk/benefit, needs device testing): removing `usesCleartextTraffic` (verify no http fallback mirrors first); `isLoggedIn` stale-pref gate logic; Firestore `users/{uid}` doc-shape validation; sync chunking; DB index additions; min-version kill-switch wiring.
