#!/usr/bin/env bash
set -euo pipefail

readonly TEST_CLASS_TIMEOUT_SECONDS="${FOXHOLE_ANDROID_TEST_CLASS_TIMEOUT_SECONDS:-1200}"
readonly TEST_CLASSES=(
  "com.foxhole.beta.ProfileRuntimeSessionAndroidTest"
  "com.foxhole.beta.ui.HomeRuntimeBehaviorTest"
  "com.foxhole.beta.ui.HomeScreenTest"
  "com.foxhole.beta.vpn.ProxyRuntimeSmokeTest"
  "com.foxhole.beta.vpn.VpnRuntimeSmokeTest"
)

dump_diagnostics() {
  local test_class="$1"
  echo "::group::diagnostics for ${test_class}"
  adb devices -l || true
  adb shell pm list packages 'com.foxhole.beta' || true
  adb shell dumpsys activity processes | sed -n '1,220p' || true
  adb shell dumpsys activity services com.foxhole.beta.debug | sed -n '1,220p' || true
  adb logcat -d -t 1500 || true
  echo "::endgroup::"
}

run_with_timeout() {
  local timeout_seconds="$1"
  shift

  if command -v timeout >/dev/null 2>&1; then
    timeout --foreground "${timeout_seconds}s" "$@"
    return
  fi
  if command -v gtimeout >/dev/null 2>&1; then
    gtimeout --foreground "${timeout_seconds}s" "$@"
    return
  fi

  python3 - "$timeout_seconds" "$@" <<'PY'
import subprocess
import sys

timeout_seconds = int(sys.argv[1])
command = sys.argv[2:]
process = subprocess.Popen(command)
try:
    sys.exit(process.wait(timeout=timeout_seconds))
except subprocess.TimeoutExpired:
    process.terminate()
    try:
        process.wait(timeout=10)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait()
    sys.exit(124)
PY
}

adb wait-for-device

for test_class in "${TEST_CLASSES[@]}"; do
  echo "::group::connectedDebugAndroidTest ${test_class}"
  adb logcat -c || true
  set +e
  run_with_timeout "$TEST_CLASS_TIMEOUT_SECONDS" \
    ./gradlew connectedDebugAndroidTest --info \
      -Pandroid.testInstrumentationRunnerArguments.class="$test_class"
  status=$?
  set -e
  if [[ "$status" -ne 0 ]]; then
    echo "connectedDebugAndroidTest failed for ${test_class} with exit code ${status}" >&2
    dump_diagnostics "$test_class"
    echo "::endgroup::"
    exit "$status"
  fi
  echo "::endgroup::"
done
