package com.foxhole.guard.core.settings

import com.foxhole.core.model.AppLockMode
import com.foxhole.core.model.AppLockSettings
import com.foxhole.core.model.AppTunnelLane
import com.foxhole.core.model.ClashApiSettings
import com.foxhole.core.model.DnsSettings
import com.foxhole.core.model.ExpertSettings
import com.foxhole.core.model.GuardHostingMode
import com.foxhole.core.model.InstalledAppInventoryAudit
import com.foxhole.core.model.InstalledAppInventoryChange
import com.foxhole.core.model.InstalledAppInventoryEntry
import com.foxhole.core.model.KnownApplicationIdentity
import com.foxhole.core.model.LocalAuthSettings
import com.foxhole.core.model.LocalSurfaceSettings
import com.foxhole.core.model.NetworkRulesSettings
import com.foxhole.core.model.PrivacyRouteSettings
import com.foxhole.core.model.ProxyInboundSettings
import com.foxhole.core.model.ProxySurfaceMode
import com.foxhole.core.model.SETTINGS_SCHEMA_VERSION
import com.foxhole.core.model.Settings
import com.foxhole.core.model.StatisticsRefreshInterval
import com.foxhole.core.model.StatisticsRetention
import com.foxhole.core.model.StatisticsSettings
import com.foxhole.core.model.ThemeMode
import com.foxhole.core.model.TrafficMode
import com.foxhole.core.model.V2RayApiSettings
import com.foxhole.core.model.WEB_APPS_POLL_DEFAULT_MINUTES
import com.foxhole.core.model.WEB_APPS_POLL_OPTIONS
import com.foxhole.core.model.WebAppsSettings
import com.foxhole.core.model.packages
import com.foxhole.guard.BuildConfig
import kotlin.math.abs

internal const val SETTINGS_MIN_MTU = 576
internal const val SETTINGS_MAX_MTU = 9_000
internal const val INSTALLED_APP_CHANGE_HISTORY_LIMIT = 60

private const val SETTINGS_RESET_DEFAULTS_SCHEMA_VERSION = 16
private const val TRAFFIC_MAP_DEFAULT_ENABLED_SCHEMA_VERSION = 17
private const val SETTINGS_MIN_PORT = 1
private val QUARANTINE_SIGNING_DIGEST = Regex("^[0-9a-f]{64}$")
private const val SETTINGS_MAX_PORT = 65535

