# Reviewer Test Steps

Checked against current sources: 2026-06-05

Package: `com.foxhole.guard`

No FoxHole account is required.

## Basic Review Setup

1. Install the public release artifact.
2. Open FoxHole.
3. Import a reviewer-owned VPN profile from clipboard, file, or QR code.
4. Select the imported profile.
5. Tap Start.
6. Accept Android's VPN permission prompt.
7. Confirm that the foreground notification appears.
8. Confirm that the dashboard shows connected only after validation, or shows an
   honest error if the reviewer profile cannot connect.
9. Tap Stop.

## Usage Access Flow

1. Open Settings > Privacy & local data.
2. Start the per-app traffic statistics/Usage Access flow.
3. Confirm that FoxHole explains local per-app traffic use before relying on the
   data.
4. Decline once and verify the app still works without per-app statistics.
5. Grant Usage Access, then confirm that per-app statistics can be disabled and
   cleared from Privacy & local data.

## QUERY_ALL_PACKAGES Flow

1. Open Settings > Routing apps.
2. Open the installed-app picker.
3. Search for an arbitrary installed app.
4. Select and unselect it.
5. Confirm the selection affects routing scope only inside FoxHole.

## Privacy And Deletion Flow

1. Open Settings > Privacy & local data.
2. Review Stored locally, Used in FoxHole, and Retention sections.
3. Clear diagnostics.
4. Clear network activity.
5. Clear app traffic stats.
6. Clear profiles and secrets, or factory reset on a disposable install.

## Diagnostics Export Flow

1. Open diagnostics/logs.
2. Export sanitized diagnostics.
3. Confirm exact IPs, hosts, package names, profile IDs, and session IDs are
   hidden by default.
4. Raw export should only be used when the reviewer intentionally follows the
   raw-data warning path.

## DNS Filter Flow

1. Enable DNS filtering.
2. Confirm the consent/explanation for public DNS rule-set updates.
3. Refresh the rule set.
4. Confirm failure keeps the last verified/bundled rule set instead of silently
   accepting an unverified artifact.
