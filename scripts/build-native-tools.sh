#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
lock_file="$repo_root/native/native-tools.lock.json"
source_root="${FILEFORGE_NATIVE_SOURCE_ROOT:-$repo_root/native/.sources}"
build_root="${FILEFORGE_NATIVE_BUILD_ROOT:-$repo_root/native/.build}"
output_dir="$repo_root/app/src/nativeArm64/jniLibs/arm64-v8a"
asset_dir="$repo_root/app/src/nativeArm64/assets"
license_root="$repo_root/native/.licenses"
resolved_file="$repo_root/native/resolved-sources.json"
ndk_root="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"

[[ -n "$ndk_root" && -f "$ndk_root/build/cmake/android.toolchain.cmake" ]] || {
  echo "ANDROID_NDK_HOME must identify Android NDK $(jq -r '.ndkVersion' "$lock_file")" >&2
  exit 1
}
[[ "$(basename "$ndk_root")" == "$(jq -r '.ndkVersion' "$lock_file")" ]] || {
  echo "wrong Android NDK version: $ndk_root" >&2
  exit 1
}
for command in cmake ninja jq cargo rustc; do
  command -v "$command" >/dev/null || { echo "required build command missing: $command" >&2; exit 1; }
done
command -v cargo-ndk >/dev/null || { echo "cargo-ndk is required" >&2; exit 1; }
[[ "$(cargo ndk --version)" == *"$(jq -r '.cargoNdkVersion' "$lock_file")"* ]] || {
  echo "cargo-ndk version does not match the lock" >&2
  exit 1
}
[[ "$(rustc --version)" == "rustc $(jq -r '.rustToolchain' "$lock_file")"* ]] || {
  echo "Rust toolchain does not match the lock" >&2
  exit 1
}

bash "$repo_root/scripts/fetch-native-sources.sh" --verify-only
rm -rf "$build_root"
mkdir -p "$build_root" "$output_dir" "$asset_dir/licenses"
qpdf_pkgconfig_dir="$build_root/empty-pkgconfig"
mkdir -p "$qpdf_pkgconfig_dir"
find "$output_dir" -maxdepth 1 -type f -name '*.so' -delete
find "$asset_dir/licenses" -maxdepth 1 -type f -delete

toolchain="$ndk_root/build/cmake/android.toolchain.cmake"
common_cmake=(
  -G Ninja
  -DCMAKE_TOOLCHAIN_FILE="$toolchain"
  -DANDROID_ABI=arm64-v8a
  -DANDROID_PLATFORM=26
  -DANDROID_STL=c++_static
  -DCMAKE_BUILD_TYPE=Release
  -DCMAKE_EXE_LINKER_FLAGS=-Wl,-z,max-page-size=16384,-z,common-page-size=16384
)

cmake -S "$source_root/jpegtran" -B "$build_root/jpegtran" "${common_cmake[@]}" \
  -DENABLE_SHARED=OFF -DENABLE_STATIC=ON -DWITH_TOOLS=ON -DWITH_TESTS=OFF \
  -DWITH_TURBOJPEG=OFF -DWITH_JAVA=OFF \
  -DCMAKE_INSTALL_PREFIX="$build_root/jpeg-install" -DCMAKE_INSTALL_LIBDIR=lib
cmake --build "$build_root/jpegtran" --target jpegtran-static
cmake --install "$build_root/jpegtran" --component include
cmake --install "$build_root/jpegtran" --component lib

cmake -S "$source_root/zopflipng" -B "$build_root/zopflipng" "${common_cmake[@]}" \
  -DBUILD_SHARED_LIBS=OFF -DZOPFLI_BUILD_INSTALL=OFF
cmake --build "$build_root/zopflipng" --target zopflipng

