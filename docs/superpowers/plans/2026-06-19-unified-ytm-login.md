# Unified YouTube Music Login Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Merge the two YouTube Music login entry points (WebView "Sign In with Google" + "Manual Cookie Setup") into a single "Connect to YouTube Music" card, with a display-only account email label and a switch-account action. Cloud Sync is untouched.

**Architecture:** YTM-only consolidation. The existing WebView cookie-capture flow is reused as-is. A new `getAccountEmail(ctx)` method calls YTM's `account_menu` endpoint with the stored cookie to fetch the signed-in email for display only. A new "Switch account" action clears Google-domain cookies in `CookieManager` before relaunching the WebView so the account chooser appears. No Firebase/Cloud Sync changes.

**Tech Stack:** Kotlin, Jetpack Compose, OkHttp, Gson, Android `WebView`/`CookieManager`, existing `YTMusicSession` + `YTMusicApi`.

**Spec:** `docs/superpowers/specs/2026-06-19-unified-login-design.md`

---

## File Structure

| File | Responsibility | Change |
|------|----------------|--------|
| `app/src/main/kotlin/com/vinmusic/innertube/YTMusicApi.kt` | New `getAccountEmail(ctx)` — POST to YTM account_menu, defensive recursive scan for primary account email. | Add method |
| `app/src/main/kotlin/com/vinmusic/ui/screens/SettingsScreen.kt` | Merge the two YTM options into one "Connect to YouTube Music" card; Advanced disclosure for manual cookie; display-only email label; Switch account cookie-clearing. | Restructure UI |

No other files change. `AuthViewModel`, `FirebaseSyncManager`, `YTMusicSession`, Cloud Sync card — untouched.

---

## Pre-Implementation Verification (Task 0)

This MUST happen before writing parsing code, because the `account_menu` response shape and primary-account marker field names are reverse-engineered guesses.

### Task 0: Capture a real account_menu response and confirm the email + primary-marker keys

**Files:**
- Read: `app/src/main/kotlin/com/vinmusic/innertube/YTMusicApi.kt` (buildRequest injects cookie/auth at lines 68-87)
- Notes: write findings into the spec's `### B. Display-only email` section inline

- [ ] **Step 1: Build a one-off manual probe**

The app cannot run in this sandbox (uses `android.util.Base64`/device-only APIs), so the probe runs against the live endpoint with curl using a cookie the user supplies. Create a temporary scratch file (NOT committed) to hold the cookie + curl command:

```bash
# User: paste your music.youtube.com cookie (the full string from YTMusicSession)
# then run:
COOKIE="<paste full cookie string here>"
curl -s -m 15 \
  -X POST \
  -H "Content-Type: application/json" \
  -H "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36" \
  -H "Origin: https://music.youtube.com" \
  -H "Referer: https://music.youtube.com/" \
  -H "Cookie: $COOKIE" \
  -H "Authorization: SAPISIDHASH <compute from SAPISID per YTMusicSession.authorizationHeader>" \
  -d '{"context":{"client":{"clientName":"WEB_REMIX","clientVersion":"1.20231214.00.00","hl":"en","gl":"IN"}}}' \
  "https://music.youtube.com/youtubei/v1/account/account_menu" > account_menu_response.json
```

If the SAPISIDHASH is hard to compute in-shell, simplify: temporarily add a log line in the existing `getHomePage` path (which already builds the auth header) to dump the full request headers, OR just rely on the Cookie header alone (some accounts return data with cookie-only auth). Try cookie-only first.

- [ ] **Step 2: Inspect the JSON for the email field and the primary-account marker**

Open `account_menu_response.json`. Look for:
1. The email value — confirm it appears under a key literally named `"email"`.
2. The primary/active marker — check for any of: `isPrimary`, `selected`, `isActive`, `accountType`, or a top-level `activeAccount` wrapper. Note EXACTLY which key (if any) flags the primary account.
3. If multiple accounts are present, note how the array is structured (e.g. `actions[0].getMultiPageMenuAction.menuRenderer.sections[0].accountSectionListRenderer`).

Record the real key names. These replace the guesses (`isPrimary`) in Task 1's `parseAccountEmail()` (NOT Task 3 — the hardcoded `isPrimary` lives in Task 1's parsing code).

- [ ] **Step 3: Clean up the scratch files**

```bash
rm -f account_menu_response.json
# remove the temp cookie file too
```

