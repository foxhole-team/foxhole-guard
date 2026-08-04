#!/usr/bin/env bash
# Pragmatic on-device perf protocol for devices where perfetto/ftrace is unavailable
# (Android 17 CP2A.260705.006 hides tracefs system-wide, so macrobenchmark cannot run).
# Measures: cold-start TotalTime via `am start -W` x N, and frame jank percentiles via
# `dumpsys gfxinfo <pkg>` after a scripted dashboard scroll + bottom-nav roundtrip that
# mirrors HomeMacrobenchmark's swipe/nav coordinate ratios.
set -euo pipefail

PKG="${1:?package name required}"
LABEL="${2:?output label required}"
OUT_DIR="${3:-build/pixel-gfx-bench}"
ITERATIONS="${PIXEL_BENCH_ITERATIONS:-10}"
SCROLL_ROUNDS="${PIXEL_BENCH_SCROLL_ROUNDS:-6}"
ACTIVITY="com.foxhole.guard.ui.cli.CliMainActivity"

mkdir -p "$OUT_DIR"
COLD_CSV="$OUT_DIR/${LABEL}-coldstart.csv"
GFX_TXT="$OUT_DIR/${LABEL}-gfxinfo.txt"

adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard || true
sleep 1

read -r W H < <(adb shell wm size | sed -n 's/.*: \([0-9]*\)x\([0-9]*\)/\1 \2/p')
CX=$((W / 2))
UPPER_Y=$(printf '%.0f' "$(echo "$H * 0.32" | bc)")
LOWER_Y=$(printf '%.0f' "$(echo "$H * 0.78" | bc)")
NAV_Y=$(printf '%.0f' "$(echo "$H * 0.955" | bc)")
# The current CLI dock has six equal slots. Use the centers of the first (Home) and last
# (Settings) slots so the fallback coordinate path stays aligned when semantics are unavailable.
DASH_X=$(printf '%.0f' "$(echo "$W * 0.09" | bc)")
SETT_X=$(printf '%.0f' "$(echo "$W * 0.91" | bc)")

echo "iteration,total_time_ms,wait_time_ms" > "$COLD_CSV"
for i in $(seq 1 "$ITERATIONS"); do
    adb shell am force-stop "$PKG"
    sleep 2
    OUT=$(adb shell am start -W -n "$PKG/$ACTIVITY")
    TOTAL=$(echo "$OUT" | sed -n 's/.*TotalTime: \([0-9]*\).*/\1/p' | head -1)
    WAIT=$(echo "$OUT" | sed -n 's/.*WaitTime: \([0-9]*\).*/\1/p' | head -1)
    echo "$i,${TOTAL:-NA},${WAIT:-NA}" >> "$COLD_CSV"
    sleep 2
    adb shell input keyevent KEYCODE_HOME
    sleep 1
done

# Frame stats: warm app, settle, reset counters, then scripted interaction only.
adb shell am start -W -n "$PKG/$ACTIVITY" > /dev/null
sleep 4
adb shell dumpsys gfxinfo "$PKG" reset > /dev/null
sleep 1
for i in $(seq 1 "$SCROLL_ROUNDS"); do
    adb shell input swipe "$CX" "$LOWER_Y" "$CX" "$UPPER_Y" 120
    sleep 1
    adb shell input swipe "$CX" "$UPPER_Y" "$CX" "$LOWER_Y" 120
    sleep 1
done
for i in 1 2 3; do
    adb shell input tap "$SETT_X" "$NAV_Y"
    sleep 1.5
    adb shell input tap "$DASH_X" "$NAV_Y"
    sleep 1.5
done
adb shell dumpsys gfxinfo "$PKG" > "$GFX_TXT"
adb shell am force-stop "$PKG"

echo "--- cold start ($COLD_CSV):"
cat "$COLD_CSV"
echo "--- gfxinfo summary:"
grep -E 'Total frames rendered|Janky frames|50th percentile|90th percentile|95th percentile|99th percentile' "$GFX_TXT" | head -8
