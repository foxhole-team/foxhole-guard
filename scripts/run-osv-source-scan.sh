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

curl -fsSL -o "$SCAN_TMP/$OSV_ASSET" "https://github.com/google/osv-scanner/releases/download/$OSV_VERSION/$OSV_ASSET"
curl -fsSL -o "$SCAN_TMP/osv-scanner_SHA256SUMS" "https://github.com/google/osv-scanner/releases/download/$OSV_VERSION/osv-scanner_SHA256SUMS"
(
  cd "$SCAN_TMP"
  if command -v sha256sum >/dev/null 2>&1; then
    grep "  $OSV_ASSET\$" osv-scanner_SHA256SUMS | sha256sum -c -
  else
    grep "  $OSV_ASSET\$" osv-scanner_SHA256SUMS | shasum -a 256 -c -
  fi
)
chmod +x "$SCAN_TMP/$OSV_ASSET"

rm -f "$REPORT_PATH"
set +e
"$SCAN_TMP/$OSV_ASSET" scan source -r . \
  --experimental-exclude app/build \
  --experimental-exclude build \
  --experimental-exclude output \
  --experimental-exclude artifacts \
  --experimental-exclude .tmp \
  --experimental-exclude third_party/sing-box \
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