Expected: scratch files gone, findings written into the spec / carried into Task 3.

- [ ] **Step 4: If the endpoint is unreachable or returns auth error, fall back gracefully**

If no valid cookie is available in this environment, note in the spec that the primary-marker keys remain guesses and Task 3's parser MUST be defensive (recursive scan + `(1 of N)` fallback). Do NOT block the plan — the parser is designed to fail closed.

---

## Implementation Tasks

### Task 1: Add `getAccountEmail(ctx)` to YTMusicApi (defensive, display-only)

**Files:**
- Modify: `app/src/main/kotlin/com/vinmusic/innertube/YTMusicApi.kt` (add method near `getCookie()` at line 119)

- [ ] **Step 1: If Task 0 captured a real primary-marker key, note it**

Task 0's job was to confirm which key (if any) flags the primary account. Before inserting the code below, look at Task 0's recorded findings:
- If a real key was confirmed (e.g. `"isSelected"`, `"accountType": "primary"`), use that in the `PRIMARY_MARKER_KEY` constant below.
- If Task 0 couldn't run (no cookie available) or found no marker, leave `PRIMARY_MARKER_KEY = "isPrimary"` as a best-effort guess — the parser stays defensive and the UI falls back to `(1 of N)` when multiple emails appear with no primary flag.

- [ ] **Step 2: Add the data class + single fetch/scan method**

Insert after the existing `fun getCookie()` (line 119). This replaces the earlier two-method design — one fetch, one recursive scan, returns everything in `AccountInfo`. Avoids the redundant second network round-trip:

```kotlin
/** Display-only account info (email + primary flag + total account count). */
data class AccountInfo(
    val email: String?,
    val isPrimary: Boolean,
    val count: Int,
)

// Best-effort primary-account marker. If Task 0 captured a real key, replace
// this value with it. The parser is defensive — if this key never matches,
// the UI falls back to "(1 of N)" when multiple emails are present.
private const val PRIMARY_MARKER_KEY = "isPrimary"

/**
 * Display-only: fetch the signed-in account's email + count from YTM's
 * account_menu endpoint using the stored cookie. Used ONLY for the
 * "Connected as <email>" label — never fed to Firebase or any auth flow.
 * Returns AccountInfo(email=null, isPrimary=false, count=0) on any failure
 * (the caller degrades gracefully to a no-email label). Single network call.
 */
fun getAccountInfo(ctx: Context): AccountInfo {
    val cookie = YTMusicSession.getCookie(ctx) ?: return AccountInfo(null, false, 0)
    val body = mapOf(
        "context" to webRemixContext(),
        "deviceTheme" to "DEVICE_THEME_SUPPORTED",
        "userInterfaceTheme" to "USER_INTERFACE_THEME_DARK"
    )
    val raw = try {
        buildRequest("$BASE/account/account_menu?prettyPrint=false", body)
            .build().let { http.newCall(it).execute().use { it.body?.string() } }
    } catch (e: Exception) {
        Log.e(TAG, "getAccountInfo request failed: ${e.message}")
        return AccountInfo(null, false, 0)
    } ?: return AccountInfo(null, false, 0)

    return parseAccountInfo(raw)
}

/**
 * Defensive recursive scan of account_menu JSON. Collects every node whose
 * key is "email" with a value matching an email regex, tracking whether it
 * sits under the primary-marker. Returns the primary email if any was flagged,
 * else the first email; count is the number of distinct emails found.
 */
private fun parseAccountInfo(raw: String): AccountInfo {
    return try {
        val root = gson.fromJson(raw, Map::class.java) ?: return AccountInfo(null, false, 0)
        val emailRegex = Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}""")
        val seen = LinkedHashMap<String, Boolean>() // email -> isPrimary

        fun scan(node: Any?, inPrimary: Boolean = false) {
            when (node) {
                is Map<*, *> -> {
                    var primaryHere = inPrimary
                    val primaryFlag = node[PRIMARY_MARKER_KEY]
                    if (primaryFlag is Boolean && primaryFlag) primaryHere = true
                    if (primaryFlag is String && primaryFlag.lowercase() in listOf("primary", "active", "true", "selected")) primaryHere = true

                    val emailVal = node["email"]
                    if (emailVal is String && emailRegex.matches(emailVal)) {
                        // Preserve the strongest primary flag seen for this email.
                        val prev = seen[emailVal] ?: false
                        seen[emailVal] = prev || primaryHere
                    }
                    node.values.forEach { scan(it, primaryHere) }
                }
                is List<*> -> node.forEach { scan(it, inPrimary) }
            }
        }
        scan(root)

        if (seen.isEmpty()) return AccountInfo(null, false, 0)
        val primaryEntry = seen.entries.firstOrNull { it.value }
        val chosen = primaryEntry ?: seen.entries.first()
        AccountInfo(
            email = chosen.key,
            isPrimary = chosen.value,
            count = seen.size
        )
    } catch (e: Exception) {
        Log.e(TAG, "parseAccountInfo failed: ${e.message}")
        AccountInfo(null, false, 0)
    }
}
```

