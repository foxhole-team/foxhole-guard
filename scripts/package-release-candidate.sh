#!/usr/bin/env bash
set -euo pipefail

# Build once on dev, publish the exact same signed bytes from main. This script owns the
# candidate's names and manifest so the two workflows cannot silently drift.

usage() {
  cat >&2 <<'USAGE'
usage:
  scripts/package-release-candidate.sh prepare OUTPUT_DIR SOURCE_COMMIT SOURCE_TREE
  scripts/package-release-candidate.sh verify  CANDIDATE_DIR [SOURCE_COMMIT SOURCE_TREE]
  scripts/package-release-candidate.sh published-code OWNER/REPOSITORY

Certificate source:
  config/release-cert-sha256.txt is authoritative. EXPECTED_RELEASE_CERT_SHA256 may be
  supplied by a caller, but it must match that file and the fingerprints documented in
  README.md and docs/README.ru.md.

prepare also reads the split release APKs from app/build/outputs/apk/release and the
CycloneDX JSON from FOXHOLE_SBOM_PATH (default: build/reports/cyclonedx/bom.json).
USAGE
  exit 2
}

die() {
  printf '%s\n' "$*" >&2
  exit 1
}

sha256_file() {
  local file=$1
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$file" | awk '{print $1}'
  else
    shasum -a 256 "$file" | awk '{print $1}'
  fi
}

find_build_tool() {
  local name=$1
  local sdk_root=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}
  if [[ -n "$sdk_root" ]]; then
    local candidate
    candidate="$(find "$sdk_root/build-tools" -type f -name "$name" 2>/dev/null | LC_ALL=C sort | tail -n 1)"
    if [[ -n "$candidate" && -x "$candidate" ]]; then
      printf '%s\n' "$candidate"
      return
    fi
  fi
  command -v "$name" 2>/dev/null || die "$name was not found in Android build-tools or PATH"
}

read_release_coordinates() {
  VERSION_CODE="$(
    sed -nE 's/^[[:space:]]*versionCode[[:space:]]*=[[:space:]]*([0-9]+).*$/\1/p' \
      app/build.gradle.kts | head -n 1
  )"
  VERSION_NAME="$(
    sed -nE 's/^[[:space:]]*versionName[[:space:]]*=[[:space:]]*"([^"]+)".*$/\1/p' \
      app/build.gradle.kts | head -n 1
  )"
  CORE_REVISION="$(tr -d '[:space:]' < config/foxcore-revision.txt)"

  [[ "$VERSION_CODE" =~ ^[1-9][0-9]*$ ]] || die "invalid app versionCode: $VERSION_CODE"
  [[ "$VERSION_NAME" =~ ^[0-9]+\.[0-9]+\.[0-9]+([+-][0-9A-Za-z.-]+)?$ ]] ||
    die "invalid release versionName: $VERSION_NAME"
  [[ "$CORE_REVISION" =~ ^[0-9a-f]{40}$ ]] || die "invalid pinned FoxHole Core revision"
  RELEASE_TAG="v$VERSION_NAME"
}

normalized_expected_cert() {
  local cert_file=config/release-cert-sha256.txt
  [[ -f "$cert_file" ]] || die "release certificate source is missing: $cert_file"

  local configured supplied
  configured="$(tr -d '[:space:]: ' < "$cert_file" | tr '[:upper:]' '[:lower:]')"
  [[ "$configured" =~ ^[0-9a-f]{64}$ ]] || die "invalid release certificate in $cert_file"

  supplied="$(printf '%s' "${EXPECTED_RELEASE_CERT_SHA256:-$configured}" | tr -d ': ' | tr '[:upper:]' '[:lower:]')"
  [[ "$supplied" == "$configured" ]] || die "release certificate environment does not match $cert_file"

  local readme
  for readme in README.md docs/README.ru.md; do
    [[ "$(grep -Fxc "$configured" "$readme")" == "1" ]] ||
      die "$readme must document the authoritative release certificate exactly once"
  done
  printf '%s' "$configured"
}

