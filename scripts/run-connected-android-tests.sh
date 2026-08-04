#!/usr/bin/env bash
set -euo pipefail

readonly TEST_CLASS_TIMEOUT_SECONDS="${FOXHOLE_ANDROID_TEST_CLASS_TIMEOUT_SECONDS:-900}"
readonly TEST_METHOD_TIMEOUT_SECONDS="${FOXHOLE_ANDROID_TEST_METHOD_TIMEOUT_SECONDS:-240}"
readonly TARGET_PACKAGE="${FOXHOLE_ANDROID_TEST_TARGET_PACKAGE:-com.foxhole.guard.debug}"
readonly SPEC_ARTIFACT_ROOT="${FOXHOLE_CONNECTED_TEST_ARTIFACT_ROOT:-build/connected-test-specs}"
readonly SUMMARY_FILE="$SPEC_ARTIFACT_ROOT/summary.tsv"
readonly QA_MATRIX_FILE="$SPEC_ARTIFACT_ROOT/qa-matrix.tsv"
readonly DEFAULT_REQUIRED_TEST_SPECS=(
  "com.foxhole.guard.ProfileRuntimeSessionAndroidTest#freshExactImportPreservesUserServerPort"
  "com.foxhole.guard.ProfileRuntimeSessionAndroidTest#freshDirectShareImportsPreserveProvidedRuntimeFields"
  "com.foxhole.guard.ProfileRuntimeGuardSessionAndroidTest"
  "com.foxhole.core.runtime.TorGeoIpCountryResolverDeviceTest"
  "com.foxhole.core.runtime.TorRuntimeInstallerDeviceTest"
  "com.foxhole.core.runtime.DnsFilterAssetInstallerDeviceTest"
  "com.foxhole.guard.ui.cli.CliAppNavigationTest"
  "com.foxhole.guard.ui.cli.home.CliHomeRuntimeBehaviorTest"
  "com.foxhole.guard.ui.cli.home.CliProfileQuickSelectorTest"
  "com.foxhole.guard.ui.cli.logs.CliLogsScreenTest"
  "com.foxhole.guard.core.data.LocalDataRepositoryDeviceTest"
  "com.foxhole.guard.core.data.ProfileDatabaseSchemaMigrationTest"
  "com.foxhole.core.runtime.BootReceiverRestoreAndroidTest"
)
readonly OPTIONAL_TEST_SPECS=(
  "com.foxhole.guard.ProfileRuntimeSessionAndroidTest#logsCurrentActiveProfileRuntimeSummary"
  "com.foxhole.guard.ProfileRuntimeSessionAndroidTest#manualFreshImportConnectsWhenRequested"
  "com.foxhole.guard.ProfileRuntimeSessionAndroidTest#manualDirectShareLinksLogTerminalStateAndDiagnostics"
  "com.foxhole.guard.ProfileRuntimeSessionAndroidTest#manualSmartSubscriptionLogsImportAndTargetProtocolRuntime"
  "com.foxhole.guard.ProfileRuntimeGuardSessionAndroidTest#manualSmartSubscriptionRuntimeStressCycles"
  "com.foxhole.guard.ProfileRuntimeGuardSessionAndroidTest#restoreBaselineRuntimeSettingsWhenRequested"
  "com.foxhole.guard.ProfileRuntimeGuardSessionAndroidTest#liveOptionProbeMatrixLogsVpnBoundIpResults"
  "com.foxhole.core.runtime.VpnRuntimeSmokeTest"
  "com.foxhole.core.runtime.LiveLocalFirewallGuardRuntimeTest"
)

