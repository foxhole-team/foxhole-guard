#!/usr/bin/env bash
set -euo pipefail

root="${1:?device APK artifact directory is required}"
serial="${ANDROID_SERIAL:-emulator-5554}"
mkdir -p build/reports/device
trap 'adb -s "$serial" logcat -d -b crash -b system > build/reports/device/emulator-logcat.txt 2>&1 || true' EXIT
adb -s "$serial" shell df -k /data > build/reports/device/storage.txt
available_kb="$(awk 'END {print $4}' build/reports/device/storage.txt | tr -d '\r')"
if [[ ! "$available_kb" =~ ^[0-9]+$ ]] || (( available_kb < 1048576 )); then
    cat build/reports/device/storage.txt >&2
    echo "The test emulator needs at least 1 GiB free in /data; configure disk-size: 6G." >&2
    exit 1
fi
package_ready=false
for ((attempt = 0; attempt < 30; attempt++)); do
    if adb -s "$serial" shell pm path android 2>/dev/null | grep -q '^package:'; then
        package_ready=true
        break
    fi
    sleep 2
done
[[ "$package_ready" == true ]] || { echo "Emulator Package Manager did not become ready" >&2; exit 1; }
apks=()
while IFS= read -r apk; do apks+=("$apk"); done < <(find "$root" -type f -name '*.apk' | sort)
[[ ${#apks[@]} == 3 ]] || { echo "Expected app and two instrumentation APKs" >&2; exit 1; }
# Avoid the PackageInstaller transport pipe that closes during API 37 installs.
# Push first, then ask the already-ready package manager to install the local file.
for apk in "${apks[@]}"; do
    remote="/data/local/tmp/foxhole-$(basename "$apk")"
    adb -s "$serial" push "$apk" "$remote"
    adb -s "$serial" shell pm install -r -t "$remote"
    adb -s "$serial" shell rm -f "$remote"
done

run_tests() {
    local package="$1" classes="$2" report="$3"
    local instrument_status=0
    adb -s "$serial" shell am instrument -w -r -e class "$classes" \
        "$package/androidx.test.runner.AndroidJUnitRunner" > "$report" 2>&1 || instrument_status=$?
    cat "$report"
    (( instrument_status == 0 )) || return 1
    grep -Eq '^OK \([1-9][0-9]* tests?\)' "$report" || return 1
    ! grep -Eq 'FAILURES|Process crashed|INSTRUMENTATION_FAILED' "$report"
}
failed=0
run_tests com.foxhole.core.runtime.test com.foxhole.core.runtime.FoxCoreRuntimeHandoffAndroidTest build/reports/device/runtime.txt || failed=1
run_tests com.foxhole.guard.debug.test com.foxhole.core.runtime.FoxCoreNativeSeamAndroidTest,com.foxhole.guard.GuardBoundaryAndroidTest,com.foxhole.guard.ui.cli.components.CliModalCloseControlTest build/reports/device/app.txt || failed=1
exit "$failed"
