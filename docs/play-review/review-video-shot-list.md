# Review Video Shot List

Checked against current sources: 2026-06-05

Google Play declaration videos should be 90 seconds or less each.

## VpnService Video

- Launch FoxHole.
- Show profile import or selected demo profile.
- Tap Start.
- Show Android VPN permission dialog.
- Accept.
- Show foreground notification.
- Show connected/validated dashboard, or honest validation failure if the demo
  endpoint is unavailable.
- Tap Stop.

## VpnService Prominent Disclosure / Usage Access Video

- Launch FoxHole.
- Open Privacy & local data or the per-app statistics entry point.
- Show the Usage Access disclosure text.
- Decline once.
- Trigger the flow again.
- Accept in app and grant Android Usage Access.
- Return to FoxHole and show that the feature is enabled.
- Show clear app traffic stats/deletion control.

## Foreground Service Video

- Launch FoxHole.
- Start VPN/firewall runtime.
- Show foreground notification while app is backgrounded.
- Return to FoxHole.
- Stop runtime.

## QUERY_ALL_PACKAGES Video

- Launch FoxHole.
- Open Routing apps.
- Open app picker.
- Search for an installed app.
- Select/unselect it.
- Show the selected routing scope summary.

## Privacy / Local Data Video

- Launch FoxHole.
- Open Privacy & local data.
- Show Stored locally, Used in FoxHole, and Retention.
- Clear diagnostics or network activity.
- Show factory reset entry without using it on a production device.
