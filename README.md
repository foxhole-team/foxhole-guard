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
  <strong>1.0.0-beta1</strong><br>
  iOS, macOS, and Windows versions — maybe.
</p>

---

Foxhole is a simple Android client for connecting to and managing sing-box profiles. It supports Tunnel and Proxy modes, Split Tunnel for apps and sites, and LAN Proxy.

Supports profile import from files, clipboard, QR codes, and HTTPS subscriptions (including v2raytun-style). TLS verification is strict by default; configs that require insecure TLS need explicit per-profile consent.

>No ads. No analytics. No telemetry.

---

<p align="center">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/img_foxhole_0.png" width="260">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/img_foxhole_1.png" width="260">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/img_foxhole_2.png" width="260">
</p>

---

## Key features

- Modes: Tunnel, Proxy
- Split Tunnel (apps + sites)
- LAN Proxy over Wi-Fi
- Proxy authentication
- Site-based routing rules
- Import: files, clipboard, QR, HTTPS subscriptions
- Smart start with deterministic auto protocol selection
- Local traffic statistics
- Subscription expiration tracking
- Encrypted local storage
- No ads / analytics / telemetry

QUERY_ALL_PACKAGES is used only for the Split Tunnel app picker, so Foxhole can list installed apps for include/exclude routing. Package lists stay local; if Android limits package visibility, saved package ids remain in the routing config and are shown by package name.

---

## Supported protocols

- VLESS
- Trojan
- Shadowsocks
- VMess
- Hysteria2
- WireGuard
- Outline

---

## Foxhole smart config

<p align="center">
  <img src="media/have_the_courage_use_your_own_reason-fuck_you-1984.png" width="84" height="84">
</p>

One subscription can contain one or more profiles. Foxhole converts route groups into profiles, where each entry becomes a protocol option.

### Smart start logic

- Ranks eligible supported protocols
- Tries the recommended protocol first
- Stops on the first validated success
- Falls back only after validation failure
- Caps automatic attempts at 3 candidates
- Excludes disabled, expired, cooldown, and insecure-without-consent options
- Stores success by network hash
- Analyzes:
  - success/failure history
  - last known good
  - network-scoped memory
  - DNS / upstream state
  - latency
  - connect duration
  - validation signals
  - cooldown

##### Example:
[foxhole-smart-config.sample.txt](foxhole-sample-smart-config/foxhole-smart-config.sample.txt)

---

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

---

## Disclaimer

> Android development is not our primary specialization.
> Core expertise: backend, security, ML, cryptography.
