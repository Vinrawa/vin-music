# Unified Login — 1 Tap for YouTube Music + Cloud Sync

**Date:** 2026-06-19
**Status:** Design — approved, ready for implementation plan
**Owner:** Vinrawa

## Problem

The app currently has **two separate Google logins** that both use the same
Google account but different auth systems, so the user must sign in twice:

1. **Cloud Sync** (`AuthViewModel` + `GoogleSignInClient` + Firebase) — backs up
   playlists/likes to the app's own backend.
2. **YouTube Music Connection** (`YTMusicSession` + WebView cookie) — unlocks
   personalized YTM home, library, liked songs via `SAPISID` cookie.

User goal: log in **once** and have both connect automatically. No second login.

## Technical Reality (verified via research)

Google deliberately separates these auth systems:

- `GoogleSignInAccount` (SDK) → returns an **ID token / auth code**. It does
  NOT yield YouTube Music browser session cookies.
- YTM web session → lives in browser **cookies** (`SAPISID`,
  `__Secure-3PAPISID`, `HSID`, `SID`, `APISID`…).

There is **no supported path** to derive YTM cookies from a
`GoogleSignInAccount`. Industry consensus (InnerTune, RiMusic, Music Assistant,
multiple StackOverflow threads) confirms this is a dead end:
[SO 25804725](https://stackoverflow.com/questions/25804725/android-webview-auto-login-to-https-website-by-setting-token-cookie),
[InnerTune #1810](https://github.com/z-huang/InnerTune/issues/1810),
[Music Assistant #5101](https://github.com/orgs/music-assistant/discussions/5101).

**Therefore:** the unification must be done the other way — **one WebView
login yields the YTM cookie AND the user's email**, and that email is used to
satisfy Cloud Sync's identity. The native `GoogleSignInClient` button becomes
optional/legacy.

## Goal

Replace the two login buttons with a single **"Connect Account"** flow:

- One WebView login → YTM cookie captured → YTM connected.
- Email extracted from the YTM session → Firebase bridge → Cloud Sync connected.
- User sees: `Connected: <email> (YTM + Cloud Sync)`.

## Non-Goals

- **Token/cookie refresh automation.** YTM cookies expire (~1 month). Existing
  manual re-login behavior stays. Auto-refreshing silently is fragile and
  out of scope.
- **Migrating existing Cloud Sync users off Firebase.** Backward compatibility
  is preserved — the old `GoogleSignInClient` path stays available as a
  fallback if email extraction fails.
- **Removing Firebase.** Cloud Sync still uses Firebase under the hood; we just
  feed it an email (and best-effort a credential) instead of forcing a second
  interactive Google prompt.

## Architecture

### A. Email extraction — new `InnerTube` / `YTMusicApi` method

After cookie capture, fetch the signed-in account's email from YTM's own
account endpoint (the cookie authorizes it):

```kotlin
// In YTMusicApi or InnerTube
fun getAccountEmail(ctx: Context): String?
```

- POST to `https://music.youtube.com/youtubei/v1/account/account_menu` using
  the existing `buildRequest` (which already injects `Cookie` +
  `Authorization: SAPISIDHASH` when a cookie is stored).
- Parse the JSON response for the primary account's `email` field. Defensive
  recursive scan: find any node whose key is `"email"` and whose value matches
  an email regex, take the first match.
- Return `null` on any failure → triggers the Firebase fallback path.

### B. Firebase bridge — `AuthViewModel`

New method:

```kotlin
fun connectFromYtmEmail(email: String)
```

- Looks up whether a Firebase user already exists / can be referenced by this
  email. Because we cannot mint a real Google OAuth credential from just an
  email, Cloud Sync will use **anonymous Firebase auth** (already supported by
  Firebase) and tag the resulting user with the YTM email in a local
  `UserAccount` row (existing table) + a Firebase profile note.
- This keeps Cloud Sync functional (backups tied to a stable anonymous UID
  seeded once) without requiring the second interactive Google prompt.
- If a real `GoogleSignInClient` credential was previously linked, keep using
  that richer auth — the email path is strictly a fallback/unifier.

**Edge case:** If `getAccountEmail` returns null, surface a non-blocking
notice ("YTM connected; tap Cloud Sync separately to enable backup") and leave
the legacy button reachable.

### C. Unified UI — `SettingsScreen.kt`

- Replace the two separate cards ("Cloud Sync → Connect" + the YTM section's
  "Sign In with Google" + "Manual Cookie Setup") with **one primary card**:
  `"Connect Your Account"` → opens the existing WebView dialog.
- Keep "Manual Cookie Setup" as a small secondary option under an "Advanced"
  disclosure (power users who paste cookies).
- On successful WebView login, the existing `onPageFinished` cookie-capture
  block additionally:
  1. Calls `YTMusicApi.getAccountEmail(ctx)` (IO).
  2. Calls `authVm.connectFromYtmEmail(email)`.
  3. Updates both UI states (`ytCookieConnected`, `currentUser`) and shows
     the unified toast: `"Connected: <email> (YTM + Cloud Sync)"`.
- The legacy `GoogleSignInClient` "Connect" button under Cloud Sync becomes a
  hidden/secondary path, visible only if email extraction failed.

### D. Status display

After a successful unified connect:
- Cloud Sync card reads: `Backup linked to <email> · YTM connected`
- If YTM cookie present but email extraction failed:
  `YTM connected · tap to also enable backup` (legacy Google button shown).

## Data Flow

```
Settings → "Connect Your Account"
  → WebView: accounts.google.com → music.youtube.com
  → user logs in ONCE (Google account)
  → onPageFinished detects music.youtube.com + SAPISID cookie
     → YTMusicSession.setCookie(ctx, cookies)            [YTM connected]
     → YTMusicApi.getAccountEmail(ctx)                   [email]
        ├─ success → authVm.connectFromYtmEmail(email)    [Cloud Sync connected]
        │            → toast "Connected: <email> (YTM + Cloud Sync)"
        └─ fail    → YTM connected only
                     → show legacy Google button for backup
                     → toast "YTM connected; enable backup separately"
```

## Error Handling

- `getAccountEmail` network failure / parse failure → return null, do NOT
  break YTM connection (the cookie is still valid and useful on its own).
- `connectFromYtmEmail` Firebase failure → Cloud Sync stays off, YTM stays on,
  surface a toast; legacy button remains available.
- Cookie missing `SAPISID`/`__Secure-3PAPISID` → existing behavior (won't
  capture). No change.
- WebView blocked by Google ("403 Disallowed User Agent") → existing desktop
  User-Agent override (`SettingsScreen.kt:1217`) already mitigates this.

## Testing (manual)

1. Fresh install, no login → tap "Connect Your Account" → WebView → Google
   login once → both YTM and Cloud Sync show connected, email shown.
2. Existing Cloud Sync user (Firebase already linked) → "Connect Your Account"
   → YTM connects, Cloud Sync keeps using the richer existing auth, no
   duplicate account.
3. `getAccountEmail` forced failure (e.g., airplane mode right after cookie
   capture) → YTM connects, Cloud Sync shows "enable separately", legacy
   Google button visible.
4. Manual Cookie Setup path still works for power users.

## Risks & Mitigations

| Risk | Mitigation |
|------|------------|
| Email not present in account_menu response shape varies | Defensive recursive scan for any `"email": "<regex>"` node; null on failure, never blocks YTM. |
| Anonymous-Firebase-as-Cloud-Sync-seed loses data if user clears app | Document that backup identity is per-install; legacy Google path remains the durable option. |
| Cookies expire ~monthly | Out of scope (existing manual re-login). Surface a "reconnect" hint if a YTM request starts failing. |
| Two-step (cookie then email fetch) adds ~1s | Run email fetch on IO after cookie capture, toast fires when ready; UI not blocked. |
| User expects "real" Google OAuth for Cloud Sync | Cloud Sync still uses Firebase; email-only seed is a fallback, and the legacy button is kept. |

## Files Touched

| File | Change |
|------|--------|
| `app/src/main/kotlin/com/vinmusic/innertube/YTMusicApi.kt` (or `InnerTube.kt`) | Add `getAccountEmail(ctx)` — POST to YTM account endpoint with cookie, parse email. |
| `app/src/main/kotlin/com/vinmusic/player/AuthViewModel.kt` | Add `connectFromYtmEmail(email)` — anonymous Firebase auth tagged with email; keep `signInWithGoogle` as fallback. |
| `app/src/main/kotlin/com/vinmusic/ui/screens/SettingsScreen.kt` | Unify the two cards into one "Connect Your Account"; chain cookie-capture → email → Cloud Sync; demote legacy Google button to secondary. |
