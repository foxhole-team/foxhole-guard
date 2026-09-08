#!/usr/bin/env bash
set -euo pipefail

root="${1:?device APK artifact directory is required}"
serial="${ANDROID_SERIAL:-emulator-5554}"
mkdir -p build/reports/device
apks=()
while IFS= read -r apk; do apks+=("$apk"); done < <(find "$root" -type f -name '*.apk' | sort)
[[ ${#apks[@]} == 3 ]] || { echo "Expected app and two instrumentation APKs" >&2; exit 1; }
for apk in "${apks[@]}"; do adb -s "$serial" install -r -t "$apk"; done

run_tests() {
    local package="$1" classes="$2" report="$3"
    adb -s "$serial" shell am instrument -w -r -e class "$classes" \
        "$package/androidx.test.runner.AndroidJUnitRunner" > "$report" 2>&1
    cat "$report"
    grep -Eq '^OK \([1-9][0-9]* tests?\)' "$report"
    ! grep -Eq 'FAILURES|Process crashed|INSTRUMENTATION_FAILED' "$report"
}
run_tests com.foxhole.core.runtime.test com.foxhole.core.runtime.FoxCoreRuntimeHandoffAndroidTest build/reports/device/runtime.txt
run_tests com.foxhole.guard.debug.test com.foxhole.core.runtime.FoxCoreNativeSeamAndroidTest,com.foxhole.guard.GuardBoundaryAndroidTest,com.foxhole.guard.ui.cli.components.CliModalCloseControlTest build/reports/device/app.txt
