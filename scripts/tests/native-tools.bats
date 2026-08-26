#!/usr/bin/env bats

setup() {
  export REPO_ROOT="$(cd "$BATS_TEST_DIRNAME/../.." && pwd)"
  export TEST_ROOT="$BATS_TEST_TMPDIR/case"
  mkdir -p "$TEST_ROOT/output" "$TEST_ROOT/assets" "$TEST_ROOT/sources"
  cp "$REPO_ROOT/native/native-tools.lock.json" "$TEST_ROOT/lock.json"
  cp "$REPO_ROOT/native/NOTICE.md" "$TEST_ROOT/NOTICE.md"
}

@test "verify rejects an absent native binary" {
  run env \
    FILEFORGE_NATIVE_LOCK="$TEST_ROOT/lock.json" \
    FILEFORGE_NATIVE_OUTPUT_DIR="$TEST_ROOT/output" \
    FILEFORGE_NATIVE_MANIFEST="$TEST_ROOT/assets/native-tools.json" \
    FILEFORGE_NATIVE_NOTICE="$TEST_ROOT/NOTICE.md" \
    bash "$REPO_ROOT/scripts/verify-native-tools.sh"

  [ "$status" -ne 0 ]
  [[ "$output" == *"missing native executable"* ]]
}

@test "verify rejects a non-AArch64 ELF" {
  make_inventory
  for name in qpdf oxipng jpegtran zopflipng zipalign; do
    cp /bin/true "$TEST_ROOT/output/libfileforge_${name}.so"
  done

  run verify_inventory

  [ "$status" -ne 0 ]
  [[ "$output" == *"not an AArch64 ELF"* ]]
}

@test "verify rejects an executable whose load alignment is below 16384" {
  make_inventory
  make_dummy_binaries
  make_readelf_stub 0x1000

  export READELF="$TEST_ROOT/readelf"
  run verify_inventory

  [ "$status" -ne 0 ]
  [[ "$output" == *"LOAD alignment below 16384"* ]]
}

@test "verify rejects unexpected dynamic dependencies" {
  make_inventory
  make_dummy_binaries
  make_readelf_stub 0x4000 libcrypto.so

  export READELF="$TEST_ROOT/readelf"
  run verify_inventory

  [ "$status" -ne 0 ]
  [[ "$output" == *"unexpected DT_NEEDED"* ]]
}

@test "verify rejects a differently named extra native library" {
  make_inventory
  make_dummy_binaries
  printf '\177ELFextra' > "$TEST_ROOT/output/libunexpected.so"
  chmod 755 "$TEST_ROOT/output/libunexpected.so"
  make_readelf_stub 0x4000

  export READELF="$TEST_ROOT/readelf"
  run verify_inventory

  [ "$status" -ne 0 ]
  [[ "$output" == *"native executable inventory mismatch"* ]]
}

@test "verify rejects an unlisted license or missing notice" {
  jq '.tools[0].license = "GPL-3.0-only"' "$TEST_ROOT/lock.json" > "$TEST_ROOT/unapproved.json"
  mv "$TEST_ROOT/unapproved.json" "$TEST_ROOT/lock.json"

  run verify_inventory

  [ "$status" -ne 0 ]
  [[ "$output" == *"unapproved license"* ]]
}

@test "fetch verify-only rejects a moved source ref" {
  mkdir -p "$TEST_ROOT/sources/qpdf"
  git -C "$TEST_ROOT/sources/qpdf" init -q
  git -C "$TEST_ROOT/sources/qpdf" config user.email test@example.com
  git -C "$TEST_ROOT/sources/qpdf" config user.name test
  touch "$TEST_ROOT/sources/qpdf/source"
  git -C "$TEST_ROOT/sources/qpdf" add source
  git -C "$TEST_ROOT/sources/qpdf" commit -q -m source
  jq --arg commit "$(printf '0%.0s' {1..40})" \
    '.tools = [.tools[] | select(.id == "qpdf") | .expectedCommit = $commit]' \
    "$TEST_ROOT/lock.json" > "$TEST_ROOT/one-tool-lock.json"
  jq -n --arg commit "$(git -C "$TEST_ROOT/sources/qpdf" rev-parse HEAD)" \
    '{schemaVersion:1,sources:[{id:"qpdf",commit:$commit}]}' > "$TEST_ROOT/resolved.json"

  run env \
    FILEFORGE_NATIVE_LOCK="$TEST_ROOT/one-tool-lock.json" \
    FILEFORGE_NATIVE_SOURCE_ROOT="$TEST_ROOT/sources" \
    FILEFORGE_RESOLVED_SOURCES="$TEST_ROOT/resolved.json" \
    bash "$REPO_ROOT/scripts/fetch-native-sources.sh" --verify-only

  [ "$status" -ne 0 ]
  [[ "$output" == *"resolved commit mismatch"* ]]
}

