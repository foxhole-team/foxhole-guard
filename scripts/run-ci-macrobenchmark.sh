#!/usr/bin/env bash
set -euo pipefail

readonly EVENT_NAME="${1:?GitHub event name is required}"
readonly GITHUB_REF_NAME="${2:?GitHub ref is required}"
readonly GRADLEW="${GRADLEW:-./gradlew}"
readonly TARGET_PACKAGE="${FOXHOLE_ANDROID_TEST_TARGET_PACKAGE:-com.foxhole.beta.debug}"

if [[ "$EVENT_NAME" == "pull_request" ]]; then
  "$GRADLEW" --no-daemon --console=plain --stacktrace :macrobenchmark:connectedCheck \
    -Pmacrobenchmark.targetPackage="$TARGET_PACKAGE" \
    -Pandroid.testInstrumentationRunnerArguments.class=com.foxhole.beta.macrobenchmark.HomeMacrobenchmark#startup
elif [[ "$EVENT_NAME" == "schedule" || "$GITHUB_REF_NAME" == "refs/heads/dev" ]]; then
  "$GRADLEW" --no-daemon --console=plain --stacktrace :macrobenchmark:connectedCheck \
    -Pmacrobenchmark.targetPackage="$TARGET_PACKAGE"
else
  echo "Skipping macrobenchmark for event=${EVENT_NAME} ref=${GITHUB_REF_NAME}"
fi
