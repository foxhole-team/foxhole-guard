#!/usr/bin/env python3
"""Validate Android Macrobenchmark JSON output against release gate thresholds."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any


STARTUP_BENCHMARK = "startup"
FULL_SUITE_BENCHMARKS = {
    STARTUP_BENCHMARK,
    "bottomNavigationRoundTrip",
    "homeScroll",
    "settingsTrafficTransition",
    "settingsDnsTransition",
    "settingsSmartStartTransition",
    "settingsRoutingAppsPickerSearch",
    "settingsSecurityTransition",
    "settingsApplicationTransition",
    "settingsDiagnosticsTransition",
    "settingsStatisticsTransition",
}

STARTUP_MEDIAN_MAX_MS = 1_500.0
STARTUP_MAXIMUM_MAX_MS = 2_500.0
TRANSITION_FRAME_CPU_P50_MAX_MS = 60.0
TRANSITION_FRAME_CPU_P90_MAX_MS = 180.0
TRANSITION_FRAME_CPU_P95_MAX_MS = 220.0
TRANSITION_FRAME_OVERRUN_P50_MAX_MS = 60.0
TRANSITION_FRAME_OVERRUN_P90_MAX_MS = 180.0
TRANSITION_FRAME_OVERRUN_P95_MAX_MS = 220.0
MIN_REPEAT_ITERATIONS = 3


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--output-root",
        default="macrobenchmark/build/outputs/connected_android_test_additional_output",
        help="Root directory containing AndroidX benchmarkData.json files.",
    )
    parser.add_argument(
        "--full-suite",
        action="store_true",
        help="Require the full settings-transition benchmark suite instead of startup only.",
    )
    return parser.parse_args()


def benchmark_files(output_root: Path) -> list[Path]:
    return sorted(output_root.rglob("*benchmarkData.json"))


def load_benchmarks(paths: list[Path]) -> dict[str, dict[str, Any]]:
    benchmarks: dict[str, dict[str, Any]] = {}
    for path in paths:
        data = json.loads(path.read_text())
        for benchmark in data.get("benchmarks", []):
            name = benchmark.get("name")
            if not isinstance(name, str):
                continue
            benchmarks[name] = benchmark
    return benchmarks


def metric_value(benchmark: dict[str, Any], metric: str, field: str) -> float:
    value = benchmark.get("metrics", {}).get(metric, {}).get(field)
    if not isinstance(value, (int, float)):
        raise AssertionError(f"{benchmark.get('name')} missing metric {metric}.{field}")
    return float(value)


def sampled_metric_value(benchmark: dict[str, Any], metric: str, field: str) -> float:
    value = benchmark.get("sampledMetrics", {}).get(metric, {}).get(field)
    if not isinstance(value, (int, float)):
        raise AssertionError(f"{benchmark.get('name')} missing sampled metric {metric}.{field}")
    return float(value)


def require_threshold(label: str, actual: float, maximum: float) -> None:
    if actual > maximum:
        raise AssertionError(f"{label} is {actual:.2f} ms; maximum is {maximum:.2f} ms")


def verify_startup(benchmark: dict[str, Any]) -> list[str]:
    repeat_iterations = int(benchmark.get("repeatIterations", 0))
    if repeat_iterations < MIN_REPEAT_ITERATIONS:
        raise AssertionError(f"startup repeatIterations is {repeat_iterations}; minimum is {MIN_REPEAT_ITERATIONS}")
    median = metric_value(benchmark, "timeToInitialDisplayMs", "median")
    maximum = metric_value(benchmark, "timeToInitialDisplayMs", "maximum")
    require_threshold("startup timeToInitialDisplayMs median", median, STARTUP_MEDIAN_MAX_MS)
    require_threshold("startup timeToInitialDisplayMs maximum", maximum, STARTUP_MAXIMUM_MAX_MS)
    return [
        f"startup median={median:.1f} ms",
        f"startup max={maximum:.1f} ms",
    ]


def verify_transition(benchmark: dict[str, Any]) -> list[str]:
    name = str(benchmark.get("name"))
    repeat_iterations = int(benchmark.get("repeatIterations", 0))
    if repeat_iterations < MIN_REPEAT_ITERATIONS:
        raise AssertionError(f"{name} repeatIterations is {repeat_iterations}; minimum is {MIN_REPEAT_ITERATIONS}")
    frame_count = metric_value(benchmark, "frameCount", "median")
    if frame_count <= 0:
        raise AssertionError(f"{name} has no frame samples")
    cpu_p50 = sampled_metric_value(benchmark, "frameDurationCpuMs", "P50")
    cpu_p90 = sampled_metric_value(benchmark, "frameDurationCpuMs", "P90")
    cpu_p95 = sampled_metric_value(benchmark, "frameDurationCpuMs", "P95")
    overrun_p50 = sampled_metric_value(benchmark, "frameOverrunMs", "P50")
    overrun_p90 = sampled_metric_value(benchmark, "frameOverrunMs", "P90")
    overrun_p95 = sampled_metric_value(benchmark, "frameOverrunMs", "P95")
    require_threshold(f"{name} frameDurationCpuMs P50", cpu_p50, TRANSITION_FRAME_CPU_P50_MAX_MS)
    require_threshold(f"{name} frameDurationCpuMs P90", cpu_p90, TRANSITION_FRAME_CPU_P90_MAX_MS)
    require_threshold(f"{name} frameDurationCpuMs P95", cpu_p95, TRANSITION_FRAME_CPU_P95_MAX_MS)
    require_threshold(f"{name} frameOverrunMs P50", overrun_p50, TRANSITION_FRAME_OVERRUN_P50_MAX_MS)
    require_threshold(f"{name} frameOverrunMs P90", overrun_p90, TRANSITION_FRAME_OVERRUN_P90_MAX_MS)
    require_threshold(f"{name} frameOverrunMs P95", overrun_p95, TRANSITION_FRAME_OVERRUN_P95_MAX_MS)
    return [
        f"{name} frames={frame_count:.0f}",
        f"cpuP50={cpu_p50:.1f} ms",
        f"cpuP90={cpu_p90:.1f} ms",
        f"cpuP95={cpu_p95:.1f} ms",
        f"overrunP50={overrun_p50:.1f} ms",
        f"overrunP90={overrun_p90:.1f} ms",
        f"overrunP95={overrun_p95:.1f} ms",
    ]


def main() -> int:
    args = parse_args()
    paths = benchmark_files(Path(args.output_root))
    if not paths:
        raise AssertionError(f"No benchmarkData.json files found under {args.output_root}")
    benchmarks = load_benchmarks(paths)
    required = FULL_SUITE_BENCHMARKS if args.full_suite else {STARTUP_BENCHMARK}
    missing = sorted(required - benchmarks.keys())
    if missing:
        raise AssertionError(f"Required macrobenchmarks did not run: {', '.join(missing)}")

    summaries = verify_startup(benchmarks[STARTUP_BENCHMARK])
    if args.full_suite:
        for name in sorted(FULL_SUITE_BENCHMARKS - {STARTUP_BENCHMARK}):
            summaries.extend(verify_transition(benchmarks[name]))

    print("Macrobenchmark thresholds passed:")
    for summary in summaries:
        print(f"  {summary}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except AssertionError as error:
        print(f"Macrobenchmark threshold failure: {error}", file=sys.stderr)
        raise SystemExit(1)
