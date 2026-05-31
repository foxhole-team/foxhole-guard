# Native Runtime Cleanup - 2026-05-31

Public release status: BLOCKED.

## Scope

This pass removes ad-hoc coroutine scopes from native start/close helper paths and routes blocking native/reflection calls through one dedicated runtime dispatcher.

## Changes

- Added `RuntimeNativeCallDispatcher`, a single-thread dispatcher for serialized native runtime calls.
- Updated `runBlockingRuntimeClose` to run close calls on the native dispatcher instead of creating a detached `CoroutineScope` per close.
- Updated `startFailClosed` to start native runtime work through the native dispatcher and preserve fail-closed force-kill behavior on timeout.
- Moved `ReflectiveLibboxRuntime` start/reload native/reflection work onto the native dispatcher while keeping stop orchestration outside that dispatcher so close timeouts remain effective.

## Verification

- `RuntimeStopSupportTest` and `RuntimeStartTimeoutPolicyTest` pass with the serialized dispatcher.

Public release remains blocked until cleanup-unresolved start blocking and full runtime stress gates pass on devices.
