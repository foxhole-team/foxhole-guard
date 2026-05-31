# Runtime Supervisor Ownership - 2026-05-31

Public release status: CLEARED BY 2026-06-01 LOCAL RC PASS.

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

The release blocker from this pass is cleared by the current local RC proof:
service commands dispatch through `RuntimeSupervisor`, native cleanup is
serialized, stop cleanup is idempotent for already-idle runtimes, and live VM
runtime stress returned to idle with no command backlog, TUN leak, native server
leak, cleanup-unresolved state, or callback leak. Further service decomposition
remains a P1 refactor, not a current release stop.
