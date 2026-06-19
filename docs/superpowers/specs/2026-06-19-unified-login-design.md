# Unified YouTube Music Login — Single Card (no Cloud Sync bridging)

**Date:** 2026-06-19
**Status:** Design — revised after code review, ready for implementation plan
**Owner:** Vinrawa

## Revision history

- **v1** proposed one unified "Connect Account" that would also seed Cloud Sync
  via anonymous Firebase auth, with email scraped from the YTM session.
- **v2 (this)** drops all Cloud Sync bridging. Two reasons (from code review):
  1. Anonymous Firebase auth yields a **random per-install UID** with no
     relationship to the Google account. Reinstall / second device = new UID
     = orphaned backup. That silently breaks the actual purpose of Cloud Sync
     (durable cross-device data), while the toast `Connected: <email>
     (YTM + Cloud Sync)` would actively mislead the user.
  2. The "real" fix (Firebase Custom Token Auth) is more than a small backend.
     If the backend trusts a **client-supplied** email to mint a token, that's
     an **impersonation hole** — emails are guessable, not secrets. The only
     sound version requires the backend to independently verify the email by
     presenting the user's YTM **cookie** to Google's `account_menu` endpoint
     server-side, which means the backend now handles live session cookies
     (large trust/privacy surface). Not justified for a solo/indie project now.

Cloud Sync stays exactly as-is: its own card, its own durable
`GoogleAuthProvider` flow, untouched. This feature only consolidates the
**YouTube Music** login.

## Problem

Inside the YTM section of Settings there are two separate entry points doing
the same job:

1. **"Sign In with Google"** — opens a WebView to `accounts.google.com →
   music.youtube.com`, captures the `SAPISID`/`__Secure-3PAPISID` cookie via
   `CookieManager`, stores it in `YTMusicSession`.
2. **"Manual Cookie Setup"** — paste a cookie string from a desktop browser.

Both achieve "connect to YouTube Music." Splitting them into two top-level
options is confusing UX. (This is independent of Cloud Sync, which lives in a
different card and uses a completely different auth system.)

## Goal

Merge the two YTM entry points into a single **"Connect to YouTube Music"**
card:

- Primary action: WebView login (the existing `showYtWebViewLogin` flow).
- Secondary action (under an "Advanced" disclosure): Manual Cookie paste.
- On success: show the connected account's email as a read-only label
  (`Connected as <email>`) — **display only**, never used to drive auth.
- Toast: `"YouTube Music connected"` — deliberately does NOT mention Cloud
  Sync, so it can never imply something that isn't true.

## Non-Goals

- **No Cloud Sync changes.** Cloud Sync keeps its own card + its own
  `GoogleSignInClient`/Firebase flow. Durability of cross-device backup is
  preserved exactly. The single-tap unification is scoped to YTM only.
- **No anonymous auth, no email→UID bridging, no custom tokens.** See revision
  history for the security/durability reasoning.
- **No cookie refresh automation.** Cookies expire (~1 month); existing manual
  re-login stays. A "reconnect" hint may be surfaced later if YTM requests
  start failing, but not in this change.

## Architecture

### A. UI consolidation — `SettingsScreen.kt`

Replace the two current YTM options with one card:

```
┌─ Connect to YouTube Music ───────────────┐
│  [icon]  Connect to YouTube Music         │
│          Unlock your YTM home, library    │
│          and liked songs                  │
│                                           │
│          (when connected:)                │
│          Connected as x@gmail.com         │
│          [Disconnect]   [Switch account]  │
│                                           │
│          ▸ Advanced (paste cookie)        │
└───────────────────────────────────────────┘
```

- Primary "Connect" button opens the existing `showYtWebViewLogin` dialog
  (no logic change — same WebView, same cookie capture).
- "Advanced" disclosure expands to reveal the existing manual-cookie
  `OutlinedTextField` + Save. Same `YTMusicSession.setCookie` call.
- Disconnect = existing `YTMusicSession.setCookie(ctx, null)` + clears
  `ytCookieConnected`.

### B. Display-only email — `YTMusicApi.getAccountEmail(ctx)`

Keep the `getAccountEmail` method, but **display-only**:

- POST to `https://music.youtube.com/youtubei/v1/account/account_menu` using
  the existing `buildRequest` (already injects `Cookie` +
  `Authorization: SAPISIDHASH` when a cookie is stored).
- Defensive recursive scan for any node whose key is `"email"` and whose value
  matches an email regex.
