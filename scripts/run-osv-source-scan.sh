#!/usr/bin/env bash
set -euo pipefail

REPORT_DIR="${FOXHOLE_SECURITY_REPORT_DIR:-build/reports/security}"
mkdir -p "$REPORT_DIR"

OSV_VERSION="${OSV_VERSION:-v2.3.5}"
OSV_ASSET="${OSV_ASSET:-}"
SCAN_TMP="${RUNNER_TEMP:-${TMPDIR:-/tmp}}"
REPORT_PATH="${OSV_REPORT_PATH:-$REPORT_DIR/osv-source.json}"
mkdir -p "$(dirname "$REPORT_PATH")"

if [[ -z "$OSV_ASSET" ]]; then
  case "$(uname -s):$(uname -m)" in
    Linux:x86_64 | Linux:amd64) OSV_ASSET="osv-scanner_linux_amd64" ;;
    Linux:aarch64 | Linux:arm64) OSV_ASSET="osv-scanner_linux_arm64" ;;
    Darwin:x86_64 | Darwin:amd64) OSV_ASSET="osv-scanner_darwin_amd64" ;;
    Darwin:arm64) OSV_ASSET="osv-scanner_darwin_arm64" ;;
    *)
      echo "Unsupported OS/architecture for osv-scanner: $(uname -s) $(uname -m)" >&2
      exit 1
      ;;
  esac
fi

OSV_CACHE_PREFIX="$SCAN_TMP/osv-scanner-$OSV_VERSION"
OSV_SCANNER_PATH="$OSV_CACHE_PREFIX-$OSV_ASSET"
OSV_SUMS_PATH="$OSV_CACHE_PREFIX-SHA256SUMS"

download_file() {
  local destination="$1"
  local url="$2"
  curl --continue-at - --connect-timeout 20 --max-time 1800 --speed-limit 1024 --speed-time 30 --retry 3 --retry-all-errors -fsSL -o "$destination" "$url"
}

download_if_missing() {
  local destination="$1"
  local url="$2"
  [[ -s "$destination" ]] || download_file "$destination" "$url"
}

download_if_missing "$OSV_SUMS_PATH" "https://github.com/google/osv-scanner/releases/download/$OSV_VERSION/osv-scanner_SHA256SUMS"

checksum_line="$(grep "  $OSV_ASSET\$" "$OSV_SUMS_PATH")"
checksum="${checksum_line%% *}"

verify_scanner_checksum() {
  if command -v sha256sum >/dev/null 2>&1; then
    printf '%s  %s\n' "$checksum" "$OSV_SCANNER_PATH" | sha256sum -c -
  else
    printf '%s  %s\n' "$checksum" "$OSV_SCANNER_PATH" | shasum -a 256 -c -
  fi
}

if ! verify_scanner_checksum >/dev/null 2>&1; then
  download_file "$OSV_SCANNER_PATH" "https://github.com/google/osv-scanner/releases/download/$OSV_VERSION/$OSV_ASSET"
fi
if ! verify_scanner_checksum; then
  rm -f "$OSV_SCANNER_PATH"
  download_file "$OSV_SCANNER_PATH" "https://github.com/google/osv-scanner/releases/download/$OSV_VERSION/$OSV_ASSET"
  verify_scanner_checksum
fi
chmod +x "$OSV_SCANNER_PATH"

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/native-deps.sh"
ndk_root="${ANDROID_NDK_HOME:-${ANDROID_HOME:?Android SDK is required}/ndk/29.0.14206865}"
python3 scripts/verify-tor-package-boundary.py "$native_deps_dir" "$ndk_root" "$REPORT_DIR"

core_root="${FOXCORE_SOURCE_ROOT:-$(dirname "$native_deps_repo_root")/foxhole-core}"
(cd "$core_root" && scripts/tor-rsa-gate.sh)
scan_inputs=()
if [[ $# -gt 0 ]]; then
  for sbom in "$@"; do
    [[ -s "$sbom" ]] || { echo "Missing dependency SBOM: $sbom" >&2; exit 1; }
    scan_inputs+=(--sbom "$sbom")
  done
else
  # Re-resolve under the same project lockfiles and verification policy on every gate run.
  # The metadata checksum catalog may contain historical artifacts, so it is not a dependency graph.
  ./gradlew --no-daemon --console=plain --max-workers=2 \
    -I scripts/resolved-maven-inventory.init.gradle \
    -Pfoxhole.mavenInventoryDir="$REPORT_DIR" foxholeResolvedMavenInventory
  for scope in runtime build; do
    scan_inputs+=(--sbom "$REPORT_DIR/maven-$scope.cdx.json")
  done
  scan_inputs+=(--sbom "$REPORT_DIR/tor-source.cdx.json")
fi
rm -f "$REPORT_PATH"
set +e
"$OSV_SCANNER_PATH" scan source "${scan_inputs[@]}" \
  --verbosity error \
  --format json \
  --output-file "$REPORT_PATH"
scanner_status=$?
echo "osv-scanner exit code: $scanner_status"
set -e
if [[ "$scanner_status" -ne 0 && "$scanner_status" -ne 1 ]]; then
  exit "$scanner_status"
fi
python3 scripts/classify-osv-report.py "$REPORT_PATH" config/osv-exceptions.json \
  --scanner-status "$scanner_status"
