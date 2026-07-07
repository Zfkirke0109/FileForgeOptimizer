# FileForge Optimizer / SameType Optimizer

Rootless Android MVP for bulk same-extension, same-type file optimization.

This project is intentionally conservative. It uses Android's Storage Access Framework (SAF) folder picker instead of root and instead of broad all-files access. It never modifies APK files unless APK Lab Mode is explicitly enabled.

## What it does

Workflow implemented:

1. Pick a folder with SAF.
2. Recursively scan files.
3. Detect type using extension + magic bytes.
4. Copy file bytes into app processing memory.
5. Run a format-specific safe optimizer.
6. Verify optimized output still opens/parses as the same type.
7. Compare byte size.
8. Replace original only if the optimized version is smaller.
9. Create a backup folder and undo log in the selected directory.

## Modes

- **Safe Mode**
  - APKs are skipped.
  - ZIP-like containers are recompressed conservatively.
  - PNG IDAT streams are recompressed while preserving non-IDAT chunks.
  - JPEG comments/APP1 metadata are stripped without recompressing image data.
  - PDF only removes trailing bytes after final `%%EOF` when present.

- **Aggressive Mode**
  - Slower/higher compression settings.
  - PNG removes text/time/exif ancillary chunks.
  - JPEG can remove APP13 Photoshop/IPTC metadata.
  - XML/JSON/SVG/TXT minify only runs when the checkbox is enabled.

- **APK Lab Mode**
  - Disabled by default.
  - Treats `.apk` like a ZIP container.
  - WARNING: Recompressing an APK can invalidate its v2+ signature and make it non-installable or unable to update the original app.

## Supported formats in this MVP

- `.zip`, `.jar`, `.epub`, `.docx`, `.xlsx`, `.pptx`
- `.png`
- `.jpg`, `.jpeg`
- `.pdf`
- `.json`, `.xml`, `.svg`, `.txt` when the text-minify checkbox is enabled
- `.apk` only when APK Lab Mode is enabled

## Known limitations

- This MVP uses a 300 MB per-file memory guard. Large streaming optimization should be added before processing huge archives or videos.
- It does not optimize video/audio formats because most are already compressed and same-type lossless recompression rarely helps.
- PDF optimization is intentionally minimal. A deeper PDF optimizer would require a proper PDF library or bundled native tool such as qpdf/mutool.
- JPEG optimization is metadata-only and does not recompress pixels.
- APK Lab Mode does not zipalign or re-sign APKs. It is for archive experiments only.
- Text minification can alter whitespace. Leave it off when whitespace is meaningful.

## Build with Android Studio

1. Open Android Studio.
2. Choose **Open** and select this folder.
3. Let Gradle sync.
4. Build > Build Bundle(s) / APK(s) > Build APK(s).
5. Install the debug APK on your Galaxy S23 Ultra.

## Build with GitHub Actions

1. Push this folder to a GitHub repo.
2. Open the **Actions** tab.
3. Run **Build debug APK**.
4. Download the uploaded APK artifact.

## Why SAF instead of root?

SAF lets the user grant an app access to a chosen directory and its subdirectories using `ACTION_OPEN_DOCUMENT_TREE`. This is exactly the kind of rootless access this app needs. Broad `MANAGE_EXTERNAL_STORAGE` access is not used in this MVP.

Official docs:

- SAF folder access: https://developer.android.com/training/data-storage/shared/documents-files
- All-files access guidance: https://developer.android.com/training/data-storage/manage-all-files
- APK signing: https://source.android.com/docs/security/features/apksigning
- zipalign: https://developer.android.com/tools/zipalign

## Next upgrades

Good next steps:

1. Add streaming ZIP optimization to remove the 300 MB RAM guard.
2. Add Android notification progress for long runs.
3. Add a Restore screen that reads the undo log and restores backups.
4. Add optional native binaries for qpdf, oxipng, jpegtran, zopflipng, and zipalign where licenses allow.
5. Add a dry-run mode that reports possible savings before writing anything.