required_test_specs() {
  if [[ -n "${FOXHOLE_REQUIRED_CONNECTED_TEST_SPECS:-}" ]]; then
    printf '%s\n' "$FOXHOLE_REQUIRED_CONNECTED_TEST_SPECS" |
      tr ',' '\n' |
      sed -E 's/^[[:space:]]+//; s/[[:space:]]+$//' |
      awk 'NF > 0'
  else
    printf '%s\n' "${DEFAULT_REQUIRED_TEST_SPECS[@]}"
  fi
  if [[ -n "${FOXHOLE_EXTRA_CONNECTED_TEST_SPECS:-}" ]]; then
    printf '%s\n' "$FOXHOLE_EXTRA_CONNECTED_TEST_SPECS" |
      tr ',' '\n' |
      sed -E 's/^[[:space:]]+//; s/[[:space:]]+$//' |
      awk 'NF > 0'
  fi
  if [[ "${FOXHOLE_REQUIRE_RUNTIME_STRESS:-0}" == "1" ]]; then
    printf '%s\n' "com.foxhole.guard.ProfileRuntimeGuardSessionAndroidTest#manualSmartSubscriptionRuntimeStressCycles"
  fi
  if [[ "${FOXHOLE_LIVE_VPN_SMOKE:-0}" == "1" &&
        "${FOXHOLE_REQUIRED_CONNECTED_TEST_SPECS:-}" != *"com.foxhole.core.runtime.VpnRuntimeSmokeTest"* ]]; then
    printf '%s\n' "com.foxhole.core.runtime.VpnRuntimeSmokeTest"
  fi
  if [[ "${FOXHOLE_LIVE_LOCAL_GUARD:-0}" == "1" &&
        "${FOXHOLE_REQUIRED_CONNECTED_TEST_SPECS:-}" != *"com.foxhole.core.runtime.LiveLocalFirewallGuardRuntimeTest"* ]]; then
    printf '%s\n' "com.foxhole.core.runtime.LiveLocalFirewallGuardRuntimeTest"
  fi
}

dump_diagnostics() {
  local test_spec="$1"
  echo "::group::diagnostics for ${test_spec}"
  adb devices -l || true
  adb shell pm list packages 'com.foxhole.guard' || true
  adb shell dumpsys activity processes | sed -n '1,220p' || true
  adb shell dumpsys activity services com.foxhole.guard.debug | sed -n '1,220p' || true
  adb logcat -d -t 1500 || true
  echo "::endgroup::"
}

safe_spec_name() {
  printf '%s' "$1" | sed -E 's/[^A-Za-z0-9._#-]+/_/g; s/#/__/g'
}

