# Public release readiness - 2026-05-31

Final decision: **LOCAL RC PASS**

This document was updated after the 2026-06-01 local stabilization pass. The
previous blocked result in this file is superseded: the Android VM and physical
Pixel were both available, the runtime network path fixes were verified with
live firewall/tunnel probes, and the full local release hardening suite completed
without a new app-side blocker.

No GitHub push was performed.

## Build under test

- Branch: `dev`
- Build variants: debug, debug androidTest, macrobenchmark debug, release APK
- sing-box/libbox: `v1.13.12`, commit `1086ab2563320e0da0c23b3a491d8dfa0939dff4`
- Release `BuildConfig`: `ALLOW_INSECURE_TLS_BY_DEFAULT=false`,
  `ENABLE_DIAGNOSTIC_LOGCAT=false`, `LIBBOX_SOURCE_VERSION=1.13.12`

## Devices

- Android VM: `emulator-5554`, `FoxHole_Debug_VM(AVD) - 17`
- Physical Pixel: `2A091FDH3001PA`, release package `com.foxhole.beta`

The debug app was reinstalled on the Android VM after connected and
macrobenchmark instrumentation runs.

## Local gate summary

| Gate | Result | Evidence |
| --- | --- | --- |
| Detekt and baseline guard | PASS | `:app:detekt verifyDetektBaseline` |
| Unit, behavior, assemble, lint, coverage | PASS | `:app:testDebugUnitTest :app:verifyRequiredBehaviorTests :app:compileDebugAndroidTestKotlin :app:assembleDebug :app:assembleRelease :app:lintDebug :app:lintRelease :app:lintVitalRelease :app:jacocoDebugUnitTestReport :app:jacocoDebugUnitTestCoverageVerification` |
| Release native asset verification | PASS | `scripts/verify-adguard-dns-assets.sh app/build/outputs/apk/debug/*.apk app/build/outputs/apk/release/*.apk` |
| Normal connected tests on VM | PASS | XML: `app/build/outputs/androidTest-results/connected/debug/TEST-FoxHole_Debug_VM(AVD) - 17-_app-.xml`, 54 tests, 0 failures, 0 errors, 6 live-gated skips |
| Live local firewall on VM | PASS | `LiveLocalFirewallGuardRuntimeTest`, 3 live tests, including 30 firewall toggles |
| Live VPN smoke on VM | PASS | `VpnRuntimeSmokeTest`, 3 live tests |
| Runtime stress on VM | PASS | `scripts/run-runtime-stress-gate.sh`, 30 VLESS cycles, terminal state `CONNECTED`, final stop idle |
| UI macrobenchmark on VM | PASS | XML: `macrobenchmark/build/outputs/androidTest-results/connected/debug/TEST-FoxHole_Debug_VM(AVD) - 17-_macrobenchmark-.xml`, 9 tests, 0 failures, 1 expected skip |
| Pixel release install and launch | PASS | `adb -s 2A091FDH3001PA install -r app/build/outputs/apk/release/app-release.apk`, launch succeeded |
| Pixel release network and VPN | PASS | VPN network validated, DNS `/1.1.1.1`, `ping google.com` 0 percent packet loss |
| Gitleaks | PASS | `scripts/run-gitleaks-scan.sh`, no leaks found |
| OSV source scan | PASS WITH KNOWN FINDINGS | Wrapper completed; findings are the known `gradle/verification-metadata.xml` build-tool metadata findings |
| SBOM | PASS | `./gradlew --no-daemon --console=plain --stacktrace cyclonedxBom -Pfoxhole.sbom=true` |

## Runtime and network proof

VM VLESS 30-cycle stress:

- Final cycle: `terminalState=CONNECTED`, `ipRefresh=ok:5.181.3.93`
- Final stopped snapshot: `commandQueueDepth=0`, `nativeServer=false`,
  `tunFd=false`, `callbacks=0`, `cleanupUnresolved=false`
- Final memory snapshot: `rssKb=410396`, `pssKb=283684`,
  `nativeHeapKb=8621`, `javaHeapKb=9456`, `threads=65`

VM local firewall live suite:

- `localFirewallGuardPreservesDashboardInternetAndDns`: PASS
- `localFirewallGuardStartsAndStopsVpnNetwork`: PASS
- `firewallToggleThirtyTimesNoRuntimeLeak`: PASS
- Cycle 30 memory snapshot: `pssKb=278326`, `javaHeapKb=15760`,
  `threads=65`

VM live VPN smoke:

- `activeProfileConnectsWithWorkingDnsAndIpInfo`: PASS
- `activeProfileReconnectsAfterDisconnectAndDnsStillWorks`: PASS
- `wifiToCellularSwitchKeepsVpnConnectedAndDnsWorking`: PASS
- Logs included `vpn network passed validation endpoint probe`,
  `validation_result=success`, `validated tunnel ip refresh published to dashboard`,
  and `health_probe_success`.

App-side log scans for the current live runs did not find `Binding socket`,
`EPERM`, repeated dashboard `UnknownHostException`, `geo refresh failed`,
`OutOfMemory`, `ANR`, cleanup-unresolved, or stop escalation entries. The only
remaining broad-device `EPERM` noise observed was Android system-server SNTP
noise, not FoxHole app-side network binding.

## Pixel release proof

Release package:

- `versionCode=2`, `versionName=1.0.0-beta1`
- `primaryCpuAbi=arm64-v8a`
- APK signing version: `2`
- No `DEBUGGABLE` package flag observed in the checked release package dump

Runtime/network:

- Release process launched: `com.foxhole.beta/.MainActivity`
- Android connectivity reported `VPN CONNECTED` and validated for
  `VPN:com.foxhole.beta`, with underlying Wi-Fi network and DNS `/1.1.1.1`
- Device shell DNS/network smoke through the active device path:
  `ping google.com`, 1 transmitted, 1 received, 0 percent packet loss

Memory:

- Settled release snapshot after current release reinstall:
  `TOTAL PSS=292391 KB`, `TOTAL RSS=413912 KB`, `Java Heap=63624 KB`,
  `Graphics=69548 KB`
- Transient launch/GC snapshots reached about `301854 KB` and `330553 KB` PSS
  immediately after launch/reinstall, then settled back under the 300 MB PSS
  target after idle.

## Remaining notes

- Public-facing release copy remains `public beta 1.0`.
- Store/F-Droid screenshots were not regenerated or overwritten.
- The OSV source scan still reports known scanner findings against Gradle
  verification metadata. No new secret leak was found by gitleaks.
- Normal broad connected test XML still has live-gated skips by design; the live
  firewall, VPN smoke, and runtime stress suites above were run explicitly.
