#!/usr/bin/env bash
set -euo pipefail

readonly PACKAGE_NAME="${FOXHOLE_PACKAGE:-com.foxhole.guard.debug}"
readonly TIMESTAMP="$(date +%Y%m%d-%H%M%S)"
readonly OUTPUT_DIR="${FOXHOLE_OUTPUT_DIR:-build/foxhole-debug-state/${TIMESTAMP}}"

usage() {
  cat <<EOF
Usage: ${0##*/} [output-dir]

Collects FoxHole Android debug state from adb into an artifact directory.

Environment:
  ANDROID_SERIAL       adb serial to target explicitly
  FOXHOLE_PACKAGE     package name to inspect (default: com.foxhole.guard.debug)
  FOXHOLE_OUTPUT_DIR  output directory (default: build/foxhole-debug-state/<timestamp>)
EOF
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

if [[ $# -gt 1 ]]; then
  usage >&2
  exit 2
fi

target_dir="${1:-$OUTPUT_DIR}"

adb_cmd=(adb)
if [[ -n "${ANDROID_SERIAL:-}" ]]; then
  adb_cmd+=(-s "$ANDROID_SERIAL")
fi

capture() {
  local name="$1"
  shift
  local path="$target_dir/$name"
  {
    printf '# command:'
    printf ' %q' "${adb_cmd[@]}" "$@"
    printf '\n# captured_at: %s\n\n' "$(date -Is)"
    "${adb_cmd[@]}" "$@"
  } > "$path" 2>&1 || true
}

capture_shell() {
  local name="$1"
  shift
  capture "$name" shell "$@"
}

command -v adb >/dev/null 2>&1 || {
  echo "adb is not on PATH" >&2
  exit 1
}

mkdir -p "$target_dir"

{
  printf 'captured_at=%s\n' "$(date -Is)"
  printf 'package=%s\n' "$PACKAGE_NAME"
  printf 'android_serial=%s\n' "${ANDROID_SERIAL:-}"
  printf 'output_dir=%s\n' "$target_dir"
} > "$target_dir/collection.env"

adb devices -l > "$target_dir/adb-devices.txt" 2>&1 || true
"${adb_cmd[@]}" wait-for-device

capture "logcat-threadtime.log" logcat -d -v threadtime
capture "logcat-all-threadtime.log" logcat -b all -d -v threadtime
capture_shell "dumpsys-meminfo.txt" dumpsys meminfo "$PACKAGE_NAME"
capture_shell "dumpsys-activity-processes.txt" dumpsys activity processes
capture_shell "dumpsys-connectivity.txt" dumpsys connectivity
capture_shell "dumpsys-vpn-management.txt" dumpsys vpn_management
capture_shell "ps-A.txt" ps -A
capture_shell "package-path.txt" pm path "$PACKAGE_NAME"
capture_shell "package-info.txt" dumpsys package "$PACKAGE_NAME"
capture_shell "package-list-foxhole.txt" pm list packages -f com.foxhole
capture_shell "appops.txt" appops get "$PACKAGE_NAME"

pid="$("${adb_cmd[@]}" shell pidof "$PACKAGE_NAME" 2>/dev/null | tr -d '\r' | awk '{print $1}' || true)"
if [[ -n "$pid" ]]; then
  capture_shell "proc-status.txt" cat "/proc/$pid/status"
  capture_shell "proc-smaps-rollup.txt" cat "/proc/$pid/smaps_rollup"
  capture_shell "proc-limits.txt" cat "/proc/$pid/limits"
else
  printf 'pid not found for %s\n' "$PACKAGE_NAME" > "$target_dir/proc-status.txt"
fi

printf 'FoxHole debug state collected in %s\n' "$target_dir"
