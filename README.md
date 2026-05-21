<p align="center">
  <img src="fastlane/metadata/android/images/icon.png" alt="Foxhole" width="120" height="120">
</p>

<p align="center">
  <strong>English</strong> |
  <a href="README.ru.md">Русский</a>
</p>

<p align="center">
  <a href="https://f-droid.org/">
    <img src="media/fdroid.png" alt="F-Droid" height="68">
  </a>
<br><strong>Pending publication on F-Droid</strong>
</p>

Foxhole is an Android client for connecting to and managing `sing-box` VPN profiles with support for v2raytun-format subscriptions, VPN tunnel mode, local proxy mode, split routing, and optional routing of selected apps' TCP traffic through the Tor network inside the VPN.

The app can run connections in VPN tunnel and local proxy modes, configure routing for apps and domains, use a local DNS rule set, and apply basic traffic restriction rules.

Foxhole contains no ads, analytics, or telemetry.

## Status

- Current version: `1.0.0-beta1`
- Platform: Android
- Other platform versions are not planned for now

## Key features

- VPN tunnel and local proxy mode
- Split Tunnel for apps and domains
- Optional TCP traffic routing through the Tor network
- Local DNS filtering
- DNS filtering exceptions for apps and domains
- LAN Proxy over Wi-Fi
- Proxy authentication
- Domain-based routing rules
- Profile import from files, clipboard, QR codes, and HTTPS subscriptions
- Foxhole Smart Config for grouping connection variants
- Smart start for local selection of a suitable connection variant
- Local traffic statistics
- Subscription expiration display
- Encrypted local VPN profile storage

## Supported protocols

- VLESS
- Trojan
- Shadowsocks
- VMess
- Hysteria2
- WireGuard
- Outline

## Foxhole smart config

Foxhole smart config lets a single subscription contain multiple logical profiles and multiple protocol variants inside each profile.

For example, one profile can contain several functionally equivalent connection variants using different protocols or transports. This keeps connection variants synchronized and lets Foxhole choose a suitable variant without manually switching each individual URI.

Example:

[Foxhole smart config](foxhole-sample-smart-config/foxhole-smart-config.sample.txt)

## Smart start

Smart start automatically selects a suitable connection variant from the available protocol options.

The algorithm runs locally on the device:

- tries available protocol variants;
- ranks them based on past experience and the current network;
- tries the recommended variant first;
- connects through the first variant that passes validation.

The algorithm is sequential and bounded:

- tries up to three candidates during automatic connection;
- moves to the next variant only after a failure;
- can check all variants during manual metrics refresh.

Over time, the system adapts:

- remembers successful connections, including for a specific network;
- avoids recently failing protocols;
- considers the last working variant;
- keeps separate memory for different networks.

Ranking takes into account:

- history of successful and failed connections;
- DNS and network state;
- latency and connection time;
- signs of valid traffic;
- cooldowns and recent failures.

Automatically excluded from selection:

- disabled variants;
- protocols in cooldown.

Smart start does not send connection, configuration, traffic, or app usage data to the developer. Decisions are made on the device.

## DNS filtering

Foxhole can use a local DNS rule set to block ad, tracker, telemetry, and potentially malicious domains.

The app includes a bundled DNS filter based on AdGuard DNS. Filter updates can be enabled from the public Foxhole repository:

