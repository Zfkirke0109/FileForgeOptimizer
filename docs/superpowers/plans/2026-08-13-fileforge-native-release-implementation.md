# FileForge Native arm64, CI, Release, and Publication Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Produce reproducible standard and source-built arm64-native APKs, safely route five licensed tools, verify/sign/release both artifacts, and make the repository public only after a clean full-history exposure scan.

**Architecture:** The normal APK has no third-party executables. The native flavor packages arm64 PIE executables under the extracted native-library directory and advertises them through a generated manifest. A shared runner stages SAF files privately, applies fixed tool policies, and returns candidates to the common verifier; CI pins source refs, records resolved commits, validates licenses/ELFs/APKs, and fails closed.

**Tech Stack:** Android Gradle Plugin 8.7.3, Gradle 8.10.2, Android NDK 27.2.12479018, CMake/Ninja, Rust/cargo-ndk for oxipng, GitHub Actions, apksigner, zipalign, Gitleaks, Kotlin/JUnit.

## Global Constraints

- Depends on both the core and Android UI implementation plans.
- Produce exactly `FileForgeOptimizer-standard.apk` and `FileForgeOptimizer-native-arm64.apk`.
- Both variants keep `applicationId 'com.fileforge.optimizer'`, the same version code, and the same configured signing certificate.
- Native ABI is exactly `arm64-v8a`; standard remains ABI-neutral.
- Source refs are fixed to qpdf `v12.4.0`, oxipng `v10.2.0`, libjpeg-turbo `3.1.4.1`, Zopfli `zopfli-1.0.3`, and AOSP build `refs/tags/platform-tools-31.0.0` for zipalign source.
- No opaque third-party prebuilt executable is committed or downloaded into an APK.
- Every bundled component must appear in the machine-readable allowlist and human-readable notices.
- Native failure never writes an original and always returns through common fallback/verification policy.
- The repository stays private until a current-tree and full-reachable-history exposure scan passes.
- Signing key material and secret values never enter commits, logs, caches, or artifacts.
- Every task follows red-green-refactor and ends in a focused commit.

---

## File Structure

- Modify `app/build.gradle`: `standard`/`nativeArm64` flavors, ABI packaging, generated manifests, output naming.
- Modify `app/src/main/AndroidManifest.xml`: extracted native executable packaging.
- Create `native/native-tools.lock.json`: source refs and license allowlist.
- Create `native/NOTICE.md`: bundled attributions.
- Create `native/CMakeLists.txt`: native target orchestration where C/C++ sources apply.
- Create `scripts/fetch-native-sources.sh`: strict source checkout and resolved-commit record.
- Create `scripts/build-native-tools.sh`: NDK/Rust builds and packaging.
- Create `scripts/verify-native-tools.sh`: ELF ABI, dynamic dependency, 16 KiB alignment, and notice validation.
- Create `app/src/main/java/com/fileforge/optimizer/NativeToolManifest.kt`.
- Create `app/src/main/java/com/fileforge/optimizer/NativeToolRegistry.kt`.
- Create `app/src/main/java/com/fileforge/optimizer/NativeToolRunner.kt`.
- Create flavor assets under `app/src/standard/assets/` and generated `app/src/nativeArm64/assets/`.
- Replace `.github/workflows/build-debug-apk.yml` with build/test/sign/artifact jobs and add history scanning.
- Modify `README.md` and add `SECURITY.md` plus release documentation.

### Task 1: Native Manifest, Registry, and Fixed Command Policies

**Files:**
- Create: `app/src/main/java/com/fileforge/optimizer/NativeToolManifest.kt`
- Create: `app/src/main/java/com/fileforge/optimizer/NativeToolRegistry.kt`
- Test: `app/src/test/java/com/fileforge/optimizer/NativeToolRegistryTest.kt`
- Create: `app/src/standard/assets/native-tools.json`

