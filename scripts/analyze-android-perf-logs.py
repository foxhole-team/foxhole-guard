#!/usr/bin/env python3
"""Summarize Android/FoxHole performance signals from log artifacts."""

from __future__ import annotations

import argparse
import gzip
import json
import re
import sys
from collections import Counter
from dataclasses import dataclass, field
from pathlib import Path
from typing import Iterable, Iterator, TextIO


LOG_SUFFIXES = {
    "",
    ".log",
    ".txt",
    ".out",
    ".err",
    ".logcat",
    ".trace",
    ".stack",
    ".meminfo",
    ".gz",
}

SKIPPED_FRAMES_RE = re.compile(r"Skipped\s+(\d+)\s+frames", re.IGNORECASE)
OOM_RE = re.compile(
    r"OutOfMemoryError|out of memory|Failed to allocate|Throwing OOME",
    re.IGNORECASE,
)
GC_PRESSURE_RE = re.compile(
    r"Clamp target GC heap|WaitForGc|blocking GC|GC_FOR_ALLOC|Background concurrent copying GC|"
    r"Explicit concurrent copying GC|ClampGrowthLimit|HeapTaskDaemon",
    re.IGNORECASE,
)
STRICT_DISK_RE = re.compile(
    r"DiskReadViolation|disk read|disk-read|policy violation.*disk",
    re.IGNORECASE,
)
STACK_FRAME_RE = re.compile(r"\s+at\s+([A-Za-z0-9_.$]+)\.([A-Za-z0-9_$<>-]+)\(([^)]*)\)")
JAVA_HEAP_RE = re.compile(r"\bjava_heap_kb=(\d+)\b", re.IGNORECASE)
MEMINFO_LINE_RE = re.compile(
    r"^\s*(Java Heap|Native Heap|Graphics|Unknown|TOTAL PSS|TOTAL RSS|TOTAL):\s+([0-9,]+)\b",
    re.IGNORECASE,
)
PROC_STATUS_RE = re.compile(r"^\s*(VmRSS|VmHWM|RssAnon|Threads):\s+([0-9,]+)\s*(?:kB)?", re.IGNORECASE)
RUNTIME_HEALTH_RE = re.compile(r"\bruntime-health\b|Runtime health", re.IGNORECASE)
RUNTIME_SNAPSHOT_RE = re.compile(
    r"runtime resource snapshot|rss_kb=|pss_kb=|java_heap_kb=|native_heap_kb=",
    re.IGNORECASE,
)
VPN_CONTEXT_RE = re.compile(
    r"\b(TRANSPORT_VPN|VpnService|VpnManager|VpnNetwork|VPN|tunnel|connection|ConnectionState)\b"
)
VPN_CONNECTED_RE = re.compile(r"\bCONNECTED\b|\bstate[ =:]+connected\b", re.IGNORECASE)
VPN_DISCONNECTED_RE = re.compile(r"\bDISCONNECTED\b|\bstate[ =:]+disconnected\b", re.IGNORECASE)
FATAL_RE = re.compile(r"\bFATAL EXCEPTION\b|(?:\sE\s+AndroidRuntime\s*:)|\bFatal signal\b", re.IGNORECASE)
ANR_RE = re.compile(r"\bANR\b|Application Not Responding|Input dispatching timed out", re.IGNORECASE)
IGNORED_SYSTEM_ANR_RE = re.compile(
    r"Application Not Responding:\s+"
    r"(?:com\.android\.launcher3|com\.google\.android\.apps\.nexuslauncher)\b",
    re.IGNORECASE,
)
FIRST_FRAME_RE = re.compile(r"\bfirst_frame_after_tap_ms=(\d+(?:\.\d+)?)\b")
ANALYZER_SUMMARY_RE = re.compile(
    r"^(Android perf log summary|input files:|lines scanned:|max skipped frames:|"
    r"total skipped-frame events:|total skipped frames:|OOM count:|FATAL count:|"
    r"ANR count:|max Java heap seen:|runtime-health snapshot count:|runtime-health line count:|"
    r"VPN connect/disconnect count:|GC pressure lines:|StrictMode disk-read events:|"
    r"first frame after tap:|memory maxima:|top OOM stack roots:|top StrictMode stack roots:|"
    r"GC pressure categories:|parse warnings:)",
)


