# FileForge Optimizer: Streaming, Recovery, Native Tools, and Material You

Date: 2026-08-13
Status: Approved design
Target repository: `Zfkirke0109/FileForgeOptimizer`
Target device: Samsung Galaxy S23 Ultra, arm64-v8a, Android 16

## Purpose

Turn the current single-activity Android MVP into a recoverable, long-running optimizer that can analyze and optimize large ZIP-family files without loading them into memory, report progress outside the activity, restore previous versions, use optional permissively licensed native tools, and preview exact savings without modifying user files.

The same release also modernizes the UI with Material You, provides a Samsung-friendly dark experience, adds creator and license information, checks GitHub Releases for updates, and prepares the repository for public visibility.

## Scope

This release adds:

1. Streaming ZIP-family and APK Lab processing that is not subject to the former 300 MB byte-array guard.
2. A user-initiated foreground service with Android notification progress and cancellation.
3. Versioned, crash-resilient undo logs plus a Restore screen.
4. Separate standard and arm64-native APK artifacts.
5. Optional qpdf, oxipng, jpegtran, zopflipng, and zipalign routing in the native artifact.
6. Exact dry-run savings analysis with zero selected-folder writes.
7. Material 3 dynamic colors, System/Light/Dark/AMOLED themes, and Samsung-friendly dark styling.
8. Optimize, Restore, and About destinations.
9. An unauthenticated public GitHub Releases update checker.
10. A full repository-history exposure scan before the repository changes from private to public.

This release does not add video or audio transcoding, root access, broad all-files access, automatic APK installation, or automatic deletion of backups.

## Architecture

### Activities and navigation

`MainActivity` becomes a Material 3 host with three destinations:

- **Optimize** configures and starts runs, displays live state, and shows the last report.
- **Restore** discovers undo logs under the selected SAF tree and restores an entire run or selected entries.
- **About** displays creator information, installed version and variant, native-tool availability, open-source notices, GitHub links, issue reporting, and update checking.

Views use Material Components rather than introducing a Compose migration in the same release. This keeps the refactor bounded while still supporting Material 3 and dynamic colors.

### Foreground optimization service

`OptimizationService` owns active optimization and dry-run work. The activity starts it directly in response to a visible user action and binds while visible for live progress.

The service:

- enters the foreground immediately with a low-importance notification channel;
- uses the `mediaProcessing` foreground-service type on supported Android versions;
- exposes immutable progress snapshots through a local binder;
- updates the notification at a throttled cadence rather than once per buffer;
- provides a Cancel notification action;
- stops cleanly on completion, cancellation, fatal failure, or Android's foreground-service timeout;
- persists the terminal report so reopening the activity does not lose the outcome.

Android 13 and newer receive an in-context `POST_NOTIFICATIONS` permission request. If permission is denied, the run may continue subject to Android foreground-service rules, and the app explains that detailed notification progress will not appear in the notification drawer.

### Engine boundaries

The engine is split into focused components:

- `TreeScanner`: recursively enumerates the granted SAF tree, excludes FileForge artifacts, and emits file metadata.
- `FileTypeDetector`: detects supported types from the name and a bounded header read.
- `OptimizationCoordinator`: selects streaming, in-memory, or native execution and applies transaction rules.
- `StreamingZipOptimizer`: transforms ZIP-family input to a temporary candidate using bounded buffers.
- `ByteArrayOptimizerAdapter`: retains current format optimizers with an explicit per-format memory limit.
- `NativeToolRunner`: stages SAF input to private cache, invokes an available packaged tool with fixed arguments, enforces timeout and output limits, and returns a candidate.
- `CandidateVerifier`: validates type, format integrity, byte size, and hashes before commit.
- `BackupStore`: streams originals into the run backup tree.
- `UndoLogRepository`: writes v2 logs and reads both v2 and legacy logs.
- `RestoreCoordinator`: verifies backup records and performs independent per-file restores.
- `RunReportStore`: persists the latest progress and terminal report in app-private storage.

These components communicate with models and interfaces that do not depend on activity widgets, allowing local unit tests.

## Streaming and Transaction Flow

### Type detection

The scanner opens each file only long enough to read a small header. ZIP-family files and APK Lab candidates are never converted to a full `ByteArray`.

### ZIP-family candidate creation

`StreamingZipOptimizer` reads a `ZipInputStream` and writes a `ZipOutputStream` backed by an app-private temporary file. It preserves directory entries and safe metadata needed for compatibility, normalizes only fields deliberately covered by the selected mode, and rejects malformed entry names such as absolute paths or parent traversal.