verify_apk() {
  local apk=$1
  local cert_report=$2
  local expected_cert=$3
  local apksigner=$4
  local aapt2=$5

  local report actual package_line native_line
  report="$($apksigner verify --verbose --print-certs "$apk")"
  printf '%s\n' "$report" >> "$cert_report"
  grep -Fq 'Verified using v2 scheme (APK Signature Scheme v2): true' <<<"$report" ||
    die "APK Signature Scheme v2 is missing from $(basename "$apk")"
  actual="$(
    awk -F': ' '/certificate SHA-256 digest/ { print $NF; exit }' <<<"$report" |
      tr -d ':' |
      tr '[:upper:]' '[:lower:]'
  )"
  [[ -n "$actual" ]] || die "could not read signing certificate digest from $(basename "$apk")"
  [[ "$actual" == "$expected_cert" ]] || die "release certificate mismatch for $(basename "$apk")"

  package_line="$($aapt2 dump badging "$apk" | sed -n '1p')"
  [[ "$package_line" == *"name='com.foxhole.guard'"* ]] || die "unexpected package in $(basename "$apk")"
  [[ "$package_line" == *"versionCode='$VERSION_CODE'"* ]] || die "unexpected versionCode in $(basename "$apk")"
  [[ "$package_line" == *"versionName='$VERSION_NAME'"* ]] || die "unexpected versionName in $(basename "$apk")"
  native_line="$($aapt2 dump badging "$apk" | sed -n '/^native-code:/p' | sed -n '1p')"
  [[ "$native_line" == "native-code: 'arm64-v8a'" ]] ||
    die "release APK has an unexpected native ABI inventory: $(basename "$apk")"
  "$aapt2" dump permissions "$apk" |
    grep -F "uses-permission: name='android.permission.REQUEST_INSTALL_PACKAGES'" >/dev/null ||
    die "GitHub release APK is missing REQUEST_INSTALL_PACKAGES: $(basename "$apk")"
}

