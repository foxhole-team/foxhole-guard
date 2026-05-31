# Runtime Stress Gate - 2026-05-31

Public release status: BLOCKED.

## Scope

This pass turns the existing live smart-profile runtime stress test into a clearer release gate and adds a direct local runner for device stress passes.

## Changes

- Extended `manualSmartSubscriptionRuntimeStressCycles` cycle logs with connection state, runtime phase, runtime generation, native generation, command queue depth, IP panel states, and Tor state.
- Added assertions that each normal disconnect returns the app-facing connection state and runtime phase to idle.
- Added an assertion that the runtime command queue is empty after each stress cycle.
- Kept existing assertions for native server, TUN fd, host/config cleanup, callback cleanup, cleanup-unresolved, active service, active VPN network, RSS monotonic growth, and RSS delta.
- Added `scripts/run-runtime-stress-gate.sh` for local live stress runs with explicit subscription input, cycle count, protocol selection, VPN permission app-op attempt, and debug reinstall after instrumentation.
- Added runtime stress as an optional connected-test spec and made it release-blocking when `FOXHOLE_REQUIRE_RUNTIME_STRESS=1`.

## Verification

- This change is mostly connected/live gated. The next local verification step is compile, detekt, and debug unit tests; device execution requires Pixel/AVD availability and live subscription input.

Public release remains blocked until the stress gate is run on the required devices with 0 ANR, 0 OOM, cleanup-unresolved false, no native fd/server leaks, and stable memory.
