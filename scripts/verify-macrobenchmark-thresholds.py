#!/usr/bin/env python3
"""Validate Android Macrobenchmark JSON output against release gate thresholds."""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path
from typing import Any


STARTUP_BENCHMARK = "startup"
WARM_STARTUP_BENCHMARK = "warmStartup"
FULL_SUITE_BENCHMARKS = {
    STARTUP_BENCHMARK,
    WARM_STARTUP_BENCHMARK,
    "bottomNavigationRoundTrip",
    "dashboardTrafficMapOpen",
    "homeScroll",
    "settingsTrafficTransition",
    "settingsDnsTransition",
    "settingsSmartStartTransition",
    "settingsRoutingAppsPickerSearch",
    "settingsSecurityTransition",
    "settingsApplicationTransition",
    "settingsDiagnosticsTransition",
    "settingsStatisticsTransition",
    "permissionFlow",
}

STARTUP_MEDIAN_MAX_MS = 1_500.0
STARTUP_MAXIMUM_MAX_MS = 2_500.0
TRANSITION_FRAME_CPU_P50_MAX_MS = 60.0
TRANSITION_FRAME_CPU_P90_MAX_MS = 180.0
TRANSITION_FRAME_CPU_P95_MAX_MS = 220.0
TRANSITION_FRAME_OVERRUN_P50_MAX_MS = 60.0
TRANSITION_FRAME_OVERRUN_P90_MAX_MS = 180.0
TRANSITION_FRAME_OVERRUN_P95_MAX_MS = 220.0
STRICT_FRAME_P95_MAX_MS = 16.6
STRICT_FRAME_MAXIMUM_MAX_MS = 700.0
FIRST_FRAME_P50_MAX_MS = 80.0
FIRST_FRAME_P95_MAX_MS = 140.0
FIRST_FRAME_MAXIMUM_MAX_MS = 220.0
MIN_REPEAT_ITERATIONS = 3
FIRST_FRAME_RE = re.compile(r"\bfirst_frame_after_tap_ms=(\d+(?:\.\d+)?)\b")
REQUIRED_TRACE_METRIC_LABELS = {
    STARTUP_BENCHMARK: {"HomeScreenFirstCompositionSumMs"},
    "warmStartup": {"HomeScreenFirstCompositionSumMs"},
    "homeScroll": {"HomeScreenFirstCompositionSumMs"},
    "bottomNavigationRoundTrip": {"SettingsNavigationSumMs"},
    "dashboardTrafficMapOpen": {
        "TrafficMapLoadShapesSumMs",
        "TrafficMapRenderLandBitmapSumMs",
        "TrafficMapBuildRoutesSumMs",
        "TrafficMapDrawSumMs",
    },
    "settingsRoutingAppsPickerSearch": {
        "AppPickerFilterSumMs",
        "AppIconLoadSumMs",
        "SettingsNavigationSumMs",
    },
    "permissionFlow": {"SettingsNavigationSumMs"},
}


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
    parser.add_argument(
        "--strict-release",
        action="store_true",
        help="Apply public-release frame and first-frame acceptance thresholds.",
    )
    parser.add_argument(
        "--navigation-log",
        help="Optional logcat file containing FoxholeNavigation first_frame_after_tap_ms telemetry.",
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


def sampled_metric_optional(benchmark: dict[str, Any], metric: str, fields: tuple[str, ...]) -> float | None:
    values = benchmark.get("sampledMetrics", {}).get(metric, {})
    if not isinstance(values, dict):
        return None
    for field in fields:
        value = values.get(field)
        if isinstance(value, (int, float)):
            return float(value)
    return None


def require_threshold(label: str, actual: float, maximum: float) -> None:
    if actual > maximum:
        raise AssertionError(f"{label} is {actual:.2f} ms; maximum is {maximum:.2f} ms")


def require_trace_metrics(benchmark: dict[str, Any]) -> list[str]:
    name = str(benchmark.get("name"))
    required_labels = REQUIRED_TRACE_METRIC_LABELS.get(name, set())
    metrics = benchmark.get("metrics", {})
    missing = [
        label
        for label in sorted(required_labels)
        if not any(metric_name.startswith(label) for metric_name in metrics)
    ]
    if missing:
        raise AssertionError(f"{name} missing trace metric(s): {', '.join(missing)}")
    return [f"{name} trace={label}" for label in sorted(required_labels)]


def verify_launch(benchmark: dict[str, Any]) -> list[str]:
    name = str(benchmark.get("name"))
    repeat_iterations = int(benchmark.get("repeatIterations", 0))
    if repeat_iterations < MIN_REPEAT_ITERATIONS:
        raise AssertionError(f"{name} repeatIterations is {repeat_iterations}; minimum is {MIN_REPEAT_ITERATIONS}")
    median = metric_value(benchmark, "timeToInitialDisplayMs", "median")
    maximum = metric_value(benchmark, "timeToInitialDisplayMs", "maximum")
    require_threshold(f"{name} timeToInitialDisplayMs median", median, STARTUP_MEDIAN_MAX_MS)
    require_threshold(f"{name} timeToInitialDisplayMs maximum", maximum, STARTUP_MAXIMUM_MAX_MS)
    return [
        f"{name} median={median:.1f} ms",
        f"{name} max={maximum:.1f} ms",
    ] + require_trace_metrics(benchmark)


def verify_transition(
    benchmark: dict[str, Any],
    *,
    strict_release: bool,
) -> list[str]:
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
    cpu_p95_max = STRICT_FRAME_P95_MAX_MS if strict_release else TRANSITION_FRAME_CPU_P95_MAX_MS
    overrun_p95_max = STRICT_FRAME_P95_MAX_MS if strict_release else TRANSITION_FRAME_OVERRUN_P95_MAX_MS
    require_threshold(f"{name} frameDurationCpuMs P95", cpu_p95, cpu_p95_max)
    require_threshold(f"{name} frameOverrunMs P50", overrun_p50, TRANSITION_FRAME_OVERRUN_P50_MAX_MS)
    require_threshold(f"{name} frameOverrunMs P90", overrun_p90, TRANSITION_FRAME_OVERRUN_P90_MAX_MS)
    require_threshold(f"{name} frameOverrunMs P95", overrun_p95, overrun_p95_max)
    frame_max = (
        sampled_metric_optional(benchmark, "frameDurationCpuMs", ("maximum", "max", "P100", "P99"))
        or sampled_metric_optional(benchmark, "frameOverrunMs", ("maximum", "max", "P100", "P99"))
    )
    if strict_release:
        if frame_max is None:
            raise AssertionError(f"{name} missing maximum frame metric for frozen-frame gate")
        require_threshold(f"{name} maximum frame duration", frame_max, STRICT_FRAME_MAXIMUM_MAX_MS)
    return [
        f"{name} frames={frame_count:.0f}",
        f"cpuP50={cpu_p50:.1f} ms",
        f"cpuP90={cpu_p90:.1f} ms",
        f"cpuP95={cpu_p95:.1f} ms",
        f"overrunP50={overrun_p50:.1f} ms",
        f"overrunP90={overrun_p90:.1f} ms",
        f"overrunP95={overrun_p95:.1f} ms",
        f"frameMax={frame_max:.1f} ms" if frame_max is not None else "frameMax=unavailable",
    ] + require_trace_metrics(benchmark)


def percentile(
    values: list[float],
    percentile_value: float,
) -> float:
    if not values:
        raise AssertionError("Cannot calculate percentile for an empty sample")
    ordered = sorted(values)
    if len(ordered) == 1:
        return ordered[0]
    position = (len(ordered) - 1) * percentile_value
    lower = int(position)
    upper = min(lower + 1, len(ordered) - 1)
    fraction = position - lower
    return ordered[lower] + (ordered[upper] - ordered[lower]) * fraction


def verify_navigation_first_frames(path: str | None) -> list[str]:
    if path is None:
        raise AssertionError("strict release macrobenchmark gate requires --navigation-log")
    log_path = Path(path)
    if not log_path.is_file():
        raise AssertionError(f"navigation log is missing: {log_path}")
    values = [float(match.group(1)) for match in FIRST_FRAME_RE.finditer(log_path.read_text(errors="replace"))]
    if not values:
        raise AssertionError(f"navigation log contains no first_frame_after_tap_ms telemetry: {log_path}")
    p50 = percentile(values, 0.50)
    p95 = percentile(values, 0.95)
    maximum = max(values)
    require_threshold("first_frame_after_tap_ms P50", p50, FIRST_FRAME_P50_MAX_MS)
    require_threshold("first_frame_after_tap_ms P95", p95, FIRST_FRAME_P95_MAX_MS)
    require_threshold("first_frame_after_tap_ms max", maximum, FIRST_FRAME_MAXIMUM_MAX_MS)
    return [
        f"firstFrameSamples={len(values)}",
        f"firstFrameP50={p50:.1f} ms",
        f"firstFrameP95={p95:.1f} ms",
        f"firstFrameMax={maximum:.1f} ms",
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

    summaries = verify_launch(benchmarks[STARTUP_BENCHMARK])
    if args.full_suite:
        strict_release = args.strict_release or args.full_suite
        summaries.extend(verify_launch(benchmarks[WARM_STARTUP_BENCHMARK]))
        for name in sorted(FULL_SUITE_BENCHMARKS - {STARTUP_BENCHMARK, WARM_STARTUP_BENCHMARK}):
            summaries.extend(verify_transition(benchmarks[name], strict_release=strict_release))
        if strict_release:
            summaries.extend(verify_navigation_first_frames(args.navigation_log))

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