**Interfaces:**
- Produces: `NativeToolId { QPDF, OXIPNG, JPEGTRAN, ZOPFLIPNG, ZIPALIGN }`.
- Produces: `NativeToolDescriptor(id, version, executableName, licenseId, sourceUrl, resolvedCommit)`.
- Produces: `NativeToolRegistry.available(id)` and `commandFor(id, input, output, mode)`.

- [ ] **Step 1: Write failing registry/policy tests**

Assert the standard manifest exposes zero tools, unknown/duplicate IDs fail parsing, executable paths must remain under `applicationInfo.nativeLibraryDir`, and command arrays contain no shell interpolation.

```kotlin
@Test fun qpdfSafePolicyIsLosslessAndUsesSeparateOutput() {
    val command = registry.commandFor(QPDF, input, output, OptimizeMode.SAFE)
    assertEquals(input.absolutePath, command[command.lastIndex - 1])
    assertEquals(output.absolutePath, command.last())
    assertFalse(command.any { it.contains("--jpeg-quality") })
}
```

- [ ] **Step 2: Run focused tests and verify red**

Run: `gradle :app:testDebugUnitTest --tests '*NativeToolRegistryTest'`

- [ ] **Step 3: Implement strict manifest parsing**

Reject missing fields, duplicate tools, paths with separators, unapproved license IDs, and binaries absent from nativeLibraryDir. Standard `native-tools.json` is exactly:

```json
{"schemaVersion":1,"abi":null,"tools":[]}
```

- [ ] **Step 4: Implement fixed argument templates**

Use argument arrays, never `/system/bin/sh -c`:

```text
qpdf --stream-data=compress --object-streams=generate --recompress-flate --compression-level=9 INPUT OUTPUT
oxipng -o 4 --preserve --out OUTPUT INPUT
jpegtran -copy all -optimize -progressive -outfile OUTPUT INPUT
zopflipng --keepchunks=gAMA,cHRM,sRGB,iCCP,pHYs,tEXt,zTXt,iTXt INPUT OUTPUT
zipalign -f -P 16 4 INPUT OUTPUT
```

JPEG always uses `-copy all` so EXIF orientation, ICC profiles, and other rendering-critical application markers survive both modes; APK zipalign remains gated by APK Lab Mode.

- [ ] **Step 5: Run tests and commit**

```bash
gradle :app:testDebugUnitTest --tests '*NativeToolRegistryTest'
git add app/src/main/java/com/fileforge/optimizer/NativeToolManifest.kt app/src/main/java/com/fileforge/optimizer/NativeToolRegistry.kt app/src/standard/assets/native-tools.json app/src/test
git commit -m "feat: define native tool policies"
```

### Task 2: Cancellable Native Runner and Kotlin Fallback

**Files:**
- Create: `app/src/main/java/com/fileforge/optimizer/NativeToolRunner.kt`
- Modify: `app/src/main/java/com/fileforge/optimizer/OptimizationCoordinator.kt`
- Test: `app/src/test/java/com/fileforge/optimizer/NativeToolRunnerTest.kt`
- Test: `app/src/test/java/com/fileforge/optimizer/NativeFallbackTest.kt`

**Interfaces:**
- Consumes: registry, candidates, cancellation, common verifier.
- Produces: `NativeExecution.Success`, `Unavailable`, `TimedOut`, `Failed`, `Cancelled`.
- Produces: `run(tool, stagedInput, candidateOutput, mode, cancellation): NativeExecution`.

- [ ] **Step 1: Write failing process and fallback tests**

Inject a `ProcessLauncher` fake. Cover exit 0, nonzero exit, missing output, oversized captured text, timeout, cancellation, malformed output, output not smaller, and native failure followed by Kotlin success without original writes.

- [ ] **Step 2: Run focused tests and verify red**

Run: `gradle :app:testDebugUnitTest --tests '*NativeToolRunnerTest' --tests '*NativeFallbackTest'`

- [ ] **Step 3: Implement bounded process execution**

Stage input and output below app cache, cap combined captured stdout/stderr at 64 KiB, poll cancellation, enforce qpdf/jpegtran/zipalign 10-minute and oxipng/zopflipng 30-minute ceilings, destroy then forcibly destroy on timeout/cancel, and sanitize private absolute paths from messages.

