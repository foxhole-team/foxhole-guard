# FoxHole Privacy Policy

Effective draft date (draft): 2026-06-05

App: FoxHole (`com.foxhole.beta`)

Developer/store listing entity: FoxHole

Privacy contact: TODO: replace with the actual public developer privacy contact
before publication.

This privacy policy explains how FoxHole accesses, stores, uses, and shares data
for its VPN, local proxy, firewall, DNS, TOR routing, traffic statistics, and
diagnostic features.

## Summary

FoxHole is designed as a local-first VPN and network-control app. FoxHole does
not include ads, advertising analytics, or developer telemetry. Installed app
lists, local risk signals, traffic statistics, diagnostics, Smart start history,
DNS exceptions, and DNS filtering state stay on the device unless the user
explicitly exports or sends them.

FoxHole does not sell user data.

## Data Stored On The Device

FoxHole may store these local data types:

- VPN/proxy profiles, subscription metadata, selected active profile, protocol
  choices, and routing rules.
- Profile secrets such as tokens, keys, usernames, passwords, and server
  settings from imported VPN configurations.
- App settings, theme, language, DNS settings, local proxy settings, safe mode
  state, and auto-start preferences.
- Traffic totals, destination countries, per-app traffic windows when Usage
  Access is enabled, app baselines, and anomaly evidence.
- DNS and network activity journal entries when the user enables the relevant
  logging/settings.
- Diagnostics, runtime status, error evidence, and temporary diagnostics export
  files.
- Generated DNS filter cache and verified rule-set metadata when DNS filtering
  is enabled.

Profiles, secrets, settings, statistics, and diagnostics are used only for
FoxHole features shown in the app: connecting the VPN/proxy, validating tunnel
health, showing the dashboard, detecting anomalies, configuring app routing,
running DNS/firewall controls, exporting user-requested diagnostics, and
restoring user-selected runtime state after reboot.

## Sensitive Permissions And Access

FoxHole uses Android VpnService to create a user-initiated VPN tunnel or local
firewall guard. The app shows a foreground notification while the runtime is
active.

FoxHole uses broad package visibility (`QUERY_ALL_PACKAGES`) to show installed
apps for split routing, DNS exceptions, the local installed-app overview, local
risk signals, and optional per-app traffic statistics. The installed-app
inventory is not sold, used for ads, or sent to the developer.

FoxHole requests Usage Access (`PACKAGE_USAGE_STATS`) only when the user enables
per-app traffic statistics. The app displays an in-app consent screen before
using this feature. Per-app traffic data is stored locally and can be turned off
and deleted in Settings.

FoxHole uses Camera permission only to scan QR codes for profile import.

FoxHole uses notification permission to show VPN/proxy status and runtime
health. It uses boot completed only to restore user-selected startup behavior.

## Network Requests

FoxHole may make network requests for user-selected app features:

- Downloading user-provided HTTPS subscription profiles.
- Checking VPN/proxy connectivity and current IP information through configured
  or built-in public endpoints.
- Operating the selected VPN/proxy/TOR/DNS route.
- Downloading the optional public FoxHole DNS rule-set manifest and artifacts.
- User-initiated sharing or export through Android's share sheet or document
  picker.

These requests are required for the selected feature. Remote endpoints can see
ordinary request metadata such as source IP address, request time, and TLS
connection metadata. User-provided VPN/subscription providers process requests
according to the provider chosen by the user.

FoxHole does not operate a developer telemetry backend for ads, analytics, or
profiling.

## Sharing

FoxHole does not automatically share installed app lists, DNS logs, traffic
statistics, diagnostics, profile contents, or routing settings with the
developer.

The user may explicitly export or share:

- VPN profile exports.
- Sanitized diagnostics archives.
- Raw network activity logs only after choosing the raw export path and warning
  flow.

Sanitized diagnostics exports remove IP addresses, hosts, package names,
profile identifiers, session identifiers, and configuration details before the
file is shared. Raw exports can contain sensitive network and package details and
should only be shared when the user intentionally chooses that option.

## Security

FoxHole disables Android backup and device transfer for app data. Profile
secrets are encrypted with Android Keystore-backed file encryption. The local
profile database uses SQLCipher with a Keystore-protected passphrase. App
settings are written through the encrypted settings store.

Public release builds keep diagnostic logcat, strict mode, release probes, and
insecure TLS defaults disabled by release verification tasks.

## Retention And Deletion

Diagnostics and statistics follow retention settings in the app. Profiles,
profile secrets, routing rules, and app settings remain until the user deletes
them or factory resets FoxHole.

The Privacy & local data screen can:

- Clear diagnostics.
- Clear network activity.
- Clear app traffic statistics and turn per-app collection off.
- Clear profiles and secrets.
- Factory reset FoxHole local data.

FoxHole has no in-app account system. If a future release adds accounts, this
policy and the account deletion flow must be updated before release.

## Children

FoxHole is a VPN/network utility for general users and is not directed to
children.

## Changes

This draft should be reviewed before publication and updated whenever FoxHole's
data access, collection, sharing, retention, or permissions change.
