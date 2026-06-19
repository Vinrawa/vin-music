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

Record the real key names. These replace the guesses (`isPrimary`/`selected`/`activeAccount`) in Task 3.

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

- [ ] **Step 1: Add the method**

Insert after the existing `fun getCookie()` (line 119) so it sits with the other account-related helpers:

```kotlin
/**
 * Display-only: fetch the signed-in account's email from YTM's account_menu
 * endpoint using the stored cookie. Used ONLY for the "Connected as <email>"
 * label — never fed to Firebase or any auth flow. Returns null on any failure
 * (the caller degrades gracefully to a no-email label).
 *
 * NOTE: the primary-account marker key was captured from a real response in
 * Task 0. If that verification wasn't possible, parsing stays defensive
 * (recursive email scan + "(1 of N)" fallback).
 */
fun getAccountEmail(ctx: Context): String? {
    val cookie = YTMusicSession.getCookie(ctx) ?: return null
    // Build an authenticated POST. buildRequest already injects Cookie +
    // Authorization: SAPISIDHASH, so we reuse it with an empty body context.
    val body = mapOf(
        "context" to webRemixContext(),
        "deviceTheme" to "DEVICE_THEME_SUPPORTED",
        "userInterfaceTheme" to "USER_INTERFACE_THEME_DARK"
    )
    val raw = try {
        buildRequest("$BASE/account/account_menu?prettyPrint=false", body)
            .build().let { http.newCall(it).execute().use { it.body?.string() } }
    } catch (e: Exception) {
        Log.e(TAG, "getAccountEmail request failed: ${e.message}")
        return null
    } ?: return null

    return parseAccountEmail(raw)
}

/**
 * Defensive recursive scan of the account_menu JSON. Looks for any node whose
 * key is "email" with a value matching an email regex. Collects ALL matches
 * (multiple accounts possible). If exactly one, returns it. If several, tries
 * to pick the one flagged primary per Task 0's confirmed key; if no primary
 * marker, returns the first and the caller appends " (1 of N)".
 */
private fun parseAccountEmail(raw: String): String? {
    return try {
        val root = gson.fromJson(raw, Map::class.java) ?: return null
        val emailRegex = Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}""")
        val emails = mutableListOf<Pair<String, Boolean>>() // (email, isPrimary)

        fun scan(node: Any?, inPrimary: Boolean = false) {
            when (node) {
                is Map<*, *> -> {
                    var primaryHere = inPrimary
                    // Primary marker — replace this key name with the one
                    // confirmed in Task 0. Common candidates: isPrimary, selected,
                    // isActive. Check truthy boolean OR "primary"/"active" string.
                    val primaryFlag = node["isPrimary"]
                    if (primaryFlag is Boolean && primaryFlag) primaryHere = true
                    if (primaryFlag is String && primaryFlag.lowercase() in listOf("primary", "active", "true")) primaryHere = true

                    val emailVal = node["email"]
                    if (emailVal is String && emailRegex.matches(emailVal)) {
                        emails.add(emailVal to primaryHere)
                    }
                    node.values.forEach { scan(it, primaryHere) }
                }
                is List<*> -> node.forEach { scan(it, inPrimary) }
            }
        }
        scan(root)

        when {
            emails.isEmpty() -> null
            emails.any { it.second } -> emails.first { it.second }.first
            else -> emails.first().first
        }
    } catch (e: Exception) {
        Log.e(TAG, "parseAccountEmail failed: ${e.message}")
        null
    }
}

/** How many distinct emails were found — used by the UI to append "(1 of N)". */
fun getAccountEmailCount(ctx: Context): Int {
    val cookie = YTMusicSession.getCookie(ctx) ?: return 0
    val body = mapOf(
        "context" to webRemixContext(),
        "deviceTheme" to "DEVICE_THEME_SUPPORTED",
        "userInterfaceTheme" to "USER_INTERFACE_THEME_DARK"
    )
    val raw = try {
        buildRequest("$BASE/account/account_menu?prettyPrint=false", body)
            .build().let { http.newCall(it).execute().use { it.body?.string() } }
    } catch (e: Exception) { return 0 } ?: return 0
    return countEmails(raw)
}

private fun countEmails(raw: String): Int {
    return try {
        val root = gson.fromJson(raw, Map::class.java) ?: return 0
        val emailRegex = Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}""")
        val seen = mutableSetOf<String>()
        fun scan(node: Any?) {
            when (node) {
                is Map<*, *> -> {
                    val e = node["email"]
                    if (e is String && emailRegex.matches(e)) seen.add(e)
                    node.values.forEach { scan(it) }
                }
                is List<*> -> node.forEach { scan(it) }
            }
        }
        scan(root)
        seen.size
    } catch (_: Exception) { 0 }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `.\gradlew.bat :app:compileReleaseKotlin 2>&1 | Select-String -Pattern "error:|BUILD" | Select-Object -Last 10`
Expected: `BUILD SUCCESSFUL` (no new errors)

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/vinmusic/innertube/YTMusicApi.kt
git commit -m "feat: add display-only getAccountEmail to YTMusicApi (account_menu scan)"
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
val switchAccount = {
    val cm = CookieManager.getInstance()
    cm.removeAllCookies(null)
    cm.flush()
    showYtWebViewLogin = true
}
```