prepare_candidate() {
  local output_dir=$1
  local source_commit=$2
  local source_tree=$3
  [[ "$source_commit" =~ ^[0-9a-f]{40}$ ]] || die "invalid source commit"
  [[ "$source_tree" =~ ^[0-9a-f]{40}$ ]] || die "invalid source tree"
  mkdir -p "$output_dir"
  [[ -z "$(find "$output_dir" -mindepth 1 -maxdepth 1 -print -quit)" ]] ||
    die "candidate output directory is not empty: $output_dir"

  read_release_coordinates
  local expected_cert apksigner aapt2
  expected_cert=$(normalized_expected_cert)
  apksigner=$(find_build_tool apksigner)
  aapt2=$(find_build_tool aapt2)

  local -a apks=()
  while IFS= read -r apk; do
    apks+=("$apk")
  done < <(find app/build/outputs/apk/release -maxdepth 1 -type f -name '*.apk' | LC_ALL=C sort)
  [[ ${#apks[@]} -eq 1 ]] || die "expected exactly one arm64-v8a release APK, found ${#apks[@]}"
  local arm64_source
  arm64_source=$(printf '%s\n' "${apks[@]}" | grep 'arm64-v8a' || true)
  [[ -f "$arm64_source" ]] || die "arm64-v8a release APK was not produced"

  local arm64_name="FoxHole-${RELEASE_TAG}-arm64-v8a-release.apk"
  cp "$arm64_source" "$output_dir/$arm64_name"

  local sbom_source=${FOXHOLE_SBOM_PATH:-build/reports/cyclonedx/bom.json}
  [[ -f "$sbom_source" ]] || die "CycloneDX SBOM was not generated: $sbom_source"
  local sbom_name="FoxHole-${RELEASE_TAG}-sbom.cdx.json"
  cp "$sbom_source" "$output_dir/$sbom_name"

  local cert_report="$output_dir/release-certs.txt"
  : > "$cert_report"
  printf '== %s ==\n' "$arm64_name" >> "$cert_report"
  verify_apk "$output_dir/$arm64_name" "$cert_report" "$expected_cert" "$apksigner" "$aapt2"

  local arm64_sha
  arm64_sha=$(sha256_file "$output_dir/$arm64_name")
  jq -n \
    --argjson versionCode "$VERSION_CODE" \
    --arg versionName "$VERSION_NAME" \
    --arg apkName "$arm64_name" \
    --arg apkSha256 "$arm64_sha" \
    '{
      versionCode: $versionCode,
      versionName: $versionName,
      apkName: $apkName,
      apkSha256: $apkSha256,
      notes: "See the release notes on GitHub."
    }' > "$output_dir/update-manifest.json"

  jq -n \
    --arg sourceCommit "$source_commit" \
    --arg sourceTree "$source_tree" \
    --arg versionName "$VERSION_NAME" \
    --argjson versionCode "$VERSION_CODE" \
    --arg tag "$RELEASE_TAG" \
    --arg coreRevision "$CORE_REVISION" \
    --arg arm64Apk "$arm64_name" \
    --arg sbom "$sbom_name" \
    '{
      schema: 1,
      sourceCommit: $sourceCommit,
      sourceTree: $sourceTree,
      versionName: $versionName,
      versionCode: $versionCode,
      tag: $tag,
      coreRevision: $coreRevision,
      arm64Apk: $arm64Apk,
      sbom: $sbom,
      updateChannel: "github"
    }' > "$output_dir/CANDIDATE.json"

  : > "$output_dir/SHA256SUMS"
  local file
  for file in \
    "$arm64_name" \
    "$sbom_name" \
    release-certs.txt \
    update-manifest.json \
    CANDIDATE.json
  do
    printf '%s  %s\n' "$(sha256_file "$output_dir/$file")" "$file" >> "$output_dir/SHA256SUMS"
  done
  verify_candidate "$output_dir" "$source_commit" "$source_tree"
}

verify_candidate() {
  local candidate_dir=$1
  local expected_source_commit=${2:-}
  local expected_source_tree=${3:-}
  read_release_coordinates
  [[ -f "$candidate_dir/CANDIDATE.json" ]] || die "candidate metadata is missing"
  [[ -f "$candidate_dir/SHA256SUMS" ]] || die "candidate checksums are missing"

  local canonical_arm64_name="FoxHole-${RELEASE_TAG}-arm64-v8a-release.apk"
  local canonical_sbom_name="FoxHole-${RELEASE_TAG}-sbom.cdx.json"
  local expected_files actual_files
  expected_files="$(
    printf '%s\n' \
      CANDIDATE.json \
      SHA256SUMS \
      "$canonical_arm64_name" \
      "$canonical_sbom_name" \
      release-certs.txt \
      update-manifest.json |
      LC_ALL=C sort
  )"
  actual_files="$(
    find "$candidate_dir" -mindepth 1 -maxdepth 1 -type f -exec basename {} \; |
      LC_ALL=C sort
  )"
  [[ "$actual_files" == "$expected_files" ]] || die "candidate contains an unexpected file set"
  if find "$candidate_dir" -mindepth 1 -maxdepth 1 ! -type f -print -quit | grep -q .; then
    die "candidate contains a directory, symlink, or special file"
  fi

  (
    cd "$candidate_dir"
    if command -v sha256sum >/dev/null 2>&1; then
      sha256sum --check --strict SHA256SUMS
    else
      shasum -a 256 -c SHA256SUMS
    fi
  )

  local metadata="$candidate_dir/CANDIDATE.json"
  local source_commit source_tree arm64_name sbom_name
  source_commit=$(jq -er '.sourceCommit' "$metadata")
  source_tree=$(jq -er '.sourceTree' "$metadata")
  arm64_name=$(jq -er '.arm64Apk' "$metadata")
  sbom_name=$(jq -er '.sbom' "$metadata")
  [[ "$source_commit" =~ ^[0-9a-f]{40}$ ]] || die "candidate source commit is invalid"
  [[ "$source_tree" =~ ^[0-9a-f]{40}$ ]] || die "candidate source tree is invalid"
  [[ "$arm64_name" == "$canonical_arm64_name" ]] ||
    die "candidate arm64 APK name is not canonical"
  [[ "$sbom_name" == "$canonical_sbom_name" ]] ||
    die "candidate SBOM name is not canonical"
  [[ -z "$expected_source_commit" || "$source_commit" == "$expected_source_commit" ]] ||
    die "candidate source commit does not match the selected dev run"
  [[ -z "$expected_source_tree" || "$source_tree" == "$expected_source_tree" ]] ||
    die "candidate source tree does not match main"

  jq -e \
    --arg versionName "$VERSION_NAME" \
    --argjson versionCode "$VERSION_CODE" \
    --arg tag "$RELEASE_TAG" \
    --arg coreRevision "$CORE_REVISION" '
      .schema == 1 and
      .versionName == $versionName and
      .versionCode == $versionCode and
      .tag == $tag and
      .coreRevision == $coreRevision and
      .updateChannel == "github"
    ' "$metadata" >/dev/null

  for file in "$arm64_name" "$sbom_name" release-certs.txt update-manifest.json; do
    [[ -f "$candidate_dir/$file" ]] || die "candidate file is missing: $file"
  done

  jq -e \
    --arg versionName "$VERSION_NAME" '
      .metadata.component.name == "foxhole-android" and
      .metadata.component.version == $versionName
    ' "$candidate_dir/$sbom_name" >/dev/null

  local arm64_sha
  arm64_sha=$(sha256_file "$candidate_dir/$arm64_name")
  jq -e \
    --argjson versionCode "$VERSION_CODE" \
    --arg versionName "$VERSION_NAME" \
    --arg apkName "$arm64_name" \
    --arg apkSha256 "$arm64_sha" '
      .versionCode == $versionCode and
      .versionName == $versionName and
      .apkName == $apkName and
      .apkSha256 == $apkSha256
    ' "$candidate_dir/update-manifest.json" >/dev/null

  local expected_cert apksigner aapt2 verify_report
  expected_cert=$(normalized_expected_cert)
  apksigner=$(find_build_tool apksigner)
  aapt2=$(find_build_tool aapt2)
  verify_report=$(mktemp)
  verify_apk "$candidate_dir/$arm64_name" "$verify_report" "$expected_cert" "$apksigner" "$aapt2"
  rm -f "$verify_report"
}

