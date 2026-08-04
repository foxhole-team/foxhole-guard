# VpnService Declaration Draft

Checked against current sources: 2026-06-05

## Core Functionality

Yes. FoxHole's core purpose is to provide a user-controlled VPN/proxy/network
protection client. It imports user-provided VPN profiles, starts a device-level
VPN tunnel or local firewall guard through Android VpnService, supports split
routing, validates tunnel health, and shows active runtime status.

## Permitted Use Category

Primary category: VPN app / network-related tool / device security firewall.

FoxHole uses VpnService for:

- Device-level encrypted VPN tunnel operation.
- Local firewall guard mode.
- Split routing for selected apps or excluded apps.
- DNS filtering inside the VPN route when enabled.
- Optional TOR routing for supported TCP traffic.
- Tunnel validation and user-visible connection health.

FoxHole does not use VpnService to manipulate ads, redirect traffic for
monetization, or collect personal/sensitive data without disclosure and consent.

## Data Access Through VpnService

Depending on the user's settings, VpnService operation can process network
traffic metadata needed for routing and diagnostics:

- Destination country/host/port where Android/runtime can determine it.
- Traffic volume totals.
- Per-app traffic only when Usage Access and per-app stats are enabled.
- Runtime diagnostics and error evidence.

This data is used locally for the dashboard, statistics, traffic map, DNS,
firewall, anomaly detection, and troubleshooting. It is not sent to FoxHole
developer servers unless the user explicitly exports or shares diagnostics.

## Encryption

FoxHole uses VPN/proxy protocols from user-imported profiles to carry traffic to
the selected endpoint. Release builds keep insecure TLS defaults disabled.
Profiles that require weaker trust behavior must be explicit user-owned
decisions and are not a global default.

## Prominent Disclosure And Consent

Reviewer should verify these in app:

- Android VPN consent is required before the VPN service starts.
- Usage Access has a separate in-app consent screen before per-app traffic stats
  are used.
- Privacy & local data explains local storage, retention, and deletion.
- Diagnostics export warns when raw private data can be included.

## Required Review Video

Provide a video of 90 seconds or less showing:

- Open FoxHole.
- Import or select a demo VPN profile.
- Tap Start.
- Android VpnService permission screen.
- Accept permission.
- Foreground notification appears.
- Dashboard reaches connected or an honest validation error.
- Tap Stop.

If Usage Access is shown in the same submission, provide a separate disclosure
video showing the consent screen and the behavior when the user declines.