@dataclass
class Summary:
    input_files: int = 0
    lines: int = 0
    skipped_frame_events: int = 0
    total_skipped_frames: int = 0
    max_skipped_frames: int = 0
    oom_count: int = 0
    gc_pressure_lines: int = 0
    strict_disk_events: int = 0
    fatal_count: int = 0
    anr_count: int = 0
    runtime_health_lines: int = 0
    runtime_health_snapshots: int = 0
    vpn_connected_events: int = 0
    vpn_disconnected_events: int = 0
    max_java_heap_kb: int | None = None
    memory_maxima_kb: dict[str, int] = field(default_factory=dict)
    top_oom_roots: Counter[str] = field(default_factory=Counter)
    top_strict_roots: Counter[str] = field(default_factory=Counter)
    gc_samples: Counter[str] = field(default_factory=Counter)
    first_frame_after_tap_ms: list[float] = field(default_factory=list)
    parse_errors: list[str] = field(default_factory=list)

    def note_memory(self, key: str, value: int) -> None:
        current = self.memory_maxima_kb.get(key)
        if current is None or value > current:
            self.memory_maxima_kb[key] = value

    def note_java_heap(self, value: int) -> None:
        if self.max_java_heap_kb is None or value > self.max_java_heap_kb:
            self.max_java_heap_kb = value


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Analyze Android logcat, dumpsys meminfo, and FoxholeDiag runtime-health artifacts.",
    )
    parser.add_argument(
        "paths",
        nargs="*",
        help="Log files or directories to scan. Reads stdin when omitted or when '-' is used.",
    )
    parser.add_argument(
        "--json",
        action="store_true",
        help="Print a machine-readable JSON summary instead of the text report.",
    )
    parser.add_argument(
        "--top",
        type=int,
        default=5,
        help="Number of stack roots to print for top lists.",
    )
    parser.add_argument(
        "--fail-on-skipped-frames",
        action="store_true",
        help="Exit non-zero when any Choreographer skipped-frame event is found.",
    )
    parser.add_argument(
        "--max-skipped-frames",
        type=int,
        default=0,
        help="Maximum allowed skipped frames for --fail-on-skipped-frames.",
    )
    parser.add_argument(
        "--fail-on-fatal",
        action="store_true",
        help="Exit non-zero when FATAL/AndroidRuntime/Fatal signal lines are found.",
    )
    parser.add_argument(
        "--fail-on-anr",
        action="store_true",
        help="Exit non-zero when ANR lines are found.",
    )
    parser.add_argument(
        "--fail-on-oom",
        action="store_true",
        help="Exit non-zero when OOM lines are found.",
    )
    parser.add_argument(
        "--fail-on-strict-disk",
        action="store_true",
        help="Exit non-zero when StrictMode disk-read events are found.",
    )
    return parser.parse_args()


def discover_files(paths: Iterable[str]) -> Iterator[Path]:
    for raw_path in paths:
        if raw_path == "-":
            continue
        path = Path(raw_path)
        if path.is_dir():
            for child in sorted(path.rglob("*")):
                if child.is_file() and should_read(child):
                    yield child
        elif path.is_file() and should_read(path):
            yield path


def should_read(path: Path) -> bool:
    if path.name.startswith("."):
        return False
    if path.name == "perf-log-summary.txt":
        return False
    if path.suffix == ".gz":
        return path.with_suffix("").suffix in LOG_SUFFIXES
    return path.suffix in LOG_SUFFIXES or path.name in {"meminfo", "logcat", "test-results.log"}


def open_text(path: Path) -> TextIO:
    if path.suffix == ".gz":
        return gzip.open(path, "rt", encoding="utf-8", errors="replace")
    return path.open("rt", encoding="utf-8", errors="replace")


def is_analyzer_summary_line(line: str) -> bool:
    return ANALYZER_SUMMARY_RE.search(line.strip()) is not None


def is_ignored_system_anr_line(line: str) -> bool:
    return IGNORED_SYSTEM_ANR_RE.search(line) is not None


