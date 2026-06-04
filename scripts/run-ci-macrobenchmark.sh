#!/usr/bin/env bash
set -euo pipefail

readonly EVENT_NAME="${1:?GitHub event name is required}"
readonly GITHUB_REF_NAME="${2:?GitHub ref is required}"
readonly GRADLEW="${GRADLEW:-./gradlew}"
readonly TARGET_PACKAGE="${FOXHOLE_ANDROID_TEST_TARGET_PACKAGE:-com.foxhole.beta.debug}"

install_target_app() {
  adb wait-for-device
  if [[ "$TARGET_PACKAGE" == "com.foxhole.beta.debug" ]]; then
    "$GRADLEW" --no-daemon --console=plain --stacktrace :app:installDebug
  fi
  if ! adb shell pm path "$TARGET_PACKAGE" >/dev/null; then
    echo "Macrobenchmark target package is not installed: ${TARGET_PACKAGE}" >&2
    adb shell pm list packages 'com.foxhole.beta' >&2 || true
    exit 1
  fi
}

if [[ "$EVENT_NAME" == "pull_request" ]]; then
  install_target_app
  "$GRADLEW" --no-daemon --console=plain --stacktrace :macrobenchmark:connectedCheck \
    -Pmacrobenchmark.targetPackage="$TARGET_PACKAGE" \
    -Pandroid.testInstrumentationRunnerArguments.class=com.foxhole.beta.macrobenchmark.HomeMacrobenchmark#startup
  python3 scripts/verify-macrobenchmark-thresholds.py
elif [[ "$EVENT_NAME" == "schedule" || "$GITHUB_REF_NAME" == "refs/heads/dev" ]]; then
  install_target_app
  "$GRADLEW" --no-daemon --console=plain --stacktrace :macrobenchmark:connectedCheck \
    -Pmacrobenchmark.targetPackage="$TARGET_PACKAGE"
  python3 scripts/verify-macrobenchmark-thresholds.py --full-suite
else
  echo "Skipping macrobenchmark for event=${EVENT_NAME} ref=${GITHUB_REF_NAME}"
fi