The implementation uses a fixed-size transfer buffer. Output size is measured from the candidate file. ZIP64-capable platform APIs are used where the archive requires them. Empty archives and archives with unsupported or malformed structures are reported without modifying the original.

### Real run commit

For each candidate that is smaller and verified:

1. Stream the original to its unique backup path.
2. Flush and hash the backup.
3. Confirm the backup size and SHA-256 match the original read.
4. Stream the candidate to the original SAF document.
5. Reopen and verify the committed document.
6. If verification fails, immediately attempt restoration from the verified backup.
7. Append and flush one completed v2 undo record only after successful replacement.

Failures are isolated per file. A failed file increments the report error count and does not stop the remaining run unless the user cancels or the engine encounters a run-wide invariant failure.

### Byte-array formats

PNG, JPEG, PDF, JSON, XML, SVG, and TXT continue to use byte-array Kotlin implementations when the standard APK cannot use a native tool. They retain an explicit memory guard because their current algorithms are not streaming. The UI and report distinguish `skipped_memory_limit` from unsupported files. Removing those remaining guards requires future streaming implementations for each format and is outside this release.

## Dry-Run Semantics

Dry run executes the same scanner, optimizer selection, candidate generation, and verification paths used by a real run so reported savings are exact rather than estimated.

During dry run:

- candidates exist only in app-private cache;
- the selected SAF tree receives no directories, backups, undo logs, receipts, or file writes;
- originals are never opened for output;
- candidate files are deleted after measurement and verification;
- the report records files that would be optimized, potential savings, skips, and errors;
- cancellation deletes remaining temporary files.

Tests enforce the zero-mutation requirement with a recording document gateway.

## Undo Log v2

Each real run uses a unique directory named `FileForge_Backups_<run-id>` and a log named `FileForge_Undo_v2_<run-id>.jsonl`.

The first JSON line is a header containing:

- schema version;
- run ID and timestamps;
- selected mode and feature settings;
- app version and build variant;
- initial run status `RUNNING`.

When the run ends, the repository appends and flushes a terminal summary JSON line containing the final status and aggregate report. It never rewrites the header or already committed file records, which keeps the log recoverable on SAF providers that do not support reliable random-access updates. A log without a terminal summary is treated as an interrupted run whose completed file records are still restorable.

Each subsequent line is an independent record containing:

- original relative path;
- backup relative path;
- original and optimized byte sizes;
- original and optimized SHA-256 hashes;
- file kind;
- optimizer/tool identifier;
- note and completion timestamp.

Records are appended and flushed after each committed replacement. Paths are JSON strings, so characters that break the legacy pipe-delimited format are preserved correctly.

`UndoLogRepository` also parses the existing `FileForge_Undo_*.txt` layout. Legacy records without hashes remain visible for manual recovery reference, but the Restore UI labels them view-only and does not allow them to authorize a document write.

## Restore Behavior

The Restore destination lists discovered logs by date, status, entry count, and recoverable bytes. It supports restoring all eligible v2 entries or selected eligible entries. The confirmed request carries the undo document identity, entry count, and exact raw-byte SHA-256 so the service can reject a changed log.

For every selected record, `RestoreCoordinator`:

1. Resolves the backup and original path within the selected root.
2. Rejects path traversal and paths outside the granted tree.
3. Requires a v2 SHA-256 record bound to the original SAF document identity.
4. Verifies that the current original still matches the recorded optimized size and SHA-256.
5. Verifies that the backup exists and matches the recorded original size and SHA-256.
6. Streams the backup to the original document.
7. Reopens and verifies the restored size and SHA-256.
8. Records an independent result and continues with remaining entries.

A restore creates `FileForge_Restore_<run-id>_<timestamp>.jsonl` as an audit receipt. It does not delete or alter the backup or original undo log. Repeated restoration is therefore safe and auditable.

## Native arm64 Distribution

The release workflow produces:

- `FileForgeOptimizer-standard.apk`
- `FileForgeOptimizer-native-arm64.apk`

The standard artifact contains Kotlin optimizers only. The native artifact contains source-built `arm64-v8a` tools and uses the same application ID and signing identity, allowing an in-place switch between variants when version codes are compatible.

Native sources are pinned by version or commit and verified by checksum in CI. Builds use a pinned Android NDK. The workflow retains source notices, generates a machine-readable tool manifest, checks executable architecture, and validates native page alignment. No opaque prebuilt third-party executables are committed.

