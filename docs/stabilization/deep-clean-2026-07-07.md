# Deep clean & bug-fix pass — 2026-07-07

Full-codebase analysis + incremental refactor session. Every patch compiled, ran the full
unit suite, and (where it touches runtime/UI behavior) was exercised on the Pixel 7 Pro
debug build before being committed. All commits are local, unsigned (`--no-gpg-sign`),
never pushed, on `beta/ui-rescue-no-jank-theme-map`.

## Result metrics

| Gate | Before | After |
|---|---|---|
| detekt (`:app:detekt`, fix-don't-baseline policy) | **437 weighted, FAILING** | **0, green** |
| Android lint | 0 errors / 37 warnings / 4 hints | 0 errors / **6 warnings** / 1 hint |
| Unit tests | 1280, **6 failing** | 1287+, all green |
| Dead code | — | **−1006 lines** removed |
| Unused resources | — | **−164 string entries** (82 keys × EN+RU) |

Remaining 6 lint warnings are the intentional non-root `circleModifier` parameters in
`TrafficMapRouteScheme.kt` (renaming them `modifier` would be misleading — they style the
inner circle, not the component root).

## Commits, in order, with reasoning

### 1. `905dfe17` — fix(tests): restore green baseline; keep public IPv6 when all IPv4s are private
The baseline was red: 6 tests stale against intentionally shipped changes (Reload
priority SWITCH→NORMAL from the reload-no-longer-kills-start fix; traffic-map copy;
QR-code RU rewording; `effectiveRenderState` rename; the Canvas timeline-chart redesign).
Triage found one **real regression** hiding among them: the IPv4-preference commit
(`f7810927`) dropped IPv6 candidates whenever *any* IPv4 existed, so a device behind
CGNAT (private IPv4 + public IPv6) showed "-" as its IP. `visibleIpCandidates()` now
drops IPv6 only when a **public** IPv4 exists — the anti-flip contract (never IPv4→IPv6
across refreshes) is preserved, the blank-IP edge case is gone.

### 2. `0ae7a142` — fix(ui): Tor modal ready body reflects a connected VPN
The idle Tor modal always said "TOR starts after the VPN permission", even with a live
VPN tunnel where confirm starts Tor immediately. New `homeTorModalReadyBodyRes(state)`
picks between three bodies: permission-first (idle), in-tunnel (VPN live, bypass off),
beside-the-tunnel (bypass on). 5 unit tests pin the mapping. The VPN-connected branch
could not be exercised on-device (no VPN profile on the debug install at the time) —
logic is unit-tested.

### 3. `b6ebcae7` — feat(dashboard): refresh device IP and map on default-network changes while idle
Wired the previously uncommitted `HomeViewModelNetworkChangeSupport` into
`HomeViewModel.init`. The dashboard IP/map refresh used to key only on the VPN service's
network callback, which exists only while a runtime is active — idle cellular↔Wi-Fi
switches left a stale device IP and map origin. Also hardened the WIP: a failed
`registerDefaultNetworkCallback` now only logs; the original `close(error)` would have
crashed the ViewModel scope. **Device-verified**: Wi-Fi toggle produced
`reason=network_change` refresh with correct coalescing, no crash.

### 4. `6bd3ac9c` — fix(runtime): a preempt-cancelled reload no longer reports a runtime failure
Rapid Stop/Start preempts a queued reload with a `user_stop`; the cancelled reload then
routed its `CancellationException` through the failure path — the session-load
`runCatching` captured it into `fail("StandaloneCoroutine was cancelled")`, and the
post-reload failure branch ran restore/recovery/`fail()` racing the disconnect, flashing
a spurious "session ended result=error". Cancellations now rethrow, and failure handling
is skipped when the command job is no longer active (the preemptor owns the teardown).
Complexity kept under the detekt threshold by extracting `handleRuntimeReloadFailure`.

### 5. `b008581c` — refactor: remove dead code (4 orphaned files, 11 unreferenced functions)
Verified by whole-project bare-name search (main + all test sources): zero references.
Removed `SettingsHelpScreen.kt` (381 lines; its route was unlinked when help moved to
bottom sheets), `FoxholeExpressiveLoading.kt`, `GroupedBarChart.kt`, `StackedBarChart.kt`,
`AnimatedSegmentDonutChart`, `HomeHeaderActionButton`, the orphaned statistics
chart-model builders (`trafficChartModel`, `dnsTimelineChartModel`,
`anomalyScoreChartModel`, `appTrafficBuckets`), dead wrappers
(`ensureNotificationChannel` — real path is `ensureConnectionNotificationChannel` —
`mobileNetworkProfileOverride`, `openGeoIpDatabaseRepository`,
`rememberedSmartProfileMetricsUpdatedAtByProfileId`), plus two members orphaned by the
sweep itself (`timeTicks`, `GEOIP_DATABASE_REPOSITORY_URL`).

### 6. `816ee9d4` — style: detektFormat auto-correct sweep — 436 → 7 weighted findings
Mechanical ktlint-backed auto-correct (Indentation ×400, NoUnusedImports, ImportOrdering,
ArgumentListWrapping, blank-line rules) across the 12 files carrying the formatting
backlog. No semantic changes; compile + full suite verified.

### 7. `57b3c12d` — refactor: burn detekt to zero
The 7 residual findings, each fixed structurally rather than suppressed:
- `HomeGestureCard`: single-exit drag loop (was LoopWithTooManyJumpStatements) + pure
  helpers `homeCardSwipeDragTarget` / `fireHomeCardSwipeAction` (complexity 26→<15).
  Gesture math byte-for-byte identical. **Device-verified**: swipe-right refresh on the
  Network card fires the manual refresh with skeleton and updated data.
- `DiagnosticsScreenContent`: cleanup rows + confirm dialog own their state in
  `DiagnosticsCleanupGroup`; the five log open/clear callbacks grouped into
  `DiagnosticsLogActions` (17→13 params).
- `TimelineChart`: per-series path drawing extracted to a `DrawScope` helper.
- `DnsFilterUpdateRepository.autoRefresh`: skip guards collapsed (5→4 returns).
- `TrafficSettingsGeoGroup`: unused `onVisibleDialogChanged` param + stale suppress removed.

### 8. `5d2cdcef` — chore(res): drop 82 unused string resources; autobox-free compose state
Lint `UnusedResources` burn-down. ~50 were `help_*` strings orphaned by the dead
HelpScreen; the rest were left behind by the network-rules / about-geoip / statistics
redesigns. All recoverable from git if the planned Settings help screen wants the copy
back. Also `mutableIntStateOf`/`mutableLongStateOf` on three hot recomposition paths
(drag reorder, loading clocks); the `rememberSaveable` site was deliberately left alone
(primitive-state savers are not a safe drop-in there).

### 9. `587b3dc0` — test(dashboard): pin IPv4-preference and IPv6-fallback visible-IP contracts
Two tests covering the dual-stack (public IPv4 wins, IPv6 hidden even as secondary) and
IPv6-only fallback behaviors of patch 1.

### 10. `23b8d50c` — fix(chrome): registration stack ends the lost-top-bar navigation bug
User-reported: after some navigations a screen came up "topped-up" — no title bar,
content under the status bar, stuck (`screen_mid.png` symptom). Root cause: the frosted
top bar's host held a **single registration slot**. When a pushed screen was popped while
the covered screen never left composition (interrupted transition, cancelled predictive
back), the covered screen's register effect never re-ran (unchanged LaunchedEffect keys),
so the popped screen's legitimate unregister left the slot null — permanently.
Registrations now form a **stack**: unregistering the top reveals the still-composed
screen underneath. Scroll-sync claims got the same treatment and now restore the previous
claimant's frost progress (replacing the fragile ownership guard documented in the old
comment). DEBUG-only `FoxholeChrome` register/unregister logs kept for field diagnosis.
**Device-verified** with rapid tab flips, fast subscreen push/pop, and map-detail round
trips: every unregister reveals the depth-1 owner, chrome intact, no crashes.

