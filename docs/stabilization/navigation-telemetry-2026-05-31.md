# Navigation Telemetry - 2026-05-31

Public release status: BLOCKED.

## Scope

This pass makes settings-detail navigation measurable and prevents rapid duplicate route pushes without changing the existing Android-style transition timing.

## Changes

- Added debug-only `FoxholeNavigation` transition telemetry for accepted settings-detail taps.
- Added `navigateToSettingsDetail(...)` with `launchSingleTop = true`.
- Added a 350 ms duplicate-tap suppression gate for the same settings-detail route.
- Routed settings-detail entries, nested picker entries, diagnostics links, and the dashboard privacy-route shortcut through the shared helper.
- Added test tags for settings traffic, diagnostics, and statistics rows so detail routes are easier to target.
- Added macrobenchmark scenarios for Settings -> Traffic, DNS, Security, Application, Expert, Diagnostics, and Statistics transitions.

## Verification

- Added unit coverage for the duplicate-tap gate and the existing subtle detail transition offset.

Public release remains blocked until these macrobenchmarks are run on the Android VM/Pixel targets and first-frame telemetry is collected from device logs.
