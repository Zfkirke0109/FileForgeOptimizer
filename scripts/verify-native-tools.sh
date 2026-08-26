#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
lock_file="${FILEFORGE_NATIVE_LOCK:-$repo_root/native/native-tools.lock.json}"
output_dir="${FILEFORGE_NATIVE_OUTPUT_DIR:-$repo_root/app/src/nativeArm64/jniLibs/arm64-v8a}"
manifest_file="${FILEFORGE_NATIVE_MANIFEST:-$repo_root/app/src/nativeArm64/assets/native-tools.json}"
notice_file="${FILEFORGE_NATIVE_NOTICE:-$repo_root/native/NOTICE.md}"
readelf_command="${READELF:-readelf}"

for command in jq "$readelf_command"; do
  command -v "$command" >/dev/null || { echo "required command missing: $command" >&2; exit 1; }
done

case "$(jq -r '[.tools[].license] | unique | sort | join(",")' "$lock_file")" in
  Apache-2.0,IJG-AND-BSD-3-Clause,MIT) ;;
  *) echo "unapproved license in native lock" >&2; exit 1 ;;
esac
[[ -f "$notice_file" ]] || { echo "native NOTICE is missing" >&2; exit 1; }
for id in qpdf oxipng jpegtran zopflipng zipalign; do
  grep -Fqi "$id" "$notice_file" || { echo "native NOTICE is missing $id" >&2; exit 1; }
  binary="$output_dir/libfileforge_${id}.so"
  [[ -f "$binary" ]] || { echo "missing native executable: $binary" >&2; exit 1; }
  [[ -x "$binary" ]] || { echo "native executable is not executable: $binary" >&2; exit 1; }
done

[[ -f "$manifest_file" ]] || { echo "generated native manifest is missing" >&2; exit 1; }
jq -e '.schemaVersion == 1 and .abi == "arm64-v8a" and (.tools | length == 5)' "$manifest_file" >/dev/null || {
  echo "generated native manifest is invalid" >&2
  exit 1
}

mapfile -t actual_files < <(find "$output_dir" -maxdepth 1 -type f -name '*.so' -printf '%f\n' | sort)
mapfile -t expected_files < <(jq -r '.tools[].id | "libfileforge_" + . + ".so"' "$lock_file" | sort)
[[ "${actual_files[*]}" == "${expected_files[*]}" ]] || {
  echo "native executable inventory mismatch" >&2
  exit 1
}

mapfile -t allowed_dependencies < <(jq -r '.systemLibraries[]' "$lock_file")
dependency_allowed() {
  local dependency="$1" allowed
  for allowed in "${allowed_dependencies[@]}"; do [[ "$dependency" == "$allowed" ]] && return 0; done
  return 1
}

for binary in "$output_dir"/libfileforge_*.so; do
  header="$($readelf_command -h "$binary")"
  [[ "$header" == *"ELF64"* && "$header" == *"AArch64"* && "$header" == *"DYN"* ]] || {
    echo "not an AArch64 ELF PIE: $binary" >&2
    exit 1
  }
  while read -r alignment; do
    [[ -n "$alignment" ]] || continue
    value=$((alignment))
    (( value >= 16384 )) || { echo "LOAD alignment below 16384: $binary ($alignment)" >&2; exit 1; }
  done < <($readelf_command -lW "$binary" | awk '$1 == "LOAD" {print $NF}')
  while read -r dependency; do
    [[ -n "$dependency" ]] || continue
    dependency_allowed "$dependency" || { echo "unexpected DT_NEEDED $dependency in $binary" >&2; exit 1; }
  done < <($readelf_command -dW "$binary" | sed -n 's/.*Shared library: \[\([^]]*\)\].*/\1/p')
  if $readelf_command -dW "$binary" | grep -Eq '\((RPATH|RUNPATH)\)'; then
    echo "RPATH/RUNPATH is forbidden: $binary" >&2
    exit 1
  fi
done

for id in qpdf oxipng jpegtran zopflipng zipalign; do
  expected_commit="$(jq -r --arg id "$id" '.tools[] | select(.id == $id) | .expectedCommit' "$lock_file")"
  expected_license="$(jq -r --arg id "$id" '.tools[] | select(.id == $id) | .license' "$lock_file")"
  expected_name="libfileforge_${id}.so"
  jq -e --arg id "$id" --arg commit "$expected_commit" --arg license "$expected_license" --arg name "$expected_name" \
    '.tools[] | select(.id == $id and .resolvedCommit == $commit and .licenseId == $license and .executableName == $name)' \
    "$manifest_file" >/dev/null || { echo "manifest metadata mismatch for $id" >&2; exit 1; }
done

echo "verified five pinned arm64 PIE native tools"