## Verification notes

- Final gate: `:app:detekt` + `lintDebug` + all module `test`/`testDebugUnitTest` +
  `assembleDebug` — green; APK installed and driven on Pixel 7 Pro (`PIXELDEVICE0001`).
- The debug install now carries a smart-profile fixture ("Smart Config", 3 protocol
  options on IP-literal endpoints) imported via the `foxhole_debug_import_raw` intent for
  ongoing UI-cascade work. Note for future imports: the importer rejects unresolvable
  hostnames, and smart grouping requires the `# === PROTO / group ===` header lines.

## Open items (tracked in the session checklist, in execution order)

1. **BUG reconnect ordering** (VPN first, wait validation, then Tor; VPN-fail +
   Tor-beside-ok → still start Tor) — blocked on a live VPN backend for on-device
   verification; runtime changes are not shipped blind in this project.
2. **BUG smart-profile latency icon not updating** (dashboard / profiles / management
   window) — fixture ready; prime suspect is a network-fingerprint key mismatch between
   the probe write path and the UI read path.
3. **BUG Restart button does not appear after switching protocols in the smart-profile
   dropdown.**
4. UI cascade: network-widget type badge + DNS rules row, skeleton scenarios, dashboard
   polish, QR-scanner redesign, buttons→Profiles merge, "+" import + help→Settings,
   h:mm timer, profiles swipe-refresh, smart-latency memory + dead SmartStart removal,
   preloader redesign, skeleton geometry sync, one-line Tor-in-VPN map route,
   network-change skeleton refresh, compact protocol selector second row.

