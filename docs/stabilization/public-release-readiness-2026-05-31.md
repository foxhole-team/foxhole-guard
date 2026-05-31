# Public release readiness - 2026-05-31

Final decision: **BLOCKED**

Public release is not allowed from this local pass. The build, unit, lint,
required connected UI tests, macrobenchmark wrapper, security scans, release
APK build, native asset verification, and SBOM job are green, but required live
runtime stress did not complete one successful tunnel cycle with either supplied
private profile. Both live profiles reached native start and Android VPN network
establishment, then failed VPN-bound tunnel validation with
`TunnelConnectivityProbeTimeoutException`.

## Build under test

- Commit: `489df6616fb8f6519a1ee92340af615f728ed447`
- Branch: `dev`
- Build variants: debug, debug androidTest, macrobenchmark debug, release split APKs
- Release probe properties: `-Pfoxhole.releaseProbe=true -Pfoxhole.splitApks=true`
- SBOM property: `-Pfoxhole.sbom=true` only

## Devices

- Android VM: `emulator-5554`, model `sdk_gphone16k_x86_64`, Android `17`, SDK `37`, ABI `x86_64`
- Physical Pixel: not visible to `adb devices -l` during the final pass, so Pixel install and Pixel runtime verification were not run.

After the final macrobenchmark/instrumentation pass, `:app:installDebug` was run
again on `emulator-5554`, then the app was launched and a final state snapshot
was collected at `build/foxhole-debug-state/final-vm-state-installed-20260531-1437`.

## Local gate summary

| Gate | Result | Evidence |
| --- | --- | --- |
| Normal Gradle configuration without SBOM | PASS | `./gradlew --no-daemon --console=plain --stacktrace tasks` |
| Detekt and baseline guard | PASS | `:app:detekt verifyDetektBaseline` |
| Lint | PASS | `:app:lintDebug :app:lintRelease :app:lintVitalRelease` |
| Debug unit tests and coverage | PASS | `:app:testDebugUnitTest jacocoDebugUnitTestReport jacocoDebugUnitTestCoverageVerification` |
| Debug APK and androidTest APK build | PASS | `:app:assembleDebug assembleDebugAndroidTest` |
| Release APK build | PASS | `:app:assembleRelease verifyReleaseBuildConfigDefaults` |
| Release native asset verification | PASS | `scripts/verify-adguard-dns-assets.sh app/build/outputs/apk/debug/*.apk app/build/outputs/apk/release/*.apk` |
| Required connected tests on VM | PASS | `scripts/run-connected-android-tests.sh`, artifact root `build/connected-test-specs-post-stress-gate` |
| UI macrobenchmark on VM | PASS | `scripts/run-ci-macrobenchmark.sh schedule refs/heads/dev`, 10 discovered, 1 expected skipped `homeScroll`, 0 failures |
| Runtime live stress on VM | BLOCKED | VLESS and Hysteria2 50-cycle required runs both failed during cycle 1 validation |
| Gitleaks | PASS | `scripts/run-gitleaks-scan.sh`, no leaks found |
| OSV source scan | PASS WITH KNOWN FINDINGS | Wrapper completed under repo policy; upstream scanner reports known `gradle/verification-metadata.xml` build-tool metadata findings |
| SBOM job | PASS | `./gradlew --no-daemon --console=plain --stacktrace cyclonedxBom -Pfoxhole.sbom=true` |
| Pixel verification | BLOCKED | Pixel not visible to adb |

## Runtime stress summary

Supplied live profiles:

- `/home/adam/Downloads/vless-reality-asax-private-proxy-20260525.txt`
- `/home/adam/Downloads/hysteria2-asax-private-proxy-20260525 (1).yaml`

Required live commands attempted on `emulator-5554`:

- `FOXHOLE_RUNTIME_STRESS_CYCLES=50 FOXHOLE_RUNTIME_STRESS_PROTOCOLS=VLESS`
- `FOXHOLE_RUNTIME_STRESS_CYCLES=50 FOXHOLE_RUNTIME_STRESS_PROTOCOLS=HYSTERIA2`

Both profiles failed before completing a successful cycle:

- Native runtime start succeeded.
- Android VPN network establishment was observed.
- VLESS host TCP preflight passed.
- VPN-bound validation timed out.
- Cleanup after the error path was clean: `cleanup_unresolved=false`, `native_server=false`, `tun_fd=false`.

Captured evidence:

