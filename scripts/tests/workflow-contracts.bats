#!/usr/bin/env bats

setup() {
  export REPO_ROOT="$(cd "$BATS_TEST_DIRNAME/../.." && pwd)"
  export BUILD_WORKFLOW="$REPO_ROOT/.github/workflows/build-debug-apk.yml"
  export FEATURE_WORKFLOW="$REPO_ROOT/.github/workflows/verify-feature.yml"
  export SECRET_WORKFLOW="$REPO_ROOT/.github/workflows/secret-scan.yml"
  export LINT_WORKFLOW="$REPO_ROOT/.github/workflows/lint-sources.yml"
  export INSTRUMENTED_WORKFLOW="$REPO_ROOT/.github/workflows/instrumented-tests.yml"
}

@test "release workflow pins toolchains and verifies both flavors" {
  grep -Fq 'fetch-depth: 0' "$BUILD_WORKFLOW"
  grep -Fq 'ndk;27.2.12479018' "$BUILD_WORKFLOW"
  grep -Fq 'cmake;3.22.1' "$BUILD_WORKFLOW"
  grep -Fq 'rustup toolchain install 1.88.0' "$BUILD_WORKFLOW"
  grep -Fq 'cargo install cargo-ndk --version 4.1.2 --locked' "$BUILD_WORKFLOW"
  grep -Fq 'bash scripts/build-native-tools.sh' "$BUILD_WORKFLOW"
  grep -Fq ':app:testStandardDebugUnitTest' "$BUILD_WORKFLOW"
  grep -Fq ':app:testNativeArm64DebugUnitTest' "$BUILD_WORKFLOW"
  grep -Fq ':app:lintStandardDebug' "$BUILD_WORKFLOW"
  grep -Fq ':app:lintNativeArm64Debug' "$BUILD_WORKFLOW"
  grep -Fq ':app:assembleStandardRelease' "$BUILD_WORKFLOW"
  grep -Fq ':app:assembleNativeArm64Release' "$BUILD_WORKFLOW"
}

@test "release workflow signs exact assets and destroys temporary key material" {
  for secret in KEYSTORE_BASE64 KEYSTORE_PASSWORD KEY_ALIAS KEY_PASSWORD EXPECTED_SIGNER_SHA256; do
    grep -Fq "$secret" "$BUILD_WORKFLOW"
  done
  grep -Fq '$RUNNER_TEMP/fileforge-release.jks' "$BUILD_WORKFLOW"
  grep -Fq 'FileForgeOptimizer-standard.apk' "$BUILD_WORKFLOW"
  grep -Fq 'FileForgeOptimizer-native-arm64.apk' "$BUILD_WORKFLOW"
  grep -Fq 'zipalign" -f -P 16 4' "$BUILD_WORKFLOW"
  grep -Fq 'apksigner" verify --verbose --print-certs' "$BUILD_WORKFLOW"
  grep -Fq 'native tool inventory mismatch' "$BUILD_WORKFLOW"
  grep -Fq 'Remove signing material' "$BUILD_WORKFLOW"
  grep -Fq 'if: always()' "$BUILD_WORKFLOW"
}

@test "release signing and artifact publication require the main ref" {
  [ "$(grep -Fc "github.ref == 'refs/heads/main'" "$BUILD_WORKFLOW")" -ge 3 ]
}

