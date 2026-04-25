#!/usr/bin/env bash
set -euo pipefail

readonly TEST_CLASS_TIMEOUT_SECONDS="${FOXHOLE_ANDROID_TEST_CLASS_TIMEOUT_SECONDS:-900}"
readonly TEST_METHOD_TIMEOUT_SECONDS="${FOXHOLE_ANDROID_TEST_METHOD_TIMEOUT_SECONDS:-240}"
readonly TARGET_PACKAGE="${FOXHOLE_ANDROID_TEST_TARGET_PACKAGE:-com.foxhole.beta.debug}"
readonly TEST_SPECS=(
  "com.foxhole.beta.ProfileRuntimeSessionAndroidTest"
  "com.foxhole.beta.ui.HomeRuntimeBehaviorTest"
  "com.foxhole.beta.ui.HomeScreenTest#opensSettingsFromBottomNavigation"
  "com.foxhole.beta.ui.HomeScreenTest#legacySwipeZonesAreRemoved"
  "com.foxhole.beta.ui.HomeScreenTest#horizontalSwipesSwitchDashboardAndSettingsSections"
  "com.foxhole.beta.ui.HomeScreenTest#systemBackFromRoutingAppsReturnsSettingsHome"
  "com.foxhole.beta.ui.HomeScreenTest#settingsTabAlwaysReturnsToSettingsRootAfterSwitchingSections"
  "com.foxhole.beta.ui.HomeScreenTest#rapidBottomNavigationTapsKeepUiResponsive"
  "com.foxhole.beta.ui.HomeScreenTest#opensProfilesFromHomeAction"
  "com.foxhole.beta.ui.HomeScreenTest#profilesExportActionSelectsInlineTargetsAndOpensDestinationDialog"
  "com.foxhole.beta.ui.HomeScreenTest#profilesRowTapSelectsProfileWithoutOpeningDetailAndDeleteDialogShowsName"
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

for test_spec in "${TEST_SPECS[@]}"; do
  echo "::group::connectedDebugAndroidTest ${test_spec}"
  adb logcat -c || true
  adb shell am force-stop "$TARGET_PACKAGE" >/dev/null 2>&1 || true
  adb shell pm clear "$TARGET_PACKAGE" >/dev/null 2>&1 || true
  test_timeout="$TEST_CLASS_TIMEOUT_SECONDS"
  if [[ "$test_spec" == *"#"* ]]; then
    test_timeout="$TEST_METHOD_TIMEOUT_SECONDS"
  fi
  live_smoke_args=()
  if [[ "$test_spec" == com.foxhole.beta.vpn.VpnRuntimeSmokeTest* ]]; then
    adb shell cmd appops set "$TARGET_PACKAGE" ACTIVATE_VPN allow >/dev/null 2>&1 || true
    live_smoke_args=(-Pandroid.testInstrumentationRunnerArguments.foxhole.liveVpnSmoke=1)
  fi
  set +e
  run_with_timeout "$test_timeout" \
    ./gradlew connectedDebugAndroidTest --info \
      -Pandroid.testInstrumentationRunnerArguments.class="$test_spec" \
      "${live_smoke_args[@]}"
  status=$?
  set -e
  if [[ "$status" -ne 0 ]]; then
    echo "connectedDebugAndroidTest failed for ${test_spec} with exit code ${status}" >&2
    dump_diagnostics "$test_spec"
    echo "::endgroup::"
    exit "$status"
  fi
  echo "::endgroup::"
done
