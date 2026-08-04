# Static Safety Guards - 2026-05-31

Public release status: CLEARED BY 2026-06-01 LOCAL RC PASS.

## Scope

This pass removes unsafe Android system-service force unwraps from runtime paths and adds static unit guards for the migration rules that are not fully enforceable by detekt yet.

## Changes

- Added `requireSystemServiceSafe(...)` with service name, SDK, device model, and process context in the failure message.
- Replaced `getSystemService<T>()!!` in runtime gateway, reflection, connection controller, VPN service, and proxy service paths.
- Added `RuntimeStaticSafetyGuardTest`.
- Guarded against new `getSystemService<T>()!!` usage.
- Guarded against runtime/service `FoxholeVpnRuntimeBridge.updateIpInfo(null)` regressions while allowing the existing explicit dashboard clear path.
- Added an allowlist fence for existing legacy bridge users so new direct bridge dependencies fail tests.
- Guarded native start/close helper bodies against detached `CoroutineScope(...)` creation.
- Added the static guard suite to `verifyRequiredBehaviorTests`.

## Verification

- Static guards are unit-test backed and run as part of the required behavior test verification.

The release blocker from this pass is cleared by the current local RC proof:
runtime ownership migration and live runtime/device gates passed locally. The
legacy bridge allowlist remains a narrowing task for follow-up hardening.