internal fun Settings.normalized(): Settings {
    val resetDefaults = schemaVersion < SETTINGS_RESET_DEFAULTS_SCHEMA_VERSION
    val enableTrafficMapByDefault = schemaVersion < TRAFFIC_MAP_DEFAULT_ENABLED_SCHEMA_VERSION
    return copy(
        schemaVersion = SETTINGS_SCHEMA_VERSION,
        ui =
        ui.copy(
            themeMode = if (resetDefaults) ThemeMode.SYSTEM else ui.themeMode,
            onboardingCompleted = ui.onboardingCompleted,
            betaNoticeAcknowledged = ui.betaNoticeAcknowledged,
            showExpertSettings = ui.showExpertSettings && expert.unlockedAt != null,
            // Schema 19 retires the unsafe proxy-only service. The field remains decodable for a
            // one-shot migration, but the UI must never offer a path that bypasses the VPN TUN.
            showTrafficModeSelector = false,
            supportBotHandleOverride = storedSupportBotHandleOverride(ui.supportBotHandleOverride),
            trafficMapEnabled = if (enableTrafficMapByDefault) true else ui.trafficMapEnabled,
        ),
        connection =
        connection.copy(
            ipInfoEndpoint = normalizeIpInfoEndpoint(connection.ipInfoEndpoint),
        ),
        updateSources = updateSources.normalized(),
        // Safe mode owns exactly ONE traffic field — the mode (updateTrafficMode keeps safe mode
        // only while mode == TUNNEL); stack/MTU/ipv6/strategy are plain tunnel tuning. Replacing
        // the WHOLE block with TrafficSettings() made all four silently unsavable on a fresh
        // install: normalized() reverted the write, update() saw next == current and never hit
        // disk. Explicit reset paths still clear the whole block — that is a user action.
        traffic =
        traffic.copy(
            // FoxCore has one ownership model: VpnService + TUN. A stored PROXY value migrates
            // to TUNNEL; its enabled local listeners live on in expert.localSurfaces.
            mode = TrafficMode.TUNNEL,
            mtu = traffic.mtu.coerceIn(SETTINGS_MIN_MTU, SETTINGS_MAX_MTU),
        ),
        // Legacy migration: the old Security-screen "system DNS protection" flag becomes the DNS
        // screen's "replace system DNS" toggle; the expert flag itself is cleared below.
        dns = dns.normalized().copy(replaceSystemDns = dns.replaceSystemDns || expert.systemDnsProtectionEnabled),
        networkRules = networkRules.normalized(),
        // Safe mode owns exactly the four fields that ENGAGE the Tor lane (mode, permitted,
        // scope, bypass) — the setters carrying the `safeModeEnabled && …` clause. Replacing the
        // WHOLE block with PrivacyRouteSettings() made the bridge/rotation/reconnect toggles
        // silently unsavable on a fresh install — same defect and fix as the traffic block above.
        privacyRoute =
        privacyRoute
            .normalized()
            .disarmedBySafeMode(connection.safeModeEnabled),
        expert =
        expert.normalized(
            safeModeEnabled = connection.safeModeEnabled,
            resetScreenshotBlocking = resetDefaults,
            storedSchemaVersion = schemaVersion,
        ),
        statistics = statistics.normalized(),
        appLock = appLock.normalized(),
        webApps = webApps.normalized(firewallEnabled = expert.firewallEnabled),
        widgets = widgets.copy(alphaPercent = widgets.alphaPercent.coerceIn(0, 100)),
        smartProfilePreferences = normalizeSmartProfilePreferences(smartProfilePreferences),
        profileTrafficTotals =
        profileTrafficTotals
            .groupBy { total -> total.trafficKey() }
            .values
            .mapNotNull { items -> items.maxByOrNull { total -> total.updatedAt } }
            .sortedByDescending { total -> total.updatedAt },
        installedAppInventoryAudit = installedAppInventoryAudit.normalized(),
        // The recording preference and the device-local Usage Access decision are independent.
        // Coupling them with a two-way AND made a transient/indeterminate AppOps read persistently
        // switch recording off. Runtime collection still requires both plus the live AppOp.
        appTrafficStatsEnabled = appTrafficStatsEnabled,
        appTrafficUsageAccessConsent = appTrafficUsageAccessConsent,
        usageTrackingStartedAt = usageTrackingStartedAt.takeIf { it > 0L } ?: System.currentTimeMillis(),
    )
}

private fun ExpertSettings.normalized(
    safeModeEnabled: Boolean,
    resetScreenshotBlocking: Boolean,
    storedSchemaVersion: Int,
): ExpertSettings {
    val normalizedPendingQuarantinePackages = normalizedPendingQuarantinePackages()
    // A pending decision is itself an enforcement fact. Repair the BLOCK lane from it instead of
    // intersecting pending with whatever assignment happened to survive an edit/restore.
    val normalizedAssignments = normalizedAppAssignments(normalizedPendingQuarantinePackages)
    val normalizedBlockedPackages = normalizedAssignments.filterValues { it == AppTunnelLane.BLOCK }.keys
    val normalizedPendingDetails = normalizedPendingDetails(normalizedPendingQuarantinePackages)
    val firewallRequiredByPending = normalizedPendingQuarantinePackages.isNotEmpty()
    val normalizedFirewallEnabled = firewallEnabled || firewallRequiredByPending
    val quarantineArmed = newAppQuarantineEnabled && normalizedFirewallEnabled
    val normalizedQuarantinePolicyRevision =
        quarantinePolicyRevision.normalizedQuarantinePolicyRevision(
            quarantineArmed = quarantineArmed,
            blockedPackagesPresent = normalizedBlockedPackages.isNotEmpty(),
        )
    val normalizedKnownApplications = normalizedKnownApplications(quarantineArmed)
    val normalized =
        copy(
            appAssignments = normalizedAssignments,
            quarantineKnownApplications = normalizedKnownApplications,
            pendingQuarantinePackages = normalizedPendingQuarantinePackages,
            pendingQuarantineAppDetails = normalizedPendingDetails,
            quarantinePolicyRevision = normalizedQuarantinePolicyRevision,
            blockedPackagesEnabled = firewallRequiredByPending ||
                (blockedPackagesEnabled && normalizedBlockedPackages.isNotEmpty()),
            blockAppsAlways = firewallRequiredByPending ||
                (blockAppsAlways && blockedPackagesEnabled && normalizedBlockedPackages.isNotEmpty()),
            siteRoutingAction = siteRoutingAction.coerceSiteRoutingAction(),
            blockScreenshots = if (resetScreenshotBlocking) false else blockScreenshots,
            firewallEnabled = normalizedFirewallEnabled,
            newAppQuarantineEnabled = quarantineArmed,
            // Migrated into DnsSettings.replaceSystemDns (see Settings.normalized); always off here.
            systemDnsProtectionEnabled = false,
            rawLiveDiagnostics = rawLiveDiagnostics && BuildConfig.DEBUG,
            localSurfaces = localSurfaces.normalized().migratedProxySurfaceModesIfNeeded(storedSchemaVersion),
        )
    return if (!safeModeEnabled) normalized else normalized.disarmedBySafeMode()
}