- [ ] **Step 3: Verify it compiles**

Run: `.\gradlew.bat :app:compileReleaseKotlin 2>&1 | Select-String -Pattern "error:|BUILD" | Select-Object -Last 10`
Expected: `BUILD SUCCESSFUL` (no new errors)

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/vinmusic/innertube/YTMusicApi.kt
git commit -m "feat: add display-only getAccountInfo to YTMusicApi (single account_menu scan)"
```

---

### Task 2: Add Switch-account cookie-clearing helper

**Files:**
- Modify: `app/src/main/kotlin/com/vinmusic/ui/screens/SettingsScreen.kt`

- [ ] **Step 1: Add a helper that clears Google cookies before relaunching the WebView**

Near the top of the `SettingsScreen` composable (after the existing `ytCookieConnected` state declarations around line 96), add a remembered lambda. This is what the "Switch account" button will call:

```kotlin
// Clears Google-domain cookies so the WebView shows the account chooser
// instead of silently re-logging-in with the cached account.
//
// IMPORTANT — async race fix: CookieManager.removeAllCookies(callback) is
// asynchronous. If we launch the WebView immediately after calling it, the
// WebView can load BEFORE the cookie store is actually cleared → silent
// re-login with the old account (the exact bug this helper exists to fix).
// So we pass a ValueCallback that defers showYtWebViewLogin = true until the
// clear completes. We also clear ytCookieConnected up-front so the UI
// reflects the disconnected state immediately while the chooser is loading.
val switchAccount = {
    val cm = CookieManager.getInstance()
    ytCookieConnected = false
    ytAccountEmail = null
    cm.removeAllCookies { _ ->
        // Invoked on the main thread once the cookie store is cleared.
        cm.flush()
        showYtWebViewLogin = true
    }
}
```

Note: `removeAllCookies` is the simplest correct call. `removeSessionCookies` is a narrower alternative if testing shows it suffices — but `removeAllCookies` guarantees the chooser appears. If a targeted clear to `accounts.google.com` / `.google.com` is preferred later, swap it in; the behavior (chooser appears) is the acceptance criterion. The `ValueCallback<Boolean>` defers WebView launch until clear completes — do NOT revert to calling `showYtWebViewLogin = true` synchronously after `removeAllCookies`.

- [ ] **Step 2: Verify it compiles**

Run: `.\gradlew.bat :app:compileReleaseKotlin 2>&1 | Select-String -Pattern "error:|BUILD" | Select-Object -Last 10`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/vinmusic/ui/screens/SettingsScreen.kt
git commit -m "feat: add switch-account cookie-clearing helper in SettingsScreen"
```

---

### Task 3: Merge the two YTM entry points into one card

This is the main UI change. Replace the two separate options (the "Sign In with Google" WebView button and the "Manual Cookie Setup" button, currently inside `showYtLoginOptionsDialog`) with a single "Connect to YouTube Music" card.

**Files:**
- Modify: `app/src/main/kotlin/com/vinmusic/ui/screens/SettingsScreen.kt`

- [ ] **Step 1: Read the current YTM section to locate exact code to replace**

Read `app/src/main/kotlin/com/vinmusic/ui/screens/SettingsScreen.kt` around lines 900-1130 to find:
- The `showYtLoginOptionsDialog` block and its two option Boxes ("Sign In with Google" + "Manual Cookie Setup").
- The cookie-connected state display (`ytCookieConnected`).
- The `showYtCookieDialog` block (the manual paste field).

Note the exact line numbers — they'll be the replace target.

- [ ] **Step 2: Add a state for the display-only email + a version counter**

