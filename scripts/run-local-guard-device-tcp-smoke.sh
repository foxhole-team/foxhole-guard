#!/usr/bin/env bash
set -euo pipefail

readonly ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly GRADLEW="${GRADLEW:-$ROOT_DIR/gradlew}"
readonly TARGET_PACKAGE="${FOXHOLE_ANDROID_TEST_TARGET_PACKAGE:-com.foxhole.guard.debug}"
readonly TEST_SPEC="com.foxhole.core.runtime.LiveLocalFirewallGuardRuntimeTest#localFirewallGuardPreservesDashboardInternetAndDns"
readonly HOLD_MS="${FOXHOLE_LOCAL_GUARD_HOLD_MS:-60000}"
readonly READY_TIMEOUT_SECONDS="${FOXHOLE_LOCAL_GUARD_READY_TIMEOUT_SECONDS:-120}"
readonly TCP_SETTLE_SECONDS="${FOXHOLE_LOCAL_GUARD_TCP_SETTLE_SECONDS:-15}"
readonly TCP_TIMEOUT_SECONDS="${FOXHOLE_LOCAL_GUARD_TCP_TIMEOUT_SECONDS:-5}"
readonly TCP_ATTEMPTS="${FOXHOLE_LOCAL_GUARD_TCP_ATTEMPTS:-2}"
readonly TCP_RETRY_DELAY_SECONDS="${FOXHOLE_LOCAL_GUARD_TCP_RETRY_DELAY_SECONDS:-5}"
readonly ENDPOINTS="${FOXHOLE_LOCAL_GUARD_TCP_ENDPOINTS:-1.1.1.1:443 example.com:443 www.google.com:443 cp.cloudflare.com:443}"
readonly ARTIFACT_DIR="${FOXHOLE_LOCAL_GUARD_TCP_ARTIFACT_DIR:-$ROOT_DIR/build/local-guard-device-tcp-smoke}"
readonly GRADLE_LOG="$ARTIFACT_DIR/gradle.log"
readonly LOGCAT_FILE="$ARTIFACT_DIR/logcat.txt"