@test "workflow actions are pinned to immutable commits" {
  for workflow in "$BUILD_WORKFLOW" "$FEATURE_WORKFLOW" "$SECRET_WORKFLOW" "$LINT_WORKFLOW" \
    "$INSTRUMENTED_WORKFLOW"; do
    while IFS= read -r action; do
      [[ "$action" =~ @[0-9a-f]{40}([[:space:]]*#.*)?$ ]]
    done < <(grep -E '^[[:space:]]*-?[[:space:]]*uses:' "$workflow")
  done
}

@test "feature workflow uses flavored tasks and builds pinned native sources" {
  grep -Fq 'bash scripts/build-native-tools.sh' "$FEATURE_WORKFLOW"
  grep -Fq ':app:testStandardDebugUnitTest' "$FEATURE_WORKFLOW"
  grep -Fq ':app:testNativeArm64DebugUnitTest' "$FEATURE_WORKFLOW"
  grep -Fq ':app:assembleStandardDebug' "$FEATURE_WORKFLOW"
  grep -Fq ':app:assembleNativeArm64Debug' "$FEATURE_WORKFLOW"
  run grep -E ':app:(test|lint|assemble)Debug' "$FEATURE_WORKFLOW"
  [ "$status" -ne 0 ]
}

@test "secret scan verifies the pinned scanner against complete history" {
  [ -f "$SECRET_WORKFLOW" ]
  grep -Fq 'fetch-depth: 0' "$SECRET_WORKFLOW"
  grep -Fq 'gitleaks_8.30.1_linux_x64.tar.gz' "$SECRET_WORKFLOW"
  grep -Fq '551f6fc83ea457d62a0d98237cbad105af8d557003051f41f3e7ca7b3f2470eb' "$SECRET_WORKFLOW"
  grep -Fq 'gitleaks git --redact --no-banner --report-format sarif' "$SECRET_WORKFLOW"
}

@test "lint workflow pins both linters and checks shell and YAML sources" {
  [ -f "$LINT_WORKFLOW" ]
  grep -Fq 'yamllint==1.38.0' "$LINT_WORKFLOW"
  grep -Fq 'shellcheck-v0.11.0.linux.x86_64.tar.xz' "$LINT_WORKFLOW"
  grep -Fq '8c3be12b05d5c177a04c29e3c78ce89ac86f1595681cab149b65b97c4e227198' "$LINT_WORKFLOW"
  grep -Fq 'sha256sum --check --strict' "$LINT_WORKFLOW"
  grep -Fq 'yamllint --strict --config-file .yamllint' "$LINT_WORKFLOW"
  grep -Fq 'shellcheck scripts/*.sh' "$LINT_WORKFLOW"
}

@test "shell scripts and YAML are clean under the pinned linters" {
  command -v shellcheck >/dev/null || skip "shellcheck is not installed"
  command -v yamllint >/dev/null || skip "yamllint is not installed"
  run shellcheck "$REPO_ROOT"/scripts/*.sh
  [ "$status" -eq 0 ]
  run yamllint --strict --config-file "$REPO_ROOT/.yamllint" \
    "$REPO_ROOT/.github" "$REPO_ROOT/.yamllint"
  [ "$status" -eq 0 ]
}

@test "instrumented workflow runs the on-device suites on an Android 15 emulator" {
  [ -f "$INSTRUMENTED_WORKFLOW" ]
  grep -Fq 'system-images;android-35;google_apis;x86_64' "$INSTRUMENTED_WORKFLOW"
  grep -Fq '99-kvm4all.rules' "$INSTRUMENTED_WORKFLOW"
  grep -Fq 'sys.boot_completed' "$INSTRUMENTED_WORKFLOW"
  grep -Fq ':app:connectedStandardDebugAndroidTest' "$INSTRUMENTED_WORKFLOW"
  grep -Fq 'packages: platform-tools' "$INSTRUMENTED_WORKFLOW"
}

@test "instrumented workflow fails fast instead of hanging on a dead emulator" {
  # The first run hung 43 minutes: the emulator could not find the AVD
  # avdmanager wrote, died at once, and `adb wait-for-device` never returned.
  grep -Fq 'export ANDROID_AVD_HOME=' "$INSTRUMENTED_WORKFLOW"
  grep -Fq 'emulator -list-avds' "$INSTRUMENTED_WORKFLOW"
  grep -Fq 'kill -0 "$emulator_pid"' "$INSTRUMENTED_WORKFLOW"
  run grep -E '^[[:space:]]*adb wait-for-device[[:space:]]*$' "$INSTRUMENTED_WORKFLOW"
  [ "$status" -ne 0 ]
}

@test "instrumented suites declare the AndroidJUnit4 runner they are written for" {
  grep -Fq "testInstrumentationRunner 'androidx.test.runner.AndroidJUnitRunner'" \
    "$REPO_ROOT/app/build.gradle"
}

@test "repository release metadata exists" {
  [ -f "$REPO_ROOT/.github/release.yml" ]
}
