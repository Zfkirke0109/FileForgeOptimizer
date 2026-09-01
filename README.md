# FileForge Optimizer

FileForge Optimizer is a rootless Android app for conservative, same-type file optimization. It uses Android's Storage Access Framework (SAF), so it can access only the folder the user selects and does not request broad all-files access.

## Choose a release

The two release APKs use the same application ID and release signer, so installing one variant replaces the other while preserving app data.

| APK | Device support | Native tools | Best for |
|---|---|---|---|
| `FileForgeOptimizer-standard.apk` | Android 8.0+ (API 26), any supported device ABI | None | Smallest and most portable build |
| `FileForgeOptimizer-native-arm64.apk` | Android 8.0+ on `arm64-v8a` | qpdf, oxipng, jpegtran, zopflipng, zipalign | Native PDF/image optimization and aligned APK Lab output |

Both variants retain the Kotlin optimizers. If a native tool is missing, times out, fails, or produces an invalid candidate, FileForge discards that candidate and safely uses the Kotlin path. The About screen identifies the installed variant, ABI, and verified native-tool inventory.

## Optimization workflow

The core engine:

1. Recursively scans the selected SAF tree while excluding FileForge backup, undo, restore, and temporary artifacts.
2. Detects types from the filename and a bounded header read.
3. Processes ZIP-family files through a strict forward-only streaming optimizer with fixed-size buffers and app-private candidates.
4. Routes PNG, JPEG, and PDF through a verified native tool when the native-arm64 variant can do so safely; otherwise it uses the bounded Kotlin adapter.
5. Verifies every candidate and accepts it only when it is smaller and remains the same format.
6. In a real run, streams and verifies a backup, durably journals the verified candidate before opening the original for replacement, then verifies the replacement.
7. Emits immutable progress snapshots and a terminal report without depending on an activity.

ZIP-family processing has no whole-archive RAM guard: archives are never converted into one complete byte array. The 64 MiB input limit applies to the Kotlin PNG, JPEG, PDF, JSON, XML, SVG, and TXT optimizers. Native PDF/image processing stages input and output in app-private storage instead of loading the whole document into memory.

## Exact dry run

Dry run follows the same detection, optimization, candidate verification, and size-comparison path as a real run. It reports exact potential savings while keeping candidates in app-private cache. It does not create backups, undo logs, receipts, directories, or any writes in the selected tree, and it removes each temporary candidate after measurement.

## Backups, undo, and restore

Each real run writes:

- verified originals under `FileForge_Backups_<run-id>/`;
- an append-only `FileForge_Undo_v2_<run-id>.jsonl` log;
- one flushed header, one flushed record per committed replacement, and one terminal summary for completion, cancellation, or failure.

FileForge verifies the name, type, and resolved identity returned by SAF creation calls and fails closed before original-file mutation when those checks disagree. Android's generic SAF API does not offer a universal atomic create-if-absent operation, so run identifiers combine high-resolution time with random entropy to make cross-process name collisions impractical without claiming provider-independent atomic exclusivity.

The undo reader accepts v2 JSONL and the legacy text format. Only v2 entries containing SHA-256 hashes and the original SAF document identity are selectable for automatic restore; legacy size-only records remain visible as recovery references but cannot authorize a write. Confirmation is bound to the exact undo-log identity, entry count, and raw-byte SHA-256. Before writing, FileForge also verifies that the current document still matches the recorded optimized version, then verifies the backup and the restored original. Real restore attempts create independent `FileForge_Restore_*` audit receipts without deleting backups or undo logs.

If an undo entry write or flush fails, FileForge treats that writer as poisoned, stops before mutating that file, preserves the verified backup because record durability may be ambiguous, and leaves the log conservatively interrupted without appending more records. Once the entry is durable, the verified backup remains recoverable even if the process dies during replacement; an entry whose original was never changed is handled idempotently. Recoverable provider and orchestration errors return a terminal failed report and attempt a failed terminal record when the writer is healthy. VM-fatal failures (`OutOfMemoryError`, `StackOverflowError`, and `ThreadDeath`) are rethrown after best-effort cleanup, with secondary finalization failures attached rather than masking the primary.

## Modes and formats

- **Safe Mode** uses conservative ZIP recompression, preserves PNG non-IDAT chunks, removes selected JPEG metadata without recompressing pixels, and only trims bytes after a PDF's final `%%EOF` on the Kotlin path.
- **Aggressive Mode** uses stronger ZIP compression and can remove additional configured PNG/JPEG metadata. The native-arm64 build selects zopflipng instead of oxipng for PNG.
- **Text minification** enables JSON, XML, SVG, and TXT minification and may change meaningful whitespace.
- **APK Lab Mode** is disabled by default. When enabled, APKs use the streaming ZIP path and the native-arm64 variant runs zipalign on an accepted candidate.

Supported types are `.zip`, `.jar`, `.epub`, `.docx`, `.xlsx`, `.pptx`, `.png`, `.jpg`, `.jpeg`, `.pdf`, and—when text minification is enabled—`.json`, `.xml`, `.svg`, and `.txt`. `.apk` is supported only in APK Lab Mode.

APK Lab recompression can invalidate an APK signature and make the result non-installable or unable to update the original app. Native zipalign does not restore that signature; re-sign any APK Lab output with its legitimate signing identity before installation.

## Foreground operation and appearance

Optimization and restore run in a single foreground service with a cancel action and durable terminal state. Android still requires a foreground-service notification when notification permission is denied, although detailed drawer updates may be hidden. If Android invokes its media-processing service time limit, FileForge cancels cooperatively, finalizes the run safely, and reports that the time limit was reached. Individual native processes also fail over after 10 minutes for qpdf, jpegtran, and zipalign, or 30 minutes for oxipng and zopflipng.

The About screen offers System, Light, Dark, and AMOLED themes. Update checks occur only when the user requests one; the app validates the GitHub release response, selects the APK matching the installed variant, and opens the release page in the browser. It never downloads or installs an update itself.

## Build and verification

The project uses Java 17, Gradle 8.10.2, Android SDK 35, build-tools 35.0.0, and minSdk 26. Build the portable variant without native sources:

```bash
gradle :app:testStandardDebugUnitTest :app:lintStandardDebug :app:assembleStandardRelease
```

Building the native-arm64 variant also requires Android NDK 27.2.12479018, CMake 3.22.1, Ninja, Rust 1.88.0 with the `aarch64-linux-android` target, cargo-ndk 4.1.2, Git, and jq:

```bash
bash scripts/fetch-native-sources.sh
bash scripts/build-native-tools.sh
bash scripts/verify-native-tools.sh
gradle :app:testNativeArm64DebugUnitTest :app:lintNativeArm64Debug :app:assembleNativeArm64Release
```

See [Native tools](docs/native-tools.md) for the pinned source and license model and [Releasing](docs/releasing.md) for the signed-artifact procedure.

## Security and privacy

FileForge does not use root or request `MANAGE_EXTERNAL_STORAGE`. SAF grants access only to a user-selected directory and its descendants through `ACTION_OPEN_DOCUMENT_TREE`. See [SECURITY.md](SECURITY.md) to report a vulnerability. This repository does not currently grant a project-wide open-source license; third-party components retain the licenses included with the native-arm64 artifact.

References: [Storage Access Framework](https://developer.android.com/training/data-storage/shared/documents-files), [all-files access guidance](https://developer.android.com/training/data-storage/manage-all-files), [APK signing](https://source.android.com/docs/security/features/apksigning), and [zipalign](https://developer.android.com/tools/zipalign).
