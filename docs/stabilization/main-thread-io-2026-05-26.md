# Main-Thread IO Stabilization - 2026-05-26

Status: PR 2 partial stabilization

## Changes

- Added `BuildConfig.ENABLE_STRICT_MODE`, controlled by `-Pfoxhole.strictMode=true`.
- Debug builds still keep diagnostic logcat enabled by default, but StrictMode is now opt-in for interactive profiling.
- Moved application startup warmup scope to `Dispatchers.IO`.
- Moved `HomeViewModel` secure settings warmup, profile preload, and app-traffic usage-access probe onto `Dispatchers.IO`.
- Added suspend/off-main `RuntimeHealthMetrics.captureResourceSnapshot(...)` and `recordResourceSnapshotAsync(...)`.
- Synchronous runtime resource snapshots now skip `/proc/self/status`, `Debug.getMemoryInfo`, native heap, and thread-stack capture when called from the main thread, and log `skipped_expensive_fields=true`.
- VPN/proxy service runtime-health snapshots use the async IO path for start/stop; destroy-time snapshots use the synchronous cheap fallback so the cancelled service scope cannot drop the event and main-thread disk/native probes are still skipped.

## Verification

- Added `RuntimeHealthMetricsTest` for main-thread expensive-field refusal.
- Added `BuildConfigDiagnosticsTest` proving debug diagnostic logcat and StrictMode flags are separate.
- `./gradlew --no-daemon --console=plain --stacktrace :app:testDebugUnitTest --tests com.foxhole.beta.vpn.RuntimeHealthMetricsTest --tests com.foxhole.beta.BuildConfigDiagnosticsTest`
- `./gradlew --no-daemon --console=plain --stacktrace :app:detekt verifyDetektBaseline`
- `./gradlew --no-daemon --console=plain --stacktrace :app:testDebugUnitTest`
- `./gradlew --no-daemon --console=plain --stacktrace :app:lintDebug`

## Remaining Work

- Follow-up phases still need route-state splitting, diagnostics caps, runtime supervisor ownership, native executor cleanup, and full stress/macrobenchmark gates.
