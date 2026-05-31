# Runtime Supervisor Ownership - 2026-05-31

Public release status: BLOCKED.

## Scope

This pass moves runtime control-plane ownership toward `RuntimeSupervisor` without rewriting the full Android service lifecycle in one unsafe jump.

## Changes

- Added explicit `RuntimeCommand` model for tunnel, proxy, Tor-only, local guard, reload, stop, and kill commands.
- Added `RuntimeSupervisor.dispatch(command)` so command priority/reason are derived from the command model.
- Added `RuntimeControlPlaneOwnershipState` owned by `RuntimeSupervisor`.
- Delegated `FoxholeVpnService` active session, local guard mode, validation-active flag, callback registration flags, and active VPN network handle through supervisor ownership state.
- Expanded `RuntimeEvent` with native, VPN network, upstream, validation, Tor, and cleanup-unresolved events.
- Reducer now supports lifecycle phase transitions and turns cleanup-unresolved into a safe error state.

## Verification

- Added reducer tests for native start, VPN network availability, validation success, cleanup-unresolved error, and stale lifecycle events.
- Added supervisor tests for ownership state and explicit command dispatch.

Public release remains blocked until service connect/reload/stop decisions are fully moved behind supervisor commands and native cleanup is serialized by the native adapter.
