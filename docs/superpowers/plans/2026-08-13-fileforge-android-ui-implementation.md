# FileForge Foreground Service and Material You UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Run optimization reliably in a cancellable foreground service and deliver Material You Optimize, Restore, and About experiences with Samsung-focused dark modes and manual update checking.

**Architecture:** A started-and-bound `OptimizationService` owns engine lifetime and persists immutable run state. `MainActivity` hosts three Material Components screen controllers behind bottom navigation. Theme, update, notification, and UI concerns remain separate from the core engine.

**Tech Stack:** Android SDK 35, Kotlin, Material Components for Android, AppCompat, platform foreground services/notifications, SAF, `HttpURLConnection`, GitHub Releases REST API, JUnit and AndroidX instrumentation.

## Global Constraints

- Depends on the completed FileForge core implementation plan.
- Keep `applicationId 'com.fileforge.optimizer'`, `minSdk 26`, `targetSdk 35`, and Java/Kotlin 17.
- Foreground work starts only from a visible user action and uses `mediaProcessing` where available.
- Request `POST_NOTIFICATIONS` only in context on Android 13+; denial must not crash the run.
- Do not add root, broad storage, background polling, analytics, embedded GitHub tokens, silent downloads, or APK-install permissions.
- Theme choices are exactly System, Light, Dark, and AMOLED and persist across restarts.
- About credit is exactly `Created by Zachary Kirke` and links to `https://github.com/Zfkirke0109`.
- Update checks occur only on button press and open a browser release page.
- Every task follows red-green-refactor and ends in a focused commit.

---

## File Structure

- Modify `app/build.gradle`: Material/AppCompat and Android test dependencies.
- Create `app/src/main/java/com/fileforge/optimizer/FileForgeApplication.kt`: dynamic colors and notification channel initialization.
- Create `app/src/main/java/com/fileforge/optimizer/ThemePreferences.kt`: theme persistence/application.
- Create `app/src/main/java/com/fileforge/optimizer/RunStateRepository.kt`: latest progress and terminal report.
- Create `app/src/main/java/com/fileforge/optimizer/OptimizationNotification.kt`: channel and notification rendering.
- Create `app/src/main/java/com/fileforge/optimizer/OptimizationService.kt`: started/bound service and cancellation.
- Rewrite `app/src/main/java/com/fileforge/optimizer/MainActivity.kt`: Material host and navigation.
- Create `app/src/main/java/com/fileforge/optimizer/OptimizeScreenController.kt`.
- Create `app/src/main/java/com/fileforge/optimizer/RestoreScreenController.kt`.
- Create `app/src/main/java/com/fileforge/optimizer/AboutScreenController.kt`.
- Create `app/src/main/java/com/fileforge/optimizer/UpdateChecker.kt`.
- Replace `app/src/main/res/values/styles.xml` with focused theme/string/color resources.
- Add Android tests under `app/src/androidTest/java/com/fileforge/optimizer/`.
- Modify `app/src/main/AndroidManifest.xml`.

### Task 1: Material 3 Theme and Samsung Dark Modes

**Files:**
- Modify: `app/build.gradle`
- Create: `app/src/main/java/com/fileforge/optimizer/FileForgeApplication.kt`
- Create: `app/src/main/java/com/fileforge/optimizer/ThemePreferences.kt`
- Create: `app/src/main/res/values/themes.xml`
- Create: `app/src/main/res/values/colors.xml`
- Create: `app/src/main/res/values-night/colors.xml`
- Create: `app/src/main/res/values/strings.xml`
- Delete after replacement: `app/src/main/res/values/styles.xml`
- Test: `app/src/test/java/com/fileforge/optimizer/ThemePreferencesTest.kt`

**Interfaces:**
- Produces: `ThemeMode { SYSTEM, LIGHT, DARK, AMOLED }`.
- Produces: `ThemePreferences.read()`, `save(mode)`, and `apply(mode)`.

- [ ] **Step 1: Write failing theme mapping tests**

```kotlin
@Test fun persistsAllSupportedModes() {
    ThemeMode.entries.forEach { mode ->
        store.save(mode)
        assertEquals(mode, store.read())
    }
}
```

- [ ] **Step 2: Run focused tests and verify red**

Run: `gradle :app:testDebugUnitTest --tests '*ThemePreferencesTest'`

