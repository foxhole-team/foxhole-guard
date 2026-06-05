#!/usr/bin/env bash
set -euo pipefail

readonly ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly GRADLEW="${GRADLEW:-$ROOT_DIR/gradlew}"
readonly TARGET_PACKAGE="${FOXHOLE_ANDROID_TEST_TARGET_PACKAGE:-com.foxhole.beta.debug}"
readonly TIMESTAMP="$(date +%Y%m%d-%H%M%S)"
readonly LABEL="${FOXHOLE_DEVICE_PROOF_LABEL:-device-runtime}"
readonly ARTIFACT_ROOT="${FOXHOLE_DEVICE_PROOF_ARTIFACT_ROOT:-$ROOT_DIR/build/device-runtime-proof/$LABEL-$TIMESTAMP}"
readonly SUMMARY_FILE="$ARTIFACT_ROOT/summary.tsv"
readonly DEFAULT_CONNECTED_SPECS="com.foxhole.beta.ui.HomeRuntimeBehaviorTest,com.foxhole.beta.vpn.ProxyRuntimeSmokeTest,com.foxhole.beta.vpn.BootReceiverRestoreAndroidTest"

usage() {
  cat <<'EOF'
Usage:
  ANDROID_SERIAL=2A091FDH3001PA FOXHOLE_DEVICE_PROOF_LABEL=pixel-debug \
    scripts/run-device-runtime-proof.sh

Optional environment:
  FOXHOLE_DEVICE_PROOF_RUN_CONNECTED=1
  FOXHOLE_DEVICE_PROOF_RUN_RUNTIME_STRESS=0
  FOXHOLE_DEVICE_PROOF_RUN_LOCAL_FIREWALL=0
  FOXHOLE_DEVICE_PROOF_RUN_MACROBENCHMARK=0
  FOXHOLE_DEVICE_PROOF_CONNECTED_SPECS=<comma-separated connected test specs>
  FOXHOLE_DEVICE_PROOF_ARTIFACT_ROOT=build/device-runtime-proof/<label>

Runtime stress still requires the usual FOXHOLE_RUNTIME_STRESS_* subscription inputs.
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
  echo "ANDROID_SERIAL must be set for device runtime proof." >&2
  exit 2
fi

cd "$ROOT_DIR"
mkdir -p "$ARTIFACT_ROOT"
printf 'step\tstatus\tartifact\n' > "$SUMMARY_FILE"

adb_cmd=(adb -s "$ANDROID_SERIAL")
logcat_pid=""

cleanup() {
  if [[ -n "$logcat_pid" ]]; then
    kill "$logcat_pid" >/dev/null 2>&1 || true
    wait "$logcat_pid" >/dev/null 2>&1 || true
  fi
  "$GRADLEW" --no-daemon --console=plain --stacktrace :app:installDebug \
    > "$ARTIFACT_ROOT/reinstall-debug.log" 2>&1 || true
}
trap cleanup EXIT

"${adb_cmd[@]}" wait-for-device
{
  printf 'captured_at=%s\n' "$(date -Is)"
  printf 'android_serial=%s\n' "$ANDROID_SERIAL"
  printf 'label=%s\n' "$LABEL"
  printf 'target_package=%s\n' "$TARGET_PACKAGE"
  printf 'device_api=%s\n' "$("${adb_cmd[@]}" shell getprop ro.build.version.sdk | tr -d '\r')"
  printf 'fingerprint=%s\n' "$("${adb_cmd[@]}" shell getprop ro.build.fingerprint | tr -d '\r')"
} > "$ARTIFACT_ROOT/device.env"

"${adb_cmd[@]}" logcat -c || true
"${adb_cmd[@]}" logcat -v threadtime > "$ARTIFACT_ROOT/live-logcat-threadtime.log" 2>&1 &
logcat_pid="$!"

run_step() {
  local name="$1"
  shift
  local step_dir="$ARTIFACT_ROOT/$name"
  local status=0
  mkdir -p "$step_dir"
  set +e
  "$@" > "$step_dir/stdout-stderr.log" 2>&1
  status=$?
  set -e
  printf '%s\t%s\t%s\n' "$name" "$status" "$step_dir" >> "$SUMMARY_FILE"
  if [[ "$status" -ne 0 ]]; then
    echo "Device runtime proof step failed: $name (status $status). See $step_dir" >&2
    return "$status"
  fi
}

run_connected() {
  FOXHOLE_CONNECTED_TEST_ARTIFACT_ROOT="$ARTIFACT_ROOT/connected/artifacts" \
  FOXHOLE_REQUIRED_CONNECTED_TEST_SPECS="${FOXHOLE_DEVICE_PROOF_CONNECTED_SPECS:-$DEFAULT_CONNECTED_SPECS}" \
  FOXHOLE_REQUIRE_CONNECTED_QA_MATRIX=0 \
    bash scripts/run-connected-android-tests.sh
}

run_runtime_stress() {
  FOXHOLE_RUNTIME_STRESS_ARTIFACT_DIR="$ARTIFACT_ROOT/runtime-stress/artifacts" \
    bash scripts/run-runtime-stress-gate.sh
}

run_local_firewall_stress() {
  "$GRADLEW" --no-daemon --console=plain --stacktrace :app:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=com.foxhole.beta.vpn.LiveLocalFirewallGuardRuntimeTest \
    -Pandroid.testInstrumentationRunnerArguments.foxhole.liveLocalGuard=1 \
    -Pandroid.testInstrumentationRunnerArguments.foxhole.liveLocalGuardStress=1 \
    -Pandroid.testInstrumentationRunnerArguments.foxhole.localGuardStressCycles="${FOXHOLE_LOCAL_GUARD_STRESS_CYCLES:-30}"
  "$GRADLEW" --no-daemon --console=plain --stacktrace :app:installDebug
}

run_macrobenchmark() {
  FOXHOLE_MACROBENCHMARK_LOG_ROOT="$ARTIFACT_ROOT/macrobenchmark-logcat" \
  FOXHOLE_REQUIRE_FULL_MACROBENCHMARK=1 \
    bash scripts/run-ci-macrobenchmark.sh schedule refs/heads/dev
}

"$GRADLEW" --no-daemon --console=plain --stacktrace :app:installDebug :app:installDebugAndroidTest

if [[ "${FOXHOLE_DEVICE_PROOF_RUN_CONNECTED:-1}" == "1" ]]; then
  run_step connected run_connected
fi
if [[ "${FOXHOLE_DEVICE_PROOF_RUN_RUNTIME_STRESS:-0}" == "1" ]]; then
  run_step runtime-stress run_runtime_stress
fi
if [[ "${FOXHOLE_DEVICE_PROOF_RUN_LOCAL_FIREWALL:-0}" == "1" ]]; then
  run_step local-firewall-stress run_local_firewall_stress
fi
if [[ "${FOXHOLE_DEVICE_PROOF_RUN_MACROBENCHMARK:-0}" == "1" ]]; then
  run_step macrobenchmark run_macrobenchmark
fi

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

printf 'Device runtime proof artifacts: %s\n' "$ARTIFACT_ROOT"