matrix_dimensions_for_spec() {
  local test_spec="$1"
  case "$test_spec" in
    com.foxhole.guard.ProfileRuntimeSessionAndroidTest#freshExactImportPreservesUserServerPort)
      printf '%s\n' "profile_import" "runtime_config_integrity"
      ;;
    com.foxhole.guard.ProfileRuntimeSessionAndroidTest#freshDirectShareImportsPreserveProvidedRuntimeFields)
      printf '%s\n' "profile_import" "direct_share_import"
      ;;
    com.foxhole.guard.ProfileRuntimeGuardSessionAndroidTest)
      printf '%s\n' "guard_session" "runtime_config_integrity"
      ;;
    com.foxhole.guard.ProfileRuntimeGuardSessionAndroidTest#manualSmartSubscriptionRuntimeStressCycles)
      printf '%s\n' "runtime_stress" "smart_subscription" "vpn_runtime"
      ;;
    com.foxhole.guard.ProfileRuntimeSessionAndroidTest#logsCurrentActiveProfileRuntimeSummary)
      printf '%s\n' "diagnostics_logs" "runtime_config_integrity"
      ;;
    com.foxhole.guard.ProfileRuntimeSessionAndroidTest#manualFreshImportConnectsWhenRequested)
      printf '%s\n' "profile_import" "vpn_runtime"
      ;;
    com.foxhole.guard.ProfileRuntimeSessionAndroidTest#manualDirectShareLinksLogTerminalStateAndDiagnostics)
      printf '%s\n' "direct_share_import" "diagnostics_logs"
      ;;
    com.foxhole.guard.ProfileRuntimeSessionAndroidTest#manualSmartSubscriptionLogsImportAndTargetProtocolRuntime)
      printf '%s\n' "smart_subscription" "diagnostics_logs" "vpn_runtime"
      ;;
    com.foxhole.guard.ProfileRuntimeGuardSessionAndroidTest#restoreBaselineRuntimeSettingsWhenRequested)
      printf '%s\n' "settings_restore" "runtime_config_integrity"
      ;;
    com.foxhole.guard.ProfileRuntimeGuardSessionAndroidTest#liveOptionProbeMatrixLogsVpnBoundIpResults)
      printf '%s\n' "vpn_validation" "diagnostics_logs"
      ;;
    com.foxhole.guard.ui.cli.CliAppNavigationTest)
      printf '%s\n' "navigation" "settings" "back_stack" "runtime_ui"
      ;;
    com.foxhole.guard.ui.cli.home.CliHomeRuntimeBehaviorTest)
      printf '%s\n' "dashboard_ui" "runtime_ui"
      ;;
    com.foxhole.guard.ui.cli.home.CliProfileQuickSelectorTest)
      printf '%s\n' "profile_management" "runtime_ui"
      ;;
    com.foxhole.core.runtime.TorGeoIpCountryResolverDeviceTest)
      printf '%s\n' "tor_geoip"
      ;;
    com.foxhole.core.runtime.TorRuntimeInstallerDeviceTest)
      printf '%s\n' "tor_runtime" "runtime_config_integrity"
      ;;
    com.foxhole.core.runtime.DnsFilterAssetInstallerDeviceTest)
      printf '%s\n' "dns_filter" "runtime_config_integrity"
      ;;
    com.foxhole.core.runtime.LiveLocalFirewallGuardRuntimeTest)
      printf '%s\n' "local_guard" "vpn_runtime" "foreground_service"
      ;;
    com.foxhole.guard.ui.cli.logs.CliLogsScreenTest)
      printf '%s\n' "diagnostics_logs" "privacy_controls"
      ;;
    com.foxhole.guard.core.data.LocalDataRepositoryDeviceTest)
      printf '%s\n' "local_data_privacy" "privacy_controls" "secret_deletion_path"
      ;;
    com.foxhole.guard.core.data.ProfileDatabaseSchemaMigrationTest)
      printf '%s\n' "database_migration" "storage_integrity"
      ;;
    com.foxhole.core.runtime.VpnRuntimeSmokeTest)
      printf '%s\n' "vpn_runtime" "foreground_service" "vpn_validation"
      ;;
    com.foxhole.core.runtime.BootReceiverRestoreAndroidTest)
      printf '%s\n' "boot_restore" "package_replace_restore" "foreground_service"
      ;;
    *)
      printf '%s\n' "unclassified"
      ;;
  esac
}

record_matrix_rows() {
  local test_spec="$1"
  local requirement="$2"
  local result="$3"
  local tests="$4"
  local artifact_dir="$5"
  local dimension

  while IFS= read -r dimension; do
    if [[ -n "$dimension" ]]; then
      printf '%s\t%s\t%s\t%s\t%s\t%s\n' \
        "$dimension" "$test_spec" "$requirement" "$result" "$tests" "$artifact_dir" >> "$QA_MATRIX_FILE"
    fi
  done < <(matrix_dimensions_for_spec "$test_spec")
}

required_matrix_dimensions() {
  printf '%s\n' \
    "back_stack" \
    "boot_restore" \
    "dashboard_ui" \
    "database_migration" \
    "diagnostics_logs" \
    "direct_share_import" \
    "dns_filter" \
    "foreground_service" \
    "guard_session" \
    "local_data_privacy" \
    "navigation" \
    "package_replace_restore" \
    "privacy_controls" \
    "profile_import" \
    "profile_management" \
    "runtime_config_integrity" \
    "runtime_ui" \
    "secret_deletion_path" \
    "settings" \
    "storage_integrity" \
    "tor_geoip" \
    "tor_runtime"
  if [[ "${FOXHOLE_LIVE_VPN_SMOKE:-0}" == "1" || "${FOXHOLE_LIVE_LOCAL_GUARD:-0}" == "1" ]]; then
    printf '%s\n' "vpn_runtime"
  fi
  if [[ "${FOXHOLE_REQUIRE_RUNTIME_STRESS:-0}" == "1" ]]; then
    printf '%s\n' "runtime_stress" "smart_subscription" "vpn_runtime"
  fi
}