published_version_code() {
  local repository=$1
  [[ "$repository" =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]] || die "invalid GitHub repository"
  command -v gh >/dev/null 2>&1 || die "gh is required to inspect published releases"
  command -v jq >/dev/null 2>&1 || die "jq is required to inspect published releases"

  local releases_json manifest_json
  releases_json=$(mktemp)
  manifest_json=$(mktemp)
  gh api -X GET "repos/$repository/releases" -f per_page=100 > "$releases_json"

  local release_id
  release_id=$(jq -r 'map(select(.draft == false))[0].id // empty' "$releases_json")
  if [[ -z "$release_id" ]]; then
    rm -f "$releases_json" "$manifest_json"
    printf '0\n'
    return
  fi

  local manifest_count manifest_url
  manifest_count=$(
    jq --argjson releaseId "$release_id" '
      map(select(.id == $releaseId))[0].assets |
      map(select(.name == "update-manifest.json")) |
      length
    ' "$releases_json"
  )
  [[ "$manifest_count" == "1" ]] ||
    die "latest published release must contain exactly one update-manifest.json"
  manifest_url=$(
    jq -er --argjson releaseId "$release_id" '
      map(select(.id == $releaseId))[0].assets |
      map(select(.name == "update-manifest.json"))[0].url
    ' "$releases_json"
  )
  gh api -X GET -H 'Accept: application/octet-stream' "$manifest_url" > "$manifest_json"

  local version_code
  version_code=$(jq -er '.versionCode | select(type == "number" and floor == . and . >= 1)' "$manifest_json")
  [[ "$version_code" =~ ^[1-9][0-9]*$ ]] || die "published update manifest has an invalid versionCode"
  rm -f "$releases_json" "$manifest_json"
  printf '%s\n' "$version_code"
}

[[ $# -ge 2 ]] || usage
command=$1
shift
case "$command" in
  prepare)
    [[ $# -eq 3 ]] || usage
    prepare_candidate "$@"
    ;;
  verify)
    [[ $# -ge 1 && $# -le 3 ]] || usage
    verify_candidate "$@"
    ;;
  published-code)
    [[ $# -eq 1 ]] || usage
    published_version_code "$@"
    ;;
  *) usage ;;
esac
