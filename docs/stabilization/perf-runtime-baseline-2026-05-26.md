# FoxHole Perf/Runtime Baseline - 2026-05-26

Status: **PUBLIC RELEASE BLOCKED**

Scope: evidence-agent baseline for the unified stabilization program. This document captures the current release-blocking evidence before behavior changes. Repository state inspected locally: branch `dev`, commit `7d068a4`.

## Evidence Summary

| Area | Baseline evidence | Release impact |
| --- | --- | --- |
| ANR | Handoff evidence reports an input dispatch timeout during interactive debug/runtime use. The raw ANR trace was not present in the local repo artifacts inspected on 2026-05-26. | Blocked until runtime and navigation stress produce 0 ANR. |
| Memory | Handoff evidence reports Pixel 7 Pro debug build at approximately 620 MB RSS and 534 MB PSS. Java heap was near the 256 MB growth limit. Local connected-test artifacts contain system `/proc/meminfo` snapshots, not process-level `dumpsys meminfo` for the failing run. | Blocked until heap/RSS gates pass and raw process snapshots are archived. |
| OOM | Handoff evidence reports a fatal main-thread OOM while Compose rendered `Icon` / `HomeNetworkDetailLine`. The current local log artifacts do not contain that stack. | Blocked until dashboard hot path is stabilized and OOM does not reproduce under stress. |
| Choreographer | Handoff evidence reports severe skipped-frame events. Existing local logs should be reprocessed with `scripts/analyze-android-perf-logs.py` after new captures. | Blocked until dashboard/settings first frame and jank gates pass. |
| StrictMode | Handoff evidence reports app-owned disk-read stacks on Activity/ViewModel/service lifecycle paths, including database/SQLCipher/keystore and `/proc/self/status` resource snapshots. | Blocked until navigation/service lifecycle paths have no app-owned main-thread disk reads. |
| VPN network churn | Handoff evidence reports underlying network churn and runtime start/stop/preempt/force-kill activity flooding diagnostics/state. | Blocked until runtime events are coalesced and ownership is moved under `RuntimeSupervisor`. |
| IP state | Source grep still finds `FoxholeVpnRuntimeBridge.updateIpInfo(null)` in runtime paths including `FoxholeVpnService`, `FoxholeProxyService`, `FoxholeConnectionLifecycle`, and `HomeViewModelRuntimeSupport`. | Blocked because connect/reload can still publish null IP and cause dashboard flicker. |
| Runtime ownership | `RuntimeSupervisor`, `RuntimeUiState`, reducer tests, and diagnostics exist, but `FoxholeVpnService` still owns substantial lifecycle/session/network/validation state. | Blocked until supervisor/state-store ownership is completed. |
| Native cleanup | Native diagnostics expose generation, cleanup state, native server/TUN ownership, and stop reason, but close/start helpers still need a single native executor and unresolved-cleanup start blocking. | Blocked until cleanup unresolved is honest and blocks reconnect. |

## Current Memory Data

The failing Pixel process-level meminfo bundle is missing from local artifacts. The stabilization handoff gives these process-level values:

| Metric | Value |
| --- | --- |
| Device | Pixel 7 Pro |
| Build | Debug |
| RSS | ~620 MB |
| PSS | ~534 MB |
| Java heap | Near 256 MB growth limit |
| Graphics | Not captured in local artifacts |
| Unknown | Not captured in local artifacts |
| Thread count | Not captured in local artifacts |
| RssAnon / high-water RSS | Not captured in local artifacts |

The new `scripts/collect-foxhole-debug-state.sh` captures `dumpsys meminfo`, `/proc/<pid>/status`, and `/proc/<pid>/smaps_rollup` so future baseline and stress runs preserve these missing fields.

## Current Code/CI State

- Normal Gradle configuration gates CycloneDX behind `-Pfoxhole.sbom=true` in `build.gradle.kts`.
- `.github/workflows/android.yml` already has separate `verify`, `sbom`, and `connected-tests` jobs. The connected job runs emulator tests and invokes `scripts/run-ci-macrobenchmark.sh`.
- Mandatory verify commands are present in CI for Gradle tasks, detekt/baseline, debug/release/vital lint, unit tests, Jacoco report/verification, release assemble with `-Pfoxhole.releaseProbe=true -Pfoxhole.splitApks=true`, native asset verification, gitleaks, and OSV source scan.
- Release workflow still performs release quality gates and SBOM generation, but public release is blocked by runtime/UI evidence until connected and macrobenchmark gates are mandatory on release-candidate commits.

