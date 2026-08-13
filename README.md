# FileForge Optimizer

FileForge Optimizer is a rootless Android app for conservative, same-type file optimization. It uses Android's Storage Access Framework (SAF), so the app can access only the folder the user selects and does not request broad all-files access.

## Optimization workflow

The core engine:

1. Recursively scans the selected SAF tree while excluding FileForge backup, undo, restore, and temporary artifacts.
2. Detects types from the filename and a bounded header read.
3. Processes ZIP-family files through a strict forward-only streaming optimizer with fixed-size buffers and app-private candidate files.
4. Processes supported non-ZIP formats through the Kotlin byte-array adapter, which has an explicit 64 MiB input limit.
5. Verifies every candidate and accepts it only when it is smaller and remains the same format.
6. In a real run, streams and verifies a backup before replacing the original, verifies the replacement, and durably appends a v2 undo record.
7. Emits immutable progress snapshots and a terminal report without depending on an activity.

ZIP-family processing has no archive-size RAM guard: archives are never converted into one complete byte array. The remaining 64 MiB limit applies only to the current PNG, JPEG, PDF, JSON, XML, SVG, and TXT Kotlin optimizers.

## Exact dry run

Dry run follows the same detection, optimization, candidate verification, and size-comparison path as a real run. It reports exact potential savings while keeping candidates in app-private cache. It does not create backups, undo logs, receipts, directories, or writes of any kind in the selected tree, and it removes each temporary candidate after measurement.

## Backups, undo, and restore

Each real run writes:

- verified originals under `FileForge_Backups_<run-id>/`;
- an append-only `FileForge_Undo_v2_<run-id>.jsonl` log;
- one flushed header, one flushed record per committed replacement, and one terminal summary for completion, cancellation, or failure.

The undo reader accepts both v2 JSONL and the legacy text format. V2 restores verify SHA-256 and size; legacy restores are clearly limited to size verification. Restore operations verify the backup before writing, verify the restored original afterward, and create independent `FileForge_Restore_*` audit receipts without deleting backups or undo logs.

## Modes

- **Safe Mode** uses conservative ZIP recompression, preserves PNG non-IDAT chunks, removes selected JPEG metadata without recompressing pixels, and only trims bytes after a PDF's final `%%EOF`.
- **Aggressive Mode** uses stronger compression and can remove additional configured PNG/JPEG metadata.
- **Text minification** enables JSON, XML, SVG, and TXT minification and may change meaningful whitespace.
- **APK Lab Mode** is disabled by default. When explicitly enabled, APKs use the ZIP streaming path. Recompression can invalidate APK signatures and make an APK non-installable or unable to update the original app.

## Supported formats

- ZIP-family: `.zip`, `.jar`, `.epub`, `.docx`, `.xlsx`, `.pptx`
- Images: `.png`, `.jpg`, `.jpeg`
- Documents and text: `.pdf`, plus `.json`, `.xml`, `.svg`, `.txt` when text minification is enabled
- `.apk` only when APK Lab Mode is enabled

## Current limitations

- ZIP entry comments are best-effort because ordinary comments live in the central directory and cannot always be known before forward-only payload output.
- PNG, JPEG, PDF, and text-family Kotlin optimizers still require complete input arrays and are skipped above 64 MiB.
- PDF optimization is intentionally minimal until the optional native qpdf variant is available.
- JPEG optimization is metadata-only and does not recompress pixels.
- APK Lab output is not yet zipaligned or re-signed.
- Audio and video transcoding is outside this project's lossless same-type scope.

## Build

Open the repository in Android Studio, allow Gradle to sync, and choose **Build > Build Bundle(s) / APK(s) > Build APK(s)**.

GitHub Actions also runs the JVM tests, Android lint, and debug APK assembly. The workflow artifact can be downloaded from its completed run in the repository's **Actions** tab.

## Why SAF instead of root?

SAF grants access only to a user-selected directory and its descendants through `ACTION_OPEN_DOCUMENT_TREE`. FileForge does not use root or request `MANAGE_EXTERNAL_STORAGE`.

References:

- [Storage Access Framework](https://developer.android.com/training/data-storage/shared/documents-files)
- [All-files access guidance](https://developer.android.com/training/data-storage/manage-all-files)
- [APK signing](https://source.android.com/docs/security/features/apksigning)
- [zipalign](https://developer.android.com/tools/zipalign)