@test "build queries cargo-ndk through the cargo subcommand" {
  mkdir -p "$TEST_ROOT/bin" "$TEST_ROOT/ndk/27.2.12479018/build/cmake"
  touch "$TEST_ROOT/ndk/27.2.12479018/build/cmake/android.toolchain.cmake"
  cat > "$TEST_ROOT/bin/cargo" <<EOF
#!/usr/bin/env bash
printf '%s\n' "\$*" >> "$TEST_ROOT/cargo-arguments"
[[ "\$*" == "ndk --version" ]] && printf 'cargo-ndk 4.1.2\n'
EOF
  cat > "$TEST_ROOT/bin/cargo-ndk" <<'EOF'
#!/usr/bin/env bash
printf 'This binary may only be called via `cargo ndk`.\n' >&2
exit 1
EOF
  cat > "$TEST_ROOT/bin/rustc" <<'EOF'
#!/usr/bin/env bash
printf 'rustc 1.88.0 (test)\n'
EOF
  ln -s /bin/true "$TEST_ROOT/bin/cmake"
  ln -s /bin/true "$TEST_ROOT/bin/ninja"
  chmod 755 "$TEST_ROOT/bin/cargo" "$TEST_ROOT/bin/cargo-ndk" "$TEST_ROOT/bin/rustc"

  run env \
    PATH="$TEST_ROOT/bin:$PATH" \
    ANDROID_NDK_HOME="$TEST_ROOT/ndk/27.2.12479018" \
    FILEFORGE_NATIVE_SOURCE_ROOT="$TEST_ROOT/sources" \
    bash "$REPO_ROOT/scripts/build-native-tools.sh"

  [ "$status" -ne 0 ]
  [ -f "$TEST_ROOT/cargo-arguments" ]
  grep -Fxq 'ndk --version' "$TEST_ROOT/cargo-arguments"
  [[ "$output" != *"cargo-ndk version does not match the lock"* ]]
}

@test "build runs cargo-ndk through the cargo subcommand" {
  run grep -F -- '(cd "$source_root/oxipng" && cargo ndk -t arm64-v8a -P 26 build' "$REPO_ROOT/scripts/build-native-tools.sh"
  [ "$status" -eq 0 ]
}

@test "build supplies qpdf's pinned libjpeg cache variables" {
  run grep -F -- '-DLIBJPEG_H_PATH=' "$REPO_ROOT/scripts/build-native-tools.sh"
  [ "$status" -eq 0 ]

  run grep -F -- '-DLIBJPEG_LIB_PATH=' "$REPO_ROOT/scripts/build-native-tools.sh"
  [ "$status" -eq 0 ]
}

@test "zipalign shim provides the AOSP log include and fatal macros" {
  [ -f "$REPO_ROOT/native/zipalign-shim/include/log/log.h" ]
  grep -Fq 'LOG_FATAL_IF' "$REPO_ROOT/native/zipalign-shim/include/utils/Log.h"
  grep -Fq 'LOG_ALWAYS_FATAL_IF' "$REPO_ROOT/native/zipalign-shim/include/utils/Log.h"
}

make_inventory() {
  jq '{schemaVersion:1,abi:"arm64-v8a",tools:[.tools[] | {id,version,executableName:("libfileforge_" + .id + ".so"),licenseId:.license,sourceUrl:.repository,resolvedCommit:.expectedCommit}]}' \
    "$TEST_ROOT/lock.json" > "$TEST_ROOT/assets/native-tools.json"
}

make_dummy_binaries() {
  for name in qpdf oxipng jpegtran zopflipng zipalign; do
    printf '\177ELFdummy' > "$TEST_ROOT/output/libfileforge_${name}.so"
    chmod 755 "$TEST_ROOT/output/libfileforge_${name}.so"
  done
}

make_readelf_stub() {
  local alignment="$1"
  local dependency="${2:-libc.so}"
  cat > "$TEST_ROOT/readelf" <<EOF
#!/usr/bin/env bash
case "\$1" in
  -h) printf '  Class: ELF64\n  Type: DYN (Position-Independent Executable file)\n  Machine: AArch64\n' ;;
  -l|-lW) printf '  LOAD 0x0 0x0 0x0 0x1 0x1 R E $alignment\n' ;;
  -d|-dW) printf ' 0x0000000000000001 (NEEDED) Shared library: [$dependency]\n' ;;
esac
EOF
  chmod 755 "$TEST_ROOT/readelf"
}

verify_inventory() {
  local env_args=(
    "FILEFORGE_NATIVE_LOCK=$TEST_ROOT/lock.json"
    "FILEFORGE_NATIVE_OUTPUT_DIR=$TEST_ROOT/output"
    "FILEFORGE_NATIVE_MANIFEST=$TEST_ROOT/assets/native-tools.json"
    "FILEFORGE_NATIVE_NOTICE=$TEST_ROOT/NOTICE.md"
  )
  if [[ -n "${READELF:-}" ]]; then env_args+=("READELF=$READELF"); fi
  env "${env_args[@]}" bash "$REPO_ROOT/scripts/verify-native-tools.sh"
}
