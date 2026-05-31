# Route State Split - 2026-05-31

Public release status: BLOCKED.

## Scope

This pass separates the ViewModel's high-volume activity/diagnostics payload from the route states used by Dashboard and Settings.

## Changes

- Added an internal `coreUiState` that excludes diagnostics entries, anomaly events, network activity events, traffic windows, and app traffic windows.
- Kept the existing `uiState` as a compatibility full-state by layering capped activity streams on top of `coreUiState`.
- Moved Dashboard, Settings, Profiles, and Routing route states onto `coreUiState`.
- Added route-level `distinctUntilChanged()` and `Dispatchers.Default` flow isolation for route model construction.
- Moved Statistics to `statisticsRouteState`, so normal Settings routes no longer compute or carry `statisticsDashboard`.
- Settings route construction can now explicitly omit live traffic, installed app inventory, and activity payloads.

## Verification

- Added `RouteStateIsolationTest` for Settings payload omission, Statistics payload retention, and Dashboard diagnostics insensitivity.

Public release remains blocked until diagnostics paging, dashboard hot-path optimization, runtime ownership migration, native cleanup refactor, connected stress, and macrobenchmark gates pass.