private fun ExpertSettings.normalizedPendingQuarantinePackages(): List<String> =
    pendingQuarantinePackages
        .map(String::trim)
        .filter(String::isNotBlank)
        .filterNot { packageName -> packageName == BuildConfig.APPLICATION_ID }
        .distinct()

private fun ExpertSettings.normalizedAppAssignments(
    pendingPackages: List<String>,
): Map<String, AppTunnelLane> =
    appAssignments.filterKeys { packageName ->
        packageName.isNotBlank() && packageName != BuildConfig.APPLICATION_ID
    } + pendingPackages.associateWith { AppTunnelLane.BLOCK }

private fun ExpertSettings.normalizedPendingDetails(
    pendingPackages: List<String>,
) = pendingQuarantineAppDetails
    .mapNotNull { details ->
        val packageName = details.packageName.trim()
        if (packageName !in pendingPackages) {
            null
        } else {
            details.copy(
                packageName = packageName,
                label = details.label.trim().ifBlank { packageName },
                firstInstallTime = details.firstInstallTime?.takeIf { value -> value > 0L },
                detectedAt = details.detectedAt.takeIf { value -> value > 0L } ?: 0L,
                installerPackageName = details.installerPackageName?.trim()?.takeIf(String::isNotBlank),
                riskSignals = details.riskSignals.distinct(),
            )
        }
    }.distinctBy { details -> details.packageName }

private fun Long.normalizedQuarantinePolicyRevision(
    quarantineArmed: Boolean,
    blockedPackagesPresent: Boolean,
): Long =
    coerceAtLeast(0L).let { revision ->
        if (revision == 0L && (quarantineArmed || blockedPackagesPresent)) 1L else revision
    }

private fun ExpertSettings.normalizedKnownApplications(
    quarantineArmed: Boolean,
): List<KnownApplicationIdentity> =
    if (quarantineArmed) {
        quarantineKnownApplications
            .mapNotNull(KnownApplicationIdentity::normalizedForQuarantineOrNull)
            .distinctBy(KnownApplicationIdentity::packageName)
            .sortedBy(KnownApplicationIdentity::packageName)
    } else {
        emptyList()
    }

private fun KnownApplicationIdentity.normalizedForQuarantineOrNull(): KnownApplicationIdentity? {
    val normalizedPackage = packageName.trim().takeIf(String::isNotBlank) ?: return null
    val normalizedDigest = signingCertificateSha256?.trim()?.lowercase()
    if (normalizedDigest != null && !QUARANTINE_SIGNING_DIGEST.matches(normalizedDigest)) {
        // Never weaken a malformed certificate pin to a package-only allow entry. Dropping the
        // complete identity makes the native policy quarantine the package instead.
        return null
    }
    return copy(
        packageName = normalizedPackage,
        signingCertificateSha256 = normalizedDigest,
        firstSeenAtMs = firstSeenAtMs?.takeIf { value -> value > 0L },
    )
}

/**
 * Safe mode drops every expert routing/exposure lane but keeps the firewall block lane and its
 * quarantine. THE single definition of what safe mode owns — [Settings.normalized],
 * [SettingsRepository.updateSafeModeEnabled] and [Settings.resetExpertSettingsToSafeDefaults]
 * all route through it so they cannot drift apart again.
 * Written as a NEGATIVE `copy()` on purpose: the old positive whitelist silently reset every
 * field added after it was written (diagnosticsRetentionPolicy was a latent victim). With copy()
 * a new field survives by default and SafeModeExpertInvariantTest fails until it is classified.
 */
internal fun ExpertSettings.disarmedBySafeMode(): ExpertSettings {
    val defaults = ExpertSettings()
    return copy(
        // Routing lanes — the whole reason safe mode exists.
        sniff = defaults.sniff,
        strictRoute = defaults.strictRoute,
        bypassLan = defaults.bypassLan,
        allowPrivateOutboundHosts = defaults.allowPrivateOutboundHosts,
        perAppRoutingMode = defaults.perAppRoutingMode,
        siteRoutingAction = defaults.siteRoutingAction,
        // Locally published proxy/DNS surfaces are an attack surface, not tunnel tuning.
        localSurfaces = defaults.localSurfaces,
        // Raw live diagnostics echo destinations into the journal (debug builds only anyway).
        rawLiveDiagnostics = defaults.rawLiveDiagnostics,
        // The firewall lane survives: BLOCK assignments and their pending quarantine are
        // protection, not routing. Everything else keeps its value via copy().
        appAssignments = appAssignments.filterValues { it == AppTunnelLane.BLOCK },
    )
}