def parse_stream(lines: Iterable[str], summary: Summary) -> None:
    pending_oom_frames = 0
    pending_strict_frames = 0

    for raw_line in lines:
        summary.lines += 1
        line = raw_line.rstrip("\n")
        if is_analyzer_summary_line(line):
            continue

        skipped = SKIPPED_FRAMES_RE.search(line)
        if skipped:
            frames = int(skipped.group(1))
            summary.skipped_frame_events += 1
            summary.total_skipped_frames += frames
            summary.max_skipped_frames = max(summary.max_skipped_frames, frames)

        if RUNTIME_HEALTH_RE.search(line):
            summary.runtime_health_lines += 1
            if RUNTIME_SNAPSHOT_RE.search(line):
                summary.runtime_health_snapshots += 1

        java_heap = JAVA_HEAP_RE.search(line)
        if java_heap:
            summary.note_java_heap(int(java_heap.group(1)))

        meminfo = MEMINFO_LINE_RE.search(line)
        if meminfo:
            key = meminfo.group(1).lower().replace(" ", "_")
            value = parse_int(meminfo.group(2))
            if value is not None:
                summary.note_memory(key, value)
                if key == "java_heap":
                    summary.note_java_heap(value)

        proc_status = PROC_STATUS_RE.search(line)
        if proc_status:
            key = proc_status.group(1).lower()
            value = parse_int(proc_status.group(2))
            if value is not None:
                summary.note_memory(key, value)

        if GC_PRESSURE_RE.search(line):
            summary.gc_pressure_lines += 1
            summary.gc_samples[classify_gc_line(line)] += 1

        if FATAL_RE.search(line):
            summary.fatal_count += 1

        if ANR_RE.search(line) and not is_ignored_system_anr_line(line):
            summary.anr_count += 1

        first_frame = FIRST_FRAME_RE.search(line)
        if first_frame:
            summary.first_frame_after_tap_ms.append(float(first_frame.group(1)))

        if OOM_RE.search(line):
            summary.oom_count += 1
            pending_oom_frames = 80

        if STRICT_DISK_RE.search(line):
            summary.strict_disk_events += 1
            pending_strict_frames = 80

        if pending_oom_frames > 0:
            root = stack_root(line)
            if root and not is_framework_noise(root):
                summary.top_oom_roots[root] += 1
                pending_oom_frames = 0
            else:
                pending_oom_frames -= 1

        if pending_strict_frames > 0:
            root = stack_root(line)
            if root and not is_strictmode_noise(root):
                summary.top_strict_roots[root] += 1
                pending_strict_frames = 0
            else:
                pending_strict_frames -= 1

        vpn_context_line = VPN_CONTEXT_RE.search(line) and "NOT_VPN" not in line
        if vpn_context_line and VPN_CONNECTED_RE.search(line):
            summary.vpn_connected_events += 1
        if vpn_context_line and VPN_DISCONNECTED_RE.search(line):
            summary.vpn_disconnected_events += 1


def parse_int(value: str) -> int | None:
    try:
        return int(value.replace(",", ""))
    except ValueError:
        return None


def stack_root(line: str) -> str | None:
    match = STACK_FRAME_RE.search(line)
    if not match:
        return None
    return f"{match.group(1)}.{match.group(2)}"


def is_framework_noise(root: str) -> bool:
    return root.startswith(
        (
            "java.lang.OutOfMemoryError",
            "dalvik.system.VMStack",
            "android.os.StrictMode",
            "java.lang.Thread",
        )
    )


def is_strictmode_noise(root: str) -> bool:
    return root.startswith(
        (
            "android.os.StrictMode",
            "dalvik.system.VMStack",
            "java.lang.Thread",
        )
    )


def classify_gc_line(line: str) -> str:
    lowered = line.lower()
    if "clamp" in lowered:
        return "gc_clamp"
    if "waitforgc" in lowered:
        return "wait_for_gc"
    if "blocking gc" in lowered:
        return "blocking_gc"
    if "gc_for_alloc" in lowered:
        return "gc_for_alloc"
    if "heap" in lowered:
        return "heap_pressure"
    return "gc_other"


def report_text(summary: Summary, top: int) -> str:
    lines = [
        "Android perf log summary",
        f"input files: {summary.input_files}",
        f"lines scanned: {summary.lines}",
        f"max skipped frames: {summary.max_skipped_frames}",
        f"total skipped-frame events: {summary.skipped_frame_events}",
        f"total skipped frames: {summary.total_skipped_frames}",
        f"OOM count: {summary.oom_count}",
        f"FATAL count: {summary.fatal_count}",
        f"ANR count: {summary.anr_count}",
        f"max Java heap seen: {format_kb(summary.max_java_heap_kb)}",
        f"runtime-health snapshot count: {summary.runtime_health_snapshots}",
        f"runtime-health line count: {summary.runtime_health_lines}",
        (
            "VPN connect/disconnect count: "
            f"connected={summary.vpn_connected_events} "
            f"disconnected={summary.vpn_disconnected_events} "
            f"total={summary.vpn_connected_events + summary.vpn_disconnected_events}"
        ),
        f"GC pressure lines: {summary.gc_pressure_lines}",
        f"StrictMode disk-read events: {summary.strict_disk_events}",
    ]
    if summary.first_frame_after_tap_ms:
        lines.extend(
            [
                (
                    "first frame after tap: "
                    f"count={len(summary.first_frame_after_tap_ms)} "
                    f"p50={percentile(summary.first_frame_after_tap_ms, 0.50):.1f} ms "
                    f"p95={percentile(summary.first_frame_after_tap_ms, 0.95):.1f} ms "
                    f"max={max(summary.first_frame_after_tap_ms):.1f} ms"
                ),
            ],
        )

    if summary.memory_maxima_kb:
        lines.append("memory maxima:")
        for key in sorted(summary.memory_maxima_kb):
            lines.append(f"  {key}: {format_kb(summary.memory_maxima_kb[key])}")

    lines.extend(counter_lines("top OOM stack roots", summary.top_oom_roots, top))
    lines.extend(counter_lines("top StrictMode stack roots", summary.top_strict_roots, top))
    lines.extend(counter_lines("GC pressure categories", summary.gc_samples, top))

    if summary.parse_errors:
        lines.append("parse warnings:")
        for item in summary.parse_errors[:top]:
            lines.append(f"  {item}")
    return "\n".join(lines)


