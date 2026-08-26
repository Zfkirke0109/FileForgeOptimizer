# Security policy

## Supported versions

Security fixes are applied to the current release line. At publication, that is FileForge Optimizer 0.2.x. Older builds and locally modified APKs are not supported.

## Report a vulnerability privately

Use the repository's **Security → Report a vulnerability** action when it is available. If private vulnerability reporting is unavailable, open a minimal issue requesting a private contact channel; do not include exploit details, secrets, private files, or affected user data in a public issue.

Include the affected version and variant, Android version and device ABI, impact, reproducible steps, and the smallest safe proof of concept. State whether the report involves SAF identity checks, backup or restore integrity, ZIP parsing, native process execution, update metadata, signing, or CI credentials.

Do not test against data or devices you do not own or have permission to assess. Do not upload a user's original documents, backups, undo logs, keystores, signing certificates, or credentials.

## Security boundaries

- FileForge uses SAF-scoped access and does not request root or all-files access.
- Candidate files are created in app-private storage and must be smaller, structurally valid, and the same detected type before commit.
- A real replacement requires a verified backup, post-write verification, and a durable undo record.
- Restore is bound to the exact selected v2 undo-log snapshot and to the current optimized document version. Legacy undo logs are view-only because they do not carry the identity and integrity metadata required for safe mutation.
- Signed PDFs and animated PNGs are preserved on the native path.
- Native commands are selected from a strict manifest; user-controlled paths are passed as process arguments, never interpolated into a shell command.
- Native failures, validation failures, and per-tool timeouts discard the native candidate and fall back safely.
- APK Lab can invalidate third-party APK signatures by design and is disabled by default.
- Update checks are manual, bounded, HTTPS-only GitHub API requests. FileForge opens a validated release page and never self-installs.

## Release integrity

Official release APKs are built only from `main`, aligned, signed with the same expected certificate, verified with `apksigner`, and accompanied by SHA-256 files. Release workflows pin third-party actions to reviewed commit SHAs. The native-arm64 APK contains a generated manifest, notices, license texts, and exactly five source-built arm64 PIE executables; release validation rejects missing or unexpected native libraries. The standard APK contains none of those executables.

Never disclose signing material in a report. Maintainers should rotate any credential that may have been exposed before publishing a cleaned release.