private fun WebAppsSettings.normalized(firewallEnabled: Boolean): WebAppsSettings =
    copy(
        // Watchdog polling must follow the guard: turning the firewall off (any path) kills push
        // on the same write instead of leaving a background WebView without a tunnel.
        pushServiceEnabled = pushServiceEnabled && firewallEnabled,
        pollIntervalMinutes =
        WEB_APPS_POLL_OPTIONS.minByOrNull { option -> abs(option - pollIntervalMinutes) }
            ?: WEB_APPS_POLL_DEFAULT_MINUTES,
    )

private fun StatisticsSettings.normalized(): StatisticsSettings =
    copy(
        retention =
        when (retention) {
            StatisticsRetention.WEEK,
            StatisticsRetention.MONTH,
            StatisticsRetention.MONTHS_3,
            StatisticsRetention.FOREVER,
            -> retention
        },
        refreshInterval =
        when (refreshInterval) {
            StatisticsRefreshInterval.SECONDS_1,
            StatisticsRefreshInterval.SECONDS_3,
            StatisticsRefreshInterval.SECONDS_5,
            StatisticsRefreshInterval.SECONDS_10,
            -> refreshInterval
        },
    )

private fun InstalledAppInventoryAudit.normalized(): InstalledAppInventoryAudit =
    copy(
        capturedAt = capturedAt.takeIf { it > 0L } ?: 0L,
        packages =
        packages
            .filter { app -> app.packageName.isNotBlank() }
            .distinctBy(InstalledAppInventoryEntry::packageName)
            .sortedBy(InstalledAppInventoryEntry::packageName),
        recentChanges =
        recentChanges
            .filter { change -> change.packageName.isNotBlank() && change.detectedAt > 0L }
            .distinctBy { change -> "${change.type}:${change.packageName}:${change.detectedAt}" }
            .sortedByDescending(InstalledAppInventoryChange::detectedAt)
            .take(INSTALLED_APP_CHANGE_HISTORY_LIMIT),
    )

private fun PrivacyRouteSettings.normalized(): PrivacyRouteSettings =
    copy(
        // Keep the preference before a Tor lane exists; rule generation handles an empty lane.
        scope = scope,
    )

/**
 * The privacy-route half of safe mode: only the four fields that engage the Tor lane disarm.
 * NEGATIVE `copy()` for the same reason as [disarmedBySafeMode] on [ExpertSettings]: a positive
 * whitelist silently disarms every later-added field — how bridge/rotation became unsavable.
 */
private fun PrivacyRouteSettings.disarmedBySafeMode(safeModeEnabled: Boolean): PrivacyRouteSettings {
    if (!safeModeEnabled) {
        return this
    }
    val disarmed = PrivacyRouteSettings()
    return copy(
        mode = disarmed.mode,
        permitted = disarmed.permitted,
        scope = disarmed.scope,
        bypassVpnTunnel = disarmed.bypassVpnTunnel,
    )
}

private fun DnsSettings.normalized(): DnsSettings =
    copy(
        server = normalizedDnsServer(server),
        appBypassPackages =
        appBypassPackages
            .filterNot { packageName -> packageName == BuildConfig.APPLICATION_ID }
            .distinct(),
        domainBypassRules =
        domainBypassRules
            .flatMap { raw -> raw.split(',', ';', '\n') }
            .map(String::trim)
            .map { value -> value.removePrefix("*.").removePrefix(".").lowercase() }
            .filter { value -> value.isNotBlank() && value.length <= 253 }
            .distinct(),
        dnsFilterUpdateUrl = normalizeDnsFilterUpdateUrl(dnsFilterUpdateUrl),
        filtersUpdatedAt = filtersUpdatedAt?.takeIf { it > 0L },
        filtersCheckedAt = filtersCheckedAt?.takeIf { it > 0L },
    )