## Existing Build/Security Artifacts

These are historical local artifacts, not a fresh verification run:

| Artifact | Evidence |
| --- | --- |
| Release BuildConfig | `VERSION_NAME=1.0.0-beta1`, `ALLOW_INSECURE_TLS_BY_DEFAULT=false`, `ENABLE_DIAGNOSTIC_LOGCAT=false`, `LIBBOX_SOURCE_VERSION=1.13.11`, `TOR_BUNDLE_VERSION=15.0.9` |
| SBOM | Current `build/reports/cyclonedx/bom.json` is CycloneDX 1.6 with 173 components and 161 dependencies. |
| Gitleaks | `build/reports/security/gitleaks.json` reports 0 findings. |
| OSV | Existing OSV report has findings limited to Gradle verification metadata classification; this is not a fresh source scan. |

## Existing Connected/Performance Artifacts

These are also historical local artifacts:

| Artifact | Evidence |
| --- | --- |
| Connected XML | 52 tests, 0 failures, 0 errors, 4 live-gated skips. |
| Required connected spec summary | 25 required specs, 31 tests, 0 failures/errors/skips; 7 optional specs not run. |
| Macrobenchmark startup | Debug emulator `timeToInitialDisplayMs`: min 1480.7 ms, median 1522.5 ms, max 1573.2 ms over 3 runs. |
| Macrobenchmark caveat | Debug/emulator startup is not release-device proof; `homeScroll` is currently ignored/skipped. |
| Live runtime caveat | Broad connected runs can pass tests that intentionally early-return when live flags are absent; JUnit pass count is not tunnel proof. |

## Local Artifact Notes

- Older AVD connected-test artifacts exist under `build/connected-test-specs*` and `app/build/outputs/androidTest-results`.
- Older Pixel release logcat artifacts exist under `build/device-logs/`.
- No local artifact inspected in this pass contained the reported 2026-05-26 OOM stack or process-level Pixel meminfo values. Treat the handoff report as the release blocker and require a fresh capture before readiness review.

## Parser Dry Run On Existing Local Logs

Command:

```bash
scripts/analyze-android-perf-logs.py --json build/device-logs build/connected-test-final.log build/macrobenchmark-local-hardening.log
```

Result summary:

| Metric | Value |
| --- | --- |
| Input files | 23 |
| Lines scanned | 84,400 |
| Max skipped frames | 343 |
| Skipped-frame events | 4 |
| OOM count | 0 |
| Max Java heap | Unknown |
| Runtime-health snapshots | 0 |
| VPN connected events | 83 |
| VPN disconnected events | 87 |
| GC pressure lines | 4 |
| StrictMode disk-read events | 0 |

This run validates the parser mechanics on available artifacts. It does not clear the release blocker because the failing Pixel OOM/ANR/meminfo bundle is absent.

## Parser/Collector Added

- `scripts/analyze-android-perf-logs.py` parses logcat/dumpsys artifacts for skipped frames, OOM roots, GC pressure, StrictMode disk-read roots, FoxholeDiag runtime-health snapshots, process memory maxima, and VPN connect/disconnect churn.
- `scripts/collect-foxhole-debug-state.sh` captures adb logcat snapshots, `dumpsys meminfo`, activity processes, connectivity, VPN management, process list, package info, appops, and process `/proc` status/smaps when the app is running.

## Initial Release Decision

Decision: **BLOCKED**

Reasons:

- Reported OOM and near-growth-limit Java heap are P0 release blockers.
- Reported ANR/input dispatch timeout is a P0 release blocker.
- Main-thread disk IO evidence remains unresolved.
- `updateIpInfo(null)` still exists in runtime connect/reload-adjacent paths.
- RuntimeSupervisor is not yet the only lifecycle/state owner.
- Full runtime stress and UI macrobenchmark gates have not been executed with the new evidence collector/parser.
