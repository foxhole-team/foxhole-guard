<p align="center">
  <img src="fastlane/metadata/android/images/icon.png" alt="FoxHole Guard" width="120" height="120">
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

# FoxHole Guard

FoxHole Guard is a privacy and device-protection app for Android. It combines an encrypted VPN tunnel, Tor routing, a local firewall, DNS filtering, and on-device security monitoring in one app — with no ads, no analytics, and no telemetry.

It is more than a VPN client: the firewall, DNS protection, and Tor work even without a VPN profile, and every analysis runs locally on the device.

## 🛡️ What it protects and how

- **Traffic privacy** — the VPN tunnel encrypts device traffic and replaces your public IP address with the server address. Powered by the native Rust FoxCore runtime with strict TLS verification by default.
- **Anonymity** — Tor routing sends TCP traffic from selected apps, or the whole device, through the Tor network. Tor can run inside the VPN tunnel (Tor over VPN) or on its own without any VPN profile.
- **Network control** — the local firewall blocks internet access for selected apps, keeps a connection journal, and feeds the live traffic map. It works with or without a VPN.
- **DNS hygiene** — the DNS filter blocks ads, trackers, telemetry, and malicious domains using AdGuard DNS rules. System DNS protection routes all system DNS through AdGuard DNS even without a VPN profile.
- **Device security** — the new app monitor shows where each installed app came from and flags risky capabilities (accessibility, notification access, device admin, overlays, and more). Quarantine blocks new apps from the network until you allow access. Anomaly analysis reports unusual traffic patterns.
- **Data safety** — profiles and secrets are stored in an encrypted local database. Logs are sanitized before sharing; statistics and journals never leave the device.

## ⚙️ How it works

FoxHole Guard runs one native Rust FoxCore runtime behind the Android VPN tunnel. FoxCore owns protocol sessions, routing, DNS interception, traffic accounting, and Tor routing; the Android UI communicates with it through a narrow typed JNI contract. The firewall mode starts a local, filter-only VPN service, so app blocking and the connection journal work without any remote server. The DNS rule set is bundled with the app; optional updates are downloaded from the public repository and verified by signature and SHA-256 before use.

## 🛰️ FoxHole Sentinel

FoxHole Sentinel is an opt-in set of on-device checks. It does not inspect traffic payloads and is not an antivirus:

