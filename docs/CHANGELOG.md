# Changelog

## 1.0.1 - 2026-10-30

- Restore left-aligned in-app notification text within the space before the close button.

## 1.0.0 - 2026-10-30

- Use Core 1.0.1 DNS/policy fixes and publish ARM64, ARMv7, x86, x86_64 and universal APKs.

- Keep router-origin `appMetadata` in the app layer, separate from the connection URI and Core JSON. Only an authenticated export over the pinned paired-router channel can enroll the matching saved pairing, local profile ID and normalized configuration digest; imported/provider hints never enroll trust. JNI, Rust/native inputs and connection formats remain unchanged.
- Add a separate default-OFF per-pair Home VPN management opt-in with the existing public-IP confirmation and pinned TLS/paired authentication. Automatic recognition requires the selected trusted profile actually connected on its app-owned VPN Network: one bounded read-only state check per matching connection generation, without physical/default-network fallback, timers, automatic retry or provider polling. Manual foreground control remains explicit; retry preserves existing trust and automatic phone home switching/handoff remains disabled.

- Add public-beta OpenWrt management in Settings and a visible Beta badge in router sheets; keep the setup and management flow aligned with FoxHole VPN.
- Require explicit first-setup license consent. Fresh phone setup offers daily automatic router updates enabled with an opt-out; pairing preserves the existing router policy.
- Refresh What's New for supported import formats and the router public beta; keep the current public release's in-app notification presentation.

- Add User/Expert presentation with a compact dashboard while keeping routing and security consent separate.
- Support profile links, v2rayTun subscriptions, SIP008 JSON, compatible Clash/mihomo YAML and dynamic Outline sources, with visible unsupported-entry results.
- Add XHTTP stream-one over HTTP/2 and checked AmneziaWG header ranges, padding and header protection; retain experimental AmneziaWG status.
- Add local Sentinel application scanning in an isolated process, cancellation and explicit background-work controls.
- Deliver signed categorized DNS and existing GeoIP, bridge, TLS-table and threat-indicator feeds with verified cache publication.
- Require the android4 native/license profile for ARM64, ARMv7, x86 and x86_64; retain separate per-artifact packaging and device review.

- Keep complete native commands and source/tool/output evidence reproducible in APK notices; retain build timings and log identities in separate producer records.
- Make Graphics Path source downloads reproducible while preserving all twelve upstream content and Git blob pins.
- Keep GitHub and F-Droid update policies separate, with per-APK update metadata and notices.
