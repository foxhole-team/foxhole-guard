#!/usr/bin/env bash
set -euo pipefail

readonly TEST_CLASS_TIMEOUT_SECONDS="${FOXHOLE_ANDROID_TEST_CLASS_TIMEOUT_SECONDS:-900}"
readonly TEST_METHOD_TIMEOUT_SECONDS="${FOXHOLE_ANDROID_TEST_METHOD_TIMEOUT_SECONDS:-240}"
readonly TARGET_PACKAGE="${FOXHOLE_ANDROID_TEST_TARGET_PACKAGE:-com.foxhole.beta.debug}"
readonly SPEC_ARTIFACT_ROOT="${FOXHOLE_CONNECTED_TEST_ARTIFACT_ROOT:-build/connected-test-specs}"
readonly SUMMARY_FILE="$SPEC_ARTIFACT_ROOT/summary.tsv"
readonly DEFAULT_REQUIRED_TEST_SPECS=(
  "com.foxhole.beta.ProfileRuntimeSessionAndroidTest#freshExactImportPreservesUserServerPort"
  "com.foxhole.beta.ProfileRuntimeSessionAndroidTest#freshDirectShareImportsPreserveProvidedRuntimeFields"
  "com.foxhole.beta.ui.HomeRuntimeBehaviorTest"
  "com.foxhole.beta.ui.HomeScreenTest#opensSettingsFromBottomNavigation"
  "com.foxhole.beta.ui.HomeScreenTest#legacySwipeZonesAreRemoved"
  "com.foxhole.beta.ui.HomeScreenTest#horizontalSwipesSwitchDashboardAndSettingsSections"
  "com.foxhole.beta.ui.HomeScreenTest#systemBackFromRoutingAppsReturnsSettingsHome"
  "com.foxhole.beta.ui.HomeScreenTest#settingsDetailHidesBottomBarAndBackReturnsSettingsRoot"
  "com.foxhole.beta.ui.HomeScreenTest#rapidBottomNavigationTapsKeepUiResponsive"
  "com.foxhole.beta.ui.HomeScreenTest#opensProfilesFromHomeAction"
  "com.foxhole.beta.ui.HomeScreenTest#profilesExportActionSelectsInlineTargetsAndOpensDestinationDialog"
  "com.foxhole.beta.ui.HomeScreenTest#profilesRowTapSelectsProfileWithoutOpeningDetailAndDeleteDialogShowsName"
  "com.foxhole.beta.ui.HomeScreenTest#singleProfileEditOpensConfigFormWithoutStuckLoading"
  "com.foxhole.beta.ui.HomeScreenTest#opensUniversalImportMenuFromHomeAction"
  "com.foxhole.beta.ui.HomeScreenTest#settingsHomeShowsApplicationsAndSitesShortcuts"
  "com.foxhole.beta.ui.HomeScreenTest#settingsFooterShowsGithubRepositoryAction"
  "com.foxhole.beta.ui.HomeScreenTest#hiddenExpertSettingsCanBeRestoredFromVersionCard"
  "com.foxhole.beta.ui.HomeScreenTest#versionCardDoesNothingWhenExpertSettingsAreAlreadyVisible"
  "com.foxhole.beta.ui.HomeScreenTest#screenshotToggleUpdatesWindowSecureFlag"
  "com.foxhole.beta.ui.HomeScreenTest#appSettingsExposeScreenshotToggleAndUpdateWindowSecureFlag"
  "com.foxhole.beta.ui.HomeScreenTest#routingAppsScreenOpensPickerFromAddExceptionButton"
  "com.foxhole.beta.ui.HomeScreenTest#routingAppsPickerFiltersInstalledPackages"
  "com.foxhole.beta.ui.HomeScreenTest#routingSitesScreenOpensAddExceptionDialog"
  "com.foxhole.beta.ui.LiveLogsDialogTest"
  "com.foxhole.beta.vpn.ProxyRuntimeSmokeTest"
)
readonly OPTIONAL_TEST_SPECS=(
  "com.foxhole.beta.ProfileRuntimeSessionAndroidTest#logsCurrentActiveProfileRuntimeSummary"
  "com.foxhole.beta.ProfileRuntimeSessionAndroidTest#manualFreshImportConnectsWhenRequested"
  "com.foxhole.beta.ProfileRuntimeSessionAndroidTest#manualDirectShareLinksLogTerminalStateAndDiagnostics"
  "com.foxhole.beta.ProfileRuntimeSessionAndroidTest#manualSmartSubscriptionLogsImportAndTargetProtocolRuntime"
  "com.foxhole.beta.ProfileRuntimeSessionAndroidTest#restoreBaselineRuntimeSettingsWhenRequested"
  "com.foxhole.beta.ProfileRuntimeSessionAndroidTest#liveOptionProbeMatrixLogsVpnBoundIpResults"
  "com.foxhole.beta.vpn.VpnRuntimeSmokeTest"
)