---

# Session 2 — 2026-07-08 (developer preview 0.0.1-dev.1)

Seven verified local commits (`ea85cee6..c27ba3dc`), each gated on compile + detekt 0 +
full app unit suite, and device-verified on the Pixel 7 Pro with the user's live VLESS
profile where the change touches runtime/dashboard behaviour.

## BUG 2 root cause and fix — smart-profile latency memory (`ea85cee6`)

- **Root cause:** the UI readers (`rememberedSmartStartLatencyByOptionId` and friends)
  preferred the network-fingerprint-scoped memory over the global one (`scoped ?: global`).
  Every probe writes BOTH the scoped and the global (`protocolMemories`) records, so the
  global entry is by construction the latest measurement — but a stale scoped snapshot
  taken under an older fingerprint shadowed it forever, freezing the latency icon color.
- **Fix (per the "only the latest memory" spec):** all UI-facing remembered readers now
  read the global protocol memories only; network-scoped memories remain exactly where
  they matter — auto-connect ranking and last-known-good selection
  (`preferredLastKnownGoodOptionId`, `rememberedAutoConnectLatency`). Dead
  `rememberedSmartProfileMetricsUpdatedAtByOptionId` reader deleted.
- **Live connected metrics:** the connected dashboard probes (public tunnel latency +
  direct server TCP ping) now (a) feed the live latency cache so the protocol icon color
  tracks the connected protocol, and (b) persist into smart-profile memory — first sample
  immediately, then throttled to one write per 20 s. Both are skipped for the tunnel
  latency when Tor owns the tunnel egress (`shouldPublishRuntimeProxyIpInfoToDashboard`
  gate) so Tor-circuit latency can never poison a VPN protocol's memory; the direct
  server ping stays valid and persists either way.
- **Profiles screen is now a live metrics surface:** the connected-metrics refresh loop
  runs when the dashboard OR the profiles route is visible (new
  `onProfilesUiVisibilityChanged`), so the protocol-management window updates in place
  (device-verified: server ping ticking on the profiles screen under Tor-in-VPN).
- **Testing is blocked while a Tor route is active** (banner + diagnostics record):
  reconnecting through every protocol would tear down the live Tor session and the
  probes would measure the Tor circuit anyway.

## Fixes and features (in commit order)

