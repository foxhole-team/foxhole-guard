# Dashboard Hot Path - 2026-05-31

Public release status: BLOCKED.

## Scope

This pass targets the known OOM stack around `HomeNetworkDetailLine` and reduces dashboard placement animation work during live runtime updates.

## Changes

- Removed decorative row icons from `HomeNetworkDetailLine`; callers keep the same API for now, but the hot row no longer creates VectorPainter/Icon work on traffic/IP recompositions.
- Changed dashboard card `animateItem()` usage so placement animation is disabled during ordinary live updates and enabled only for non-dragged cards while reorder is active.
- Added debug-only recomposition counters for `HomeScreen`, `HomeNetworkCard`, `SettingsHomeScreen`, and `StatisticsScreen` through `FoxholeRecompose` logcat events.

## Verification

- Added `HomeDashboardHotPathTest` for dashboard placement animation gating.

Public release remains blocked until the dashboard hot path is verified by device stress/macrobenchmark and memory gates.
