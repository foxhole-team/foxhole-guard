package com.foxhole.guard.ui

import android.content.Context
import android.text.format.Formatter
import com.foxhole.core.model.ACTIVE_CONNECTION_STATES
import com.foxhole.core.model.ConnectionSnapshot
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.DashboardCard
import com.foxhole.core.model.InstalledAppOption
import com.foxhole.core.model.IpInfo
import com.foxhole.core.model.LocalSurfaceSettings
import com.foxhole.core.model.PerAppRoutingMode
import com.foxhole.core.model.Settings
import com.foxhole.core.model.tunnelSelectedPackages
import com.foxhole.guard.runtime.FoxholeVpnService
import java.util.Locale

internal fun LocalSurfaceSettings.proxySurface(): HomeProxySurface = when (proxyMode) {
    com.foxhole.core.model.ProxySurfaceMode.SOCKS5 -> HomeProxySurface("SOCKS5", socks)
    com.foxhole.core.model.ProxySurfaceMode.HTTP -> HomeProxySurface("HTTP", http)
    com.foxhole.core.model.ProxySurfaceMode.ALL -> HomeProxySurface("ALL", mixed)
}

internal fun LocalSurfaceSettings.lanProxySurface(): HomeProxySurface = when (lanProxyMode) {
    com.foxhole.core.model.ProxySurfaceMode.SOCKS5 -> HomeProxySurface("SOCKS5", socks, lanOnly = true)
    com.foxhole.core.model.ProxySurfaceMode.HTTP -> HomeProxySurface("HTTP", http, lanOnly = true)
    com.foxhole.core.model.ProxySurfaceMode.ALL -> HomeProxySurface("ALL", mixed, lanOnly = true)
}

internal fun ConnectionSnapshot.isPrimaryConnectionRuntime(): Boolean =
    state in ACTIVE_CONNECTION_STATES &&
        profileId?.let { it > 0L } == true

internal fun HomeRouteUiState.hasPrimaryConnectionRuntime(): Boolean = connection.isPrimaryConnectionRuntime()

internal fun HomeRouteUiState.hasTorOnlyRuntime(): Boolean =
    connection.state in ACTIVE_CONNECTION_STATES && connection.profileId == FoxholeVpnService.TOR_ONLY_PROFILE_ID

internal fun homePrimaryAction(state: HomeRouteUiState): HomePrimaryAction =
    if (state.hasPrimaryConnectionRuntime()) {
        homePrimaryAction(state.connection.state, state.reconnectRequired)
    } else {
        HomePrimaryAction.START
    }

internal fun homePrimaryAction(state: ConnectionState, reconnectRequired: Boolean): HomePrimaryAction = when {
    state == ConnectionState.CONNECTED && reconnectRequired -> HomePrimaryAction.RECONNECT
    state in ACTIVE_CONNECTION_STATES -> HomePrimaryAction.STOP
    else -> HomePrimaryAction.START
}

internal fun currentHomeModeOption(state: HomeRouteUiState): HomeModeOption = currentHomeModeOption(state.settings)

internal fun currentHomeModeOption(settings: Settings): HomeModeOption = when {
    settings.homeSplitTunnelConfigured() -> HomeModeOption.SPLIT
    else -> HomeModeOption.TUNNEL
}

internal fun Settings.homeSplitTunnelConfigured(): Boolean =
    expert.perAppRoutingMode != PerAppRoutingMode.FULL_TUNNEL && expert.tunnelSelectedPackages().any(String::isNotBlank)

internal fun activeProxySurface(state: HomeRouteUiState): HomeProxySurface? =
    state.settings.expert.localSurfaces.proxySurface().takeIf { surface -> surface.settings.enabled }

internal fun activeLanProxySurface(state: HomeRouteUiState): HomeProxySurface? =
    state.settings.expert.localSurfaces.lanProxySurface()
        .takeIf { state.settings.expert.localSurfaces.allowLanAccess }

internal fun primaryVisibleIp(ipInfo: IpInfo): String = primaryVisibleIpOrNull(ipInfo) ?: "-"

internal fun primaryVisibleIpOrNull(ipInfo: IpInfo): String? =
    ipInfo.visibleIpCandidates()
        .filterNot { candidate -> candidate.substringBefore('%').contains(':') }
        .firstOrNull(String::isPublicInternetAddress)

/**
 * A Tor identity is displayable only when both parts of the promise are present: a public exit
 * address and a real ISO-3166 alpha-2 country. Keeping this gate shared prevents the terminal,
 * status rows and route scheme from independently turning a partial/stale probe into success.
 */
internal fun IpInfo.confirmedTorIdentityOrNull(): IpInfo? {
    if (primaryVisibleIpOrNull(this) == null) return null
    val country = countryCode?.trim()?.uppercase(Locale.US)
    if (country == null || country !in ISO_COUNTRY_CODES) return null
    return copy(countryCode = country)
}

private val ISO_COUNTRY_CODES = Locale.getISOCountries().toSet()

internal fun formatBytes(context: Context, bytes: Long): String = Formatter.formatShortFileSize(context, bytes)

internal fun String.connectionHost(): String {
    val value = trim().removePrefix("/")
    if (value.startsWith("[")) return value.substringAfter("[").substringBefore("]").ifBlank { value }
    val colons = value.count { it == ':' }
    return when {
        colons == 1 -> value.substringBefore(":")
        colons > 1 -> value.substringBefore("%")
        else -> value
    }.ifBlank { value }
}

internal fun privacyRouteTorFirst(
    torEnabled: Boolean,
    i2pEnabled: Boolean,
    torEnabledAtMs: Long,
    i2pEnabledAtMs: Long,
): Boolean = when {
    !i2pEnabled -> true
    !torEnabled -> false
    torEnabledAtMs == 0L -> true
    i2pEnabledAtMs == 0L -> false
    else -> torEnabledAtMs <= i2pEnabledAtMs
}

internal data class InstalledAppSearchIndexRow(
    val app: InstalledAppOption,
    val normalizedLabel: String,
    val normalizedPackageName: String,
)

internal fun buildInstalledAppSearchIndex(apps: List<InstalledAppOption>): List<InstalledAppSearchIndexRow> =
    apps.map { app ->
        InstalledAppSearchIndexRow(
            app = app,
            normalizedLabel = app.label.lowercase(Locale.ROOT),
            normalizedPackageName = app.packageName.lowercase(Locale.ROOT),
        )
    }

internal fun filterIndexedApps(
    apps: List<InstalledAppSearchIndexRow>,
    query: String,
): List<InstalledAppOption> {
    val normalized = query.trim().lowercase(Locale.ROOT)
    if (normalized.isEmpty()) return apps.map(InstalledAppSearchIndexRow::app)
    return apps.filter { row -> normalized in row.normalizedLabel || normalized in row.normalizedPackageName }
        .map(InstalledAppSearchIndexRow::app)
}

internal fun normalizedDashboardCardOrder(order: List<DashboardCard>): List<DashboardCard> {
    val withStatus = if (DashboardCard.STATUS in order) order else listOf(DashboardCard.STATUS) + order
    return (withStatus + DashboardCard.entries).distinct().filter(DashboardCard.entries::contains)
}