- [ ] **Step 4: Integrate common verifier and fallback**

Only `NativeExecution.Success` with an existing candidate enters `CandidateVerifier`. Verification/no-gain/failure closes the native candidate and invokes the existing Kotlin path where supported. ZIP/APK writes still go through the backup-first coordinator.

- [ ] **Step 5: Run tests and commit**

```bash
gradle :app:testDebugUnitTest --tests '*NativeToolRunnerTest' --tests '*NativeFallbackTest'
git add app/src/main/java/com/fileforge/optimizer/NativeToolRunner.kt app/src/main/java/com/fileforge/optimizer/OptimizationCoordinator.kt app/src/test
git commit -m "feat: run native optimizers with safe fallback"
```

### Task 3: Pinned Source Build and License Gate

**Files:**
- Create: `native/native-tools.lock.json`
- Create: `native/NOTICE.md`
- Create: `native/CMakeLists.txt`
- Create: `scripts/fetch-native-sources.sh`
- Create: `scripts/build-native-tools.sh`
- Create: `scripts/verify-native-tools.sh`
- Test: `scripts/tests/native-tools.bats`

**Interfaces:**
- Produces executables named `libfileforge_qpdf.so`, `libfileforge_oxipng.so`, `libfileforge_jpegtran.so`, `libfileforge_zopflipng.so`, and `libfileforge_zipalign.so` under `app/src/nativeArm64/jniLibs/arm64-v8a/`.
- Produces generated `app/src/nativeArm64/assets/native-tools.json` containing resolved commits and versions.

- [ ] **Step 1: Write failing shell contract tests**

Assert `--verify-only` rejects a moved tag/ref, unlisted license, missing notice, non-AArch64 ELF, executable with unexpected `DT_NEEDED`, absent binary, and segment alignment below 16384.

- [ ] **Step 2: Run Bats tests and verify red**

Run: `bats scripts/tests/native-tools.bats`

Expected: failures because scripts and lock file do not exist.

- [ ] **Step 3: Add exact lock inventory**

`native-tools.lock.json` records these repository/ref/license triples:

```json
{
  "schemaVersion": 1,
  "ndkVersion": "27.2.12479018",
  "tools": [
    {"id":"qpdf","repository":"https://github.com/qpdf/qpdf.git","ref":"v12.4.0","license":"Apache-2.0"},
    {"id":"oxipng","repository":"https://github.com/oxipng/oxipng.git","ref":"v10.2.0","license":"MIT"},
    {"id":"jpegtran","repository":"https://github.com/libjpeg-turbo/libjpeg-turbo.git","ref":"3.1.4.1","license":"IJG-AND-BSD-3-Clause"},
    {"id":"zopflipng","repository":"https://github.com/google/zopfli.git","ref":"zopfli-1.0.3","license":"Apache-2.0"},
    {"id":"zipalign","repository":"https://android.googlesource.com/platform/build","ref":"refs/tags/platform-tools-31.0.0","license":"Apache-2.0"}
  ]
}
```

The fetch script resolves each ref once, records the full commit in `native/resolved-sources.json`, copies upstream license/notice files, and refuses a dirty or unrecorded source tree.

- [ ] **Step 4: Implement arm64 source builds**

Use the NDK CMake toolchain with `ANDROID_ABI=arm64-v8a`, `ANDROID_PLATFORM=26`, `ANDROID_STL=c++_static`, PIE, release optimization, and linker maximum page size 16384. Use cargo-ndk with a locked Rust toolchain for oxipng. Build AOSP zipalign from the pinned source and its pinned Apache-compatible AOSP dependencies; the license gate must enumerate each dependency before linking.

- [ ] **Step 5: Package executable ELFs and generated manifest**

Rename only verified PIE executables to the `libfileforge_*.so` package names. Do not rename shared libraries and treat them as commands. Generate the runtime manifest from the resolved-source record and copy consolidated notices into assets.

- [ ] **Step 6: Verify and commit**

