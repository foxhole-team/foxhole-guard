#!/usr/bin/env bash
set -euo pipefail

readonly APP_PACKAGE="${FOXHOLE_SOAK_APP_PACKAGE:-com.foxhole.beta.debug}"
readonly TEST_PACKAGE="${FOXHOLE_SOAK_TEST_PACKAGE:-com.foxhole.beta.debug.test}"
readonly RUNNER="${FOXHOLE_SOAK_RUNNER:-androidx.test.runner.AndroidJUnitRunner}"
readonly TEST_CLASS="${FOXHOLE_SOAK_TEST_CLASS:-com.foxhole.beta.ProfileRuntimeSessionAndroidTest}"
readonly TEST_METHOD="${FOXHOLE_SOAK_TEST_METHOD:-manualSmartSubscriptionVlessTcpBackgroundHold}"
readonly OUTPUT_ROOT="${FOXHOLE_SOAK_OUTPUT_ROOT:-/tmp/foxhole-local-runtime-soak/$(date +%Y%m%d-%H%M%S)}"
readonly HOLD_MS="${FOXHOLE_SOAK_HOLD_MS:-900000}"
readonly PROBE_INTERVAL_MS="${FOXHOLE_SOAK_PROBE_INTERVAL_MS:-60000}"
readonly ROUNDS="${FOXHOLE_SOAK_ROUNDS:-0}"
readonly DURATION_SECONDS="${FOXHOLE_SOAK_DURATION_SECONDS:-28800}"
readonly BROWSER_SETTLE_SECONDS="${FOXHOLE_SOAK_BROWSER_SETTLE_SECONDS:-12}"
readonly BROWSER_WAIT_SECONDS="${FOXHOLE_SOAK_BROWSER_WAIT_SECONDS:-180}"
readonly BROWSER_PREPARE="${FOXHOLE_SOAK_BROWSER_PREPARE:-1}"
readonly REQUIRE_SUCCESS="${FOXHOLE_SOAK_REQUIRE_SUCCESS:-1}"
readonly ALLOW_INSECURE_TLS="${FOXHOLE_SOAK_ALLOW_INSECURE_TLS:-0}"
readonly KEEP_APP_VISIBLE="${FOXHOLE_SOAK_KEEP_APP_VISIBLE:-1}"
readonly GOOGLE_CHECK_URL="${FOXHOLE_SOAK_GOOGLE_URL:-https://www.google.com/}"
readonly IP_CHECK_URL="${FOXHOLE_SOAK_IP_URL:-https://1.1.1.1/cdn-cgi/trace}"
readonly SPEC="${TEST_CLASS}#${TEST_METHOD}"

usage() {
  cat <<EOF
Usage:
  FOXHOLE_SOAK_SUBSCRIPTION_URL=https://example.test/sub.txt $0

Common environment:
  FOXHOLE_SOAK_SERIALS=emulator-5554,emulator-5556   Device serials. Defaults to connected emulator-* devices.
  FOXHOLE_SOAK_OUTPUT_ROOT=/tmp/foxhole-local-runtime-soak/manual-run
  FOXHOLE_SOAK_ROUNDS=0                              0 means loop until duration expires.
  FOXHOLE_SOAK_DURATION_SECONDS=28800                Wall-clock cap when rounds is 0.
  FOXHOLE_SOAK_HOLD_MS=900000                        Runtime hold inside each instrumentation pass.
  FOXHOLE_SOAK_PROBE_INTERVAL_MS=60000               In-app vpn-bound probe interval.
  FOXHOLE_SOAK_BROWSER_WAIT_SECONDS=180              Wait for connected hold before browser checks.
  FOXHOLE_SOAK_REQUIRE_SUCCESS=1                     Fail the pass on runtime/IP/traffic evidence problems.
  FOXHOLE_SOAK_GOOGLE_URL=https://www.google.com/    Browser Google smoke URL.
  FOXHOLE_SOAK_IP_URL=https://1.1.1.1/cdn-cgi/trace  Browser IP smoke URL; literal IP avoids DNS-provider false negatives.
  FOXHOLE_SOAK_BROWSER_PREPARE=1                     Warm Chrome before instrumentation so browser checks avoid UiAutomation conflict.
  FOXHOLE_SOAK_ALLOW_INSECURE_TLS=0                  Preserve strict subscription TLS by default.
  FOXHOLE_SOAK_TEST_METHOD=$TEST_METHOD

Output:
  $OUTPUT_ROOT/<serial>/summary.tsv
  $OUTPUT_ROOT/<serial>/round-0001/{instrumentation.log,logcat.txt,browser-*.png,browser-*-connectivity.txt}
EOF
}

die() {
  echo "error: $*" >&2
  exit 1
}