def counter_lines(title: str, counter: Counter[str], top: int) -> list[str]:
    lines = [f"{title}:"]
    if not counter:
        lines.append("  none")
        return lines
    for key, count in counter.most_common(top):
        lines.append(f"  {count} {key}")
    return lines


def format_kb(value: int | None) -> str:
    if value is None:
        return "unknown"
    mib = value / 1024
    return f"{value} KB ({mib:.1f} MiB)"


def percentile(
    values: list[float],
    percentile_value: float,
) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    if len(ordered) == 1:
        return ordered[0]
    position = (len(ordered) - 1) * percentile_value
    lower = int(position)
    upper = min(lower + 1, len(ordered) - 1)
    fraction = position - lower
    return ordered[lower] + (ordered[upper] - ordered[lower]) * fraction


def fail_reasons(summary: Summary, args: argparse.Namespace) -> list[str]:
    failures: list[str] = []
    if args.fail_on_skipped_frames and summary.max_skipped_frames > args.max_skipped_frames:
        failures.append(
            "skipped frames exceeded "
            f"{args.max_skipped_frames}: max={summary.max_skipped_frames}, events={summary.skipped_frame_events}",
        )
    if args.fail_on_fatal and summary.fatal_count > 0:
        failures.append(f"fatal lines found: {summary.fatal_count}")
    if args.fail_on_anr and summary.anr_count > 0:
        failures.append(f"ANR lines found: {summary.anr_count}")
    if args.fail_on_oom and summary.oom_count > 0:
        failures.append(f"OOM lines found: {summary.oom_count}")
    if args.fail_on_strict_disk and summary.strict_disk_events > 0:
        failures.append(f"StrictMode disk-read events found: {summary.strict_disk_events}")
    return failures


def json_summary(summary: Summary) -> str:
    payload = {
        "input_files": summary.input_files,
        "lines": summary.lines,
        "max_skipped_frames": summary.max_skipped_frames,
        "total_skipped_frame_events": summary.skipped_frame_events,
        "total_skipped_frames": summary.total_skipped_frames,
        "oom_count": summary.oom_count,
        "fatal_count": summary.fatal_count,
        "anr_count": summary.anr_count,
        "max_java_heap_kb": summary.max_java_heap_kb,
        "runtime_health_snapshot_count": summary.runtime_health_snapshots,
        "runtime_health_line_count": summary.runtime_health_lines,
        "vpn_connected_events": summary.vpn_connected_events,
        "vpn_disconnected_events": summary.vpn_disconnected_events,
        "gc_pressure_lines": summary.gc_pressure_lines,
        "strict_disk_read_events": summary.strict_disk_events,
        "first_frame_after_tap_ms_count": len(summary.first_frame_after_tap_ms),
        "first_frame_after_tap_ms_p50": percentile(summary.first_frame_after_tap_ms, 0.50),
        "first_frame_after_tap_ms_p95": percentile(summary.first_frame_after_tap_ms, 0.95),
        "first_frame_after_tap_ms_max": max(summary.first_frame_after_tap_ms) if summary.first_frame_after_tap_ms else None,
        "memory_maxima_kb": summary.memory_maxima_kb,
        "top_oom_stack_roots": dict(summary.top_oom_roots.most_common()),
        "top_strictmode_stack_roots": dict(summary.top_strict_roots.most_common()),
        "gc_pressure_categories": dict(summary.gc_samples.most_common()),
        "parse_errors": summary.parse_errors,
    }
    return json.dumps(payload, indent=2, sort_keys=True)


def main() -> int:
    args = parse_args()
    summary = Summary()

    if not args.paths or "-" in args.paths:
        parse_stream(sys.stdin, summary)

    for path in discover_files(args.paths):
        summary.input_files += 1
        try:
            with open_text(path) as stream:
                parse_stream(stream, summary)
        except OSError as error:
            summary.parse_errors.append(f"{path}: {error}")

    print(json_summary(summary) if args.json else report_text(summary, args.top))
    failures = fail_reasons(summary, args)
    if failures:
        print("Android perf log gate failed:", file=sys.stderr)
        for failure in failures:
            print(f"  {failure}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