- **App checks** score installer source and declared high-risk Android capabilities. A risk signal is context for the user, not proof that an app is malicious. On top of those heuristics the beta ships a bundled indicator set built from the public [stalkerware-indicators](https://github.com/AssoEchap/stalkerware-indicators) research data (CC-BY-4.0, Echap): 612 application ids and 471 signing-certificate fingerprints, matched offline against what is installed. Only `type: stalkerware` entries are imported — consensual monitoring apps are not accused. The signed *update* channel for that list is built but not yet hosted, so the bundled set is what a beta build matches on until an app release refreshes it.
- **Traffic anomalies** compare one-minute aggregate windows with the device's own local baseline. Per-app and background signals additionally require statistics and Android Usage Access; when anomaly and relevant statistics lanes are off, FoxHole does not retain these analysis windows.
- **Guard journal** requires a FoxHole PIN and event monitoring. It records app installs, updates and removals, service lifecycle heartbeats, and suspected monitoring gaps in a sealed, hash-chained local journal. Verification after unlock can reveal edits, gaps, or truncation relative to the last anchored checkpoint.

The default **economy** mode does not keep an extra permanent service: Android package broadcasts record changes and WorkManager periodically reconciles anything missed. **Reinforced** mode keeps a visible foreground service while no VPN/firewall runtime is hosting the guard, which improves liveness evidence but can use more battery. Android force-stop pauses receivers and scheduled work until FoxHole is opened again. Sentinel cannot prevent root-level tampering or deletion of the app's data.

## 📱 Scenarios

- **Everyday privacy** — start the VPN and turn on the DNS filter: traffic is encrypted, ads and trackers are blocked.
- **Maximum anonymity** — start the VPN with a TCP protocol and turn on Tor routing for the apps that need it.
- **Tor without VPN** — start Tor with no VPN profile; TCP traffic from the selected scope goes through the Tor network.
- **Protection without VPN** — turn on the firewall and system DNS protection to block apps and filter DNS with no VPN profile at all.

## 📦 Status

- Current public release: `1.1.0-beta5` (versionCode 68)
- Platform: Android
- Other platform versions are not planned for now

## ✨ Key features

- VPN tunnel and local protection modes
- Split tunnel for apps and domains
- Tor routing for selected apps or the entire device (TCP)
- Local firewall with per-app blocking and quarantine for new apps
- Local DNS filtering with per-app and per-domain exceptions
- System DNS protection without a VPN profile
- New app monitor with installer source and risk signals
- Anomaly analysis of local traffic
- Live traffic map with destination countries
- LAN proxy over Wi-Fi with proxy authentication
- Domain-based routing rules
- Profile import from files, clipboard, QR codes, and HTTPS subscriptions
- Smart Config with on-device auto-connect
- Local traffic statistics and subscription expiration display
- Encrypted local profile storage

## 🔌 Supported protocols

- VLESS
- Trojan
- Shadowsocks
- VMess
- Hysteria2
- WireGuard
- Outline
- Naive
- TUIC
- AnyTLS

## 🗂️ Smart Config

Smart Config lets a single subscription hold multiple logical profiles, each with several protocol variants.

One profile can group functionally equivalent connection variants that use different protocols or transports. This keeps the variants synchronized and lets FoxHole Guard pick a working one without switching each URI by hand.

Auto-connect runs entirely on the device. It tries the available variants, ranks them by past results and the current network, connects through the first variant that passes validation, and remembers what worked per network. It never sends connection, configuration, traffic, or app-usage data to the developer.

Example:

[Smart Config sample](foxhole-sample-smart-config/foxhole-smart-config.sample.txt)

## 🌐 DNS filtering

FoxHole Guard can use a local DNS rule set to block ad, tracker, telemetry, and potentially malicious domains.

The beta uses a bundled DNS filter based on AdGuard DNS. The signed remote channel stays off by
default and activates only for a compatible schema-2 publication from the public Foxhole
repository:

[foxhole-dns](https://github.com/foxhole-team/foxhole-dns)

Official manifest endpoint:

```text
https://foxhole-team.github.io/foxhole-dns/manifest.json
```

The DNS rule set is built in CI from the open AdGuard DNS filter. A remote candidate is accepted
only after schema, signature, expiry, monotonic sequence, size and SHA-256 checks in both Android
and FoxCore.

The downloaded file is compiled FoxCore DNS filtering data, not executable code.

If the compatible signed channel has not been published yet, or any verification fails, FoxHole
Guard does not use the downloaded file and continues with the bundled or last verified filter.

The user can disable DNS filtering and add exceptions for individual apps or domains.

## 🧅 Tor network routing

FoxHole Guard can route TCP traffic through the Tor network. UDP traffic is not sent through Tor.

With Tor routing enabled inside the VPN, selected TCP traffic goes through the Tor network while UDP stays on the regular VPN route or is blocked by app settings. Tor can also run on the device route without any VPN profile. If Tor stays on the device route while the VPN is active, both run in parallel; this mode can increase battery usage.

## 🔒 Permissions and privacy

FoxHole Guard uses only the permissions needed for the app's stated features.

- `INTERNET` — network connectivity, profile downloads, VPN/proxy operation, connection checks, selected DNS server operation, and optional DNS rule set updates.
- `ACCESS_NETWORK_STATE` and `ACCESS_WIFI_STATE` — network and Wi-Fi state detection for VPN/proxy and the LAN proxy.
- `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_SPECIAL_USE` — persistent VPN/proxy/firewall operation and the optional reinforced Sentinel host, all with a visible notification.
- `QUERY_ALL_PACKAGES` — showing installed apps for the split tunnel, DNS exceptions, firewall rules, local app overview, and optional per-app traffic statistics.
- `PACKAGE_USAGE_STATS` — requested only when per-app traffic statistics are enabled.
- `CAMERA` — QR code scanning for profile import.
- `POST_NOTIFICATIONS` — VPN/proxy/firewall status and local security notifications.
- `RECEIVE_BOOT_COMPLETED` — restoring the user-selected startup mode after reboot, if enabled by the user.
- `WAKE_LOCK` — keeping an active VPN/proxy session alive.

Installed package lists, local risk signals, statistics, diagnostics, auto-connect history, user DNS exceptions, and DNS filtering state stay on the device unless the user explicitly exports or sends them.

## 📡 Network requests

FoxHole Guard may make network requests for features selected by the user:

- loading HTTPS subscriptions;
- checking connection health;
- obtaining current IP address information through a configurable endpoint;
- operating the selected DNS server;
- optionally downloading an updated DNS rule set from the public Foxhole repository;
- reaching the Tor network, and the bridge list the user selected, when a Tor route is enabled;
- reaching the I2P network through the bundled i2pd router, when the user enables I2P;
- user-initiated export or diagnostic transfer, if such a feature is explicitly selected by the user.

FoxHole Guard does not perform advertising analytics and does not send telemetry to the developer.

When DNS filter updates are enabled, the app downloads a manifest file and DNS rule set. The downloaded rule set is used only after signature, size, and SHA-256 verification. FoxHole Guard does not send the developer installed app lists, custom DNS exceptions, DNS logs, or filtering statistics.

## ✅ Release verification

For an APK downloaded from a project release:

**1. The file is the one that was published.** Download the APK and `SHA256SUMS`
from the same release, then run:

```
sha256sum -c SHA256SUMS
```

**2. It was signed by us.** The file digest above changes with every build, so it
proves nothing about who produced the file. The signing certificate does — it is
the same one for every FoxHole Guard release, and `apksigner` prints it:

```
apksigner verify --print-certs Foxhole-<tag>-<abi>-release.apk
```

The expected **signing certificate** fingerprints (not the APK's own digest) are:

- certificate SHA-256:
  ```
  fcbf14862040fbe26726f06f3016ec3b027d8431c76cb4c25c13c5a06837177c
  ```

- certificate SHA-1:
  ```
  ae609bb369398071cc5ac53c9ac10f20f43c4d01
  ```

The release workflow refuses to publish an APK whose certificate differs, and
every release also carries the `release-certs.txt` that `apksigner` produced on
the build machine.

## 🛠️ To Do

> FoxHole Guard uses FoxCore as its only network runtime and keeps one Android VPN tunnel alive while compatible routing rules change in place.

Planned work:

* Keep rule changes seamless without rebuilding the Android tunnel
* Harden split routing, firewall fail-closed behavior and recovery
* Improve runtime stability for TCP and UDP protocols
* Improve protocol validation, fallback logic, and connection state handling

## 📄 Licenses

FoxHole Guard and FoxCore are **GPL-3.0-or-later**. The full text is in
[LICENSE](LICENSE); a complete component-by-component list with versions is in
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) and in the CycloneDX SBOM
published with every release.

The components that ship inside the APK:

| Component | What it is | License |
|---|---|---|
| FoxCore | the network runtime (Rust), built from source | GPL-3.0-or-later |
| Arti | the Tor client FoxCore links, not the C `tor` daemon | MIT OR Apache-2.0 |
| lyrebird, conjure-client | Tor pluggable transports, prebuilt from the Tor Project's expert bundle | BSD-3-Clause (plus the licenses of their vendored Go modules) |
| i2pd | the I2P router, vendored source under `third_party/i2pd` | BSD-3-Clause, © The PurpleI2P Project |
| AdGuard DNS filter | the bundled DNS rule set (`.fhds`) | GPL-3.0 |
| Silkscreen, Press Start 2P, LanaPixel | the pixel typefaces | SIL Open Font License 1.1 |
| Natural Earth | the vector data behind the connection map | public domain |
| lazysodium-android | crypto bindings | MPL-2.0 |
| JNA | native access used by lazysodium | LGPL-2.1-or-later OR Apache-2.0 |

## ❤️ Donate

- **XMR (Monero):**
```
48yBVPTdcyJ1WoJtnKmVpEZziEsDy4HvbCW7eQDS9mfdiWPFXwZ8F5h9YZ2UTTBLxPcJgQgvth7iqLZM2yMCaQ432qaouqr
```
- **BTC (Bitcoin):**
 ```
bc1qatnyy7jcpqrp0d3dk9rta9vqfejgh4mysd6m2f
  ```

## ⚠️ Disclaimer

> Android application development is not our primary focus.
> Our core focus is backend systems, security, machine learning, and cryptography.

- Tor is a trademark of The Tor Project. FoxHole Guard is not a product of The Tor Project and is not endorsed, sponsored, or affiliated with The Tor Project.

- FoxHole Guard is not a product of AdGuard and is not affiliated with AdGuard Software Ltd.