csv_to_lines() {
  tr ',' '\n' | sed -E 's/^[[:space:]]+//; s/[[:space:]]+$//' | awk 'NF > 0'
}

discover_emulators() {
  adb devices | awk '$1 ~ /^emulator-/ && $2 == "device" { print $1 }'
}

serials() {
  if [[ -n "${FOXHOLE_SOAK_SERIALS:-}" ]]; then
    printf '%s\n' "$FOXHOLE_SOAK_SERIALS" | csv_to_lines
  else
    discover_emulators
  fi
}

quote_for_log() {
  printf '%q' "$1"
}

apk_path() {
  local pattern="$1"
  local path
  path="$(find app/build/outputs/apk -path "$pattern" -type f | sort | tail -n 1 || true)"
  [[ -n "$path" ]] || die "APK not found for pattern: $pattern"
  printf '%s\n' "$path"
}

adb_device() {
  local serial="$1"
  shift
  adb -s "$serial" "$@"
}

collect_device_info() {
  local serial="$1"
  local out_dir="$2"
  {
    echo "serial=$serial"
    echo "captured_at=$(date -Is)"
    adb_device "$serial" shell getprop ro.product.model || true
    adb_device "$serial" shell getprop ro.build.fingerprint || true
    adb_device "$serial" shell getprop ro.build.version.release || true
    adb_device "$serial" shell settings get global http_proxy || true
    adb_device "$serial" shell cmd connectivity airplane-mode || true
    adb_device "$serial" shell dumpsys connectivity | sed -n '1,180p' || true
  } > "$out_dir/device-info.txt" 2>&1
}

install_apks() {
  local serial="$1"
  local out_dir="$2"
  local app_apk="$3"
  local test_apk="$4"
  mkdir -p "$out_dir"
  {
    echo "install app: $(quote_for_log "$app_apk")"
    adb_device "$serial" install -r -t "$app_apk"
    echo "install androidTest: $(quote_for_log "$test_apk")"
    adb_device "$serial" install -r -t "$test_apk"
    adb_device "$serial" shell cmd appops set "$APP_PACKAGE" ACTIVATE_VPN allow || true
    adb_device "$serial" shell pm list packages "$APP_PACKAGE" || true
    adb_device "$serial" shell pm list instrumentation | grep -F "$TEST_PACKAGE" || true
  } > "$out_dir/install.log" 2>&1
}

start_logcat() {
  local serial="$1"
  local out_file="$2"
  adb_device "$serial" logcat -c || true
  adb_device "$serial" logcat -v threadtime > "$out_file" 2>&1 &
  printf '%s\n' "$!"
}

stop_pid() {
  local pid="$1"
  kill "$pid" >/dev/null 2>&1 || true
  wait "$pid" >/dev/null 2>&1 || true
}

instrumentation_log_failed() {
  local log_file="$1"
  if grep -Eq 'FAILURES!!!|INSTRUMENTATION_STATUS_CODE: -2|Tests run: .*Failures: [1-9]|Tests run: .*Errors: [1-9]|Process crashed' "$log_file"; then
    return 0
  fi
  if ! grep -Eq 'OK \([0-9]+ tests?\)' "$log_file"; then
    return 0
  fi
  return 1
}

capture_screen() {
  local serial="$1"
  local prefix="$2"
  adb_device "$serial" exec-out screencap -p > "${prefix}.png" 2> "${prefix}.screencap.err" || true
}

capture_screen_and_ui() {
  local serial="$1"
  local prefix="$2"
  capture_screen "$serial" "$prefix"
  adb_device "$serial" shell uiautomator dump "/sdcard/foxhole-soak-ui.xml" >/dev/null 2>&1 || true
  adb_device "$serial" pull "/sdcard/foxhole-soak-ui.xml" "${prefix}.xml" >/dev/null 2>&1 || true
  adb_device "$serial" shell rm -f "/sdcard/foxhole-soak-ui.xml" >/dev/null 2>&1 || true
}

tap_screen_percent() {
  local serial="$1"
  local x_percent="$2"
  local y_percent="$3"
  local size width height
  size="$(adb_device "$serial" shell wm size 2>/dev/null | sed -nE 's/.*Physical size: ([0-9]+)x([0-9]+).*/\1 \2/p' | tail -n 1)"
  read -r width height <<< "$size"
  [[ -n "${width:-}" && -n "${height:-}" ]] || return 0
  adb_device "$serial" shell input tap "$((width * x_percent / 100))" "$((height * y_percent / 100))" >/dev/null 2>&1 || true
}

dismiss_browser_first_run_if_visible() {
  local serial="$1"
  local prefix="$2"
  if [[ ! -s "${prefix}.xml" ]] || grep -Eiq 'Make Chrome your own|Use without an account|Stay signed out|Add account to device' "${prefix}.xml" 2>/dev/null; then
    tap_screen_percent "$serial" 50 88
    sleep 2
    tap_screen_percent "$serial" 50 90
    sleep 2
  fi
}