### Native routing

| File kind | Tool | Intended behavior |
| --- | --- | --- |
| PDF | qpdf | Lossless structural and stream optimization without image resampling |
| PNG | oxipng | Default lossless PNG optimization |
| PNG aggressive | zopflipng | Optional slower candidate; used only when it preserves configured chunks and beats the other verified candidate |
| JPEG | jpegtran | Lossless coefficient/Huffman optimization; metadata policy follows Safe or Aggressive mode |
| APK Lab | zipalign | Alignment after ZIP processing; signature-invalidating warning remains mandatory |

The native APK never assumes a tool is usable. `NativeToolRegistry` checks the manifest and executable path at runtime. `NativeToolRunner` uses fixed argument templates, private staging paths, captured output, cancellation, and per-tool timeouts. Missing tools, nonzero exits, timeouts, malformed output, or candidates that are not smaller trigger the Kotlin fallback or a clear skip. Originals are not touched until the common verifier and transaction layer accepts a candidate.

### Licensing

The initial license inventory is:

- qpdf: Apache License 2.0.
- oxipng: MIT License.
- libjpeg-turbo/jpegtran: IJG and compatible BSD-style licenses.
- Zopfli/zopflipng: Apache License 2.0.
- zipalign: built from the applicable Apache-licensed AOSP source and dependencies after CI verifies their license metadata.

Every bundled source and dependency must have a compatible redistribution license and its required notice in the APK About screen and release source bundle. The native build fails closed when the allowlist or notice inventory is incomplete.

## Material You and Samsung Dark Experience

The app adopts a Material 3 DayNight theme and Material Components widgets. `DynamicColors` applies the device wallpaper palette on Android 12 and newer, including Samsung's system-provided palette, with a tested fallback scheme on earlier devices.

The theme selector provides:

- **System**: follows the device's light/dark setting and uses dynamic colors when available.
- **Light**: fixed light mode with dynamic accents when available.
- **Dark**: Material 3 dark surfaces with dynamic accents.
- **AMOLED**: near-black background and dark tonal surfaces with sufficient separation, readable contrast, and restrained dynamic accents.

Samsung-focused treatment includes edge-to-edge system bars, navigation and status icon contrast, large touch targets, comfortable vertical spacing, and layouts verified on the S23 Ultra display size. The implementation avoids hardcoded widget colors and force-dark behavior that can conflict with dynamic themes.

## About and Update Checking

The About destination displays:

- `Created by Zachary Kirke`;
- a link to `https://github.com/Zfkirke0109`;
- app version, version code, variant, ABI, and installed native tools;
- links to `Zfkirke0109/FileForgeOptimizer` and its issue tracker;
- bundled open-source license notices;
- a Check for updates action.

The app declares only the normal `INTERNET` permission for update checks. It queries:

`GET https://api.github.com/repos/Zfkirke0109/FileForgeOptimizer/releases/latest`

with GitHub's recommended media type and API-version header. No GitHub token or credential is embedded. The response is size-limited and parsed for `tag_name`, `html_url`, and release assets. Semantic version comparison determines whether the installed version is current. When an update exists, the dialog identifies the matching standard or native-arm64 asset and opens the release page in the user's browser. The app does not silently download or install APKs and does not request unknown-app installation permission.

Offline, timeout, rate-limit, no-release, malformed-response, current-version, and newer-version outcomes receive distinct user messages. Checks occur only when the user taps the button.

## Progress, Cancellation, and Reports

Progress snapshots contain phase, current relative path, files discovered, files processed, candidates, optimized files, skipped files by reason, errors, bytes read, bytes written, actual savings, potential dry-run savings, and optional total work.

The notification shows:

- `Analyzing` for dry run or `Optimizing` for a real run;
- current file name;
- determinate progress after discovery when feasible, otherwise an indeterminate indicator;
- accumulated savings;
- a Cancel action.

Cancellation is cooperative. Scanner, streaming loops, hashing, native waiting, backup, and restore loops check a shared token. A cancellation never begins a new file commit. If received during a commit, the coordinator completes or rolls back that file before terminating. Terminal status is one of `COMPLETED`, `COMPLETED_WITH_ERRORS`, `CANCELLED`, or `FAILED`.

## Security and Public-Repository Gate

Before changing repository visibility, scan the complete reachable Git history and current tree for:

- credentials, API tokens, passwords, signing key material, and private keys;
- `.jks`, `.keystore`, certificate bundles, environment files, or credential exports;
- personal paths, private data, or generated artifacts unsuitable for publication;
- workflow values that should be GitHub secrets.