usage() {
  cat <<'EOF'
Usage:
  scripts/run-local-guard-device-tcp-smoke.sh

Optional environment:
  ANDROID_SERIAL / FOXHOLE_LOCAL_GUARD_SERIAL
  FOXHOLE_ANDROID_TEST_TARGET_PACKAGE=com.foxhole.guard.debug
  FOXHOLE_LOCAL_GUARD_HOLD_MS=60000
  FOXHOLE_LOCAL_GUARD_READY_TIMEOUT_SECONDS=120
  FOXHOLE_LOCAL_GUARD_TCP_SETTLE_SECONDS=15
  FOXHOLE_LOCAL_GUARD_TCP_TIMEOUT_SECONDS=5
  FOXHOLE_LOCAL_GUARD_TCP_ATTEMPTS=2
  FOXHOLE_LOCAL_GUARD_TCP_RETRY_DELAY_SECONDS=5
  FOXHOLE_LOCAL_GUARD_TCP_ENDPOINTS="1.1.1.1:443 example.com:443"
  FOXHOLE_LOCAL_GUARD_TCP_ARTIFACT_DIR=build/local-guard-device-tcp-smoke
EOF
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

if [[ -n "${FOXHOLE_LOCAL_GUARD_SERIAL:-}" ]]; then
  export ANDROID_SERIAL="$FOXHOLE_LOCAL_GUARD_SERIAL"
fi

adb_cmd() {
  if [[ -n "${ANDROID_SERIAL:-}" ]]; then
    adb -s "$ANDROID_SERIAL" "$@"
  else
    adb "$@"
  fi
}

endpoint_host() {
  printf '%s' "${1%:*}"
}

endpoint_port() {
  printf '%s' "${1##*:}"
}

probe_tcp() {
  local endpoint="$1"
  local host
  local port
  host="$(endpoint_host "$endpoint")"
  port="$(endpoint_port "$endpoint")"
  adb_cmd shell toybox nc -z -w "$TCP_TIMEOUT_SECONDS" "$host" "$port" >/dev/null
}

collect_logcat() {
  mkdir -p "$ARTIFACT_DIR"
  adb_cmd logcat -d >"$LOGCAT_FILE" 2>/dev/null || true
}

install_debug() {
  "$GRADLEW" --no-daemon --console=plain :app:installDebug >/dev/null || true
}

gradle_pid=""
cleanup() {
  local status=$?
  collect_logcat
  if [[ -n "$gradle_pid" ]] && kill -0 "$gradle_pid" >/dev/null 2>&1; then
    wait "$gradle_pid" >/dev/null 2>&1 || true
  fi
  install_debug
  echo "local guard TCP smoke artifacts: $ARTIFACT_DIR"
  exit "$status"
}
trap cleanup EXIT

cd "$ROOT_DIR"
mkdir -p "$ARTIFACT_DIR"
rm -f "$GRADLE_LOG" "$LOGCAT_FILE"

adb_cmd wait-for-device
adb_cmd shell svc power stayon true >/dev/null 2>&1 || true
adb_cmd shell settings put system screen_off_timeout 1800000 >/dev/null 2>&1 || true

baseline_endpoints=()
for endpoint in $ENDPOINTS; do
  if probe_tcp "$endpoint"; then
    baseline_endpoints+=("$endpoint")
    echo "baseline tcp ok: $endpoint"
  else
    echo "baseline tcp unavailable: $endpoint"
  fi
done

if [[ "${#baseline_endpoints[@]}" -eq 0 ]]; then
  echo "No baseline TCP endpoints were reachable before local guard." >&2
  exit 1
fi

adb_cmd logcat -c || true
adb_cmd shell cmd appops set "$TARGET_PACKAGE" ACTIVATE_VPN allow >/dev/null 2>&1 || true

"$GRADLEW" --no-daemon --console=plain :app:connectedDebugAndroidTest \
  "-Pandroid.testInstrumentationRunnerArguments.class=$TEST_SPEC" \
  "-Pandroid.testInstrumentationRunnerArguments.foxhole.liveLocalGuard=1" \
  "-Pandroid.testInstrumentationRunnerArguments.foxhole.requestVpnPermission=1" \
  "-Pandroid.testInstrumentationRunnerArguments.foxhole.liveLocalGuardHoldMs=$HOLD_MS" \
  >"$GRADLE_LOG" 2>&1 &
gradle_pid=$!

ready_message="liveLocalGuardInternet ready holdMs=$HOLD_MS"
deadline=$((SECONDS + READY_TIMEOUT_SECONDS))
while ((SECONDS < deadline)); do
  if adb_cmd logcat -d 2>/dev/null | grep -q "$ready_message"; then
    echo "local guard ready: $ready_message"
    break
  fi
  if ! kill -0 "$gradle_pid" >/dev/null 2>&1; then
    wait "$gradle_pid" || true
    echo "connectedDebugAndroidTest exited before local guard was ready. Tail of $GRADLE_LOG:" >&2
    tail -80 "$GRADLE_LOG" >&2 || true
    exit 1
  fi
  sleep 2
done

if ! adb_cmd logcat -d 2>/dev/null | grep -q "$ready_message"; then
  echo "Timed out waiting for local guard ready message: $ready_message" >&2
  tail -80 "$GRADLE_LOG" >&2 || true
  exit 1
fi

failed=0
if [[ "$TCP_SETTLE_SECONDS" -gt 0 ]]; then
  echo "waiting ${TCP_SETTLE_SECONDS}s for device TCP to settle after local guard ready"
  sleep "$TCP_SETTLE_SECONDS"
fi

for endpoint in "${baseline_endpoints[@]}"; do
  attempt=1
  while true; do
    if probe_tcp "$endpoint"; then
      echo "local guard tcp ok: $endpoint attempt=$attempt"
      break
    fi
    if [[ "$attempt" -ge "$TCP_ATTEMPTS" ]]; then
      echo "local guard tcp failed: $endpoint attempts=$attempt" >&2
      failed=1
      break
    fi
    echo "local guard tcp retry: $endpoint attempt=$attempt" >&2
    sleep "$TCP_RETRY_DELAY_SECONDS"
    attempt=$((attempt + 1))
  done
done

if [[ "$failed" -ne 0 ]]; then
  exit 1
fi

wait "$gradle_pid"
gradle_pid=""
