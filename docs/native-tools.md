# Native tools

The `nativeArm64` flavor builds five executables from source for Android `arm64-v8a`. Downloaded host executables are never packaged. The `standard` flavor contains an empty manifest and no native executables.

| Tool | Runtime use | Version/ref | License |
|---|---|---|---|
| qpdf | PDF optimization | 12.4.0 | Apache-2.0 |
| oxipng | Safe-mode PNG optimization | 10.2.0 | MIT |
| jpegtran from libjpeg-turbo | Lossless JPEG transform/metadata handling | 3.1.4.1 | IJG and BSD-3-Clause |
| zopflipng | Aggressive-mode PNG optimization | 1.0.3 | Apache-2.0 |
| AOSP zipalign | APK Lab candidate alignment | platform-tools-31.0.0 | Apache-2.0 |

The exact repositories, refs, resolved 40-character commits, permitted licenses, Android system dependencies, NDK, Rust, and cargo-ndk versions are authoritative in [`native/native-tools.lock.json`](../native/native-tools.lock.json). AOSP system-core and libziparchive source revisions used by the zipalign build are locked there as dependencies. [`native/NOTICE.md`](../native/NOTICE.md) lists upstream sources and bundled notices.

## Reproduce the build

Install these prerequisites:

- Java 17 and Gradle 8.10.2;
- Android SDK 35 and build-tools 35.0.0;
- Android NDK 27.2.12479018 and CMake 3.22.1;
- Ninja, Git, jq, Rust 1.88.0, the `aarch64-linux-android` Rust target, and cargo-ndk 4.1.2.

Set `ANDROID_HOME`, `ANDROID_NDK_HOME`, `JAVA_HOME`, `CARGO_HOME`, and `RUSTUP_HOME` for that toolchain, then run:

```bash
bash scripts/fetch-native-sources.sh
bash scripts/build-native-tools.sh
bash scripts/verify-native-tools.sh
bats scripts/tests/native-tools.bats scripts/tests/workflow-contracts.bats
gradle :app:testNativeArm64DebugUnitTest :app:lintNativeArm64Debug :app:assembleNativeArm64Release
```

`fetch-native-sources.sh` resolves each declared ref and refuses it unless the fetched commit exactly matches the lock. It copies required upstream license and notice files from those verified checkouts. `build-native-tools.sh` compiles for API 26 with static C++ support where applicable, PIE output, and 16 KiB maximum/common linker page alignment.

Generated sources, build directories, license copies, manifests, and executables are ignored by Git. CI regenerates them from the lock. This design makes source inputs and binary properties independently verifiable; it does not claim byte-for-byte reproducibility across arbitrary host toolchains.

## Verification gates

`verify-native-tools.sh` rejects a build unless:

- exactly the five declared executable files exist;
- every file is an executable ELF64 AArch64 PIE;
- every load segment supports at least 16 KiB alignment;
- every dynamic dependency is on the lock file's Android system-library allowlist;
- no RPATH or RUNPATH exists;
- the generated manifest matches the locked commit, license, and executable name for every tool;
- the notice and license inventory is complete.

The release workflow independently inspects both APKs. It requires zero FileForge native executables in the standard APK and exactly five under `lib/arm64-v8a/` in the native APK.

## Runtime containment

Native tools receive only app-private staged input and candidate paths. Output is captured with a 64 KiB ceiling, private paths are removed from error text, cancellation terminates the child process, and each process has a fixed time limit. A candidate must be smaller, use the expected private path, and pass a streaming format verifier. The normal backup/replace/verify/undo transaction remains the only path that can mutate the selected original.

Signed PDFs and APNG files are not sent to the native optimizer. Any unavailable tool, process error, timeout, malformed output, or failed validation discards the candidate and invokes the Kotlin fallback. APK zipalign is applied after streaming ZIP optimization; if alignment fails, FileForge retains the already verified streaming candidate rather than the failed native output.