Run:

```bash
bash scripts/fetch-native-sources.sh
bash scripts/build-native-tools.sh
bash scripts/verify-native-tools.sh
bats scripts/tests/native-tools.bats
```

Commit scripts, locks, manifests, and notices, but exclude fetched source trees, build directories, and binary outputs from git.

```bash
git add native scripts .gitignore app/src/standard/assets
git commit -m "build: add reproducible arm64 native toolchain"
```

### Task 4: Standard and Native Gradle Variants

**Files:**
- Modify: `app/build.gradle`
- Modify: `app/src/main/AndroidManifest.xml`
- Create: `app/src/nativeArm64/assets/.gitkeep`
- Create: `app/src/nativeArm64/jniLibs/arm64-v8a/.gitkeep`
- Test: `app/src/test/java/com/fileforge/optimizer/BuildVariantTest.kt`

**Interfaces:**
- Produces Gradle variants `standardDebug`, `standardRelease`, `nativeArm64Debug`, `nativeArm64Release`.
- Produces `BuildConfig.FILEFORGE_VARIANT` values `standard` or `native-arm64`.

- [ ] **Step 1: Write failing variant inventory test**

Assert standard reports zero native tools and native manifest declares exactly five arm64 tools. Assert both flavors expose the same application ID and version code.

- [ ] **Step 2: Run standard test and verify red**

Run: `gradle :app:testDebugUnitTest --tests '*BuildVariantTest'`

Expected: compilation fails because `BuildConfig.FILEFORGE_VARIANT` and flavor resources do not exist.

- [ ] **Step 3: Add one flavor dimension and packaging rules**

Configure `distribution` flavors `standard` and `nativeArm64`, move the empty manifest into the standard source set, add the generated manifest/native directory to the native source set, apply an arm64 ABI filter only on native, and set `jniLibs.useLegacyPackaging = true` plus `android:extractNativeLibs="true"` so packaged executables have filesystem paths. Add build-config variant strings and deterministic archive base names. Set app `versionCode 2` and `versionName '0.2.0'` for both.

- [ ] **Step 4: Build both debug variants**

Run:

```bash
gradle :app:assembleStandardDebug
bash scripts/build-native-tools.sh
gradle :app:assembleNativeArm64Debug
```

Expected: both APKs build; native contains five `libfileforge_*.so` entries; standard contains none.

- [ ] **Step 5: Commit**

```bash
git add app/build.gradle app/src/main/AndroidManifest.xml app/src/nativeArm64 app/src/test/java/com/fileforge/optimizer/BuildVariantTest.kt
git commit -m "build: split standard and native arm64 APKs"
```

### Task 5: CI, Signing, Artifacts, and Release Checks

**Files:**
- Modify: `.github/workflows/build-debug-apk.yml`
- Create: `.github/workflows/secret-scan.yml`
- Create: `.github/release.yml`
- Test: local workflow lint plus Gradle tasks.

**Interfaces:**
- Produces signed artifacts with exact requested filenames.
- Consumes existing `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`, and `EXPECTED_SIGNER_SHA256` secrets without exposing values.

- [ ] **Step 1: Write the workflow acceptance checklist before editing YAML**

The build must checkout full history, set Java 17, install SDK 35/build-tools 35.0.0/NDK 27.2.12479018/Rust, run unit tests and lint, build native sources, assemble both release variants, zipalign before signing, verify the same signer digest, scan APK contents/notices, and upload two artifacts.

- [ ] **Step 2: Replace the single-debug workflow**

Use concurrency cancellation per ref and least-privilege permissions (`contents: read`). Decode the keystore only under `$RUNNER_TEMP/fileforge-release.jks`, `chmod 600`, and delete it in `if: always()`. Never echo environment values. Rename signed outputs exactly.

- [ ] **Step 3: Add full-history secret scan**

