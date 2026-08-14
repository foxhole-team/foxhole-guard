#!/usr/bin/env bash
set -euo pipefail

mkdir -p build/reports/security

OSV_VERSION="${OSV_VERSION:-v2.3.5}"
OSV_ASSET="${OSV_ASSET:-}"
SCAN_TMP="${RUNNER_TEMP:-${TMPDIR:-/tmp}}"
REPORT_PATH="build/reports/security/osv-source.json"

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

rm -f "$REPORT_PATH"
set +e
"$OSV_SCANNER_PATH" scan source -r . \
  --verbosity error \
  --experimental-exclude app/build \
  --experimental-exclude build \
  --experimental-exclude output \
  --experimental-exclude artifacts \
  --experimental-exclude .tmp \
  --format json \
  --output-file "$REPORT_PATH"
scanner_status=$?
echo "osv-scanner exit code: $scanner_status"
set -e
if [[ "$scanner_status" -ne 0 && "$scanner_status" -ne 1 ]]; then
  exit "$scanner_status"
fi

python3 - <<'PY'
import json
import pathlib
import sys

report_path = pathlib.Path("build/reports/security/osv-source.json")
if not report_path.exists():
    sys.exit("OSV report was not generated")
data = json.loads(report_path.read_text())
runtime_findings = []
build_metadata_findings = []
for result in data.get("results", []):
    source_path = result.get("source", {}).get("path", "")
    for package in result.get("packages", []):
        vulnerabilities = package.get("vulnerabilities", [])
        if not vulnerabilities:
            continue
        package_name = package.get("package", {}).get("name", "unknown")
        ids = ",".join(vulnerability.get("id", "unknown") for vulnerability in vulnerabilities)
        finding = f"{source_path}: {package_name} [{ids}]"
        if source_path.endswith("gradle/verification-metadata.xml"):
            build_metadata_findings.append(finding)
        else:
            runtime_findings.append(finding)
if build_metadata_findings:
    print("Known build-tool metadata OSV findings:")
    for finding in build_metadata_findings:
        print(f"- {finding}")
if runtime_findings:
    print("Runtime/source dependency OSV findings:")
    for finding in runtime_findings:
        print(f"- {finding}")
    sys.exit(1)
PY
