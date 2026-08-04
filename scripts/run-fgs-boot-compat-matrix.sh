#!/usr/bin/env bash
set -euo pipefail

readonly ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly GRADLEW="${GRADLEW:-$ROOT_DIR/gradlew}"
readonly TARGET_PACKAGE="${FOXHOLE_ANDROID_TEST_TARGET_PACKAGE:-com.foxhole.guard.debug}"
readonly TIMESTAMP="$(date +%Y%m%d-%H%M%S)"
readonly ARTIFACT_ROOT="${FOXHOLE_FGS_BOOT_COMPAT_ARTIFACT_ROOT:-$ROOT_DIR/build/fgs-boot-compat-matrix/$TIMESTAMP}"
readonly DEFAULT_REQUIRED_SPECS="com.foxhole.core.runtime.BootReceiverRestoreAndroidTest,com.foxhole.guard.ui.cli.CliAppNavigationTest"
readonly DEFAULT_COMPAT_FLAGS=("FGS_BOOT_COMPLETED_RESTRICTIONS")
readonly COMPAT_FLAGS_RAW="${FOXHOLE_FGS_BOOT_COMPAT_FLAGS:-${DEFAULT_COMPAT_FLAGS[*]}}"

usage() {
  cat <<'EOF'
Usage:
  ANDROID_SERIAL=emulator-5554 scripts/run-fgs-boot-compat-matrix.sh

Optional environment:
  FOXHOLE_ANDROID_TEST_TARGET_PACKAGE=com.foxhole.guard.debug
  FOXHOLE_FGS_BOOT_COMPAT_ARTIFACT_ROOT=build/fgs-boot-compat-matrix/<label>
  FOXHOLE_FGS_BOOT_COMPAT_FLAGS="FGS_BOOT_COMPLETED_RESTRICTIONS"
  FOXHOLE_REQUIRED_CONNECTED_TEST_SPECS=com.foxhole.core.runtime.BootReceiverRestoreAndroidTest,com.foxhole.guard.ui.cli.CliAppNavigationTest
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

if [[ -z "${ANDROID_SERIAL:-}" ]]; then
  echo "ANDROID_SERIAL must be set so the FGS/boot compat proof targets one explicit device." >&2
  exit 2
fi

cd "$ROOT_DIR"

adb_cmd=(adb -s "$ANDROID_SERIAL")
compat_flags=()
read -r -a compat_flags <<< "$COMPAT_FLAGS_RAW"
enabled_flags=()

capture_shell() {
  local output="$1"
  shift
  {
    printf '# command:'
    printf ' %q' "${adb_cmd[@]}" shell "$@"
    printf '\n# captured_at: %s\n\n' "$(date -Is)"
    "${adb_cmd[@]}" shell "$@"
  } > "$output" 2>&1 || true
}

capture_broadcast() {
  local label="$1"
  local action="$2"
  local output_dir="$ARTIFACT_ROOT/broadcasts/$label"
  mkdir -p "$output_dir"
  "${adb_cmd[@]}" logcat -c || true
  {
    printf '# action=%s\n# captured_at=%s\n\n' "$action" "$(date -Is)"
    "${adb_cmd[@]}" shell am broadcast --receiver-include-background -a "$action" -p "$TARGET_PACKAGE"
  } > "$output_dir/am-broadcast.txt" 2>&1 || true
  "${adb_cmd[@]}" logcat -d -v threadtime > "$output_dir/logcat-threadtime.log" 2>/dev/null || true
}

cleanup() {
  local flag
  for flag in "${enabled_flags[@]}"; do
    "${adb_cmd[@]}" shell am compat disable "$flag" "$TARGET_PACKAGE" \
      > "$ARTIFACT_ROOT/compat-disable-$flag.txt" 2>&1 || true
  done
  "$GRADLEW" --no-daemon --console=plain --stacktrace :app:installDebug \
    > "$ARTIFACT_ROOT/reinstall-debug.log" 2>&1 || true
}
trap cleanup EXIT

mkdir -p "$ARTIFACT_ROOT"/{broadcasts,connected,debug-state}

"${adb_cmd[@]}" wait-for-device
api_level="$("${adb_cmd[@]}" shell getprop ro.build.version.sdk | tr -d '\r')"
fingerprint="$("${adb_cmd[@]}" shell getprop ro.build.fingerprint | tr -d '\r')"
{
  printf 'captured_at=%s\n' "$(date -Is)"
  printf 'android_serial=%s\n' "$ANDROID_SERIAL"
  printf 'api_level=%s\n' "$api_level"
  printf 'fingerprint=%s\n' "$fingerprint"
  printf 'target_package=%s\n' "$TARGET_PACKAGE"
  printf 'compat_flags=%s\n' "$COMPAT_FLAGS_RAW"
} > "$ARTIFACT_ROOT/device.env"

"$GRADLEW" --no-daemon --console=plain --stacktrace :app:installDebug :app:installDebugAndroidTest

capture_shell "$ARTIFACT_ROOT/package-before.txt" dumpsys package "$TARGET_PACKAGE"
capture_shell "$ARTIFACT_ROOT/compat-before.txt" am compat enabled "$TARGET_PACKAGE"

for flag in "${compat_flags[@]}"; do
  [[ -n "$flag" ]] || continue
  if "${adb_cmd[@]}" shell am compat enable "$flag" "$TARGET_PACKAGE" \
      > "$ARTIFACT_ROOT/compat-enable-$flag.txt" 2>&1; then
    enabled_flags+=("$flag")
  else
    printf 'Compat flag %s could not be enabled on API %s; see compat-enable-%s.txt\n' \
      "$flag" "$api_level" "$flag" | tee -a "$ARTIFACT_ROOT/warnings.txt" >&2
  fi
done
capture_shell "$ARTIFACT_ROOT/compat-after-enable.txt" am compat enabled "$TARGET_PACKAGE"

export FOXHOLE_CONNECTED_TEST_ARTIFACT_ROOT="$ARTIFACT_ROOT/connected"
export FOXHOLE_REQUIRED_CONNECTED_TEST_SPECS="${FOXHOLE_REQUIRED_CONNECTED_TEST_SPECS:-$DEFAULT_REQUIRED_SPECS}"
export FOXHOLE_REQUIRE_CONNECTED_QA_MATRIX=0
bash scripts/run-connected-android-tests.sh | tee "$ARTIFACT_ROOT/run-connected-android-tests.log"

capture_broadcast boot-completed android.intent.action.BOOT_COMPLETED
capture_broadcast package-replaced android.intent.action.MY_PACKAGE_REPLACED

FOXHOLE_PACKAGE="$TARGET_PACKAGE" \
FOXHOLE_OUTPUT_DIR="$ARTIFACT_ROOT/debug-state/final" \
  scripts/collect-foxhole-debug-state.sh "$ARTIFACT_ROOT/debug-state/final" \
  | tee "$ARTIFACT_ROOT/collect-foxhole-debug-state.log"

python3 scripts/analyze-android-perf-logs.py \
  --fail-on-fatal \
  --fail-on-anr \
  --fail-on-oom \
  --fail-on-strict-disk \
  "$ARTIFACT_ROOT" \
  | tee "$ARTIFACT_ROOT/perf-log-summary.txt"

printf 'FGS/boot compat proof artifacts: %s\n' "$ARTIFACT_ROOT"