Checkout with `fetch-depth: 0`, download `gitleaks_8.30.1_linux_x64.tar.gz` from the official Gitleaks v8.30.1 release, require SHA-256 `551f6fc83ea457d62a0d98237cbad105af8d557003051f41f3e7ca7b3f2470eb`, and run `gitleaks git --redact --no-banner --report-format sarif`. Upload only the sanitized SARIF when policy permits. Scan on pull requests, pushes to main, and manual dispatch.

- [ ] **Step 4: Validate workflows and artifacts**

Run `python -m pip install yamllint==1.35.1` followed by `yamllint .github/workflows`, then locally run all available build steps. In GitHub Actions, confirm both APK artifacts, `apksigner verify --verbose --print-certs`, `zipalign -c -P 16 4`, five native executables only in native APK, and matching expected certificate digest.

- [ ] **Step 5: Commit**

```bash
git add .github/workflows .github/release.yml
git commit -m "ci: verify signed standard and native releases"
```

### Task 6: Documentation, Pull Request, and Public Visibility Gate

**Files:**
- Modify: `README.md`
- Create: `SECURITY.md`
- Create: `docs/native-tools.md`
- Create: `docs/releasing.md`

**Interfaces:**
- Produces public-facing build/use/restore/update/license/security documentation.

- [ ] **Step 1: Update documentation against shipped behavior**

Document standard versus native artifacts, arm64 requirement, streaming scope, remaining non-ZIP memory limit, foreground notification/time limit, dry-run zero-write guarantee, backup/restore behavior, APK signature warning, theme modes, About/update behavior, license sources, reproducible native build commands, signing secrets by name only, and vulnerability reporting.

- [ ] **Step 2: Run a current-tree exposure scan**

Search tracked files for private keys, credential formats, token prefixes, passwords, `.jks`, `.keystore`, `.env`, certificate bundles, personal absolute paths, and generated APKs. Treat secret names in workflow expressions as expected; any values are blockers.

- [ ] **Step 3: Run a complete reachable-history scan**

Fetch all branches/tags, run Gitleaks over history with redaction, enumerate binary extensions/history objects, and manually inspect every finding. If any real credential/key existed, stop publication, revoke it, and clean/recreate history before continuing.

- [ ] **Step 4: Open one draft pull request and require green checks**

PR title: `feat: add streaming recovery and native optimization`

PR body must summarize streaming ZIP/dry run, foreground progress, restore, Material You/About/update checking, native artifacts/licenses, verification commands, signer verification, and exposure-scan result.

- [ ] **Step 5: Review, merge, and verify main**

Resolve review findings with new tests, mark ready, merge only when build and secret scans pass, and confirm the post-merge main workflow produces both signed artifacts.

- [ ] **Step 6: Change repository visibility**

Immediately before the change, re-check the exact target `Zfkirke0109/FileForgeOptimizer`, scan result, Actions secret configuration, branch protection, and absence of publish-blocking findings. Change visibility from private to public through GitHub repository settings, then verify unauthenticated repository and release access. Do not expose or recreate signing secret values.

- [ ] **Step 7: Publish release `v0.2.0`**

Create generated release notes, attach both signed APKs and their SHA-256 files, and confirm the app's unauthenticated `/releases/latest` checker selects the correct asset for each installed variant.

- [ ] **Step 8: Commit documentation before PR merge**

```bash
git add README.md SECURITY.md docs/native-tools.md docs/releasing.md
git commit -m "docs: publish FileForge build and recovery guide"
```

## Native and Publication Verification Gate

- [ ] `gradle :app:testStandardDebugUnitTest :app:testNativeArm64DebugUnitTest :app:lintStandardDebug :app:lintNativeArm64Debug` exits 0.
- [ ] Both release variants build, align, sign, and verify with the same expected certificate SHA-256.
- [ ] Native APK has exactly five declared arm64 tools and complete notices; standard APK has none.
- [ ] Every native failure-mode test proves the original is untouched and fallback is safe.
- [ ] Current-tree and full-history exposure scans have reviewed, zero publish-blocking findings.
- [ ] The single implementation PR is green and merged before visibility changes.
- [ ] Public unauthenticated repository access and `/releases/latest` work after publication.