Near the other `remember` state declarations (around line 94-96), add. **Do NOT key the `LaunchedEffect` on `ytCookieConnected` (a boolean)** — switch-account and manual-cookie-paste both re-set the cookie while already connected, so the boolean goes `true → true` and the effect never refires, leaving the label stale. Instead use a monotonically-increasing `ytConnectionVersion` that every successful cookie write increments:

```kotlin
var ytAccountEmail by remember { mutableStateOf<String?>(null) }
var ytAccountCount by remember { mutableStateOf(1) }
var ytConnectionVersion by remember { mutableStateOf(0) }

// Re-fetch the display-only email+count on EVERY connection change, including
// true → true (switch account / re-paste cookie). Keying on the integer
// version (not the boolean) guarantees a refire each time.
LaunchedEffect(ytConnectionVersion) {
    if (ytCookieConnected) {
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            val info = YTMusicApi.getAccountInfo(ctx)
            ytAccountEmail = info.email
            ytAccountCount = info.count.coerceAtLeast(1)
        }
    } else {
        ytAccountEmail = null
        ytAccountCount = 1
    }
}
```

Every place that writes a successful cookie (WebView capture, manual paste Save, switch-account success) MUST also call `ytConnectionVersion++`. Those call sites are wired up in Steps 3, 5, and Task 2's `switchAccount`.

- [ ] **Step 3: Replace the two-option dialog with a single card**

Replace the `showYtLoginOptionsDialog` content (the two Box options) with a single consolidated card. The card has:
- Header: "Connect to YouTube Music" + subtitle.
- When NOT connected: a single "Connect" button → opens `showYtWebViewLogin`.
- When connected: "Connected as <email>" label (or "YouTube Music connected" if email null; append "(1 of N)" if count > 1), plus "Disconnect" and "Switch account" buttons.
- An "Advanced" disclosure row that expands to show the manual-cookie paste field (`showYtCookieDialog` or inline field).

Skeleton (adapt styling to match existing `SettingsScreen` card patterns — reuse `Card`/`Surface`/`Brush.linearGradient` like the Cloud Sync card at lines ~415-477):

```kotlin
// Inside where the YTM section currently renders:
Box(
    modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 20.dp)
        .clip(RoundedCornerShape(20.dp))
        .background(Brush.linearGradient(listOf(Color(0xFF191612), Color(0xFF0A0A0A))))
        .border(1.dp, VinColors.AccentLight.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
        .padding(18.dp)
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                Modifier.size(48.dp).clip(RoundedCornerShape(12.dp))
                    .background(VinColors.AccentLight.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.MusicNote, null, tint = VinColors.AccentLight, modifier = Modifier.size(28.dp))
            }
            Column(Modifier.weight(1f)) {
                Text("Connect to YouTube Music", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = VinColors.Primary)
                Text(
                    if (ytCookieConnected) {
                        val label = ytAccountEmail ?: "YouTube Music connected"
                        if (ytAccountCount > 1 && ytAccountEmail != null) "$label (1 of $ytAccountCount)" else label
                    } else "Unlock your YTM home, library and liked songs",
                    fontSize = 12.sp, color = VinColors.Secondary, lineHeight = 16.sp
                )
            }
        }

        if (!ytCookieConnected) {
            Button(
                onClick = { showYtWebViewLogin = true },
                colors = ButtonDefaults.buttonColors(containerColor = VinColors.Accent),
                shape = RoundedCornerShape(10.dp)
            ) { Text("Connect", color = Color.White, fontWeight = FontWeight.Bold) }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                onClick = {
                    // CONSCIOUS DECISION: Disconnect only clears the app's
                    // stored cookie — it does NOT clear WebView-level Google
                    // cookies. This is intentional and distinct from Switch
                    // account (which DOES clear them to force the chooser).
                    // Disconnect = "stop using this session in the app";
                    // reconnecting afterwards may silently resume the same
                    // account. If a future change wants Disconnect to also
                    // force re-auth, add removeAllCookies here — but then
                    // Switch account becomes redundant, so pick one.
                    YTMusicSession.setCookie(ctx, null)
                    ytCookieConnected = false
                    RecommendationManager.invalidateCache()
                },
                colors = ButtonDefaults.buttonColors(containerColor = VinColors.White10),
                shape = RoundedCornerShape(10.dp)
            ) { Text("Disconnect", color = VinColors.Primary) }
                Button(
                    onClick = switchAccount,
                    colors = ButtonDefaults.buttonColors(containerColor = VinColors.White10),
                    shape = RoundedCornerShape(10.dp)
                ) { Text("Switch account", color = VinColors.Primary) }
            }
        }

        // Advanced: manual cookie paste (collapsible)
        var showAdvanced by remember { mutableStateOf(false) }
        Row(
            modifier = Modifier.fillMaxWidth().clickable { showAdvanced = !showAdvanced }.padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(if (showAdvanced) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight, null, tint = VinColors.Secondary, modifier = Modifier.size(18.dp))
            Text(if (showAdvanced) "Hide advanced" else "Advanced (paste cookie)", fontSize = 12.sp, color = VinColors.Secondary)
        }
        if (showAdvanced) {
            // Reuse the existing manual-cookie OutlinedTextField + Save logic
            // that was in showYtCookieDialog. Inline it here.
            OutlinedTextField(
                value = ytCookieDraft,
                onValueChange = { ytCookieDraft = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Paste music.youtube.com cookie...", color = VinColors.Secondary, fontSize = 12.sp) },
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, color = VinColors.Primary),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = VinColors.Accent, unfocusedBorderColor = VinColors.GlassBorder, focusedContainerColor = VinColors.White10, unfocusedContainerColor = VinColors.White10)
            )
            Button(
                onClick = {
                    YTMusicSession.setCookie(ctx, ytCookieDraft)
                    ytCookieConnected = true
                    ytConnectionVersion++   // refire email fetch (true→true case)
                    RecommendationManager.invalidateCache()
                },
                colors = ButtonDefaults.buttonColors(containerColor = VinColors.Accent),
                shape = RoundedCornerShape(10.dp)
            ) { Text("Save cookie", color = Color.White) }
        }
    }
}
```