browser_check() {
  local serial="$1"
  local round_dir="$2"
  local label="$3"
  local url="$4"
  {
    echo "browser_check label=$label url=$url"
    adb_device "$serial" shell am start -a android.intent.action.VIEW -d "$url"
    sleep "$BROWSER_SETTLE_SECONDS"
    adb_device "$serial" shell dumpsys activity top | sed -n '1,180p' || true
  } > "$round_dir/browser-${label}.log" 2>&1
  capture_screen "$serial" "$round_dir/browser-${label}"
  adb_device "$serial" shell dumpsys connectivity > "$round_dir/browser-${label}-connectivity.txt" 2>&1 || true
}

prepare_browser() {
  local serial="$1"
  local out_dir="$2"
  [[ "$BROWSER_PREPARE" == "1" ]] || return 0
  {
    echo "browser_prepare url=$IP_CHECK_URL"
    adb_device "$serial" shell am start -a android.intent.action.VIEW -d "$IP_CHECK_URL"
    sleep "$BROWSER_SETTLE_SECONDS"
    adb_device "$serial" shell dumpsys activity top | sed -n '1,180p' || true
  } > "$out_dir/browser-prepare.log" 2>&1
  capture_screen_and_ui "$serial" "$out_dir/browser-prepare"
  dismiss_browser_first_run_if_visible "$serial" "$out_dir/browser-prepare"
  if [[ ! -s "$out_dir/browser-prepare.xml" ]] || grep -Eiq 'Make Chrome your own|Use without an account|Stay signed out|Add account to device' "$out_dir/browser-prepare.xml" 2>/dev/null; then
    {
      echo "browser_prepare_retry url=$IP_CHECK_URL"
      adb_device "$serial" shell am start -a android.intent.action.VIEW -d "$IP_CHECK_URL"
      sleep "$BROWSER_SETTLE_SECONDS"
      adb_device "$serial" shell dumpsys activity top | sed -n '1,180p' || true
    } >> "$out_dir/browser-prepare.log" 2>&1
    capture_screen_and_ui "$serial" "$out_dir/browser-prepare"
  fi
  adb_device "$serial" shell am force-stop com.android.chrome >/dev/null 2>&1 || true
}

run_instrumentation_round() {
  local serial="$1"
  local round_dir="$2"
  local subscription_url="$3"
  local logcat_file="$4"
  local status=0
  local instrument_pid=""
  local waited=0

  adb_device "$serial" shell am force-stop "$APP_PACKAGE" >/dev/null 2>&1 || true
  adb_device "$serial" shell pm clear "$APP_PACKAGE" >/dev/null 2>&1 || true
  adb_device "$serial" shell cmd appops set "$APP_PACKAGE" ACTIVATE_VPN allow >/dev/null 2>&1 || true

  adb_device "$serial" shell am instrument -w -r \
    -e class "$SPEC" \
    -e foxhole.liveSmartVlessTcpBackground 1 \
    -e foxhole.smartSubscriptionUrl "$subscription_url" \
    -e foxhole.backgroundHoldMs "$HOLD_MS" \
    -e foxhole.backgroundProbeIntervalMs "$PROBE_INTERVAL_MS" \
    -e foxhole.requireLiveSmartSuccess "$REQUIRE_SUCCESS" \
    -e foxhole.requestVpnPermission 1 \
    -e foxhole.allowInsecureTlsForLiveSubscription "$ALLOW_INSECURE_TLS" \
    "$TEST_PACKAGE/$RUNNER" > "$round_dir/instrumentation.log" 2>&1 &
  instrument_pid="$!"

  while kill -0 "$instrument_pid" >/dev/null 2>&1 && [[ "$waited" -lt "$BROWSER_WAIT_SECONDS" ]]; do
    if grep -q 'liveSmartBackground initialIpRefresh=ok' "$logcat_file" "$round_dir/instrumentation.log" 2>/dev/null; then
      echo "connected_hold_seen_at=$(date -Is)" > "$round_dir/browser-checks.started"
      browser_check "$serial" "$round_dir" "google" "$GOOGLE_CHECK_URL"
      browser_check "$serial" "$round_dir" "ip" "$IP_CHECK_URL"
      break
    fi
    if grep -q 'liveSmartBackground connectFailed' "$logcat_file" "$round_dir/instrumentation.log" 2>/dev/null; then
      echo "browser checks skipped: connect failed before background hold" > "$round_dir/browser-checks.skipped"
      break
    fi
    sleep 5
    waited=$((waited + 5))
  done
  if [[ ! -e "$round_dir/browser-checks.started" && ! -e "$round_dir/browser-checks.skipped" ]]; then
    echo "browser checks skipped: connected hold was not observed within ${BROWSER_WAIT_SECONDS}s" > "$round_dir/browser-checks.skipped"
  fi

  set +e
  wait "$instrument_pid"
  status=$?
  set -e
  if instrumentation_log_failed "$round_dir/instrumentation.log"; then
    status=1
  fi

  adb_device "$serial" shell dumpsys activity services "$APP_PACKAGE" > "$round_dir/services.txt" 2>&1 || true
  adb_device "$serial" shell dumpsys connectivity > "$round_dir/connectivity.txt" 2>&1 || true
  return "$status"
}

