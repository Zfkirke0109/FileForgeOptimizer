# Releasing FileForge Optimizer

This project publishes two same-signer APKs for each version:

- `FileForgeOptimizer-standard.apk`
- `FileForgeOptimizer-native-arm64.apk`

Each APK is accompanied by a same-named `.sha256` file. Do not publish unsigned local Gradle outputs as official releases.

## Protected configuration

The `Verify and release APKs` workflow consumes these GitHub Actions secrets:

- `KEYSTORE_BASE64`
- `KEYSTORE_PASSWORD`
- `KEY_ALIAS`
- `KEY_PASSWORD`
- `EXPECTED_SIGNER_SHA256`

Secret values must never be printed, copied into repository files, attached to a workflow artifact, or entered into an issue or pull request. Confirm the secret names are configured without retrieving their values. Do not generate a replacement signing key merely to make CI pass; an unintended key change breaks Android update continuity.

## Pre-merge gate

1. Confirm `app/build.gradle` has the intended `versionCode` and `versionName`, and that the release workflow artifact names use the same version.
2. Fetch complete refs and run the current-tree and reachable-history secret scans. Review every finding; rotate and remove any real credential before publication.
3. Rebuild native sources from [`native/native-tools.lock.json`](../native/native-tools.lock.json).
4. Run the shell tests, both unit-test suites, both lints, the standard instrumentation APK compile, and both release assemblies.
5. Inspect the unsigned packages: the standard APK must have no FileForge native executable, while native-arm64 must have exactly five plus its manifest, notice, and license assets.
6. Require green pull-request checks and resolve applicable review threads before merge.

Reference commands:

```bash
bash scripts/fetch-native-sources.sh
bash scripts/build-native-tools.sh
bash scripts/verify-native-tools.sh
bats scripts/tests/native-tools.bats scripts/tests/workflow-contracts.bats
gradle \
  :app:testStandardDebugUnitTest \
  :app:testNativeArm64DebugUnitTest \
  :app:lintStandardDebug \
  :app:lintNativeArm64Debug \
  :app:assembleStandardDebugAndroidTest \
  :app:assembleStandardRelease \
  :app:assembleNativeArm64Release
```

## Signing gate on `main`

The post-merge workflow:

1. decodes the configured keystore only to `$RUNNER_TEMP/fileforge-release.jks` and restricts its mode;
2. hashes the exported certificate and compares it to `EXPECTED_SIGNER_SHA256`;
3. runs build-tools 35.0.0 `zipalign -f -P 16 4` before signing;
4. signs both exact filenames with `apksigner`;
5. verifies each signature and compares its reported certificate digest to the same expected fingerprint;
6. checks alignment again after signing;
7. creates SHA-256 files beside the signed APKs;
8. deletes temporary keystore and aligned intermediates in an unconditional cleanup step.

Signing is intentionally skipped for pull requests because GitHub does not expose release secrets there. It is mandatory on a successful push to `main`.

## Publish `v0.2.0`

1. Confirm the `main` commit is the reviewed merge and all required checks passed.
2. Download both signed workflow artifacts from that exact `main` run.
3. Locally verify both `.sha256` files, `apksigner verify --verbose --print-certs`, the expected signer fingerprint, and `zipalign -c -P 16 4`.
4. Create the annotated tag and GitHub release `v0.2.0` from that exact commit with generated release notes.
5. Attach only the two signed APKs and two SHA-256 files.
6. Verify the public release page and unauthenticated `/releases/latest` API response.
7. Confirm the standard and native-arm64 asset names are both present so the in-app checker can select the installed variant.

If any signer, checksum, inventory, alignment, history-scan, or provenance check disagrees, stop. Preserve the failed logs without secret values, revoke exposed credentials where applicable, correct the source or configuration through review, and produce a new clean workflow run.
