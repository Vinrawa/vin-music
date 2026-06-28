---
name: vin-build-test-install
description: Run the full Android build-test-install cycle for Vin Music v2: compile Kotlin, run recommendation unit tests, parse XML results, build release APK, and install via ADB.
---

# Vin Music v2 — Build, Test & Install Cycle

Reusable workflow for the core Android development loop. Covers compile → test → APK build → device install with proper error handling at each stage.

## Prerequisites

- Android SDK installed at `$env:LOCALAPPDATA\Android\Sdk` (or `C:\Users\ASUS\AppData\Local\Android\Sdk`)
- ADB available at `platform-tools\adb.exe`
- Project at `D:\ASUS\Documents\vin-music-v2`
- Gradle wrapper (`gradlew.bat`) in project root

## Workflow Steps

### 1. Check ADB connection

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" devices
```

If no device listed, prompt user to connect or enable USB debugging.

### 2. Compile Kotlin

```powershell
.\gradlew :app:compileDebugKotlin 2>&1 | Select-String -Pattern "BUILD"
```

- Timeout: 300 seconds
- Working directory: `D:\ASUS\Documents\vin-music-v2`
- If BUILD SUCCESSFUL → proceed to tests
- If errors → read error output, fix, re-compile

### 3. Run recommendation unit tests

```powershell
.\gradlew :app:testDebugUnitTest --tests "com.vinmusic.recommendation.*" 2>&1 | Select-String -Pattern "BUILD"
```

- Timeout: 300 seconds
- Can narrow to specific test class: `--tests "com.vinmusic.recommendation.RecommendationScoringTest"`

### 4. Parse test XML results

```powershell
Get-ChildItem "D:\ASUS\Documents\vin-music-v2\app\build\test-results\testDebugUnitTest" -Filter "*.xml" | ForEach-Object {
    [xml](Get-Content $_.FullName)
} | ForEach-Object {
    $_.testsuite | ForEach-Object {
        "Suite: $($_.name) | Tests: $($_.tests) | Failures: $($_.failures) | Errors: $($_.errors) | Time: $($_.time)s"
    }
}
```

- Check for failures: `$_.failures -gt 0`
- If failures → read specific test case XML for error details
- Common failure causes: renamed functions, changed imports, updated assertions

### 5. Build release APK

```powershell
.\gradlew :app:assembleRelease 2>&1 | Select-String -Pattern "BUILD"
```

- Timeout: 300 seconds
- Output APK: `app\build\outputs\apk\release\app-release.apk`
- Check APK size: should be ~37-43 MB (with 53 MB bundled DB compresses ~70%)

### 6. Install on device

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" install -r "D:\ASUS\Documents\vin-music-v2\app\build\outputs\apk\release\app-release.apk"
```

- `-r` flag replaces existing installation
- If device not found → re-run step 1

## Error Recovery

- **Compile errors after edit**: Read the error line, check for unresolved references (common after renames), fix import/usage
- **Test failures**: Parse XML for specific failure message, check if assertion needs updating
- **APK too large**: Check asset sizes (recommendations.db + genre_graph.json), may need to slim DB
- **ADB device offline**: Kill and restart ADB server (`adb kill-server && adb start-server`)

## Notes

- The 131 tests across 6 test classes are in `app/src/test/kotlin/com/vinmusic/recommendation/`
- Still missing tests for: `findAcousticallySimilarTracks`, `buildAcousticQueriesForSeed`, `areGenresSimilar`, `getGenreFamily`, `loadGenreGraph`
- DB version skip (v3→v5) is safe — `fallbackToDestructiveMigration()` on read-only reference dataset
