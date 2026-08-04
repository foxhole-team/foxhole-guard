# Foreground Service Declaration Draft

Checked against current sources: 2026-07-13 (targetSdk 37)

FoxHole declares `specialUse` with an explicit subtype for every runtime service,
including the VpnService. `systemExempted` was dropped deliberately: its runtime
eligibility check rides the VPN consent app-op (`ACTIVATE_VPN`), so a boot-restore
or local-firewall start after the user revoked VPN consent would throw
`SecurityException` at `startForeground` instead of reaching the app's graceful
foreground-start-blocked handling.

## Service: `FoxholeVpnService`

Manifest type: `specialUse`

Manifest subtype:

`Runs the user-initiated VPN/firewall tunnel (VpnService) and keeps it alive
while the user's traffic is routed or filtered through the selected profile.`

Rationale: this service owns the active Android `VpnService` tunnel / local-guard
route. `specialUse` has no consent-dependent runtime eligibility, so blocked
starts always surface through the app's honest error path instead of crashing.

Play Console description:

FoxHole starts this service only for user-initiated VPN or local firewall guard
operation. The service owns the active Android VpnService tunnel, routing rules,
DNS/firewall controls, connection validation, and visible runtime notification.
The task must continue while the app is backgrounded so selected apps and DNS
traffic remain protected.

User impact if deferred:

The VPN/firewall protection requested by the user would not start immediately.
Traffic could continue outside the selected protected route until the service
starts.

User impact if interrupted:

The protected tunnel/firewall route may stop. FoxHole should show an honest
stopped/error state and, when enabled, attempt user-configured recovery.

Video:

Show app open, profile selected, Start tapped, VPN consent, foreground
notification, connected/validated dashboard, and Stop.

## Service: `FoxholeProxyService`

Manifest type: `specialUse`

Manifest subtype:

`Maintains a user-initiated local proxy runtime so the user's explicitly
configured client apps can route traffic through the selected profile while
Foxhole is not in the foreground.`

Play Console description:

FoxHole starts this service only for user-initiated local proxy operation. It
keeps the configured local proxy endpoint available while client apps route
through the selected VPN/proxy profile.

User impact if deferred:

Apps configured to use the local proxy cannot connect through FoxHole until the
proxy service starts.

User impact if interrupted:

Client apps using the proxy lose the selected route. FoxHole should show a
stopped/error state and stop exposing stale success.

Video:

Show app open, proxy mode selected, Start tapped, foreground notification, local
proxy status visible, and Stop.
