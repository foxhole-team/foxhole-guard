#!/usr/bin/env bash
set -euo pipefail

readonly EVENT_NAME="${1:?GitHub event name is required}"
readonly GITHUB_REF_NAME="${2:?GitHub ref is required}"
readonly GRADLEW="${GRADLEW:-./gradlew}"
readonly TARGET_PACKAGE="${FOXHOLE_ANDROID_TEST_TARGET_PACKAGE:-com.foxhole.beta.debug}"
readonly BASELINE_TARGET_PACKAGE="${FOXHOLE_BASELINE_PROFILE_TARGET_PACKAGE:-$TARGET_PACKAGE}"
readonly REQUIRE_FULL_SUITE="${FOXHOLE_REQUIRE_FULL_MACROBENCHMARK:-0}"
readonly PERF_LOG_ROOT="${FOXHOLE_MACROBENCHMARK_LOG_ROOT:-build/macrobenchmark-logcat}"
readonly STRICT_RELEASE_GATE="${FOXHOLE_STRICT_RELEASE_MACROBENCHMARK:-0}"
readonly FAIL_ON_SKIPPED_FRAMES="${FOXHOLE_MACROBENCHMARK_FAIL_ON_SKIPPED_FRAMES:-0}"
readonly MAX_SKIPPED_FRAMES="${FOXHOLE_MACROBENCHMARK_MAX_SKIPPED_FRAMES:-0}"

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

verify_macrobenchmark_tracing_available() {
  local trace_entry
  adb wait-for-device
  trace_entry="$(
    adb shell 'if [ -d /sys/kernel/tracing ]; then ls -1 /sys/kernel/tracing 2>/dev/null | head -n 1; fi' \
      | tr -d '\r'
  )"
  if [[ -n "$trace_entry" ]]; then
    return
  fi
  echo "Macrobenchmark tracing is unavailable: /sys/kernel/tracing has no readable entries for adb shell." >&2
  echo "AndroidX Macrobenchmark cannot collect reliable Perfetto traces on this device until the OS tracefs setup is fixed." >&2
  adb shell 'id; getprop ro.build.fingerprint; cat /proc/mounts | grep -E "tracefs|debugfs" || true; ls -ld /sys/kernel/tracing /sys/kernel/debug/tracing 2>/dev/null || true' >&2 || true
  exit 1
}

run_macrobenchmark_with_log_gate() {
  local label="$1"
  shift
  local log_path="$PERF_LOG_ROOT/${label}.logcat"
  local analyzer_args=(
    --fail-on-fatal
    --fail-on-anr
    --fail-on-oom
    --fail-on-strict-disk
  )
  mkdir -p "$PERF_LOG_ROOT"
  adb logcat -c || true

  set +e
  "$@"
  local benchmark_status=$?
  adb logcat -d > "$log_path" 2>/dev/null || true
  if [[ "$FAIL_ON_SKIPPED_FRAMES" == "1" ]]; then
    analyzer_args+=(--fail-on-skipped-frames --max-skipped-frames "$MAX_SKIPPED_FRAMES")
  fi
  python3 scripts/analyze-android-perf-logs.py \
    "${analyzer_args[@]}" \
    "$log_path"
  local log_gate_status=$?
  set -e

  if [[ "$benchmark_status" -ne 0 ]]; then
    echo "Macrobenchmark command failed for ${label} with exit code ${benchmark_status}" >&2
    return "$benchmark_status"
  fi
  if [[ "$log_gate_status" -ne 0 ]]; then
    echo "Macrobenchmark logcat gate failed for ${label}; see ${log_path}" >&2
    return "$log_gate_status"
  fi
}

