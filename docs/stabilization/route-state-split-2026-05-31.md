# Route State Split - 2026-05-31

Public release status: CLEARED BY 2026-06-01 LOCAL RC PASS.

## Scope

This pass separates the ViewModel's high-volume activity/diagnostics payload from the route states used by Dashboard and Settings.

## Changes

- Added an internal `coreUiState` that excludes diagnostics entries, anomaly events, network activity events, traffic windows, and app traffic windows.
- Kept the existing `uiState` as a compatibility full-state by layering capped activity streams on top of `coreUiState`.
- Moved Dashboard, Settings, Profiles, and Routing route states onto `coreUiState`.
- Added route-level `distinctUntilChanged()` and `Dispatchers.Default` flow isolation for route model construction.
- Moved Statistics to `statisticsRouteState`, so normal Settings routes no longer compute or carry `statisticsDashboard`.
- Settings route construction can now explicitly omit live traffic, installed app inventory, and activity payloads.
- Added `appPickerRouteState` as the only route state that carries the full installed app inventory.
- DNS, Privacy route, and Routing apps summary entry no longer trigger installed-app inventory refresh on route entry.

## Verification

- Added `RouteStateIsolationTest` for Settings payload omission, Statistics payload retention, Dashboard diagnostics insensitivity, and Routing app-inventory gating.

The release blocker from this pass is cleared by the current local RC proof:
connected stress, runtime stress, macrobenchmark, memory, and Pixel release
checks passed locally. See
`docs/BETA_CHECKLIST.md`.
