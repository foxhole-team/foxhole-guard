# App update connection continuity

Downloading an application update leaves the current VPN running. Android replaces the app process during installation, so the tunnel can briefly disconnect.

Guard uses its existing package-replacement recovery and runtime resume state. A user Stop or permission revocation leaves a persistent Stop intent and revision. Runtime teardown preserves them, and recovery dispatch checks the revision before killing or restoring a runtime.

FoxHole VPN stages a one-shot continuation only immediately before its verified Android installer launch, while it owns a validated connected tunnel. The bounded handoff contains the active profile ID, configuration digest, target version and creation time in app-private, backup-excluded storage. It expires after one hour. Package replacement or the next foreground entry can consume it once through the existing command fence, after checking the same stored profile, configuration, license and VPN permission.

Stop and permission revocation remove the handoff. Service destruction during package replacement preserves it. Idle profile selection creates no continuation. Background-start restrictions or failed recovery leave the manual Connect action available. Boot startup and Android always-on settings retain their existing behavior.

Profiles, pairing, TLS consent and kill-switch settings retain their values. A manual APK installation can require a manual reconnect when the previously installed app did not stage a continuation.