verify_qa_matrix() {
  local require_full_matrix="${FOXHOLE_REQUIRE_CONNECTED_QA_MATRIX:-}"
  if [[ -z "$require_full_matrix" ]]; then
    if [[ -n "${FOXHOLE_REQUIRED_CONNECTED_TEST_SPECS:-}" ]]; then
      require_full_matrix="0"
    else
      require_full_matrix="1"
    fi
  fi
  if [[ "$require_full_matrix" != "1" ]]; then
    echo "Connected QA matrix dimension gate skipped for an explicit reduced required spec set."
    return
  fi

  local missing=()
  local dimension
  while IFS= read -r dimension; do
    if ! awk -F '\t' -v dimension="$dimension" \
      'NR > 1 && $1 == dimension && $3 == "required" && $4 == "passed" && ($5 + 0) > 0 { found = 1 } END { exit(found ? 0 : 1) }' \
      "$QA_MATRIX_FILE"; then
      missing+=("$dimension")
    fi
  done < <(required_matrix_dimensions)

  if [[ "${#missing[@]}" -gt 0 ]]; then
    echo "Connected QA matrix is missing required passed dimensions: ${missing[*]}" >&2
    echo "Matrix:" >&2
    cat "$QA_MATRIX_FILE" >&2
    exit 1
  fi
}

clear_gradle_connected_outputs() {
  rm -rf \
    app/build/reports/androidTests \
    app/build/outputs/androidTest-results \
    app/build/outputs/connected_android_test_additional_output
}

copy_spec_artifacts() {
  local test_spec="$1"
  local safe_spec
  safe_spec="$(safe_spec_name "$test_spec")"
  local spec_dir="$SPEC_ARTIFACT_ROOT/$safe_spec"
  rm -rf "$spec_dir"
  mkdir -p "$spec_dir"

  local source
  for source in \
    app/build/reports/androidTests \
    app/build/outputs/androidTest-results \
    app/build/outputs/connected_android_test_additional_output
  do
    if [[ -e "$source" ]]; then
      cp -R "$source" "$spec_dir/$(printf '%s' "$source" | tr '/' '_')"
    fi
  done
  printf '%s' "$spec_dir"
}

is_required_test_spec() {
  local candidate="$1"
  local required_spec
  for required_spec in "${REQUIRED_TEST_SPECS[@]}"; do
    if [[ "$required_spec" == "$candidate" ]]; then
      return 0
    fi
  done
  return 1
}

