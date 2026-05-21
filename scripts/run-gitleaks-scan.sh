#!/usr/bin/env bash
set -euo pipefail

mkdir -p build/reports/security

GITLEAKS_VERSION="${GITLEAKS_VERSION:-8.24.2}"
GITLEAKS_TAG="${GITLEAKS_TAG:-v$GITLEAKS_VERSION}"
GITLEAKS_ASSET="${GITLEAKS_ASSET:-}"
SCAN_TMP="${RUNNER_TEMP:-${TMPDIR:-/tmp}}"
REPORT_PATH="build/reports/security/gitleaks.json"

if [[ -z "$GITLEAKS_ASSET" ]]; then
  case "$(uname -s):$(uname -m)" in
    Linux:x86_64 | Linux:amd64) GITLEAKS_ASSET="gitleaks_${GITLEAKS_VERSION}_linux_x64.tar.gz" ;;
    Linux:aarch64 | Linux:arm64) GITLEAKS_ASSET="gitleaks_${GITLEAKS_VERSION}_linux_arm64.tar.gz" ;;
    Darwin:x86_64 | Darwin:amd64) GITLEAKS_ASSET="gitleaks_${GITLEAKS_VERSION}_darwin_x64.tar.gz" ;;
    Darwin:arm64) GITLEAKS_ASSET="gitleaks_${GITLEAKS_VERSION}_darwin_arm64.tar.gz" ;;
    *)
      echo "Unsupported OS/architecture for gitleaks: $(uname -s) $(uname -m)" >&2
      exit 1
      ;;
  esac
fi

GITLEAKS_CACHE_PREFIX="$SCAN_TMP/gitleaks-$GITLEAKS_VERSION"
GITLEAKS_ARCHIVE_PATH="$GITLEAKS_CACHE_PREFIX-$GITLEAKS_ASSET"
GITLEAKS_SUMS_PATH="$GITLEAKS_CACHE_PREFIX-checksums.txt"
GITLEAKS_EXTRACT_DIR="$GITLEAKS_CACHE_PREFIX-${GITLEAKS_ASSET%.tar.gz}"
GITLEAKS_BIN_PATH="$GITLEAKS_EXTRACT_DIR/gitleaks"

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

download_if_missing "$GITLEAKS_SUMS_PATH" "https://github.com/gitleaks/gitleaks/releases/download/$GITLEAKS_TAG/gitleaks_${GITLEAKS_VERSION}_checksums.txt"

checksum_line="$(grep "  $GITLEAKS_ASSET\$" "$GITLEAKS_SUMS_PATH")"
checksum="${checksum_line%% *}"

verify_archive_checksum() {
  if command -v sha256sum >/dev/null 2>&1; then
    printf '%s  %s\n' "$checksum" "$GITLEAKS_ARCHIVE_PATH" | sha256sum -c -
  else
    printf '%s  %s\n' "$checksum" "$GITLEAKS_ARCHIVE_PATH" | shasum -a 256 -c -
  fi
}

if ! verify_archive_checksum >/dev/null 2>&1; then
  download_file "$GITLEAKS_ARCHIVE_PATH" "https://github.com/gitleaks/gitleaks/releases/download/$GITLEAKS_TAG/$GITLEAKS_ASSET"
fi
if ! verify_archive_checksum; then
  rm -f "$GITLEAKS_ARCHIVE_PATH"
  download_file "$GITLEAKS_ARCHIVE_PATH" "https://github.com/gitleaks/gitleaks/releases/download/$GITLEAKS_TAG/$GITLEAKS_ASSET"
  verify_archive_checksum
fi

mkdir -p "$GITLEAKS_EXTRACT_DIR"
tar -xzf "$GITLEAKS_ARCHIVE_PATH" -C "$GITLEAKS_EXTRACT_DIR" gitleaks
chmod +x "$GITLEAKS_BIN_PATH"

"$GITLEAKS_BIN_PATH" dir . \
  -c .gitleaks.toml \
  --redact \
  --no-banner \
  --report-format json \
  --report-path "$REPORT_PATH"