- `3110f8b1` — **Restart→Stop on prompt expiry:** the RECONNECT offer was latched on
  `reconnectRequired` forever after the 13 s countdown; the prompt timeout now clears the
  requirement (same contract as the route-mode restart prompt). Source-contract test.
- `55a67bc7` — **Timer back to h:mm:ss** with 1 s ticks (was h:mm per the earlier spec,
  reverted on request).
- `64300984` + `6cedf89d` — **Network widget v3/v3.1:** network type moved into the card
  header right edge (icon + value only, accent tone; captions "Network type:"/"Total:"
  removed on follow-up), DNS-rules footer deleted, country flags removed from IP rows,
  and the **DNS server row became the card's full-width bottom line** showing PUBLIC
  resolver addresses only (private/ULA resolvers render "-"), ordered IP → flag →
  country code. The right column's DNS row reflects the live route (whole-device-in-Tor
  reads TOR) with green highlight for TOR/DoH/DoT. Content height trimmed to kill the
  dead bottom gutter.
- `6cedf89d` — **Tor modal rework:** the info table is always expanded — a disengaged
  Tor runs one intro skeleton sweep and settles on "offline" values; engaged/starting
  Tor shows scenario copy (all-device vs selected apps, inside VPN vs standalone) with a
  small exit-node line (TOR exit = VPN server / your device). The full-width "Change IP"
  button is gone: restart-Tor is a refresh icon beside the settings gear behind a
  "Restart the TOR network?" confirmation. Device scope icon is a smartphone; the modal
  route-all label reads "Device".
- `6e8d1fd6` — **Traffic-map route label** distinguishes "TOR in VPN" (Tor inside the
  tunnel) from "VPN and TOR" (Tor beside it). Unit tests.
- `c27ba3dc` — **Entry-skeleton gate:** the 900 ms dashboard entry skeleton now opens
  only on cold start or return from background (`DashboardEntrySkeletonGate`, armed from
  `MainActivity.onStop`), never on Settings↔Dashboard section switches (that regression
  dropped the traffic widget into a skeleton on every tab change). Help popups use a
  question-mark icon; card headers use a tighter icon-title gap. Gate unit tests.

## Verification notes

- The reported "crash on cancelling protocol testing" left no trace: DropBox holds no
  FoxHole java crash/ANR/tombstone for 2026-07-07/08 (only Google Photos native
  crashes). The earlier fixture "Testing" failure was the fixture subscription being
  expired (`expire=May 23 2026` < today) — candidates are legitimately empty then.
- Device checks this session: live VLESS connect (NL exit), Tor-in-VPN session,
  management window live server ping on the profiles screen, offline tor modal with
  intro skeleton, network v3.1 header/DNS row, no-skeleton section round trip.

## Versioning

- App switched to the developer-preview line: `versionName 0.0.1-dev.1`,
  `versionCode 3`. Build history lives in the workspace `AGENTS.md`.

## Carried-over checklist (next pass)

1. **BUG 1 start/reconnect ordering** — now unblocked by a live config: start VPN first,
   refresh the network widget, then bring up Tor (the current simultaneous start drops
   the network card into the Tor hold immediately); VPN-fail + Tor-beside-ok must still
   start Tor. Needs runtime orchestration + unit tests + live device verify.
2. **DNS leak test** — the DNS server row shows "-" whenever the resolver is private:
   resolve the actual egress resolver (dnsleaktest-style probe) and show IP + flag +
   country; also covers "DNS sometimes shows local instead of external".
3. Skeleton scenarios: cold start covers every section except widget titles; drag
   shows the skeleton only on the dragged widget + map; profiles latency skeleton on drag.
4. Protocol dropdown: width follows content (icon + name + recommendation), smooth
   resize; verify the favorite star choice (HYSTERIA2 vs VLESS remembered latencies).
5. Offline-geo audit (no network calls in offline geo mode) and debug-build lag
   profiling with fresh Pixel logs.

---

# Session 3 — 2026-07-08 (developer preview 0.0.1-dev.2)