env PKG_CONFIG_PATH= PKG_CONFIG_LIBDIR="$qpdf_pkgconfig_dir" \
  cmake -S "$source_root/qpdf" -B "$build_root/qpdf" "${common_cmake[@]}" \
  -DBUILD_SHARED_LIBS=OFF -DBUILD_STATIC_LIBS=ON -DBUILD_DOC=OFF \
  -DINSTALL_MANUAL=OFF -DINSTALL_EXAMPLES=OFF -DINSTALL_PKGCONFIG=OFF \
  -DINSTALL_CMAKE_PACKAGE=OFF -DUSE_IMPLICIT_CRYPTO=OFF -DALLOW_CRYPTO_NATIVE=ON \
  -DREQUIRE_CRYPTO_NATIVE=ON \
  -DLIBJPEG_H_PATH="$build_root/jpeg-install/include" \
  -DLIBJPEG_LIB_PATH="$build_root/jpeg-install/lib/libjpeg.a"
cmake --build "$build_root/qpdf" --target qpdf

export CARGO_TARGET_DIR="$build_root/oxipng-target"
export RUSTFLAGS="${RUSTFLAGS:-} -C link-arg=-Wl,-z,max-page-size=16384 -C link-arg=-Wl,-z,common-page-size=16384"
(cd "$source_root/oxipng" && cargo ndk -t arm64-v8a -P 26 build --release --locked)

cmake -S "$repo_root/native" -B "$build_root/zipalign" "${common_cmake[@]}" \
  -DFILEFORGE_ZOPFLI_SOURCE="$source_root/zopflipng" \
  -DFILEFORGE_AOSP_BUILD_SOURCE="$source_root/zipalign" \
  -DFILEFORGE_SYSTEM_CORE_SOURCE="$source_root/aosp-system-core" \
  -DFILEFORGE_LIBZIPARCHIVE_SOURCE="$source_root/aosp-libziparchive"
cmake --build "$build_root/zipalign" --target fileforge_zipalign

find_one() {
  local directory="$1" name="$2"
  mapfile -t matches < <(find "$directory" -type f -name "$name" -perm -u+x)
  [[ ${#matches[@]} -eq 1 ]] || { echo "expected one built $name, found ${#matches[@]}" >&2; exit 1; }
  printf '%s\n' "${matches[0]}"
}
install -m 0755 "$(find_one "$build_root/qpdf" qpdf)" "$output_dir/libfileforge_qpdf.so"
install -m 0755 "$build_root/oxipng-target/aarch64-linux-android/release/oxipng" "$output_dir/libfileforge_oxipng.so"
install -m 0755 "$(find_one "$build_root/jpegtran" jpegtran-static)" "$output_dir/libfileforge_jpegtran.so"
install -m 0755 "$(find_one "$build_root/zopflipng" zopflipng)" "$output_dir/libfileforge_zopflipng.so"
install -m 0755 "$(find_one "$build_root/zipalign" fileforge_zipalign)" "$output_dir/libfileforge_zipalign.so"

manifest_tmp="$asset_dir/native-tools.json.tmp"
jq '{schemaVersion:1,abi:"arm64-v8a",tools:[.tools[] | {id,version,executableName:("libfileforge_" + .id + ".so"),licenseId:.license,sourceUrl:.repository,resolvedCommit:.expectedCommit}]}' \
  "$lock_file" > "$manifest_tmp"
mv "$manifest_tmp" "$asset_dir/native-tools.json"
install -m 0644 "$repo_root/native/NOTICE.md" "$asset_dir/NOTICE.md"
find "$license_root" -maxdepth 1 -type f -exec install -m 0644 '{}' "$asset_dir/licenses/" ';'

_ndk_os="$(uname -s | tr '[:upper:]' '[:lower:]')"
_ndk_arch="$(uname -m)"
READELF="$ndk_root/toolchains/llvm/prebuilt/${_ndk_os}-${_ndk_arch}/bin/llvm-readelf" \
  bash "$repo_root/scripts/verify-native-tools.sh"
