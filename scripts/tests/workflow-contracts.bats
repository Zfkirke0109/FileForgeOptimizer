#!/usr/bin/env bats

setup() {
  export REPO_ROOT="$(cd "$BATS_TEST_DIRNAME/../.." && pwd)"
  export BUILD_WORKFLOW="$REPO_ROOT/.github/workflows/build-debug-apk.yml"
  export FEATURE_WORKFLOW="$REPO_ROOT/.github/workflows/verify-feature.yml"
  export SECRET_WORKFLOW="$REPO_ROOT/.github/workflows/secret-scan.yml"
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
  for workflow in "$BUILD_WORKFLOW" "$FEATURE_WORKFLOW" "$SECRET_WORKFLOW"; do
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

@test "repository release metadata exists" {
  [ -f "$REPO_ROOT/.github/release.yml" ]
}