[foxhole-dns](https://github.com/foxhole-repo/foxhole-dns)

Official manifest endpoint:

```text
https://foxhole-repo.github.io/foxhole-dns/manifest.json
```

The DNS rule set is built in CI from the open AdGuard DNS filter, published with a manifest file, and verified by the app using signature, size, and SHA-256 before use.

The downloaded file is DNS filtering data for sing-box, not executable code.

If signature, size, or checksum verification fails, Foxhole does not use the downloaded file and continues with the bundled or last verified filter.

The user can disable DNS filtering and add exceptions for individual apps or domains.

## Tor network routing

Foxhole can optionally route TCP traffic through the Tor network. UDP traffic is not sent through the Tor network.

If Tor routing is enabled inside the VPN, selected TCP traffic goes through the Tor network, while UDP stays on the regular VPN route or is blocked by app settings. If the Tor network is kept on the device route while VPN is active, VPN and the Tor network run in parallel. This mode can increase battery usage.

## Permissions and privacy

Foxhole uses only the permissions needed for the app's stated features.

- `INTERNET` - network connectivity, profile downloads, VPN/proxy operation, connection checks, selected DNS server operation, and optional DNS rule set updates.
- `ACCESS_NETWORK_STATE` and `ACCESS_WIFI_STATE` - network and Wi-Fi state detection for VPN/proxy and LAN Proxy.
- `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_SPECIAL_USE` - persistent VPN/proxy operation with a visible notification.
- `QUERY_ALL_PACKAGES` - showing installed apps for Split Tunnel, DNS exceptions, local app overview, and optional per-app traffic statistics.
- `PACKAGE_USAGE_STATS` - requested only when per-app traffic statistics are enabled.
- `CAMERA` - QR code scanning for profile import.
- `POST_NOTIFICATIONS` - VPN/proxy status notifications.
- `RECEIVE_BOOT_COMPLETED` - restoring the user-selected startup mode after reboot, if enabled by the user.
- `WAKE_LOCK` - keeping an active VPN/proxy session alive.

Installed package lists, local risk signals, statistics, diagnostics, Smart start history, user DNS exceptions, and DNS filtering state stay on the device unless the user explicitly exports or sends them.

## Network requests

Foxhole may make network requests for features selected by the user:

- loading HTTPS subscriptions;
- checking connection health;
- obtaining current IP address information through a configurable endpoint;
- operating the selected DNS server;
- optionally downloading an updated DNS rule set from the public Foxhole repository;
- user-initiated export or diagnostic transfer, if such a feature is explicitly selected by the user.

Foxhole does not perform advertising analytics and does not send telemetry to the developer.

When DNS filter updates are enabled, the app downloads a manifest file and DNS rule set. The downloaded rule set is used only after signature, size, and SHA-256 verification. Foxhole does not send the developer installed app lists, custom DNS exceptions, DNS logs, or filtering statistics.

## Release verification

For an APK downloaded from a project release:

## Verifying release signatures

- Download APK and `SHA256SUMS` from the same release
- Run: `sha256sum -c SHA256SUMS`

- SHA-256:
  ```
  fcbf14862040fbe26726f06f3016ec3b027d8431c76cb4c25c13c5a06837177c
  ```

- SHA-1:
  ```
  ae609bb369398071cc5ac53c9ac10f20f43c4d01
  ```

## To Do

> Current implementation uses sing-box as the main runtime backend for protocol handling.
In the long term, Foxhole will move toward a more controlled runtime architecture.

Planned work:

* Rewrite most VPN protocol logic in Rust
* Reduce dependency on sing-box
* Keep sing-box as a fallback backend for unsupported or legacy configurations
* First implement native support for selected protocols:
  * Shadowsocks
  * VLESS
  * Hysteria2
  * selected TCP-based transports
* Improve runtime stability for both TCP and UDP protocols
* Improve protocol validation, fallback logic, and connection state handling

## Licenses

- Foxhole: GPL-3.0-or-later
- sing-box/libbox: GPL-3.0-or-later
- Tor core components: BSD-3-Clause
- AdGuard DNS filter: GPL-3.0

## Donate

- **XMR (Monero):**
```
48yBVPTdcyJ1WoJtnKmVpEZziEsDy4HvbCW7eQDS9mfdiWPFXwZ8F5h9YZ2UTTBLxPcJgQgvth7iqLZM2yMCaQ432qaouqr
```
- **BTC (Bitcoin):**
 ```
bc1qatnyy7jcpqrp0d3dk9rta9vqfejgh4mysd6m2f
  ```

## Disclaimer

> Android application development is not our primary focus.
> Our core focus is backend systems, security, machine learning, and cryptography.

- Tor is a trademark of The Tor Project. Foxhole is not a product of The Tor Project and is not endorsed, sponsored, or affiliated with The Tor Project.

- Foxhole is not a product of AdGuard and is not affiliated with AdGuard Software Ltd.

- Foxhole is not an official `sing-box` or SagerNet client.