run_startup_benchmark() {
  install_target_app "$BASELINE_TARGET_PACKAGE"
  verify_macrobenchmark_tracing_available
  run_macrobenchmark_with_log_gate startup \
    "$GRADLEW" --no-daemon --console=plain --stacktrace :macrobenchmark:connectedCheck \
    -Pmacrobenchmark.targetPackage="$TARGET_PACKAGE" \
    -Pandroid.testInstrumentationRunnerArguments.class=com.foxhole.beta.macrobenchmark.HomeMacrobenchmark#startup
  python3 scripts/verify-macrobenchmark-thresholds.py
  install_target_app "$TARGET_PACKAGE"
}

run_baseline_profile_generation() {
  local baseline_source
  local baseline_output_dir="app/src/release/generated/baselineProfile"
  install_target_app "$BASELINE_TARGET_PACKAGE"
  "$GRADLEW" --no-daemon --console=plain --stacktrace :macrobenchmark:connectedCheck \
    -Pmacrobenchmark.targetPackage="$BASELINE_TARGET_PACKAGE" \
    -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=BaselineProfile \
    -Pandroid.testInstrumentationRunnerArguments.class=com.foxhole.beta.macrobenchmark.FoxholeBaselineProfileGenerator
  baseline_source="$(
    find macrobenchmark/build/outputs -type f \
      \( -name '*baseline-prof*.txt' -o -name '*startup-prof*.txt' \) |
      sort |
      tail -n 1
  )"
  if [[ -z "$baseline_source" ]]; then
    echo "Baseline profile generation finished without a profile text artifact." >&2
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

run_baseline_profile_generation_when_supported() {
  if [[ "$BASELINE_TARGET_PACKAGE" != "com.foxhole.beta.debug" ]]; then
    echo "Skipping baseline profile generation for external target=${BASELINE_TARGET_PACKAGE}; debug/VM generation is the packaging source."
    return
  fi
  run_baseline_profile_generation
}

preserve_benchmark_outputs() {
  local label="$1"
  local source_root="macrobenchmark/build/outputs/connected_android_test_additional_output"
  local artifact_dir
  artifact_dir="$(pwd)/$PERF_LOG_ROOT/${label}-benchmark-output"
  if [[ ! -d "$source_root" ]]; then
    return
  fi
  rm -rf "$artifact_dir"
  mkdir -p "$artifact_dir"
  (
    cd "$source_root"
    find . -type f \
      \( -name '*benchmarkData.json' -o -name '*.perfetto-trace' \) \
      -exec cp --parents {} "$artifact_dir" \;
  )
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
  verify_macrobenchmark_tracing_available
  if [[ "$STRICT_RELEASE_GATE" != "1" ]]; then
    echo "Running functional full macrobenchmark gate. Set FOXHOLE_STRICT_RELEASE_MACROBENCHMARK=1 for Pixel/release navigation P95 and skipped-frame acceptance."
  else
    echo "Running strict release navigation gate. Whole-log skipped-frame failure remains opt-in via FOXHOLE_MACROBENCHMARK_FAIL_ON_SKIPPED_FRAMES=1."
  fi
  run_macrobenchmark_with_log_gate full-suite \
    "$GRADLEW" --no-daemon --console=plain --stacktrace :macrobenchmark:connectedCheck \
    -Pmacrobenchmark.targetPackage="$TARGET_PACKAGE" \
    -Pandroid.testInstrumentationRunnerArguments.class=com.foxhole.beta.macrobenchmark.HomeMacrobenchmark
  threshold_args=(--full-suite --navigation-log "$PERF_LOG_ROOT/full-suite.logcat")
  if [[ "$STRICT_RELEASE_GATE" == "1" ]]; then
    threshold_args+=(--strict-navigation --strict-release)
  fi
  python3 scripts/verify-macrobenchmark-thresholds.py "${threshold_args[@]}"
  preserve_benchmark_outputs full-suite
  run_baseline_profile_generation_when_supported
  install_target_app "$TARGET_PACKAGE"
elif [[ "$GITHUB_REF_NAME" == "refs/heads/dev" ]]; then
  run_startup_benchmark
else
  echo "Skipping macrobenchmark for event=${EVENT_NAME} ref=${GITHUB_REF_NAME}"
fi