run_device_soak() {
  local serial="$1"
  local app_apk="$2"
  local test_apk="$3"
  local subscription_url="$4"
  local device_dir="$OUTPUT_ROOT/$serial"
  local summary="$device_dir/summary.tsv"
  local started epoch_now round status logcat_pid round_dir failed_rounds=0

  mkdir -p "$device_dir"
  collect_device_info "$serial" "$device_dir"
  install_apks "$serial" "$device_dir" "$app_apk" "$test_apk"
  prepare_browser "$serial" "$device_dir"
  printf 'round\tstarted_at\tended_at\tstatus\tround_dir\n' > "$summary"

  started="$(date +%s)"
  round=1
  while true; do
    epoch_now="$(date +%s)"
    if [[ "$ROUNDS" != "0" && "$round" -gt "$ROUNDS" ]]; then
      break
    fi
    if [[ "$ROUNDS" == "0" && $((epoch_now - started)) -ge "$DURATION_SECONDS" ]]; then
      break
    fi

    round_dir="$device_dir/round-$(printf '%04d' "$round")"
    mkdir -p "$round_dir"
    date -Is > "$round_dir/started-at.txt"
    logcat_pid="$(start_logcat "$serial" "$round_dir/logcat.txt")"
    status=0
    run_instrumentation_round "$serial" "$round_dir" "$subscription_url" "$round_dir/logcat.txt" || status=$?
    stop_pid "$logcat_pid"
    date -Is > "$round_dir/ended-at.txt"

    printf '%s\t%s\t%s\t%s\t%s\n' \
      "$round" \
      "$(cat "$round_dir/started-at.txt")" \
      "$(cat "$round_dir/ended-at.txt")" \
      "$status" \
      "$round_dir" >> "$summary"

    if [[ "$status" -ne 0 ]]; then
      touch "$round_dir/FAILED"
      failed_rounds=1
    fi
    round=$((round + 1))
  done

  if [[ "$KEEP_APP_VISIBLE" == "1" ]]; then
    install_apks "$serial" "$device_dir/reinstall-after-soak" "$app_apk" "$test_apk"
    adb_device "$serial" shell monkey -p "$APP_PACKAGE" 1 > "$device_dir/relaunch.log" 2>&1 || true
  fi
  return "$failed_rounds"
}

main() {
  if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
    usage
    exit 0
  fi
  command -v adb >/dev/null 2>&1 || die "adb is not on PATH"
  [[ -n "${FOXHOLE_SOAK_SUBSCRIPTION_URL:-}" ]] || die "FOXHOLE_SOAK_SUBSCRIPTION_URL is required"

  mapfile -t selected_serials < <(serials)
  [[ "${#selected_serials[@]}" -gt 0 ]] || die "no emulator serials found; set FOXHOLE_SOAK_SERIALS"

  mkdir -p "$OUTPUT_ROOT"
  {
    echo "started_at=$(date -Is)"
    echo "spec=$SPEC"
    echo "serials=${selected_serials[*]}"
    echo "hold_ms=$HOLD_MS"
    echo "probe_interval_ms=$PROBE_INTERVAL_MS"
    echo "rounds=$ROUNDS"
    echo "duration_seconds=$DURATION_SECONDS"
  } > "$OUTPUT_ROOT/run.env"

  ./gradlew --no-daemon --console=plain --stacktrace :app:assembleDebug :app:assembleDebugAndroidTest > "$OUTPUT_ROOT/gradle-build.log" 2>&1
  app_apk="$(apk_path '*/debug/*debug.apk')"
  test_apk="$(apk_path '*/androidTest/debug/*debug-androidTest.apk')"

  pids=()
  for serial in "${selected_serials[@]}"; do
    run_device_soak "$serial" "$app_apk" "$test_apk" "$FOXHOLE_SOAK_SUBSCRIPTION_URL" &
    pids+=("$!")
  done

  failed=0
  for pid in "${pids[@]}"; do
    wait "$pid" || failed=1
  done

  echo "local runtime soak artifacts: $OUTPUT_ROOT"
  exit "$failed"
}

main "$@"