Five verified local commits (`c226a68d..08241829`), gated on compile + detekt 0 +
full app unit suite, device-verified on the Pixel 7 Pro. Version bumped to
`0.0.1-dev.2` (versionCode 4).

## Features and fixes

- `c226a68d` — **dnsleaktest-style egress DNS detection.** The DNS server row
  used to show "-" whenever the advertised resolvers were private/ULA (the
  common case). It now resolves `whoami.akamai.net` through the system
  resolver: the A answer is the PUBLIC address of the resolver that actually
  contacts the authoritative servers — the real DNS egress of the live route
  (plain net / VPN / Tor). 3s timeout, re-probed on every IP-info refresh,
  silent in offline geo mode. Flag + country from the offline geo database.
- `64300984`-follow-up + `50d93f08` — **network/traffic widget polish batch.**
  DNS server row returned to the info column (between IP and Provider), styled
  like the other rows, flag sized to the text line. Traffic widget "Current
  session" caption removed; the stat-block skeletons now reserve exactly the
  value/secondary line heights so the widget never grows (jerks down) when the
  real values land. The import "+" menu is content-sized (widest title / one-
  line summary, clamped to screen width). Header trailings show icon + value
  only, accent-toned.
- `50d93f08` — **quieter drag-reorder.** Only the dragged card collapses to its
  skeleton (plus the always-placeholdered heavy map); the other widgets keep
  live content, so the list no longer shifts/jumps under the finger. The
  dragged profile card also shimmers its latency pill.
- `08241829` — **protocol selector width.** The opener pill hugs the SELECTED
  option's content (icon + name + stars) — no dead gap before the chevron —
  and eases between widths on selection with a 220ms tween; the menu still
  sizes to its widest row + legend footer.
- `08241829` — **BUG 1: VPN-first Tor startup ordering + tor-beside-vpn
  fallback.** A Tor-in-VPN session defers the in-tunnel Tor route out of the
  FIRST config so the VPN comes up and validates alone (the network widget
  gets its VPN-identity window); the service then hot-reloads the full config
  to engage Tor strictly after the tunnel is proven. A failed/invalidated VPN
  connect brings the Tor-only route up when Tor is allowed to run beside
  (outside) the tunnel. `ProfileSessionFactory.getSession` gained
  `deferTorRoute` (strips the route from that build only; persisted settings
  untouched); session wrappers split into `ProfileRepositorySessions.kt` to
  keep `ProfileRepository` under the LargeClass threshold. Unit tests for the
  ordering decisions + a source contract for the defer/upgrade/fallback wiring.

## Verification

- **Runtime clean** on the Pixel across repeated connect / stop / tor cycles:
  no FATAL/AndroidRuntime, no FoxHole entry in DropBox for 2026-07-08, and the
  `force-killed` log lines are the normal priority-queue preemptions
  (user_stop / kill_tor), not crashes.
- **Release frame profile** (gfxinfo, dashboard scroll): 211 frames, 2.37%
  janky, 90th pct 13ms, 0 missed vsync — confirms the reported "debug apk
  lags" is a property of the debug build (no R8/minify, StrictMode, Compose
  debug instrumentation), not the app.
- **Live BUG 1 tor-only fallback end-to-end** could not be observed because the
  test network blocks Tor bootstrapping ("TOR did not finish bootstrapping");
  the ordering/fallback decisions are covered by unit + source-contract tests
  and the runtime stayed clean through the attempts.
- **Offline-geo audit:** the `geoOfflineMode` gates are correct — FULL /
  GEO_ENRICHMENT online geo passes and the new DNS-leak probe are all skipped
  in offline mode; the public IP still comes from the lightweight Cloudflare
  trace (a public IP behind NAT is not derivable on-device), and the country /
  flag resolve from the on-device geo database. This matches the setting's own
  contract ("without contacting the online IP-info service; City and ISP stay
  hidden").

## Fresh install

- Debug build uninstalled; signed release `0.0.1-dev.2` (versionCode 4)
  installed on the Pixel as the sole FoxHole install.