- [ ] **Step 3: Add Material dependencies and theme resources**

Add pinned dependencies compatible with SDK 35:

```groovy
implementation 'androidx.appcompat:appcompat:1.7.1'
implementation 'com.google.android.material:material:1.13.0'
androidTestImplementation 'androidx.test.ext:junit:1.2.1'
androidTestImplementation 'androidx.test.espresso:espresso-core:3.6.1'
```

Base the app on `Theme.Material3.DayNight.NoActionBar`, declare a dynamic DayNight overlay, use theme attributes instead of hardcoded widget colors, and define an AMOLED overlay with `#08090B` background and `#111318` surface containers while retaining accessible on-surface contrast.

- [ ] **Step 4: Apply dynamic colors and persistent mode**

In `FileForgeApplication.onCreate`, call `DynamicColors.applyToActivitiesIfAvailable(this)`. Apply the saved AppCompat night mode before activity content creation. AMOLED uses the dark mode plus the AMOLED theme overlay.

- [ ] **Step 5: Run tests and build resource merge**

Run: `gradle :app:testDebugUnitTest --tests '*ThemePreferencesTest' :app:processDebugResources`

Expected: PASS and no missing Material attributes.

- [ ] **Step 6: Commit**

```bash
git add app/build.gradle app/src/main/java/com/fileforge/optimizer/FileForgeApplication.kt app/src/main/java/com/fileforge/optimizer/ThemePreferences.kt app/src/main/res
git commit -m "feat: add Material You and AMOLED themes"
```

### Task 2: Run State and Foreground Notification

**Files:**
- Create: `app/src/main/java/com/fileforge/optimizer/RunStateRepository.kt`
- Create: `app/src/main/java/com/fileforge/optimizer/OptimizationNotification.kt`
- Test: `app/src/test/java/com/fileforge/optimizer/RunStateRepositoryTest.kt`
- Test: `app/src/test/java/com/fileforge/optimizer/NotificationProgressTest.kt`

**Interfaces:**
- Consumes: `ProgressSnapshot`, `OptimizationReport`, `RunStatus` from the core plan.
- Produces: `RunState.Idle`, `Running`, `Terminal`; `RunStateRepository.observe(listener)` and `publish(state)`.
- Produces: `OptimizationNotification.render(state): NotificationSpec` and stable IDs/channel constants.

- [ ] **Step 1: Write failing state and notification projection tests**

```kotlin
@Test fun dryRunNotificationSaysAnalyzingAndShowsPotentialSavings() {
    val spec = renderer.render(runningDryRunSnapshot)
    assertEquals("Analyzing files", spec.title)
    assertTrue(spec.text.contains("potential"))
    assertTrue(spec.hasCancelAction)
}
```

- [ ] **Step 2: Run focused tests and verify red**

Run: `gradle :app:testDebugUnitTest --tests '*RunStateRepositoryTest' --tests '*NotificationProgressTest'`

- [ ] **Step 3: Implement immutable state and throttled notification specs**

Persist the last terminal report as JSON in app-private preferences. Publish running snapshots in process. Render determinate progress only when total files is positive; otherwise render indeterminate. Limit notification updates to at most four per second while always delivering terminal state.

- [ ] **Step 4: Create the low-importance notification channel**

Use channel ID `fileforge_optimization`, display name `Optimization progress`, `IMPORTANCE_LOW`, no sound, and a stable notification ID. The Cancel action targets `OptimizationService.ACTION_CANCEL` with an immutable/update-current `PendingIntent`.

- [ ] **Step 5: Run focused tests and commit**

```bash
gradle :app:testDebugUnitTest --tests '*RunStateRepositoryTest' --tests '*NotificationProgressTest'
git add app/src/main/java/com/fileforge/optimizer/RunStateRepository.kt app/src/main/java/com/fileforge/optimizer/OptimizationNotification.kt app/src/test
git commit -m "feat: model persistent notification progress"
```

### Task 3: Started-and-Bound Optimization Service

