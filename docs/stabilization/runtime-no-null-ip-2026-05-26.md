# Runtime no-null IP hotfix - 2026-05-26

Public release status: BLOCKED.

## Scope

This pass removes connect/reload-time tunnel IP clearing from the legacy runtime path while the broader RuntimeStateStore migration is still in progress.

## Changes

- Added `FoxholeVpnRuntimeBridge.markIpInfoRefreshPending(...)` so runtime start/reload can publish a loading IP panel without setting `ipInfo` to `null`.
- Replaced runtime start/reload `updateIpInfo(null)` calls in `FoxholeConnectionLifecycle`, `FoxholeVpnService`, and `FoxholeProxyService`.
- Kept explicit dashboard/manual clear behavior unchanged.
- Kept the previous visible public IP during `CONNECTING`/`RECONNECTING` so a retained value is not hidden only because the connection timestamp advanced.

## Verification

- Unit tests cover bridge pending refresh publication and pending clear on new IP publication.
- Dashboard policy tests cover retaining a previous route IP while connection details load.

Public release remains blocked until the full runtime ownership, bridge migration, stress, memory, connected, and macrobenchmark gates pass.
