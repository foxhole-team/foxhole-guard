# FoxHole Guard public beta readiness

Release target: `1.1.0-beta4` (`versionCode = 67`, `applicationId = com.foxhole.guard`).

## Runtime

- [x] FoxCore is the only VPN/Tor runtime in the Android source and build graph.
- [x] Android config is translated into strict typed FoxCore schema; unknown effects fail closed.
- [x] One runtime owner controls TUN adoption, start, reload, network changes and teardown.
- [x] Tor is provided by the Rust runtime; no standalone Tor daemon is packaged.
- [x] Runtime DNS counters and typed block events feed the Android statistics UI.
- [x] Proxy-only app mode is retired; persisted values normalize to the VPN tunnel.
- [x] Live VPN-bound acceptance is green on the final Pixel build (a VPN-bound exit address was
  obtained on the owner's live subscription, 2026-08-04).

## Protocol contract

- [x] VLESS, VMess, Trojan, Shadowsocks, Outline, Hysteria2, WireGuard, Naive, TUIC and AnyTLS
  translate into matching typed FoxCore outbounds.
- [x] Unsupported transports/security fields are rejected instead of downgraded.
- [x] TLS verification is strict unless the profile's stored consent explicitly allows otherwise.
- [x] Every protocol for which a live endpoint is available has device evidence. VLESS, VMess,
  Trojan, Shadowsocks and Hysteria2 additionally carry an interoperability matrix against a
  reference sing-box 1.13.16 server on the device: 5/5.

## Build and supply chain

- [x] Rust format, workspace tests and clippy with warnings denied.
- [x] NDK r29 build and ABI/ELF gates for every shipped ABI.
- [x] Android unit tests, detekt, lint and full `check`.
- [x] Debug APK, public release APK and public release AAB.
- [x] APK/AAB inventory contains FoxCore and no retired runtime payload or class.
- [x] Dependency verification, SBOM, mapping and native-symbol artifacts.

## Device

- [x] Remove old debug/release packages and install a fresh debug build on Pixel 7 Pro.
- [x] Connected instrumentation and runtime smoke.
- [x] VPN-bound public validation, DNS interception and clean stop.
- [x] Tor bootstrap/routing/stop smoke — **Tor over VPN** is green: the Tor exit address differs
  from the VPN exit address on the same session. Direct Tor is filtered by the bench network even with the bundled
  bridges: `bridge_count=9`, the `liblyrebird.so` transport process starts, and Arti still
  reports `timed out at 8% (filtered)`. That is the network, not the build.
- [ ] Onion publication (file share) — **not provable on this stand**: it requires a Tor-only
  route, which is the shape the bench network filters. The test should gain a Tor-over-VPN
  shape so the feature becomes verifiable on filtered networks.
- [x] Crash, ANR, StrictMode and native tombstone buffers clean after the 2026-08-04 device
  pass: no FoxHole entry in the crash buffer or in StrictMode, and the only tombstones and ANR
  on the device belong to unrelated apps and predate this pass.
- [x] CPU, frame-time and memory/leak profiling evidence captured.
- [x] Reinstall debug after instrumentation so the launcher app remains available.

## Publishing

- [x] Both repositories carry the GPL-3 text and a third-party notice covering every shipped
  component (`LICENSE`, `THIRD_PARTY_NOTICES.md`).
- [ ] The pixel-flag set has a written license grant (owner's decision: keep the flags, attribute
  in `THIRD_PARTY_NOTICES.md` and on the about screen, letter to the author outstanding).
- [x] No live endpoint, credential, account UUID or bench-device serial in the working tree of
  either repository; the secret scan reads the history, not only the working directory.
- [x] The history of both repositories is rewritten before the first push (`git filter-repo`,
  2026-08-03; remotes removed).
- [x] Store copy describes FoxCore and the current protocol set.
- [x] Store screenshots remain untouched.
- [x] Changelog 66 exists in English and Russian.
- [ ] Compatible signed schema-2 DNS publication is live at the configured endpoint. Artifacts,
  manifests and `key_sha256` all agree and the repository public key matches the one pinned in
  `DnsFilterUpdateClient`; what is missing is the owner's signature — `manifest.json.sig` is a
  documented placeholder and `threat-intel-manifest.json.sig` does not exist. Runbook and a
  verified command sequence: `foxhole-dns/PUBLISHING.md`.
- [x] Release signing and monotonically increasing version preflight.
- [ ] Rust source revision is published where CI/F-Droid can fetch the pinned commit (needs the
  core push; `config/foxcore-revision.txt` is re-pinned to the tip of this pass).
