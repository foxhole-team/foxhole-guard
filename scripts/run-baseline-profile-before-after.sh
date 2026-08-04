#!/usr/bin/env bash
set -euo pipefail

readonly ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly GRADLEW="${GRADLEW:-$ROOT_DIR/gradlew}"
readonly TARGET_PACKAGE="${FOXHOLE_BASELINE_PROFILE_TARGET_PACKAGE:-com.foxhole.guard}"
readonly BENCHMARK_CLASS="${FOXHOLE_BASELINE_PROFILE_BENCHMARK_CLASS:-com.foxhole.guard.macrobenchmark.HomeMacrobenchmark}"
readonly TIMESTAMP="$(date +%Y%m%d-%H%M%S)"
readonly ARTIFACT_ROOT="${FOXHOLE_BASELINE_PROFILE_DIFF_ARTIFACT_ROOT:-$ROOT_DIR/build/reports/macrobenchmark-baseline-before-after/$TIMESTAMP}"
readonly BENCHMARK_OUTPUT_ROOT="$ROOT_DIR/macrobenchmark/build/outputs/connected_android_test_additional_output"
readonly TARGET_APK_PROOF_DIR="$ARTIFACT_ROOT/target-apk-proof"

usage() {
  cat <<'EOF'
Usage:
  ANDROID_SERIAL=PIXELDEVICE0001 FOXHOLE_BASELINE_PROFILE_TARGET_PACKAGE=com.foxhole.guard \
    scripts/run-baseline-profile-before-after.sh

Optional environment:
  FOXHOLE_BASELINE_PROFILE_TARGET_PACKAGE       Installed target package, default: com.foxhole.guard.
  FOXHOLE_BASELINE_PROFILE_BENCHMARK_CLASS     Benchmark class/method, default: HomeMacrobenchmark.
  FOXHOLE_BASELINE_PROFILE_DIFF_ARTIFACT_ROOT  Artifact root for before/after reports.
EOF
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

if [[ $# -gt 0 ]]; then
  usage >&2
  exit 2
fi

if [[ -z "${ANDROID_SERIAL:-}" ]]; then
  echo "ANDROID_SERIAL must be set for baseline profile before/after proof." >&2
  exit 2
fi

cd "$ROOT_DIR"
mkdir -p "$ARTIFACT_ROOT"

adb_cmd=(adb -s "$ANDROID_SERIAL")
"${adb_cmd[@]}" wait-for-device
if ! "${adb_cmd[@]}" shell pm path "$TARGET_PACKAGE" >/dev/null; then
  echo "Baseline profile target package is not installed: $TARGET_PACKAGE" >&2
  "${adb_cmd[@]}" shell pm list packages 'com.foxhole.guard' >&2 || true
  exit 1
fi

verify_target_apk_has_packaged_baseline_profile() {
  local apk_paths_file="$TARGET_APK_PROOF_DIR/target-apk-paths.txt"
  mkdir -p "$TARGET_APK_PROOF_DIR"
  "${adb_cmd[@]}" shell pm path "$TARGET_PACKAGE" | tr -d '\r' | sed 's/^package://' > "$apk_paths_file"

  local pulled_count=0
  local has_baseline_prof=0
  local has_baseline_profm=0
  local has_profileinstaller_metadata=0
  while IFS= read -r device_apk_path; do
    [[ -n "$device_apk_path" ]] || continue
    pulled_count=$((pulled_count + 1))
    local host_apk="$TARGET_APK_PROOF_DIR/target-$pulled_count.apk"
    local listing="$host_apk.listing.txt"
    "${adb_cmd[@]}" pull "$device_apk_path" "$host_apk" >/dev/null
    unzip -l "$host_apk" > "$listing"
    grep -q 'assets/dexopt/baseline.prof' "$listing" && has_baseline_prof=1
    grep -q 'assets/dexopt/baseline.profm' "$listing" && has_baseline_profm=1
    grep -q 'META-INF/androidx.profileinstaller_' "$listing" && has_profileinstaller_metadata=1
  done < "$apk_paths_file"

  if [[ "$pulled_count" -eq 0 ]]; then
    echo "No installed APK paths found for baseline profile target package: $TARGET_PACKAGE" >&2
    exit 1
  fi

  if [[ "$has_baseline_prof" -ne 1 || "$has_baseline_profm" -ne 1 || "$has_profileinstaller_metadata" -ne 1 ]]; then
    {
      echo "BaselineProfileMode.Require needs a target APK with a bundled baseline profile."
      echo "Target package does not include bundled baseline profile assets: $TARGET_PACKAGE"
      echo "Expected entries: assets/dexopt/baseline.prof, assets/dexopt/baseline.profm, META-INF/androidx.profileinstaller_*"
      echo "Pulled APK proof: $TARGET_APK_PROOF_DIR"
      if [[ "$TARGET_PACKAGE" == *.debug ]]; then
        echo "The debug target usually does not package release baseline profiles; install and target the release package com.foxhole.guard instead."
      fi
    } >&2
    exit 1
  fi
}

verify_target_apk_has_packaged_baseline_profile

{
  printf 'captured_at=%s\n' "$(date -Is)"
  printf 'android_serial=%s\n' "$ANDROID_SERIAL"
  printf 'target_package=%s\n' "$TARGET_PACKAGE"
  printf 'benchmark_class=%s\n' "$BENCHMARK_CLASS"
  printf 'device_api=%s\n' "$("${adb_cmd[@]}" shell getprop ro.build.version.sdk | tr -d '\r')"
  printf 'fingerprint=%s\n' "$("${adb_cmd[@]}" shell getprop ro.build.fingerprint | tr -d '\r')"
} > "$ARTIFACT_ROOT/run.env"

run_benchmark_mode() {
  local label="$1"
  local baseline_mode="$2"
  local mode_dir="$ARTIFACT_ROOT/$label"
  rm -rf "$BENCHMARK_OUTPUT_ROOT"
  mkdir -p "$mode_dir"
  "${adb_cmd[@]}" logcat -c || true
  "$GRADLEW" --no-daemon --console=plain --stacktrace :macrobenchmark:connectedCheck \
    -Pmacrobenchmark.targetPackage="$TARGET_PACKAGE" \
    -Pandroid.testInstrumentationRunnerArguments.class="$BENCHMARK_CLASS" \
    -Pandroid.testInstrumentationRunnerArguments.foxhole.baselineProfileMode="$baseline_mode" \
    > "$mode_dir/gradle.log" 2>&1
  "${adb_cmd[@]}" logcat -d -v threadtime > "$mode_dir/logcat-threadtime.log" 2>/dev/null || true
  if [[ -d "$BENCHMARK_OUTPUT_ROOT" ]]; then
    cp -R "$BENCHMARK_OUTPUT_ROOT" "$mode_dir/connected_android_test_additional_output"
  fi
  if ! find "$mode_dir" -name '*benchmarkData.json' -print -quit | grep -q .; then
    echo "No benchmarkData.json produced for $label baselineProfileMode=$baseline_mode" >&2
    find "$mode_dir" -maxdepth 4 -type f >&2 || true
    exit 1
  fi
}

run_benchmark_mode before disable
run_benchmark_mode after require

python3 - "$ARTIFACT_ROOT" <<'PY'
from __future__ import annotations

import json
import sys
from pathlib import Path
from typing import Any

root = Path(sys.argv[1])


def load_benchmarks(label: str) -> dict[str, dict[str, Any]]:
    benchmarks: dict[str, dict[str, Any]] = {}
    for path in sorted((root / label).rglob("*benchmarkData.json")):
        data = json.loads(path.read_text())
        for benchmark in data.get("benchmarks", []):
            name = benchmark.get("name")
            if isinstance(name, str):
                benchmarks[name] = benchmark
    return benchmarks


def metric(benchmark: dict[str, Any], metric_name: str, field: str) -> float | None:
    value = benchmark.get("metrics", {}).get(metric_name, {}).get(field)
    if isinstance(value, (int, float)):
        return float(value)
    value = benchmark.get("sampledMetrics", {}).get(metric_name, {}).get(field)
    if isinstance(value, (int, float)):
        return float(value)
    return None


before = load_benchmarks("before")
after = load_benchmarks("after")
common = sorted(before.keys() & after.keys())
if not common:
    raise SystemExit("No common benchmark names found in before/after baseline profile outputs")

rows: list[dict[str, Any]] = []
for name in common:
    for metric_name, field in (
        ("timeToInitialDisplayMs", "median"),
        ("timeToInitialDisplayMs", "maximum"),
        ("frameDurationCpuMs", "P95"),
        ("frameOverrunMs", "P95"),
    ):
        before_value = metric(before[name], metric_name, field)
        after_value = metric(after[name], metric_name, field)
        if before_value is None or after_value is None:
            continue
        delta = after_value - before_value
        pct = (delta / before_value * 100.0) if before_value else 0.0
        rows.append(
            {
                "benchmark": name,
                "metric": f"{metric_name}.{field}",
                "beforeMs": before_value,
                "afterMs": after_value,
                "deltaMs": delta,
                "deltaPct": pct,
            },
        )

if not rows:
    raise SystemExit("No comparable startup/frame metrics found in before/after baseline profile outputs")

(root / "summary.json").write_text(json.dumps({"rows": rows}, indent=2) + "\n")
lines = [
    "# Baseline Profile Before/After",
    "",
    "| Benchmark | Metric | Before | After | Delta | Delta % |",
    "| --- | --- | ---: | ---: | ---: | ---: |",
]
for row in rows:
    lines.append(
        "| {benchmark} | {metric} | {beforeMs:.2f} ms | {afterMs:.2f} ms | {deltaMs:+.2f} ms | {deltaPct:+.1f}% |".format(
            **row,
        ),
    )
(root / "baseline-profile-before-after.md").write_text("\n".join(lines) + "\n")
print(f"Baseline profile before/after report: {root / 'baseline-profile-before-after.md'}")
PY

python3 scripts/analyze-android-perf-logs.py \
  --fail-on-fatal \
  --fail-on-anr \
  --fail-on-oom \
  --fail-on-strict-disk \
  "$ARTIFACT_ROOT" \
  | tee "$ARTIFACT_ROOT/perf-log-summary.txt"

printf 'Baseline profile before/after artifacts: %s\n' "$ARTIFACT_ROOT"