- [ ] **Step 4: Remove the now-redundant `showYtLoginOptionsDialog` and `showYtCookieDialog` blocks**

Delete the old `if (showYtLoginOptionsDialog) { ... }` block (the two-option picker) and the old `if (showYtCookieDialog) { ... }` block (its field moved inline into the Advanced disclosure above). Keep `showYtWebViewLogin` exactly as-is.

- [ ] **Step 5: Update the toast + version counter on successful WebView login (deduped)**

The existing cookie-capture logic is **duplicated** in `onPageStarted` AND `onPageFinished` (pre-existing resilience — `onPageFinished` sometimes doesn't fire on flaky networks). Naively adding `ytConnectionVersion++` to both would fire the email fetch twice per login. Dedupe with a navigation-scoped guard: a `var captureHandledForUrl` that records the last URL we captured for, so the second handler sees it's already done and skips.

In the existing `WebViewClient` block (around line 1220), three changes:

1. Add a guard var at the top of the `webViewClient = object : WebViewClient() {` block (before `onPageStarted`):

```kotlin
webViewClient = object : WebViewClient() {
    // Dedupe cookie capture across onPageStarted + onPageFinished for the
    // same navigation. Without this, adding version++ to both handlers
    // would fire the email fetch twice per login (harmless but wasteful).
    var captureHandledForUrl: String? = null

    override fun onPageStarted(...) { ... }
    override fun onPageFinished(...) { ... }
}
```

2. In BOTH capture blocks (`onPageStarted` ~line 1224 and `onPageFinished` ~line 1242), wrap the existing capture in the guard AND add the toast change + version bump. The capture block becomes (same in both handlers):

```kotlin
if (url != null && url.contains("music.youtube.com") &&
    url != captureHandledForUrl  // dedupe: skip if already handled this nav
) {
    val cookies = CookieManager.getInstance().getCookie("https://music.youtube.com")
    if (cookies != null && (cookies.contains("SAPISID") || cookies.contains("__Secure-3PAPISID") || cookies.contains("__Secure-1PAPISID"))) {
        captureHandledForUrl = url   // mark handled for this navigation
        YTMusicSession.setCookie(context, cookies)
        CookieManager.getInstance().flush()
        ytCookieDraft = cookies
        ytCookieConnected = true
        ytConnectionVersion++        // refire email fetch (true→true case), once per nav
        RecommendationManager.invalidateCache()
        context.getSharedPreferences("vin_music_repository_cache", Context.MODE_PRIVATE).edit().clear().apply()
        showYtWebViewLogin = false
        Toast.makeText(context, "YouTube Music connected", Toast.LENGTH_LONG).show()
    }
}
```

Deliberately no Cloud Sync mention in the toast. The `captureHandledForUrl` guard is scoped to the WebView instance, resets naturally when the dialog is recreated (new login session), so it never wrongly suppresses a genuinely new login.

- [ ] **Step 6: Verify it compiles**

Run: `.\gradlew.bat :app:compileReleaseKotlin 2>&1 | Select-String -Pattern "error:|BUILD" | Select-Object -Last 10`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/vinmusic/ui/screens/SettingsScreen.kt
git commit -m "feat: merge YTM login into single 'Connect to YouTube Music' card

- One card replaces the two-option dialog (WebView + manual cookie)
- Display-only 'Connected as <email>' label via getAccountEmail
- '(1 of N)' suffix when multiple accounts ambiguous
- Switch account clears Google cookies to force account chooser
- Advanced disclosure for manual cookie paste
- Toast says 'YouTube Music connected' (no Cloud Sync implication)"
```

---

### Task 4: Manual verification (no automated tests in this codebase)

There are no instrumented/unit test facilities for this WebView-driven flow in the project. Verification is manual on a device.

**Files:** none

- [ ] **Step 1: Build the release APK**

Run: `.\gradlew.bat :app:assembleRelease`
Expected: `BUILD SUCCESSFUL`, APK produced.

- [ ] **Step 2: Manual test checklist on device**

Run through each case from the spec's Testing section:

1. Fresh install, not connected → "Connect to YouTube Music" → WebView → login once → YTM connected, label shows email, toast says "YouTube Music connected".
2. Cloud Sync card still works as before (its own Google button, durable). Reinstall + reconnect → backup recovers.
3. Advanced → paste a cookie → YTM connects, email label best-effort.
4. Multiple accounts → label `Connected as <email> (1 of N)`.
5. `getAccountInfo` failure (airplane mode right after cookie capture) → label degrades to "YouTube Music connected", no crash.
6. Switch account → Google cookies cleared → account chooser visible → different account → label updates. **Repeat this 3× back-to-back** (re-connect, switch, re-connect, switch): `removeAllCookies` is async, so this specifically verifies the WebView doesn't occasionally load before the clear completes and silently re-login with the old account.
7. Stale-label regression check: while already connected, paste a NEW cookie via Advanced → Save → label must update to the new account's email (this is the `true → true` case Fix #1 targets; without `ytConnectionVersion++` it stays stale).

- [ ] **Step 3: Report results**

For each case, note PASS/FAIL. If any FAIL, file the specific symptom before considering the feature done.

---

## Self-Review Checklist (done by plan author)

**1. Spec coverage:**
- ✅ Merge two YTM options → Task 3
- ✅ Display-only email → Task 1 (getAccountInfo) + Task 3 (label)
- ✅ Multi-account (1 of N) → Task 1 (AccountInfo.count) + Task 3 (suffix)
- ✅ Switch account cookie clearing → Task 2 + Task 3 (button)
- ✅ Advanced disclosure manual cookie → Task 3
- ✅ Toast wording → Task 3 Step 5
- ✅ Cloud Sync untouched → explicitly out of scope, no task touches it
- ✅ Forward-path note (backend cookie-verify) → spec only, not built
- ✅ Stale-label fix (true→true) → Task 3 Step 2 (version counter) + Steps 3/5 (ytConnectionVersion++)
- ✅ Switch-account async race → Task 2 (ValueCallback defers WebView launch) + Task 4 test 6 (3× repeat)

**2. Placeholder scan:** No TBD/TODO. All code blocks complete. The one genuine unknown (account_menu primary-marker key) is handled by Task 0 (capture + confirm) with an explicit "replace `PRIMARY_MARKER_KEY`" step in Task 1 and a defensive fallback if Task 0 can't run.

**3. Type consistency:** `getAccountInfo(ctx): AccountInfo` defined in Task 1 (single method, single fetch), used in Task 3 Step 2 with matching signature. `AccountInfo(email, isPrimary, count)` fields consumed in Task 3. `switchAccount` lambda defined Task 2 (now takes the async-safe form), used Task 3. `ytAccountEmail`/`ytAccountCount`/`ytConnectionVersion` declared Task 3 Step 2; `ytConnectionVersion++` added in Steps 3 (manual paste) and 5 (WebView success) — covers both `true→true` paths.