- VLESS error state: `build/foxhole-debug-state/runtime-stress-vless-error-20260531-140920`
- Hysteria2 error state: `build/foxhole-debug-state/runtime-stress-hysteria2-error-20260531-141054`
- One-cycle error-path cleanup pass: `FOXHOLE_REQUIRE_LIVE_RUNTIME_STRESS_SUCCESS=0`, connection stopped back to `IDLE`, runtime phase `idle`, queue depth `0`, callbacks `0`.

Perf parser summaries:

- VLESS log: OOM `0`, StrictMode disk-read events `0`, max Java heap `28.3 MiB`, max skipped frames `60`, runtime-health snapshots `6`, VPN connected/disconnected `3/3`.
- Hysteria2 log: OOM `0`, StrictMode disk-read events `0`, max Java heap `28.3 MiB`, max skipped frames `59`, runtime-health snapshots `12`, VPN connected/disconnected `6/6`.

## UI macrobenchmark summary

VM macrobenchmark result:

- Tests: `9` active tests in XML, `10` finished including expected skipped `homeScroll`
- Failures: `0`
- Errors: `0`
- Skipped: `1`
- XML: `macrobenchmark/build/outputs/androidTest-results/connected/debug/TEST-Medium_Phone(AVD) - 17-_macrobenchmark-.xml`
- Trace output: `macrobenchmark/build/outputs/connected_android_test_additional_output/debug/connected/Medium_Phone(AVD) - 17/`

Selected metrics:

- Startup `timeToInitialDisplayMs`: median `1191.1 ms`, min `1164.6 ms`, max `1236.3 ms`
- Settings Application transition: frame count median `18`
- Settings DNS transition: frame count median `18`
- Settings Expert transition: frame count median `10`
- Settings Statistics transition: frame count median `19`
- Settings Traffic transition: frame count median `18`
- Settings Security transition: frame count median `18`, max `26`
- Settings Diagnostics transition: frame count median `19`, max `27`

Macrobenchmark log parser summary:

- OOM count: `0`
- StrictMode disk-read events: `0`
- Max skipped frames: `58`
- Total skipped-frame events: `18`

The macrobenchmark run was on a debuggable build on an emulator, so it is useful
as a CI regression gate but not a real-device release performance substitute.

## Memory summary

Final launched debug app snapshot on the VM:

- Evidence: `build/foxhole-debug-state/final-vm-state-installed-20260531-1437/dumpsys-meminfo.txt`
- Total PSS: `144594 KB` (`141.2 MiB`)
- Total RSS: `282136 KB` (`275.5 MiB`)
- Java Heap PSS: `19924 KB` (`19.5 MiB`)
- Native Heap PSS: `15044 KB` (`14.7 MiB`)
- Dalvik heap alloc: `7960 KB` (`7.8 MiB`)
- Graphics: `0 KB`
- Threads: `41`
- `/proc` VmHWM: `292448 KB` (`285.6 MiB`)
- `/proc` VmRSS: `284168 KB` (`277.5 MiB`)
- `/proc` RssAnon: `164416 KB` (`160.6 MiB`)

The current VM pass does not reproduce the earlier OOM baseline, but the required
successful 50-cycle runtime stress and physical Pixel memory proof are still
missing.

## ANR, OOM, and StrictMode summary

- OOM: `0` in parsed runtime-stress and macrobenchmark logs.
- ANR: no ANR observed in local required connected or macrobenchmark gates.
- StrictMode disk reads: `0` in parsed runtime-stress and macrobenchmark logs.
- Choreographer skipped-frame events still appear in VM logs, but no OOM or ANR
  accompanied the current pass.

## Release gate decision

| Release rule | Result |
| --- | --- |
| 0 ANR during completed local gates | PASS |
| 0 OOM during completed local gates | PASS |
| Java heap below 180 MB target in final VM snapshot | PASS |
| RSS below 700 MB target in final VM snapshot | PASS |
| No app StrictMode disk reads in parsed logs | PASS |
| Required connected tests green | PASS |
| Macrobenchmark wrapper green | PASS |
| Runtime stress 50x tunnel/proxy/live validation | BLOCKED |
| Pixel verification | BLOCKED |
| Public release decision | BLOCKED |

The next release-candidate pass must first fix or explain the VPN-bound
validation timeout on the Android VM for the supplied VLESS/Hysteria2 profiles,
then rerun the required live runtime stress on both the Android VM and the
physical Pixel when the Pixel is visible to adb.
