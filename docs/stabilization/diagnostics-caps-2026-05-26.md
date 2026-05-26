# Diagnostics caps - 2026-05-26

Public release status: BLOCKED.

## Scope

This pass bounds diagnostics, anomaly, network-activity, and traffic-window lists before they enter `HomeUiState`.

## UI Caps

- Diagnostics preview: 500 entries.
- Anomaly events preview: 200 entries.
- Network activity preview: 200 entries.
- Traffic windows preview: 120 entries.
- App traffic windows preview: 120 entries.

## Export

`DiagnosticsLogger.snapshotForExport()` and `createExportFile()` still read the persisted diagnostics journal and apply retention limits, not the capped Compose preview. Full diagnostics archive export remains separate from UI state.

## Notes

Statistics aggregation is now visibility-gated in `settingsRouteState`: `statisticsDashboard` is computed only while the Statistics route reports visible. Public release remains blocked until route-state splitting, diagnostics paging/export improvements, connected stress, and macrobenchmark gates pass.
