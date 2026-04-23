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
</p>

<p align="center">
  <strong>public beta 1.0</strong>
</p>

<p align="center">
  iOS, macOS, and Windows versions - maybe.
</p>

Foxhole is a simple Android client for connecting to and managing sing-box profiles. It supports Tunnel and Proxy, as well as Split Tunnel for apps and sites and LAN Proxy. It supports profile import from files, the clipboard, QR codes, and HTTPS subscriptions, including compatible v2raytun-style subscriptions. No ads, analytics, or telemetry. It supports compatible configurations and its own smart config for fast Smart start with automatic VPN protocol selection.


<table>
  <tr>
    <td align="center">
      <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/img_foxhole_0.png" alt="Foxhole dashboard" width="260">
    </td>
    <td align="center">
      <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/img_foxhole_1.png" alt="Foxhole settings" width="260">
    </td>
        <td align="center">
      <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/img_foxhole_2.png" alt="Foxhole settings" width="260">
    </td>
  </tr>
</table>

Key features:

- Modes: Tunnel, Proxy
- Split Tunnel for apps and sites
- LAN Proxy over Wi-Fi for access from other devices
- Proxy authentication
- Site-based routing rules
- Import from files, clipboard, QR codes, and HTTPS subscriptions
- Multi-protocol profiles with protocol selection and Smart start
- Local traffic statistics
- Subscription expiration date display
- Encrypted local profile storage
- No ads
- No analytics
- No telemetry

Supported protocols:

- VLESS
- Trojan
- Shadowsocks
- VMess
- Hysteria2
- WireGuard
- Outline

<table>
  <tr>
    <td>
      <img src="media/have_the_courage_use_your_own_reason-fuck_you-1984.png" alt="1984" width="80" height="80">
    </td>
    <td>
      <h2>Foxhole smart config</h2>
    </td>
  </tr>
</table>

One subscription can contain one or more profiles. Foxhole turns each route group into a separate profile, and all entries inside that group become VPN protocol options for that profile.

Foxhole Smart start:

- Checks supported protocols.
- Remembers successful results by a hash of the current network.
- Analyzes:
  - success/failure history
  - last known good
  - network-scoped memory
  - metered/roaming/private DNS/upstream validation
  - remembered latency
  - connect duration
  - validation/traffic evidence
  - cooldown


An example format lives in [foxhole-smart-config.sample.txt](foxhole-sample-smart-config/foxhole-smart-config.sample.txt).

## Verifying release signatures

- Download the APK and `SHA256SUMS` from the same GitHub Release, then run `sha256sum -c SHA256SUMS`.
- Signing cert SHA-256: `fcbf14862040fbe26726f06f3016ec3b027d8431c76cb4c25c13c5a06837177c`
- Signing cert SHA-1: `ae609bb369398071cc5ac53c9ac10f20f43c4d01`

## Disclaimer

>Android application development is not our primary area of specialization. Our core expertise is focused on backend engineering, security, machine learning, cryptography, and other engineering domains.

## Donate

If you want to support the project, crypto donations are welcome.

- **XMR (Monero):** `48yBVPTdcyJ1WoJtnKmVpEZziEsDy4HvbCW7eQDS9mfdiWPFXwZ8F5h9YZ2UTTBLxPcJgQgvth7iqLZM2yMCaQ432qaouqr`
- **BTC (Bitcoin):** `bc1qatnyy7jcpqrp0d3dk9rta9vqfejgh4mysd6m2f`