**Files:**
- Create: `app/src/main/java/com/fileforge/optimizer/OptimizationService.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Test: `app/src/androidTest/java/com/fileforge/optimizer/OptimizationServiceTest.kt`

**Interfaces:**
- Consumes: engine, cancellation, and state/notification types.
- Produces: `OptimizationBinder.currentState`, `addListener`, `removeListener`.
- Produces actions: `ACTION_START`, `ACTION_RESTORE`, `ACTION_CANCEL`; extras: tree URI, serialized `RunIntent`, undo-log identifier, and restore selection.

- [ ] **Step 1: Write failing service tests**

Test foreground entry, optimization and restore actions, start rejection while already running, listener rebinding, Cancel action, terminal persistence, denied notification permission behavior, and `onTimeout` cancellation on API 35.

- [ ] **Step 2: Run instrumentation target and verify red**

Run: `gradle :app:assembleDebug :app:assembleDebugAndroidTest`

Expected: compilation fails because the service/binder are absent.

- [ ] **Step 3: Implement lifecycle ownership**

Call `startForegroundService` from the activity, call `startForeground` immediately in `onStartCommand`, execute one run on a single-thread executor, and return `START_NOT_STICKY`. Hold one atomic cancellation source. Complete/rollback the active file before terminal cancellation. Stop foreground and self only after publishing the terminal report.

- [ ] **Step 4: Implement Android 15 timeout and manifest rules**

Declare `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PROCESSING`, and `POST_NOTIFICATIONS`. Declare the non-exported service with `android:foregroundServiceType="mediaProcessing"`. Override API-35 timeout handling to cancel, publish an actionable failure/cancel summary, and stop promptly.

- [ ] **Step 5: Compile instrumentation tests and commit**

```bash
gradle :app:assembleDebug :app:assembleDebugAndroidTest
git add app/src/main/java/com/fileforge/optimizer/OptimizationService.kt app/src/main/AndroidManifest.xml app/src/androidTest
git commit -m "feat: run optimization in a foreground service"
```

### Task 4: Material Optimize Host and Live Progress

**Files:**
- Rewrite: `app/src/main/java/com/fileforge/optimizer/MainActivity.kt`
- Create: `app/src/main/java/com/fileforge/optimizer/OptimizeScreenController.kt`
- Test: `app/src/androidTest/java/com/fileforge/optimizer/OptimizeScreenTest.kt`

**Interfaces:**
- Consumes: service binder, `RunIntent`, theme preferences.
- Produces: Optimize screen start/cancel and notification permission workflows.

- [ ] **Step 1: Write failing UI assertions**

Assert presence of folder selection, Safe/Aggressive single selection, Dry run switch, APK Lab switch, text-minify switch, Start, Cancel, Restore navigation, About navigation, and live counters. Assert Start is disabled without a writable selected tree and while another run is active.

- [ ] **Step 2: Build tests and verify red**

Run: `gradle :app:assembleDebugAndroidTest`

- [ ] **Step 3: Build Material host and bottom navigation**

Use `MaterialToolbar`, a content container, and `BottomNavigationView` items `Optimize`, `Restore`, and `About`. Use Material cards, switches, segmented/single-selection mode controls, linear progress indicator, and accessible content descriptions. Apply edge-to-edge insets without placing controls under S23 Ultra system bars.

- [ ] **Step 4: Bind live state and permission request**

Bind in `onStart`, unbind in `onStop`, and never stop the service from activity lifecycle callbacks. Request `POST_NOTIFICATIONS` immediately before the first long run on API 33+ and explain denial. Restore selected tree URI and settings from preferences.

- [ ] **Step 5: Compile and commit**

```bash
gradle :app:assembleDebug :app:assembleDebugAndroidTest
git add app/src/main/java/com/fileforge/optimizer/MainActivity.kt app/src/main/java/com/fileforge/optimizer/OptimizeScreenController.kt app/src/androidTest
git commit -m "feat: add Material optimize progress screen"
```

### Task 5: Restore Destination

**Files:**
- Create: `app/src/main/java/com/fileforge/optimizer/RestoreScreenController.kt`
- Test: `app/src/androidTest/java/com/fileforge/optimizer/RestoreScreenTest.kt`

**Interfaces:**
- Consumes: `UndoLogRepository`, `RestoreCoordinator`, selected SAF root.
- Produces: log discovery, entry selection, confirmation, progress, and receipt summary UI.

- [ ] **Step 1: Write failing restore UI tests**

Assert empty state, v2/legacy badges, run date, entry count, recoverable bytes, select-all/individual selection, confirmation dialog, hash mismatch display, partial success summary, and disabled restore while optimization is running.

- [ ] **Step 2: Build test target and verify red**

Run: `gradle :app:assembleDebugAndroidTest`

- [ ] **Step 3: Implement discovery and selection UI**

List only recognized undo logs at the selected root. Parse off the main thread. Present each run in a Material card and entries in a selectable list. Confirmation states exact file count and that backups/logs remain after restore.

- [ ] **Step 4: Run restore through cancellable background execution**

Start `OptimizationService.ACTION_RESTORE` with the selected undo-log identifier and entry IDs. Reuse the foreground progress notification and cancellation token, publish independent per-entry results, and show the receipt path. Activity visibility must not own restore lifetime.

- [ ] **Step 5: Compile and commit**

```bash
gradle :app:assembleDebug :app:assembleDebugAndroidTest
git add app/src/main/java/com/fileforge/optimizer/RestoreScreenController.kt app/src/androidTest/java/com/fileforge/optimizer/RestoreScreenTest.kt
git commit -m "feat: add verified Restore screen"
```

### Task 6: About Zach and Manual GitHub Update Check

**Files:**
- Create: `app/src/main/java/com/fileforge/optimizer/UpdateChecker.kt`
- Create: `app/src/main/java/com/fileforge/optimizer/AboutScreenController.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Test: `app/src/test/java/com/fileforge/optimizer/UpdateCheckerTest.kt`
- Test: `app/src/androidTest/java/com/fileforge/optimizer/AboutScreenTest.kt`

