# QUERY_ALL_PACKAGES Declaration Draft

Checked against current sources: 2026-06-05

Permission: `android.permission.QUERY_ALL_PACKAGES`

## Core Functionality

FoxHole's routing, DNS, firewall, local risk review, and optional per-app
traffic statistics require a complete installed-app list. Without broad package
visibility, users cannot reliably choose any installed app for split routing,
DNS exceptions, TOR app scope, local app overview, or per-app statistics.

## User-Facing Uses

- Split routing: include or exclude selected apps from VPN/proxy routing.
- DNS exceptions: choose apps that should bypass or use DNS filtering rules.
- TOR routing scope: choose apps whose supported TCP traffic routes through TOR.
- Local app overview: display installed apps and local risk signals such as VPN
  service, accessibility service, notification listener, device admin, boot
  receiver, unknown installer, or overlay permission.
- Per-app traffic statistics: map Android traffic samples to app names and icons
  after Usage Access consent.

## Why Narrower Visibility Is Not Enough

The user can choose from arbitrary installed apps. Static `<queries>` entries
cannot predict user-installed packages, sideloaded apps, browsers, messengers,
games, banking apps, or local proxy clients. A narrow allow-list would make the
core routing and exception features incomplete and misleading.

## Privacy Constraints

The installed-app inventory is personal and sensitive data. FoxHole keeps it on
device for the features above. It is not sold, used for ads, shared for
analytics, or sent to the developer. User-shared diagnostics sanitize package
names by default.

## Reviewer Flow

- Open Settings > Routing apps.
- Open the app picker.
- Search for an installed app.
- Select and unselect the app.
- Confirm that the selection changes routing UI.
- Open local app overview/risk details if requested.