Note: `removeAllCookies` is the simplest correct call. `removeSessionCookies` is a narrower alternative if testing shows it suffices — but `removeAllCookies` guarantees the chooser appears. If a targeted clear to `accounts.google.com` / `.google.com` is preferred later, swap it in; the behavior (chooser appears) is the acceptance criterion.

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

- [ ] **Step 2: Add a state for the display-only email + account count**

Near the other `remember` state declarations (around line 94-96), add:

```kotlin
var ytAccountEmail by remember { mutableStateOf<String?>(null) }
var ytAccountCount by remember { mutableStateOf(1) }

// Refresh the display-only email whenever connection state changes.
LaunchedEffect(ytCookieConnected) {
    if (ytCookieConnected) {
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            ytAccountEmail = YTMusicApi.getAccountEmail(ctx)
            ytAccountCount = YTMusicApi.getAccountEmailCount(ctx).coerceAtLeast(1)
        }
    } else {
        ytAccountEmail = null
        ytAccountCount = 1
    }
}
```

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

- [ ] **Step 5: Update the toast on successful WebView login**

In the existing cookie-capture block (around line 1234 / 1252 — the `onPageFinished` / `shouldOverrideUrlLoading` handlers that call `YTMusicSession.setCookie`), the toast currently says `"Google YouTube Music Login Successful!"`. Change it to:

```kotlin
Toast.makeText(context, "YouTube Music connected", Toast.LENGTH_LONG).show()
```

Deliberately no Cloud Sync mention.

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
5. `getAccountEmail` failure (airplane mode right after cookie capture) → label degrades to "YouTube Music connected", no crash.
6. Switch account → Google cookies cleared → account chooser visible → different account → label updates.

- [ ] **Step 3: Report results**

For each case, note PASS/FAIL. If any FAIL, file the specific symptom before considering the feature done.

---

## Self-Review Checklist (done by plan author)

**1. Spec coverage:**
- ✅ Merge two YTM options → Task 3
- ✅ Display-only email → Task 1 (getAccountEmail) + Task 3 (label)
- ✅ Multi-account (1 of N) → Task 1 (countEmails) + Task 3 (suffix)
- ✅ Switch account cookie clearing → Task 2 + Task 3 (button)
- ✅ Advanced disclosure manual cookie → Task 3
- ✅ Toast wording → Task 3 Step 5
- ✅ Cloud Sync untouched → explicitly out of scope, no task touches it
- ✅ Forward-path note (backend cookie-verify) → spec only, not built

**2. Placeholder scan:** No TBD/TODO. All code blocks complete. The one genuine unknown (account_menu primary-marker key) is handled by Task 0 (capture + confirm) with a defensive fallback in Task 1's parser if Task 0 can't run.

**3. Type consistency:** `getAccountEmail(ctx): String?` and `getAccountEmailCount(ctx): Int` defined in Task 1, used in Task 3 with matching signatures. `switchAccount` lambda defined Task 2, used Task 3. `ytAccountEmail`/`ytAccountCount` declared Task 3 Step 2, used Step 3.
