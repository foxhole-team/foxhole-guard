#!/usr/bin/env bash
set -euo pipefail

readonly ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly GRADLEW="${GRADLEW:-$ROOT_DIR/gradlew}"
readonly TIMESTAMP="$(date +%Y%m%d-%H%M%S)"
readonly ARTIFACT_ROOT="${FOXHOLE_API_MATRIX_ARTIFACT_ROOT:-$ROOT_DIR/build/api-compat-matrix/$TIMESTAMP}"
readonly SUMMARY_FILE="$ARTIFACT_ROOT/summary.tsv"
readonly DEFAULT_API_LEVELS=(26 29 30 33 34 35 36 37)
readonly DEFAULT_MATRIX_SPECS="com.foxhole.core.runtime.BootReceiverRestoreAndroidTest,com.foxhole.guard.ui.cli.CliAppNavigationTest,com.foxhole.guard.ui.cli.home.CliHomeRuntimeBehaviorTest"
readonly MATRIX_SERIALS_RAW="${FOXHOLE_API_MATRIX_SERIALS:-}"
readonly ALLOW_PARTIAL="${FOXHOLE_API_MATRIX_ALLOW_PARTIAL:-0}"
readonly RUN_FGS_COMPAT="${FOXHOLE_API_MATRIX_RUN_FGS_COMPAT:-1}"

usage() {
  cat <<'EOF'
Usage:
  FOXHOLE_API_MATRIX_SERIALS='26:emulator-5556 29:emulator-5558 30:emulator-5560 33:emulator-5562 34:emulator-5564 35:emulator-5566 36:emulator-5568 37:emulator-5570' \
    scripts/run-api-compatibility-matrix.sh

Optional environment:
  FOXHOLE_API_MATRIX_SERIALS       Required api:serial pairs, separated by spaces, commas, or newlines.
  FOXHOLE_API_MATRIX_LEVELS        API list to require, default: 26 29 30 33 34 35 36 37.
  FOXHOLE_API_MATRIX_ALLOW_PARTIAL Set to 1 to run only provided serials and mark missing APIs.
  FOXHOLE_API_MATRIX_RUN_FGS_COMPAT Set to 0 to skip API 34+ FGS boot compat runner.
  FOXHOLE_API_MATRIX_SPECS         Connected test specs for each API.
  FOXHOLE_API_MATRIX_ARTIFACT_ROOT Artifact root, default: build/api-compat-matrix/<timestamp>.
EOF
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

if [[ $# -gt 0 ]]; then
  usage >&2
  exit 2
fi

if [[ -z "$MATRIX_SERIALS_RAW" ]]; then
  echo "FOXHOLE_API_MATRIX_SERIALS must provide explicit api:serial pairs." >&2
  exit 2
fi

cd "$ROOT_DIR"
mkdir -p "$ARTIFACT_ROOT"
printf 'api\tserial\tdevice_api\tconnected_status\tfgs_status\tartifact_dir\n' > "$SUMMARY_FILE"

api_levels=()
if [[ -n "${FOXHOLE_API_MATRIX_LEVELS:-}" ]]; then
  read -r -a api_levels <<< "$FOXHOLE_API_MATRIX_LEVELS"
else
  api_levels=("${DEFAULT_API_LEVELS[@]}")
fi

declare -A serial_by_api=()
normalized_pairs="$(
  printf '%s\n' "$MATRIX_SERIALS_RAW" |
    tr ',\n' '  ' |
    xargs -n1 printf '%s\n'
)"
while IFS= read -r pair; do
  [[ -n "$pair" ]] || continue
  api="${pair%%:*}"
  serial="${pair#*:}"
  if [[ -z "$api" || -z "$serial" || "$api" == "$serial" ]]; then
    echo "Invalid FOXHOLE_API_MATRIX_SERIALS entry: $pair" >&2
    exit 2
  fi
  serial_by_api["$api"]="$serial"
done <<< "$normalized_pairs"

run_api_entry() {
  local api="$1"
  local serial="$2"
  local api_dir="$ARTIFACT_ROOT/api-$api"
  local connected_status=0
  local fgs_status=0
  mkdir -p "$api_dir"

  export ANDROID_SERIAL="$serial"
  adb -s "$serial" wait-for-device
  device_api="$(adb -s "$serial" shell getprop ro.build.version.sdk | tr -d '\r')"
  fingerprint="$(adb -s "$serial" shell getprop ro.build.fingerprint | tr -d '\r')"
  {
    printf 'api=%s\n' "$api"
    printf 'serial=%s\n' "$serial"
    printf 'device_api=%s\n' "$device_api"
    printf 'fingerprint=%s\n' "$fingerprint"
  } > "$api_dir/device.env"
  if [[ "$device_api" != "$api" ]]; then
    echo "API matrix serial $serial expected API $api but device reports API $device_api" >&2
    return 1
  fi

  export FOXHOLE_CONNECTED_TEST_ARTIFACT_ROOT="$api_dir/connected"
  export FOXHOLE_REQUIRED_CONNECTED_TEST_SPECS="${FOXHOLE_API_MATRIX_SPECS:-$DEFAULT_MATRIX_SPECS}"
  export FOXHOLE_REQUIRE_CONNECTED_QA_MATRIX=0
  bash scripts/run-connected-android-tests.sh > "$api_dir/run-connected-android-tests.log" 2>&1 || connected_status=$?
  "$GRADLEW" --no-daemon --console=plain --stacktrace :app:installDebug \
    > "$api_dir/reinstall-debug-after-connected.log" 2>&1 || true

  if [[ "$RUN_FGS_COMPAT" == "1" && "$api" -ge 34 ]]; then
    FOXHOLE_FGS_BOOT_COMPAT_ARTIFACT_ROOT="$api_dir/fgs-boot-compat" \
      scripts/run-fgs-boot-compat-matrix.sh \
      > "$api_dir/run-fgs-boot-compat-matrix.log" 2>&1 || fgs_status=$?
  fi

  printf '%s\t%s\t%s\t%s\t%s\t%s\n' \
    "$api" "$serial" "$device_api" "$connected_status" "$fgs_status" "$api_dir" >> "$SUMMARY_FILE"
  if [[ "$connected_status" -ne 0 || "$fgs_status" -ne 0 ]]; then
    return 1
  fi
}

missing=()
status=0
for api in "${api_levels[@]}"; do
  serial="${serial_by_api[$api]:-}"
  if [[ -z "$serial" ]]; then
    missing+=("$api")
    printf '%s\t\tmissing\tnot-run\tnot-run\t\n' "$api" >> "$SUMMARY_FILE"
    continue
  fi
  run_api_entry "$api" "$serial" || status=$?
done

if [[ "${#missing[@]}" -gt 0 && "$ALLOW_PARTIAL" != "1" ]]; then
  echo "API compatibility matrix missing required APIs: ${missing[*]}" >&2
  status=1
fi

echo "::group::api compatibility matrix summary"
cat "$SUMMARY_FILE"
echo "::endgroup::"

exit "$status"