- **Multiple-account scoping (reviewer point):** if multiple accounts are
  signed in, the response lists several. Prefer the entry flagged as the
  active/selected account (look for an `isPrimary`/`selected` flag, or the
  first under the `activeAccount` / primary section). If no explicit primary
  marker exists, take the first email match and label it
  `Connected as <email> (1 of N)` so the user can see ambiguity rather than
  silently trust a possibly-wrong account.
- Return `null` on any failure → card shows just `"YouTube Music connected"`
  with no email. Never blocks the connection.

This email is used **only** for the `Connected as <email>` label. It is NOT
passed to Firebase, NOT used to mint any token, NOT written to Cloud Sync.

### C. What stays untouched

- `AuthViewModel`, `FirebaseSyncManager`, the Cloud Sync card, the
  `GoogleSignInClient` launcher — all unchanged.
- `YTMusicSession` cookie storage/`authorizationHeader` — unchanged.

## Data Flow

```
Settings → "Connect to YouTube Music" → Connect
  → WebView: accounts.google.com → music.youtube.com
  → user logs in once (Google account)
  → onPageFinished detects music.youtube.com + SAPISID cookie
     → YTMusicSession.setCookie(ctx, cookies)        [YTM connected]
     → YTMusicApi.getAccountEmail(ctx)               [display only]
        ├─ success → label "Connected as <email>"
        └─ fail    → label "YouTube Music connected"
     → toast: "YouTube Music connected"
     (Cloud Sync card: untouched, not mentioned)

Settings → "Connect to YouTube Music" → Advanced → paste cookie → Save
  → YTMusicSession.setCookie(ctx, pasted)            [YTM connected]
  → (email fetch best-effort, same as above)
```

## Error Handling

- `getAccountEmail` network/parse failure → label degrades to
  `"YouTube Music connected"` (no email). YTM still fully works.
- Multiple accounts with no primary marker → label `Connected as <email>
  (1 of N)`.
- Cookie missing `SAPISID`/`__Secure-3PAPISID` → existing behavior (won't
  capture), no change.
- WebView "403 Disallowed User Agent" → existing desktop User-Agent override
  (`SettingsScreen.kt:1217`) already mitigates.

## Testing (manual)

1. Fresh, not connected → "Connect to YouTube Music" → WebView → login once →
   YTM connected, label shows email, toast says "YouTube Music connected"
   (no Cloud Sync mention).
2. **Cloud Sync untouched:** open Cloud Sync card → it still works exactly as
   before (its own Google button, durable Firebase UID). Reinstall + reconnect
   → backup recovers (existing behavior, not affected by this change).
3. Advanced → paste a cookie → YTM connects, email label best-effort.
4. Multiple Google accounts signed into the WebView → label shows
   `Connected as <email> (1 of N)` when primary can't be determined.
5. `getAccountEmail` forced failure → label degrades to
   `"YouTube Music connected"`, no crash, YTM still works.

## Forward-path note (do NOT build now)

If a true single-login that also drives Cloud Sync is ever wanted, the
**only** sound design is:

> A dedicated backend that receives the **YTM cookie** (not a client-trusted
> email), independently calls Google's `account_menu` server-side to verify
> the email, and only then mints a Firebase custom token tied to that verified
> email. This makes the backend a handler of live session cookies — a real
> security/trust surface that needs dedicated review and maintenance.

Parked as "v2, pending dedicated backend + security review." Documented here
so a future engineer does not re-derive the naive (and vulnerable) "client
sends email → gets token" design.

## Risks & Mitigations

| Risk | Mitigation |
|------|------------|
| Wrong email shown when multiple accounts active | Prefer explicit primary flag; else label `(1 of N)`. Email is display-only, so a wrong label is cosmetic, not a security issue. |
| User expects single tap to also enable backup | Toast and label deliberately say "YouTube Music connected" only; Cloud Sync remains its own clearly-separate card. |
| Cookies expire ~monthly | Out of scope (existing manual re-login). |
| Consolidation breaks existing connected users | No storage format change; `YTMusicSession` cookie persists as before. UI-only restructure. |

## Files Touched

| File | Change |
|------|--------|
| `app/src/main/kotlin/com/vinmusic/innertube/YTMusicApi.kt` | Add `getAccountEmail(ctx)` — display-only email fetch with multi-account scoping. |
| `app/src/main/kotlin/com/vinmusic/ui/screens/SettingsScreen.kt` | Merge the two YTM options into one "Connect to YouTube Music" card; Advanced disclosure for manual cookie; display-only email label. |
