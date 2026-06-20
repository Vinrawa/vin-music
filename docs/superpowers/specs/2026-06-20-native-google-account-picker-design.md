# Native Google Account Picker → Pre-filled WebView

**Date:** 2026-06-20
**Status:** Approved
**Scope:** `SettingsScreen.kt` only (`showYtWebViewLogin` dialog)

## Background

VinMusic v2 logs into YouTube Music by capturing cookies (`SAPISID` / `__Secure-3PAPISID`) from a WebView that loads `accounts.google.com/ServiceLogin`. Today this means the user must type **both** their email and password every time.

The user asked for a native "Sign in with Google" experience — like other apps where you tap and pick a Google account already on the phone with no password.

### Why a true no-password flow is impossible

YouTube Music login requires **cookies**, not an OAuth token. The native Google SignIn API / Credential Manager / `AccountManager` give you an OAuth `IdToken` (good for Firebase, Drive, Gmail, etc.) — **not** a YTM cookie. Google deprecated the `weblogin:` auth scope around 2020, so there is no public path to obtain YouTube/Google web session cookies from an Android account. Every third-party YTM client (NewPipe, InnerTune, RiMusic, Metrolist) uses cookie paste or WebView login for this exact reason.

## Goal

Remove the email-typing step. Reduce first-login friction by ~half; reduce repeat friction to near-zero (cookie persists after first login).

## Design

### Flow

1. User taps **Connect YouTube Music** → `showYtWebViewLogin = true`.
2. Dialog opens to a **landing card** (not the WebView directly):
   - Primary: **"Continue with Google"** button.
   - Secondary: **"Enter email manually"** text link.
3. **"Continue with Google"** → `AccountManager.newChooseAccountIntent()` (system-level account picker, no permission required). User picks a Google account.
4. Selected email is returned via `rememberLauncherForActivityResult`.
5. WebView launches with `&Email={urlEncodedEmail}` appended to the ServiceLogin URL → user enters **password only**.
6. Existing cookie-capture logic runs unchanged (`SAPISID` → `YTMusicSession.setCookie`).
7. **"Enter email manually"** → WebView launches with no pre-fill (current behavior preserved as graceful fallback).

### Why no permission needed

`AccountManager.newChooseAccountIntent()` is system-mediated. The app never touches the account list directly — the OS picker returns only the account the user explicitly selected. No `GET_ACCOUNTS` permission, no manifest change, works on Android 5.0+ (API 21+).

### State model (local Compose state)

- `loginMode: LoginMode` — enum `{ LANDING, WEBVIEW }`. Default `LANDING`.
- `prefilledEmail: String?` — null for manual entry.
- Account picker result handled by `rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult())`.
- On result success: set `prefilledEmail`, flip `loginMode = WEBVIEW`.
- On result cancelled: stay on `LANDING`.

### Graceful degradation

- If Google's new login flow ignores `&Email=` param → login still works, user just types the email. No worse than today.
- Picker cancelled → return to landing card, no state change.
- Zero Google accounts on phone → system picker offers "Add account" via system UI; we don't need to handle this ourselves.

### URL construction

```
https://accounts.google.com/ServiceLogin?service=youtube&uilel=3&passive=true&continue=https%3A%2F%2Fmusic.youtube.com%2F&Email={URLEncoded(email)}
```

If `prefilledEmail == null`, omit `&Email=`.

## Scope

- **Touches:** `SettingsScreen.kt` only (the `showYtWebViewLogin` dialog block, currently lines 1105–1217).
- **Does not touch:** `YTMusicSession.kt`, `InnerTube.kt`, cookie-capture logic, AndroidManifest.xml.
- **No new permissions.** No new dependencies.

## Out of scope (explicitly deferred)

- Chrome Custom Tabs fallback (Option 2 from brainstorm) — rejected due to silent failure when user isn't signed into Chrome.
- True no-password OAuth flow — impossible for YTM, see Background.
- Suggesting accounts via Credential Manager — `newChooseAccountIntent` is sufficient and simpler.

## Bundled fixes (same commit + build)

These were written in a previous session, never committed/built:
1. **Unison API crash** — `parseUnisonResponse()` handles `data` as JsonArray (search) vs JsonObject (direct). `UnisonClient.kt`.
2. **Progress bar color** — `FullPlayerScreen.kt` line 1154: `activeTrackColor = VinColors.Accent` (was `animatedAccent`).
3. **switchAccount 500ms safety timeout** — `SettingsScreen.kt` (already in code).