internal fun NetworkRulesSettings.normalized(): NetworkRulesSettings {
    val normalizedWifiProfileId = wifiProfileId?.takeIf { it > 0L }
    val normalizedCellularProfileId = cellularProfileId?.takeIf { it > 0L }
    return copy(
        wifiProfileId = normalizedWifiProfileId,
        wifiProtocolOptionId = wifiProtocolOptionId?.trim()?.takeIf(String::isNotBlank),
        useWifiProfile = useWifiProfile && normalizedWifiProfileId != null,
        cellularProfileId = normalizedCellularProfileId,
        cellularProtocolOptionId = cellularProtocolOptionId?.trim()?.takeIf(String::isNotBlank),
        useCellularProfile = useCellularProfile && normalizedCellularProfileId != null,
    )
}

private fun LocalSurfaceSettings.normalized(): LocalSurfaceSettings =
    copy(
        proxyMode = proxyMode,
        lanProxyMode = lanProxyMode,
        socks = socks.normalized(),
        http = http.normalized(),
        mixed = mixed.normalized(),
        allowLanAccess = allowLanAccess,
        clashApi = clashApi.normalized(),
        v2RayApi = v2RayApi.normalized(),
        auth = auth.normalized(),
        // LAN auth is MANDATORY (stage2 §8): the LAN leg is published on the phone's Wi-Fi
        // address, so an unauthenticated inbound is an open relay into the owner's VPN/Tor for
        // the whole network. The flag is forced on here; LocalAuthSettings.normalized() replaces
        // a blank password with a generated one, and the runtime refuses a LAN inbound on a
        // blank password — "no credentials" degrades to "no LAN surface", never "open surface".
        lanAuth = lanAuth.normalized().copy(enabled = true),
    )

internal fun LocalSurfaceSettings.withProxyModeDefaults(enableDefaults: Boolean): LocalSurfaceSettings =
    if (enableDefaults) withEnabledProxyMode(proxyMode) else this

internal fun LocalSurfaceSettings.withEnabledProxyMode(mode: ProxySurfaceMode): LocalSurfaceSettings =
    when (mode) {
        ProxySurfaceMode.SOCKS5 -> copy(
            proxyMode = mode,
            socks = socks.copy(enabled = true),
            http = http.copy(enabled = false),
            mixed = mixed.copy(enabled = false),
        )
        ProxySurfaceMode.HTTP -> copy(
            proxyMode = mode,
            socks = socks.copy(enabled = false),
            http = http.copy(enabled = true),
            mixed = mixed.copy(enabled = false),
        )
        ProxySurfaceMode.ALL -> copy(
            proxyMode = mode,
            socks = socks.copy(enabled = false),
            http = http.copy(enabled = false),
            mixed = mixed.copy(enabled = true),
        )
    }

private fun LocalSurfaceSettings.migratedProxySurfaceModesIfNeeded(schemaVersion: Int): LocalSurfaceSettings {
    if (schemaVersion >= SETTINGS_SCHEMA_VERSION) {
        return this
    }
    val migratedMode =
        when {
            http.enabled -> ProxySurfaceMode.HTTP
            socks.enabled -> ProxySurfaceMode.SOCKS5
            mixed.enabled -> ProxySurfaceMode.ALL
            else -> proxyMode
        }
    return copy(
        proxyMode = migratedMode,
        lanProxyMode = migratedMode,
    ).withEnabledProxyMode(migratedMode)
}

internal fun AppLockSettings.normalized(): AppLockSettings =
    when (mode) {
        AppLockMode.PASSWORD -> this
        // Biometrics only make sense on top of an active gate; OFF drops the stale
        // flag so a later re-enable starts from a clean toggle.
        AppLockMode.SYSTEM -> copy(guardHosting = GuardHostingMode.ECONOMY)
        AppLockMode.OFF -> copy(guardHosting = GuardHostingMode.ECONOMY, biometricEnabled = false)
    }

internal fun LocalAuthSettings.normalized(): LocalAuthSettings =
    SettingsRepository.normalizeLocalProxyAuthForStorage(this)

internal fun ProxyInboundSettings.normalized(): ProxyInboundSettings =
    copy(
        host = host.trim().ifLoopbackOrDefault(),
        port = port.coerceIn(SETTINGS_MIN_PORT, SETTINGS_MAX_PORT),
    )

internal fun ClashApiSettings.normalized(): ClashApiSettings =
    copy(
        host = host.trim().ifLoopbackOrDefault(),
        port = port.coerceIn(SETTINGS_MIN_PORT, SETTINGS_MAX_PORT),
        secret = "",
    )

internal fun V2RayApiSettings.normalized(): V2RayApiSettings =
    copy(
        host = host.trim().ifLoopbackOrDefault(),
        port = port.coerceIn(SETTINGS_MIN_PORT, SETTINGS_MAX_PORT),
    )
