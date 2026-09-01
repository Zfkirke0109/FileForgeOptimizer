#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
lock_file="${FILEFORGE_NATIVE_LOCK:-$repo_root/native/native-tools.lock.json}"
source_root="${FILEFORGE_NATIVE_SOURCE_ROOT:-$repo_root/native/.sources}"
resolved_file="${FILEFORGE_RESOLVED_SOURCES:-$repo_root/native/resolved-sources.json}"
license_root="${FILEFORGE_NATIVE_LICENSE_ROOT:-$repo_root/native/.licenses}"
verify_only=false
if [[ "${1:-}" == "--verify-only" ]]; then
  verify_only=true
elif [[ $# -ne 0 ]]; then
  echo "usage: $0 [--verify-only]" >&2
  exit 2
fi

for command in jq git; do
  command -v "$command" >/dev/null || { echo "required command missing: $command" >&2; exit 1; }
done
jq -e '.schemaVersion == 1 and (.tools | type == "array") and (.licenseAllowlist | type == "array")' \
  "$lock_file" >/dev/null || { echo "invalid native source lock" >&2; exit 1; }

validate_record() {
  local record="$1"
  local id license expected
  id="$(jq -r '.id' <<<"$record")"
  license="$(jq -r '.license' <<<"$record")"
  expected="$(jq -r '.expectedCommit' <<<"$record")"
  jq -e --arg value "$license" '.licenseAllowlist | index($value) != null' "$lock_file" >/dev/null || {
    echo "unapproved license for $id: $license" >&2
    return 1
  }
  [[ "$expected" =~ ^[0-9a-f]{40}$ ]] || { echo "invalid expected commit for $id" >&2; return 1; }
}

verify_checkout() {
  local record="$1"
  local id expected checkout actual recorded
  id="$(jq -r '.id' <<<"$record")"
  expected="$(jq -r '.expectedCommit' <<<"$record")"
  checkout="$source_root/$id"
  [[ -d "$checkout/.git" ]] || { echo "verified source checkout missing: $id" >&2; return 1; }
  actual="$(git -C "$checkout" rev-parse HEAD^{commit})"
  [[ "$actual" == "$expected" ]] || {
    echo "resolved commit mismatch for $id: expected $expected, found $actual" >&2
    return 1
  }
  [[ -z "$(git -C "$checkout" status --porcelain --untracked-files=all)" ]] || {
    echo "verified source checkout is dirty: $id" >&2
    return 1
  }
  [[ -f "$resolved_file" ]] || { echo "resolved source record missing" >&2; return 1; }
  recorded="$(jq -r --arg id "$id" '.sources[] | select(.id == $id) | .commit' "$resolved_file")"
  [[ "$recorded" == "$expected" ]] || { echo "resolved record mismatch for $id" >&2; return 1; }
}

mapfile -t records < <(jq -c '.tools[], (.dependencies[]? // empty)' "$lock_file")
for record in "${records[@]}"; do validate_record "$record"; done

if $verify_only; then
  for record in "${records[@]}"; do verify_checkout "$record"; done
  echo "verified ${#records[@]} pinned native source checkouts"
  exit 0
fi

mkdir -p "$source_root" "$license_root" "$(dirname "$resolved_file")"
resolved_lines="$(mktemp "${TMPDIR:-/tmp}/fileforge-resolved.XXXXXX")"
trap 'rm -f "$resolved_lines"' EXIT
: > "$resolved_lines"

for record in "${records[@]}"; do
  id="$(jq -r '.id' <<<"$record")"
  repository="$(jq -r '.repository' <<<"$record")"
  ref="$(jq -r '.ref' <<<"$record")"
  expected="$(jq -r '.expectedCommit' <<<"$record")"
  checkout="$source_root/$id"
  if [[ ! -d "$checkout/.git" ]]; then
    mkdir -p "$checkout"
    git -C "$checkout" init -q
    git -C "$checkout" remote add origin "$repository"
  fi
  [[ "$(git -C "$checkout" remote get-url origin)" == "$repository" ]] || {
    echo "source remote mismatch for $id" >&2
    exit 1
  }
  git -C "$checkout" fetch --depth 1 --force origin "$ref"
  actual="$(git -C "$checkout" rev-parse FETCH_HEAD^{commit})"
  [[ "$actual" == "$expected" ]] || {
    echo "source ref moved for $id: expected $expected, resolved $actual" >&2
    exit 1
  }
  git -C "$checkout" checkout --detach --force "$actual"
  [[ -z "$(git -C "$checkout" status --porcelain --untracked-files=all)" ]] || {
    echo "source checkout is dirty after checkout: $id" >&2
    exit 1
  }
  jq -cn --arg id "$id" --arg repository "$repository" --arg ref "$ref" --arg commit "$actual" \
    '{id:$id,repository:$repository,ref:$ref,commit:$commit}' >> "$resolved_lines"
done

resolved_tmp="$resolved_file.tmp"
jq -s '{schemaVersion:1,sources:.}' "$resolved_lines" > "$resolved_tmp"
mv "$resolved_tmp" "$resolved_file"

copy_license() {
  local id="$1" source="$2"
  [[ -f "$source" ]] || { echo "upstream license missing for $id: $source" >&2; exit 1; }
  install -m 0644 "$source" "$license_root/$id-$(basename "$source")"
}
copy_license qpdf "$source_root/qpdf/LICENSE.txt"
copy_license qpdf "$source_root/qpdf/NOTICE.md"
copy_license oxipng "$source_root/oxipng/LICENSE"
copy_license jpegtran "$source_root/jpegtran/LICENSE.md"
copy_license zopflipng "$source_root/zopflipng/COPYING"
copy_license aosp-system-core "$source_root/aosp-system-core/NOTICE"
install -m 0644 "$source_root/aosp-system-core/libutils/NOTICE" "$license_root/zipalign-Apache-2.0.txt"

for record in "${records[@]}"; do verify_checkout "$record"; done
echo "fetched and verified ${#records[@]} pinned native source checkouts"