The existing workflow references signing secret names but must not contain secret values. GitHub Actions secrets remain configured out of band and are not copied into code, logs, documentation, artifacts, or release metadata.

Visibility changes to public only after the scan reports no publish-blocking findings. If a secret ever existed in history, publication stops until the secret is revoked and history is safely rewritten or the repository is recreated from a clean snapshot.

## Error Handling

- SAF permission loss pauses the run with an actionable reselect-folder message.
- Temporary storage exhaustion fails the current candidate without modifying the original.
- Verification failure deletes the candidate and records an error.
- Backup verification failure prevents replacement.
- Original write or post-write verification failure triggers immediate restore from the verified backup.
- Native tool output and exit details are sanitized before user display and logs.
- Notification, binding, or activity recreation failures do not own or terminate engine state.
- Restore errors are isolated per record.
- Update-check network failures never affect optimizer operation.

## Testing Strategy

### JVM unit tests

- file type detection from bounded headers;
- ZIP streaming with nested paths, directories, empty files, metadata, duplicate names, ZIP64 cases, malformed archives, CRC failures, and traversal names;
- bounded-buffer behavior using generated archives larger than the former limit without allocating the archive as one array;
- candidate comparison and verification;
- dry-run zero selected-tree mutations;
- v2 JSONL write/parse and legacy undo parsing;
- restore path validation, hashes, missing backups, partial failure, and repeated restores;
- progress aggregation and cancellation boundaries;
- native command templates, availability, timeouts, sanitized failures, and fallback;
- semantic version and update-response parsing;
- license allowlist completeness.

### Android/instrumented tests

- foreground service start, notification channel, progress, cancellation action, and terminal notification;
- activity rebinding after recreation;
- notification permission granted and denied paths;
- Material DayNight and dynamic color application;
- System, Light, Dark, and AMOLED theme persistence;
- Optimize, Restore, and About navigation;
- creator, repository, issue, and release links;
- SAF test-provider integration for backup, commit, rollback, and restore.

### CI and artifact verification

- Gradle unit tests and lint;
- standard debug/release assembly;
- native arm64 build and tests;
- APK signing identity verification;
- `zipalign` and native ELF/page-alignment checks;
- source checksum and license/notice inventory checks;
- secret and history exposure scanning;
- artifact naming and version consistency.

## Acceptance Criteria

The work is complete when:

1. A valid ZIP-family archive larger than 300 MB can be dry-run and optimized without loading the complete input or output into heap memory.
2. A user-started run continues with visible notification progress after leaving the activity and can be cancelled safely.
3. Dry run reports exact verified potential savings and produces no writes under the selected SAF tree.
4. Every successful real replacement has a verified streamed backup and a flushed v2 undo record.
5. The Restore screen can restore all or selected identity-bound v2 entries and presents legacy size-only entries as view-only recovery references.
6. Standard and native-arm64 APKs build and share the same signing identity.
7. Native routing uses the intended tool when available and safely falls back when it is not.
8. Material You, Samsung-friendly Dark/AMOLED modes, Optimize/Restore/About navigation, Zachary Kirke's GitHub link, notices, and manual update checking work on the target device class.
9. Automated tests, lint, artifact verification, licensing checks, and secret scans pass.
10. A pull request contains the implementation and evidence, and the repository becomes public only after the history exposure gate passes.

## Primary References

- Android foreground services: https://developer.android.com/develop/background-work/services/fgs
- Android foreground-service types: https://developer.android.com/develop/background-work/services/fgs/service-types
- Android foreground-service timeouts: https://developer.android.com/develop/background-work/services/fgs/timeout
- Android notification permission: https://developer.android.com/develop/ui/views/notifications/notification-permission
- Android Storage Access Framework: https://developer.android.com/training/data-storage/shared/documents-files
- Android dynamic colors: https://developer.android.com/develop/ui/views/theming/dynamic-colors
- Android dark theme: https://developer.android.com/develop/ui/views/theming/darktheme
- GitHub latest release API: https://docs.github.com/en/rest/releases/releases#get-the-latest-release
- qpdf license: https://qpdf.readthedocs.io/en/stable/license.html
- oxipng: https://github.com/oxipng/oxipng
- libjpeg-turbo licenses: https://github.com/libjpeg-turbo/libjpeg-turbo/blob/main/LICENSE.md
- Zopfli: https://github.com/google/zopfli
- Android zipalign: https://developer.android.com/tools/zipalign