parse_spec_xml() {
  local spec_dir="$1"
  python3 - "$spec_dir" <<'PY'
import pathlib
import sys
import xml.etree.ElementTree as ET

root = pathlib.Path(sys.argv[1])
totals = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
for path in root.rglob("TEST-*.xml"):
    try:
        parsed = ET.parse(path).getroot()
    except ET.ParseError:
        continue
    suites = [parsed] if parsed.tag == "testsuite" else parsed.findall(".//testsuite")
    for suite in suites:
        for key in totals:
            totals[key] += int(float(suite.attrib.get(key, "0") or 0))
print("{tests}\t{failures}\t{errors}\t{skipped}".format(**totals))
PY
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

stage_live_vpn_smoke_profile() {
  local source_file="${FOXHOLE_LIVE_VPN_PROFILE_RAW_FILE:-}"
  if [[ -z "$source_file" ]]; then
    return
  fi
  if [[ ! -f "$source_file" ]]; then
    echo "FOXHOLE_LIVE_VPN_PROFILE_RAW_FILE does not point to a readable file." >&2
    return 1
  fi

  # Keep the profile out of Gradle arguments and logs. Install once after pm clear, copy through a
  # fixed shell-owned staging path into the app's private files directory, then erase the staging
  # copy. connectedDebugAndroidTest replaces the APK without clearing this private file.
  ./gradlew --no-daemon --console=plain :app:installDebug >/dev/null
  local staging_file="/data/local/tmp/foxhole-live-vpn-smoke-$$.profile"
  adb push "$source_file" "$staging_file" >/dev/null
  local copy_status=0
  adb shell run-as "$TARGET_PACKAGE" mkdir -p files >/dev/null 2>&1 || copy_status=$?
  if [[ "$copy_status" == "0" ]]; then
    adb shell run-as "$TARGET_PACKAGE" cp "$staging_file" files/live-vpn-smoke.profile \
      >/dev/null 2>&1 || copy_status=$?
  fi
  adb shell rm -f "$staging_file" >/dev/null 2>&1 || true
  if [[ "$copy_status" != "0" ]]; then
    echo "Could not stage the live VPN profile in the app-private test directory." >&2
    return "$copy_status"
  fi
}

run_test_spec() {
  local test_spec="$1"
  local required="$2"
  local test_timeout="$TEST_CLASS_TIMEOUT_SECONDS"
  local required_label="optional"
  if [[ "$required" == "1" ]]; then
    required_label="required"
  fi
  if [[ "$test_spec" == *"#"* ]]; then
    test_timeout="$TEST_METHOD_TIMEOUT_SECONDS"
  fi

  echo "::group::connectedDebugAndroidTest ${required_label} ${test_spec}"
  adb logcat -c || true
  adb shell am force-stop "$TARGET_PACKAGE" >/dev/null 2>&1 || true
  adb shell pm clear "$TARGET_PACKAGE" >/dev/null 2>&1 || true
  clear_gradle_connected_outputs

  local instrumentation_args=(
    "-Pandroid.testInstrumentationRunnerArguments.class=$test_spec"
  )
  if [[ "$test_spec" == com.foxhole.core.runtime.VpnRuntimeSmokeTest* && "${FOXHOLE_LIVE_VPN_SMOKE:-0}" == "1" ]]; then
    stage_live_vpn_smoke_profile
    adb shell cmd appops set "$TARGET_PACKAGE" ACTIVATE_VPN allow >/dev/null 2>&1 || true
    instrumentation_args+=(
      "-Pandroid.testInstrumentationRunnerArguments.foxhole.liveVpnSmoke=1"
      "-Pandroid.testInstrumentationRunnerArguments.foxhole.requestVpnPermission=1"
    )
  fi
  if [[ "$test_spec" == com.foxhole.core.runtime.LiveLocalFirewallGuardRuntimeTest* &&
        "${FOXHOLE_LIVE_LOCAL_GUARD:-0}" == "1" ]]; then
    adb shell cmd appops set "$TARGET_PACKAGE" ACTIVATE_VPN allow >/dev/null 2>&1 || true
    instrumentation_args+=(
      "-Pandroid.testInstrumentationRunnerArguments.foxhole.liveLocalGuard=1"
      "-Pandroid.testInstrumentationRunnerArguments.foxhole.requestVpnPermission=1"
    )
    if [[ "${FOXHOLE_LIVE_LOCAL_GUARD_STRESS:-0}" == "1" ]]; then
      instrumentation_args+=(
        "-Pandroid.testInstrumentationRunnerArguments.foxhole.liveLocalGuardStress=1"
        "-Pandroid.testInstrumentationRunnerArguments.foxhole.localGuardStressCycles=${FOXHOLE_LOCAL_GUARD_STRESS_CYCLES:-30}"
      )
    fi
  fi

  if [[ "$test_spec" == "com.foxhole.guard.ProfileRuntimeGuardSessionAndroidTest#manualSmartSubscriptionRuntimeStressCycles" &&
        ( "${FOXHOLE_LIVE_RUNTIME_STRESS:-0}" == "1" || "${FOXHOLE_REQUIRE_RUNTIME_STRESS:-0}" == "1" ) ]]; then
    adb shell cmd appops set "$TARGET_PACKAGE" ACTIVATE_VPN allow >/dev/null 2>&1 || true
    instrumentation_args+=(
      "-Pandroid.testInstrumentationRunnerArguments.foxhole.liveRuntimeStress=1"
      "-Pandroid.testInstrumentationRunnerArguments.foxhole.runtimeStressCycles=${FOXHOLE_RUNTIME_STRESS_CYCLES:-50}"
      "-Pandroid.testInstrumentationRunnerArguments.foxhole.smartProtocols=${FOXHOLE_RUNTIME_STRESS_PROTOCOLS:-VLESS}"
      "-Pandroid.testInstrumentationRunnerArguments.foxhole.requireLiveSmartSuccess=${FOXHOLE_REQUIRE_LIVE_RUNTIME_STRESS_SUCCESS:-1}"
      "-Pandroid.testInstrumentationRunnerArguments.foxhole.requestVpnPermission=1"
      "-Pandroid.testInstrumentationRunnerArguments.foxhole.allowInsecureTlsForLiveSubscription=${FOXHOLE_ALLOW_INSECURE_TLS_FOR_LIVE_SUBSCRIPTION:-0}"
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
  fi

  set +e
  run_with_timeout "$test_timeout" \
    ./gradlew :app:connectedDebugAndroidTest --info \
      "${instrumentation_args[@]}"
  local status=$?
  set -e

  local spec_dir
  spec_dir="$(copy_spec_artifacts "$test_spec")"
  local tests failures errors skipped
  IFS=$'\t' read -r tests failures errors skipped < <(parse_spec_xml "$spec_dir")
  local matrix_result="passed"
  if [[ "$status" -ne 0 ]]; then
    matrix_result="failed"
  elif [[ "$tests" -eq 0 ]]; then
    matrix_result="no-tests"
  elif [[ "$skipped" -gt 0 ]]; then
    matrix_result="skipped"
  fi
  record_matrix_rows "$test_spec" "$required_label" "$matrix_result" "$tests" "$spec_dir"
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
    "$test_spec" "$required_label" "$status" "$tests" "$failures" "$errors" "$skipped" "$spec_dir" >> "$SUMMARY_FILE"

  if [[ "$status" -ne 0 ]]; then
    echo "connectedDebugAndroidTest failed for ${test_spec} with exit code ${status}" >&2
    dump_diagnostics "$test_spec"
    echo "::endgroup::"
    exit "$status"
  fi
  if [[ "$required" == "1" && "$tests" -eq 0 ]]; then
    echo "Required connected test spec produced no JUnit XML tests: ${test_spec}" >&2
    echo "::endgroup::"
    exit 1
  fi
  if [[ "$required" == "1" && "$skipped" -gt 0 ]]; then
    echo "Required connected test spec skipped ${skipped}/${tests} tests: ${test_spec}" >&2
    echo "::endgroup::"
    exit 1
  fi
  echo "::endgroup::"
}

adb wait-for-device
rm -rf "$SPEC_ARTIFACT_ROOT"
mkdir -p "$SPEC_ARTIFACT_ROOT"
printf 'spec\trequirement\tgradle_status\ttests\tfailures\terrors\tskipped\tartifact_dir\n' > "$SUMMARY_FILE"
printf 'dimension\tspec\trequirement\tresult\ttests\tartifact_dir\n' > "$QA_MATRIX_FILE"

REQUIRED_TEST_SPECS=()
while IFS= read -r test_spec; do
  REQUIRED_TEST_SPECS+=("$test_spec")
done < <(required_test_specs)
if [[ "${#REQUIRED_TEST_SPECS[@]}" -eq 0 ]]; then
  echo "No required connected test specs were selected" >&2
  exit 1
fi

for test_spec in "${REQUIRED_TEST_SPECS[@]}"; do
  run_test_spec "$test_spec" "1"
done

if [[ "${FOXHOLE_INCLUDE_OPTIONAL_CONNECTED_TESTS:-0}" == "1" ]]; then
  for test_spec in "${OPTIONAL_TEST_SPECS[@]}"; do
    if is_required_test_spec "$test_spec"; then
      continue
    fi
    run_test_spec "$test_spec" "0"
  done
else
  for test_spec in "${OPTIONAL_TEST_SPECS[@]}"; do
    if is_required_test_spec "$test_spec"; then
      continue
    fi
    record_matrix_rows "$test_spec" "optional-not-run" "not-run" "0" ""
    printf '%s\toptional-not-run\t0\t0\t0\t0\t0\t\n' "$test_spec" >> "$SUMMARY_FILE"
  done
fi

verify_qa_matrix

echo "::group::connected test summary"
cat "$SUMMARY_FILE"
echo "::endgroup::"

echo "::group::connected QA matrix"
cat "$QA_MATRIX_FILE"
echo "::endgroup::"
