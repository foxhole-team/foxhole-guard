#!/usr/bin/env bash
set -euo pipefail

readonly EVENT_NAME="${1:?GitHub event name is required}"
readonly GITHUB_REF_NAME="${2:?GitHub ref is required}"
readonly GRADLEW="${GRADLEW:-./gradlew}"
readonly TARGET_PACKAGE="${FOXHOLE_ANDROID_TEST_TARGET_PACKAGE:-com.foxhole.beta.debug}"
readonly BASELINE_TARGET_PACKAGE="${FOXHOLE_BASELINE_PROFILE_TARGET_PACKAGE:-$TARGET_PACKAGE}"
readonly REQUIRE_FULL_SUITE="${FOXHOLE_REQUIRE_FULL_MACROBENCHMARK:-0}"

install_target_app() {
  local target_package="${1:-$TARGET_PACKAGE}"
  adb wait-for-device
  if [[ "$target_package" == "com.foxhole.beta.debug" ]]; then
    "$GRADLEW" --no-daemon --console=plain --stacktrace :app:installDebug
  fi
  if ! adb shell pm path "$target_package" >/dev/null; then
    echo "Macrobenchmark target package is not installed: ${target_package}" >&2
    adb shell pm list packages 'com.foxhole.beta' >&2 || true
    exit 1
  fi
}

run_startup_benchmark() {
  install_target_app "$BASELINE_TARGET_PACKAGE"
  "$GRADLEW" --no-daemon --console=plain --stacktrace :macrobenchmark:connectedCheck \
    -Pmacrobenchmark.targetPackage="$TARGET_PACKAGE" \
    -Pandroid.testInstrumentationRunnerArguments.class=com.foxhole.beta.macrobenchmark.HomeMacrobenchmark#startup
  python3 scripts/verify-macrobenchmark-thresholds.py
}

run_baseline_profile_generation() {
  local baseline_source
  local baseline_output_dir="app/src/release/generated/baselineProfile"
  install_target_app
  "$GRADLEW" --no-daemon --console=plain --stacktrace :macrobenchmark:connectedCheck \
    -Pmacrobenchmark.targetPackage="$BASELINE_TARGET_PACKAGE" \
    -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=BaselineProfile \
    -Pandroid.testInstrumentationRunnerArguments.class=com.foxhole.beta.macrobenchmark.FoxholeBaselineProfileGenerator
  baseline_source="$(find macrobenchmark/build/outputs -type f -name '*baseline-prof*.txt' | sort | tail -n 1)"
  if [[ -z "$baseline_source" ]]; then
    echo "Baseline profile generation finished without a baseline-prof text artifact." >&2
    find macrobenchmark/build/outputs -type f >&2 || true
    exit 1
  fi
  mkdir -p "$baseline_output_dir"
  cp "$baseline_source" "$baseline_output_dir/baseline-prof.txt"
  if ! grep -q 'Lcom/foxhole/beta/' "$baseline_output_dir/baseline-prof.txt"; then
    echo "Generated baseline profile does not contain FoxHole app rules: ${baseline_output_dir}/baseline-prof.txt" >&2
    find app/src/release/generated -type f >&2 || true
    exit 1
  fi
}

if [[ "$EVENT_NAME" == "pull_request" ]]; then
  run_startup_benchmark
elif [[
  "$REQUIRE_FULL_SUITE" == "1" ||
  "$EVENT_NAME" == "schedule" ||
  "$GITHUB_REF_NAME" == "refs/heads/main" ||
  "$GITHUB_REF_NAME" == refs/tags/*
]]; then
  install_target_app
  "$GRADLEW" --no-daemon --console=plain --stacktrace :macrobenchmark:connectedCheck \
    -Pmacrobenchmark.targetPackage="$TARGET_PACKAGE"
  python3 scripts/verify-macrobenchmark-thresholds.py --full-suite
  run_baseline_profile_generation
elif [[ "$GITHUB_REF_NAME" == "refs/heads/dev" ]]; then
  run_startup_benchmark
else
  echo "Skipping macrobenchmark for event=${EVENT_NAME} ref=${GITHUB_REF_NAME}"
fi