required_test_specs() {
  if [[ -n "${FOXHOLE_REQUIRED_CONNECTED_TEST_SPECS:-}" ]]; then
    printf '%s\n' "$FOXHOLE_REQUIRED_CONNECTED_TEST_SPECS" |
      tr ',' '\n' |
      sed -E 's/^[[:space:]]+//; s/[[:space:]]+$//' |
      awk 'NF > 0'
    return
  fi
  printf '%s\n' "${DEFAULT_REQUIRED_TEST_SPECS[@]}"
}

dump_diagnostics() {
  local test_spec="$1"
  echo "::group::diagnostics for ${test_spec}"
  adb devices -l || true
  adb shell pm list packages 'com.foxhole.beta' || true
  adb shell dumpsys activity processes | sed -n '1,220p' || true
  adb shell dumpsys activity services com.foxhole.beta.debug | sed -n '1,220p' || true
  adb logcat -d -t 1500 || true
  echo "::endgroup::"
}

safe_spec_name() {
  printf '%s' "$1" | sed -E 's/[^A-Za-z0-9._#-]+/_/g; s/#/__/g'
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

  local live_vpn_smoke_arg=""
  if [[ "$test_spec" == com.foxhole.beta.vpn.VpnRuntimeSmokeTest* && "${FOXHOLE_LIVE_VPN_SMOKE:-0}" == "1" ]]; then
    adb shell cmd appops set "$TARGET_PACKAGE" ACTIVATE_VPN allow >/dev/null 2>&1 || true
    live_vpn_smoke_arg="-Pandroid.testInstrumentationRunnerArguments.foxhole.liveVpnSmoke=1"
  fi

  set +e
  if [[ -n "$live_vpn_smoke_arg" ]]; then
    run_with_timeout "$test_timeout" \
      ./gradlew :app:connectedDebugAndroidTest --info \
        -Pandroid.testInstrumentationRunnerArguments.class="$test_spec" \
        "$live_vpn_smoke_arg"
  else
    run_with_timeout "$test_timeout" \
      ./gradlew :app:connectedDebugAndroidTest --info \
        -Pandroid.testInstrumentationRunnerArguments.class="$test_spec"
  fi
  local status=$?
  set -e

  local spec_dir
  spec_dir="$(copy_spec_artifacts "$test_spec")"
  local tests failures errors skipped
  IFS=$'\t' read -r tests failures errors skipped < <(parse_spec_xml "$spec_dir")
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
    run_test_spec "$test_spec" "0"
  done
else
  for test_spec in "${OPTIONAL_TEST_SPECS[@]}"; do
    printf '%s\toptional-not-run\t0\t0\t0\t0\t0\t\n' "$test_spec" >> "$SUMMARY_FILE"
  done
fi

echo "::group::connected test summary"
cat "$SUMMARY_FILE"
echo "::endgroup::"
