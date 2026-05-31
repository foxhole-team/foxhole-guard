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
- Added instrumentation-side VPN consent dialog approval for `foxhole.requestVpnPermission=1`, using UI Automator so the live stress gate does not require a separate host-side tap helper on fresh devices.

## Verification

- `./gradlew --no-daemon --console=plain --stacktrace :app:compileDebugAndroidTestKotlin`
- `ANDROID_SERIAL=emulator-5554 FOXHOLE_RUNTIME_STRESS_CYCLES=50 FOXHOLE_RUNTIME_STRESS_PROTOCOLS=VLESS FOXHOLE_RUNTIME_STRESS_SUBSCRIPTION_RAW_FILE=/data/local/tmp/foxhole-vless-subscription.raw bash scripts/run-runtime-stress-gate.sh`
  - Result: failed during cycle 1 because the runtime reached `ConnectionState.ERROR` after VPN-bound validation timed out.
  - Evidence: native start succeeded, VLESS TCP preflight passed, Android VPN network was established, cleanup after error reported `cleanup_unresolved=false`, `native_server=false`, and `tun_fd=false`.
  - Parser summary from `build/foxhole-debug-state/runtime-stress-vless-error-20260531-140920/logcat-threadtime.log`: OOM 0, StrictMode disk-read events 0, max Java heap 28.3 MiB, runtime-health snapshots 6.
- `ANDROID_SERIAL=emulator-5554 FOXHOLE_RUNTIME_STRESS_CYCLES=50 FOXHOLE_RUNTIME_STRESS_PROTOCOLS=HYSTERIA2 FOXHOLE_RUNTIME_STRESS_SUBSCRIPTION_RAW_FILE=/data/local/tmp/foxhole-hysteria2-subscription.raw bash scripts/run-runtime-stress-gate.sh`
  - Result: failed during cycle 1 because the runtime reached `ConnectionState.ERROR` after VPN-bound validation timed out.
  - Evidence: native start succeeded, Android VPN network was established, cleanup after error reported `cleanup_unresolved=false`, `native_server=false`, and `tun_fd=false`.
  - Parser summary from the connected test log: OOM 0, StrictMode disk-read events 0, max Java heap 27.7 MiB, runtime-health snapshots 6.
- `ANDROID_SERIAL=emulator-5554 FOXHOLE_RUNTIME_STRESS_CYCLES=1 FOXHOLE_RUNTIME_STRESS_PROTOCOLS=VLESS FOXHOLE_RUNTIME_STRESS_SUBSCRIPTION_RAW_FILE=/data/local/tmp/foxhole-vless-subscription.raw FOXHOLE_REQUIRE_LIVE_RUNTIME_STRESS_SUCCESS=0 bash scripts/run-runtime-stress-gate.sh`
  - Result: passed as an error-path cleanup stress run.
  - Evidence: cycle 1 ended as `terminalState=ERROR` from `TunnelConnectivityProbeTimeoutException`, then stopped with `connectionState=IDLE`, `runtimePhase=idle`, `commandQueueDepth=0`, `nativeServer=false`, `tunFd=false`, `callbacks=0`, and `cleanupUnresolved=false`.
  - Parser summary from the connected test log: OOM 0, StrictMode disk-read events 0, max Java heap 28.2 MiB, runtime-health snapshots 10.

Public release remains blocked. The local Android VM can execute the live stress harness, but both private live profiles failed the required connected validation before completing a single successful cycle. Pixel verification is still required when the physical Pixel is visible to `adb`.
