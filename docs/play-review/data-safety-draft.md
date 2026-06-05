# Google Play Data Safety Draft

Checked against current sources: 2026-06-05

This is a Play Console drafting aid, not a substitute for the final form owner
review. Google Play requires complete and accurate declarations for the app and
any SDK behavior.

## High-Level Answers

- Ads: No.
- Advertising analytics: No.
- Developer telemetry backend: No.
- Sale of user data: No.
- User account creation: No.
- User-requested data deletion: Yes, through Settings > Privacy & local data.
- Data encrypted in transit: Yes for app HTTPS requests and encrypted VPN/proxy
  transports. User-imported profiles determine the remote provider.
- Data deletion request mechanism: In-app local deletion controls. There is no
  FoxHole account system.

## Data That Stays Local By Default

Do not mark these as shared with the developer when they remain on the device:

- Installed app inventory and local risk signals.
- Per-app traffic windows and app traffic anomalies after Usage Access consent.
- DNS/network activity records and traffic-map destination countries.
- Profiles, secrets, routing rules, settings, and Smart start history.
- Diagnostics journals and runtime metrics.

These can become user-shared data only if the user explicitly exports/sends the
file through Android's document picker or share sheet.

## Network Data Sent For Selected Features

These transfers are feature traffic, not developer analytics:

- User subscription refresh: the user's selected provider receives the request.
- VPN/proxy/TOR operation: the selected provider/network receives tunnel traffic
  necessary for the user's connection.
- IP-info/health checks: configured public endpoints can receive source IP,
  request time, and normal TLS/request metadata.
- DNS filter update: the public FoxHole DNS repository can receive source IP and
  request time for manifest/rule-set downloads.

Play Console owners should decide whether any of these are declared as collected
or handled ephemerally according to the final endpoint set and release behavior.

## Suggested Data Categories To Review

Network and diagnostics:

- Location: approximate location may be inferred from IP-info country/city data.
- App activity: app interactions and diagnostics can exist in local diagnostics.
- App info and performance: crash-like errors, diagnostics, and runtime status
  can exist locally and in user-shared diagnostics.
- Device or other identifiers: do not declare persistent device identifiers
  unless a future SDK or endpoint adds them. Current app logic does not collect
  IMEI, IMSI, Android Advertising ID, or similar identifiers for FoxHole.
- App info: installed app inventory is local and used for routing/risk review.

Profile data:

- Authentication and secrets in imported profiles are stored locally and used to
  connect to the user-selected provider. They are not sent to FoxHole developer
  servers.

User-shared exports:

- Sanitized diagnostics export removes sensitive endpoint/package/profile data.
- Raw network activity export can contain IPs, hosts, package names, profile
  IDs, session IDs, countries, ports, and traffic volume. It is user initiated.

## Security And Deletion Claims

The app can support these Play Data safety claims if final review agrees:

- Data is encrypted in transit.
- Users can request/delete local data through in-app controls.
- The app does not share user data with third parties for advertising or
  analytics.

Do not claim independent security review unless that review has been completed.
