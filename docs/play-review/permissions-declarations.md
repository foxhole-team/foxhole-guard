# Permissions Declaration Notes

Checked against current sources: 2026-06-05

Manifest package: `com.foxhole.beta`

## Declared Permissions

`INTERNET`

- Used for VPN/proxy operation, profile downloads, connection health checks,
  public IP-info checks, selected DNS operation, TOR routing, and optional DNS
  rule-set updates.

`ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`

- Used to detect network and Wi-Fi state for VPN/proxy runtime decisions, LAN
  proxy availability, reconnect behavior, and diagnostics.

`FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`

- Used for long-running user-visible VPN/local proxy/firewall runtimes that must
  keep protecting traffic while FoxHole is not in the foreground.

`BIND_VPN_SERVICE`

- Applied to `FoxholeVpnService` so only the Android system can bind the VPN
  service.

`QUERY_ALL_PACKAGES`

- Used for split routing, DNS exceptions, local installed-app overview, local
  app risk review, and optional per-app traffic statistics. See
  `query-all-packages-declaration.md`.

`PACKAGE_USAGE_STATS`

- Requested through Android special app access only after an in-app prominent
  Usage Access consent. Used for optional local per-app traffic statistics and
  anomalies. Collection can be disabled and deleted from Privacy & local data.

`CAMERA`

- Used only for QR-code profile import. Camera hardware is optional.

`POST_NOTIFICATIONS`

- Used for VPN/proxy runtime status, health, and user-visible foreground
  notifications.

`RECEIVE_BOOT_COMPLETED`

- Used to restore the user-selected startup mode after reboot or app package
  replacement. The app must remain honest if the OS blocks background startup.

`WAKE_LOCK`

- Used to keep an active user-initiated VPN/proxy runtime alive while the
  foreground service is protecting traffic.

`VIBRATE`

- Used for local UI/runtime feedback where supported.

## Permissions Not Declared

FoxHole does not declare SMS, Call Log, Contacts, precise/background location,
microphone, All files access, exact alarm, or full-screen intent permissions in
the current manifest.

## Review Evidence

- `app/src/main/AndroidManifest.xml`
- `app/src/main/res/xml/backup_rules.xml`
- `app/src/main/res/xml/data_extraction_rules.xml`
- `app/build.gradle.kts` public release privacy/preflight tasks
