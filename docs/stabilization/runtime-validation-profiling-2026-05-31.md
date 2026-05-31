# Runtime validation profiling - 2026-05-31

Public release status: BLOCKED.

## Scope

This patch improves runtime-validation observability for the current live-stress
blocker without weakening tunnel acceptance. VPN-bound validation is still
required; Android network validation, libbox activity, runtime proxy egress, and
literal endpoint evidence are not promoted into a success-shaped connected state.

## Changes

- Preserve the original tunnel probe failure as the cause of the user-facing DNS
  validation failure so timeout classification is not lost.
- Map wrapped `TunnelConnectivityProbeTimeoutException` failures to
  `AutoConnectReasonCode.VALIDATION_TIMEOUT`.
- Add per-attempt profiling from `TunnelConnectivityProbe`:
  - attempt number
  - elapsed time
  - success/failure
  - failure class/root
- Add step-level runtime validation diagnostics for:
  - awaiting the VPN network
  - DNS-independent fallback
  - pre-IP endpoint probe
  - primary IP refresh
  - IPv4 fallback IP refresh
  - post-IP literal probe
  - post-IP endpoint probe
- Keep all new diagnostics compact structured log entries.

## Verification

- `./gradlew --no-daemon --console=plain --stacktrace :app:testDebugUnitTest --tests com.foxhole.beta.vpn.TunnelConnectivityProbeTest --tests com.foxhole.beta.vpn.RuntimeValidationSessionGuardTest`

## Device note

Pixel serial `2A091FDH3001PA` appeared in adb but stayed `unauthorized` during
this pass. Debug install to Pixel is blocked until the RSA debugging prompt is
accepted on the phone.