**Interfaces:**
- Produces: `SemanticVersion.parse`, `UpdateResult.Current`, `Available`, `NoRelease`, `Offline`, `RateLimited`, `InvalidResponse`.
- Produces: About screen links and tool/version inventory.

- [ ] **Step 1: Write failing semantic-version and response tests**

Cover `v0.2.0`, prerelease text, installed newer/current/older, HTTP 404, 403 rate limit, malformed JSON, oversized response, timeout, standard asset, and native-arm64 asset.

```kotlin
@Test fun selectsNativeAssetForNativeBuild() {
    val result = parser.parse(releaseJson, installed = v("0.2.0"), variant = NATIVE_ARM64)
    assertEquals("FileForgeOptimizer-native-arm64.apk", (result as UpdateResult.Available).assetName)
}
```

- [ ] **Step 2: Run focused unit tests and verify red**

Run: `gradle :app:testDebugUnitTest --tests '*UpdateCheckerTest'`

- [ ] **Step 3: Implement bounded unauthenticated release request**

Use only `https://api.github.com/repos/Zfkirke0109/FileForgeOptimizer/releases/latest`, 10-second connect/read timeouts, `Accept: application/vnd.github+json`, the current GitHub API-version header, a descriptive User-Agent, and a 1 MiB response ceiling. Do not persist response bodies or include credentials.

- [ ] **Step 4: Implement About destination**

Show exact creator credit, GitHub profile, repository, issue tracker, `BuildConfig.VERSION_NAME`, version code, build variant, ABI, native-tool availability, license notices, theme selector, and Check for updates. Open HTTPS links with `ACTION_VIEW`; do not download or install silently.

- [ ] **Step 5: Add `INTERNET`, test, and commit**

```bash
gradle :app:testDebugUnitTest --tests '*UpdateCheckerTest'
gradle :app:assembleDebug :app:assembleDebugAndroidTest
git add app/src/main/java/com/fileforge/optimizer/UpdateChecker.kt app/src/main/java/com/fileforge/optimizer/AboutScreenController.kt app/src/main/AndroidManifest.xml app/src/test app/src/androidTest
git commit -m "feat: add creator page and update checks"
```

## Android UI Plan Verification Gate

- [ ] Run `gradle :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest`.
- [ ] On API 35/36, start a dry run, background the app, confirm progress and Cancel, then reopen and confirm terminal state.
- [ ] Verify notification-denied behavior does not crash and explains reduced visibility.
- [ ] Rotate/recreate the activity during a run and confirm service ownership.
- [ ] Verify System, Light, Dark, and AMOLED on an S23 Ultra-sized emulator/device with readable contrast and system-bar insets.
- [ ] Verify Optimize, Restore, and About destinations and all external links.
- [ ] Mock current/newer/no-release/offline/rate-limit update outcomes.
