#!/usr/bin/env bash
set -euo pipefail

readonly ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly GRADLEW="${GRADLEW:-$ROOT_DIR/gradlew}"
readonly TARGET_PACKAGE="${FOXHOLE_ANDROID_TEST_TARGET_PACKAGE:-com.foxhole.beta.debug}"
readonly TEST_SPEC="com.foxhole.beta.ProfileRuntimeSessionAndroidTest#manualSmartSubscriptionRuntimeStressCycles"
readonly CYCLES="${FOXHOLE_RUNTIME_STRESS_CYCLES:-50}"
readonly PROTOCOLS="${FOXHOLE_RUNTIME_STRESS_PROTOCOLS:-VLESS}"
readonly REQUIRE_SUCCESS="${FOXHOLE_REQUIRE_LIVE_RUNTIME_STRESS_SUCCESS:-1}"
readonly ALLOW_INSECURE_TLS="${FOXHOLE_ALLOW_INSECURE_TLS_FOR_LIVE_SUBSCRIPTION:-0}"
readonly ARTIFACT_DIR="${FOXHOLE_RUNTIME_STRESS_ARTIFACT_DIR:-}"

usage() {
  cat <<'EOF'
Usage:
  FOXHOLE_RUNTIME_STRESS_SUBSCRIPTION_URL=https://... scripts/run-runtime-stress-gate.sh

Optional environment:
  ANDROID_SERIAL / FOXHOLE_RUNTIME_STRESS_SERIAL
  FOXHOLE_RUNTIME_STRESS_CYCLES=50
  FOXHOLE_RUNTIME_STRESS_PROTOCOLS=VLESS
  FOXHOLE_RUNTIME_STRESS_SUBSCRIPTION_URL=https://...
  FOXHOLE_RUNTIME_STRESS_SUBSCRIPTION_RAW_FILE=/data/local/tmp/foxhole-subscription.raw
  FOXHOLE_RUNTIME_STRESS_SUBSCRIPTION_BASE64=...
  FOXHOLE_REQUIRE_LIVE_RUNTIME_STRESS_SUCCESS=1
  FOXHOLE_ALLOW_INSECURE_TLS_FOR_LIVE_SUBSCRIPTION=0
  FOXHOLE_RUNTIME_STRESS_ARTIFACT_DIR=build/runtime-stress-proof/<label>
EOF
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

if [[ -n "${FOXHOLE_RUNTIME_STRESS_SERIAL:-}" ]]; then
  export ANDROID_SERIAL="$FOXHOLE_RUNTIME_STRESS_SERIAL"
fi

instrumentation_args=(
  "-Pandroid.testInstrumentationRunnerArguments.class=$TEST_SPEC"
  "-Pandroid.testInstrumentationRunnerArguments.foxhole.liveRuntimeStress=1"
  "-Pandroid.testInstrumentationRunnerArguments.foxhole.runtimeStressCycles=$CYCLES"
  "-Pandroid.testInstrumentationRunnerArguments.foxhole.smartProtocols=$PROTOCOLS"
  "-Pandroid.testInstrumentationRunnerArguments.foxhole.requireLiveSmartSuccess=$REQUIRE_SUCCESS"
  "-Pandroid.testInstrumentationRunnerArguments.foxhole.requestVpnPermission=1"
  "-Pandroid.testInstrumentationRunnerArguments.foxhole.allowInsecureTlsForLiveSubscription=$ALLOW_INSECURE_TLS"
)

if [[ -n "${FOXHOLE_RUNTIME_STRESS_SUBSCRIPTION_URL:-}" ]]; then
  instrumentation_args+=(
    "-Pandroid.testInstrumentationRunnerArguments.foxhole.smartSubscriptionUrl=$FOXHOLE_RUNTIME_STRESS_SUBSCRIPTION_URL"
  )
fi
if [[ -n "${FOXHOLE_RUNTIME_STRESS_SUBSCRIPTION_RAW_FILE:-}" ]]; then
  instrumentation_args+=(
    "-Pandroid.testInstrumentationRunnerArguments.foxhole.smartSubscriptionRawFile=$FOXHOLE_RUNTIME_STRESS_SUBSCRIPTION_RAW_FILE"
  )
fi
if [[ -n "${FOXHOLE_RUNTIME_STRESS_SUBSCRIPTION_BASE64:-}" ]]; then
  instrumentation_args+=(
    "-Pandroid.testInstrumentationRunnerArguments.foxhole.smartSubscriptionBase64=$FOXHOLE_RUNTIME_STRESS_SUBSCRIPTION_BASE64"
  )
fi

if [[ -z "${FOXHOLE_RUNTIME_STRESS_SUBSCRIPTION_URL:-}" &&
      -z "${FOXHOLE_RUNTIME_STRESS_SUBSCRIPTION_RAW_FILE:-}" &&
      -z "${FOXHOLE_RUNTIME_STRESS_SUBSCRIPTION_BASE64:-}" ]]; then
  echo "No live runtime stress subscription input was provided; falling back to device file /data/local/tmp/foxhole-subscription.raw" >&2
fi

cd "$ROOT_DIR"
adb wait-for-device
adb shell cmd appops set "$TARGET_PACKAGE" ACTIVATE_VPN allow >/dev/null 2>&1 || true
if [[ -n "$ARTIFACT_DIR" ]]; then
  mkdir -p "$ARTIFACT_DIR"
  {
    printf 'captured_at=%s\n' "$(date -Is)"
    printf 'android_serial=%s\n' "${ANDROID_SERIAL:-}"
    printf 'target_package=%s\n' "$TARGET_PACKAGE"
    printf 'cycles=%s\n' "$CYCLES"
    printf 'protocols=%s\n' "$PROTOCOLS"
  } > "$ARTIFACT_DIR/run.env"
  adb logcat -c || true
fi

status=0
"$GRADLEW" --no-daemon --console=plain --stacktrace :app:installDebug :app:installDebugAndroidTest
"$GRADLEW" --no-daemon --console=plain --stacktrace :app:connectedDebugAndroidTest "${instrumentation_args[@]}" || status=$?
if [[ -n "$ARTIFACT_DIR" ]]; then
  adb logcat -d -v threadtime > "$ARTIFACT_DIR/logcat-threadtime.log" 2>/dev/null || true
  FOXHOLE_PACKAGE="$TARGET_PACKAGE" \
  FOXHOLE_OUTPUT_DIR="$ARTIFACT_DIR/debug-state" \
    scripts/collect-foxhole-debug-state.sh "$ARTIFACT_DIR/debug-state" \
    > "$ARTIFACT_DIR/collect-foxhole-debug-state.log" 2>&1 || true
  analyze_status=0
  python3 scripts/analyze-android-perf-logs.py \
    --fail-on-fatal \
    --fail-on-anr \
    --fail-on-oom \
    --fail-on-strict-disk \
    "$ARTIFACT_DIR" \
    > "$ARTIFACT_DIR/perf-log-summary.txt" 2>&1 || analyze_status=$?
  if [[ "$status" -eq 0 && "$analyze_status" -ne 0 ]]; then
    status="$analyze_status"
  fi
fi

# Instrumentation can leave the debug app absent from the launcher; keep the device usable for follow-up debugging.
"$GRADLEW" --no-daemon --console=plain --stacktrace :app:installDebug

exit "$status"
