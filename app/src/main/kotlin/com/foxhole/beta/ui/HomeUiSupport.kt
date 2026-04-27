package com.foxhole.beta.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.text.format.Formatter
import androidx.core.content.getSystemService
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.foxhole.beta.R
import com.foxhole.beta.core.model.ConnectionSnapshot
import com.foxhole.beta.core.model.ConnectionState
import com.foxhole.beta.core.model.IpInfo
import com.foxhole.beta.core.model.LocalSurfaceSettings
import com.foxhole.beta.core.model.PerAppRoutingMode
import com.foxhole.beta.core.model.Profile
import com.foxhole.beta.core.model.ProfileProtocolOption
import com.foxhole.beta.core.model.ProtocolHint
import com.foxhole.beta.core.model.ProxyInboundSettings
import com.foxhole.beta.core.model.Settings as FoxholeSettings
import com.foxhole.beta.core.model.TrafficMode
import com.foxhole.beta.core.profile.MultiProtocolProfileSupport
import com.foxhole.beta.core.settings.SMART_START_FULL_REFRESH_STALE_MS
import com.foxhole.beta.core.settings.smartProfilePreference

internal data class HomeProxySurface(
    val label: String,
    val settings: ProxyInboundSettings,
    val lanOnly: Boolean = false,
)

internal data class HomeDashboardProtocolPresentation(
    val protocolHint: ProtocolHint,
    val protocolOptions: List<ProfileProtocolOption>,
    val selectedProtocolOptionId: String?,
)

internal data class HomeDashboardLatencyPresentation(
    val latencyMs: Long? = null,
    val isDown: Boolean = false,
    val isUnavailable: Boolean = false,
)

internal enum class HomePrimaryAction {
    START,
    STOP,
    RECONNECT,
}

internal enum class HomeModeOption {
    TUNNEL,
    INCLUDE_SELECTED_APPS,
    EXCLUDE_SELECTED_APPS,
    PROXY,
}

internal fun shouldAutoRefreshIpOnForeground(connectionState: ConnectionState): Boolean =
    connectionState == ConnectionState.IDLE || connectionState == ConnectionState.ERROR

internal fun shouldShowIpInfoLoading(
    currentIpInfo: IpInfo?,
    explicitLoading: Boolean,
    connectionState: ConnectionState,
): Boolean = explicitLoading

internal fun shouldShowPendingNetworkLoading(
    visibleIpInfo: IpInfo?,
    explicitLoading: Boolean,
    connectionState: ConnectionState,
    autoConnectRunning: Boolean,
    deviceInternetAvailable: Boolean?,
): Boolean {
    if (visibleIpInfo != null) {
        return false
    }
    if (explicitLoading) {
        return true
    }
    if (deviceInternetAvailable == false) {
        return false
    }
    return false
}

internal fun shouldShowDashboardNetworkLoading(
    visibleIpInfo: IpInfo?,
    explicitLoading: Boolean,
    connectionState: ConnectionState,
    autoConnectRunning: Boolean,
    deviceInternetAvailable: Boolean?,
): Boolean =
    shouldShowIpInfoLoading(
        currentIpInfo = visibleIpInfo,
        explicitLoading = explicitLoading,
        connectionState = connectionState,
    ) ||
        shouldShowPendingNetworkLoading(
            visibleIpInfo = visibleIpInfo,
            explicitLoading = explicitLoading,
            connectionState = connectionState,
            autoConnectRunning = autoConnectRunning,
            deviceInternetAvailable = deviceInternetAvailable,
        )

internal fun isProfileReconnectRequired(
    activeProfile: Profile?,
    connection: ConnectionSnapshot,
): Boolean {
    val profileId = activeProfile?.id ?: return false
    val connectedProfileId = connection.profileId ?: return false
    if (connection.state !in setOf(ConnectionState.CONNECTING, ConnectionState.CONNECTED, ConnectionState.RECONNECTING)) {
        return false
    }
    if (profileId != connectedProfileId) {
        return true
    }
    val connectedProtocol = connection.protocolHint ?: return false
    return activeProfile.protocolHint != connectedProtocol
}

internal fun shouldAutoRefreshIpAfterConnect(
    previousState: ConnectionState?,
    currentState: ConnectionState,
): Boolean = previousState != ConnectionState.CONNECTED && currentState == ConnectionState.CONNECTED

internal fun shouldShowSmartStartRefreshReminder(
    activeProfile: Profile?,
    settings: FoxholeSettings,
    now: Long = System.currentTimeMillis(),
): Boolean {
    val profile = activeProfile?.takeIf(MultiProtocolProfileSupport::hasMultipleSupportedOptions) ?: return false
    val preference = settings.smartProfilePreference(profile.id) ?: return false
    val refreshedAt = preference.lastFullSmartRefreshAt ?: return false
    return now - refreshedAt > SMART_START_FULL_REFRESH_STALE_MS
}

internal fun shouldAwaitAutoConnectValidationGrace(
    connectionState: ConnectionState,
    vpnNetworkAvailable: Boolean,
): Boolean = connectionState == ConnectionState.CONNECTING && vpnNetworkAvailable

@Composable
internal fun rememberDefaultInternetAvailability(): State<Boolean?> {
    val appContext = LocalContext.current.applicationContext
    val connectivityManager = remember(appContext) { appContext.getSystemService<ConnectivityManager>() }
    val defaultInternetAvailable =
        remember(connectivityManager) {
            mutableStateOf(resolveDefaultInternetAvailability(connectivityManager))
        }
    DisposableEffect(connectivityManager) {
        val manager =
            connectivityManager ?: return@DisposableEffect onDispose {
            }
        val callback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    defaultInternetAvailable.value = resolveDefaultInternetAvailability(manager)
                }

                override fun onLost(network: Network) {
                    defaultInternetAvailable.value = resolveDefaultInternetAvailability(manager)
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities,
                ) {
                    defaultInternetAvailable.value = resolveDefaultInternetAvailability(manager)
                }

                override fun onLinkPropertiesChanged(
                    network: Network,
                    linkProperties: LinkProperties,
                ) {
                    defaultInternetAvailable.value = resolveDefaultInternetAvailability(manager)
                }
            }
        defaultInternetAvailable.value = resolveDefaultInternetAvailability(manager)
        manager.registerDefaultNetworkCallback(callback)
        onDispose {
            runCatching { manager.unregisterNetworkCallback(callback) }
        }
    }
    return defaultInternetAvailable
}

private fun resolveDefaultInternetAvailability(
    connectivityManager: ConnectivityManager?,
): Boolean? {
    val manager = connectivityManager ?: return null
    val activeNetwork = manager.activeNetwork ?: return false
    val capabilities = manager.getNetworkCapabilities(activeNetwork) ?: return null
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
        !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
}

internal fun resolveDashboardSelectedOptionId(activeProfile: Profile?): String? =
    activeProfile
        ?.let(MultiProtocolProfileSupport::selectedOption)
        ?.id

internal fun resolveDashboardLatencyOptionId(activeProfile: Profile?): String? =
    resolveDashboardSelectedOptionId(activeProfile)
        ?: activeProfile
            ?.protocolHint
            ?.takeIf { hint -> hint !in setOf(ProtocolHint.UNKNOWN, ProtocolHint.SING_BOX) }
            ?.name
            ?.lowercase()

internal fun protocolHintChipLabel(protocol: ProtocolHint): String =
    when (protocol) {
        ProtocolHint.VLESS -> "VLESS"
        ProtocolHint.TROJAN -> "TROJAN"
        ProtocolHint.SHADOWSOCKS -> "SHADOWSOCKS"
        ProtocolHint.WIREGUARD -> "WIREGUARD"
        ProtocolHint.HYSTERIA2 -> "HYSTERIA2"
        ProtocolHint.VMESS -> "VMESS"
        ProtocolHint.OUTLINE -> "OUTLINE"
        ProtocolHint.SING_BOX -> "SING-BOX"
        ProtocolHint.UNKNOWN -> "UNKNOWN"
    }

@Composable
internal fun homeStatusLabel(state: ConnectionState): String =
    when (state) {
        ConnectionState.CONNECTED -> stringResource(R.string.home_status_connected)
        ConnectionState.CONNECTING -> stringResource(R.string.status_connecting)
        ConnectionState.RECONNECTING -> stringResource(R.string.status_reconnecting)
        ConnectionState.ERROR -> stringResource(R.string.status_error)
        ConnectionState.IDLE -> stringResource(R.string.home_status_not_connected)
    }

@Composable
internal fun homeStatusTone(state: ConnectionState): Color =
    when (state) {
        ConnectionState.CONNECTED -> Color(0xFF7BD69D)
        ConnectionState.ERROR -> Color(0xFFC95353)
        ConnectionState.CONNECTING,
        ConnectionState.RECONNECTING,
        ConnectionState.IDLE,
        -> MaterialTheme.colorScheme.onSurfaceVariant
    }

internal fun activeProxySurface(state: HomeRouteUiState): HomeProxySurface? {
    if (state.settings.traffic.mode != TrafficMode.PROXY) {
        return null
    }
    return preferredDashboardProxySurface(state.settings.expert.localSurfaces)
}

internal fun activeLanProxySurface(state: HomeRouteUiState): HomeProxySurface? {
    if (!state.settings.expert.localSurfaces.allowLanAccess) {
        return null
    }
    return preferredDashboardProxySurface(state.settings.expert.localSurfaces)
}

internal fun resolveHomeDashboardProtocolPresentation(
    activeProfile: Profile?,
    autoConnect: AutoConnectUiState,
): HomeDashboardProtocolPresentation {
    if (activeProfile == null) {
        return HomeDashboardProtocolPresentation(
            protocolHint = ProtocolHint.UNKNOWN,
            protocolOptions = emptyList(),
            selectedProtocolOptionId = null,
        )
    }
    val selectedProtocolOptionId =
        if (autoConnect.running) {
            autoConnect.currentOptionId ?: activeProfile.selectedProtocolOptionId
        } else {
            activeProfile.selectedProtocolOptionId
        }
    val protocolOptions =
        if (autoConnect.running && autoConnect.options.isNotEmpty()) {
            autoConnect.options.map { option ->
                ProfileProtocolOption(
                    id = option.optionId,
                    displayName = option.displayName,
                    protocolHint = option.protocolHint,
                    isSelected = option.optionId == selectedProtocolOptionId,
                )
            }
        } else {
            activeProfile.protocolOptions.map { option ->
                option.copy(isSelected = option.id == selectedProtocolOptionId)
            }
        }
    val protocolHint =
        protocolOptions.firstOrNull { option -> option.id == selectedProtocolOptionId }?.protocolHint
            ?: activeProfile.protocolHint
    return HomeDashboardProtocolPresentation(
        protocolHint = protocolHint,
        protocolOptions = protocolOptions,
        selectedProtocolOptionId = selectedProtocolOptionId,
    )
}

internal fun resolveDashboardLatencyPresentation(state: HomeRouteUiState): HomeDashboardLatencyPresentation {
    if (state.autoConnect.running) {
        val currentOption =
            state.autoConnect.options.firstOrNull { option ->
                option.optionId == state.autoConnect.currentOptionId
            }
        if (currentOption?.status == AutoConnectProbeStatus.FAILED) {
            return HomeDashboardLatencyPresentation(isDown = true)
        }
        if (currentOption?.status in setOf(AutoConnectProbeStatus.SUCCESS, AutoConnectProbeStatus.WINNER)) {
            currentOption?.latencyMs?.let { latencyMs ->
                return HomeDashboardLatencyPresentation(latencyMs = latencyMs)
            }
            if (currentOption?.latencyUnavailable == true) {
                return HomeDashboardLatencyPresentation(isUnavailable = true)
            }
        }
        state.autoConnect.options.firstOrNull { option ->
            option.status in setOf(AutoConnectProbeStatus.SUCCESS, AutoConnectProbeStatus.WINNER) &&
                option.latencyMs != null
        }?.latencyMs?.let { latencyMs ->
            return HomeDashboardLatencyPresentation(latencyMs = latencyMs)
        }
        if (
            state.autoConnect.options.any { option ->
                option.status in setOf(AutoConnectProbeStatus.SUCCESS, AutoConnectProbeStatus.WINNER) &&
                    option.latencyUnavailable
            }
        ) {
            return HomeDashboardLatencyPresentation(isUnavailable = true)
        }
        return HomeDashboardLatencyPresentation()
    }
    return if (state.connection.state in setOf(ConnectionState.CONNECTED, ConnectionState.CONNECTING, ConnectionState.RECONNECTING)) {
        when {
            state.selectedProtocolLatencyMs != null ->
                HomeDashboardLatencyPresentation(latencyMs = state.selectedProtocolLatencyMs)
            state.selectedProtocolLatencyUnavailable ->
                HomeDashboardLatencyPresentation(isUnavailable = true)
            else -> HomeDashboardLatencyPresentation()
        }
    } else {
        HomeDashboardLatencyPresentation()
    }
}

internal fun resolveDashboardSelectedLatencyMs(state: HomeRouteUiState): Long? =
    resolveDashboardLatencyPresentation(state).latencyMs

internal fun resolveDashboardSelectedLatencyDown(state: HomeRouteUiState): Boolean =
    resolveDashboardLatencyPresentation(state).isDown

internal fun resolveDashboardSelectedLatencyUnavailable(state: HomeRouteUiState): Boolean =
    resolveDashboardLatencyPresentation(state).isUnavailable

private fun preferredDashboardProxySurface(
    surfaces: LocalSurfaceSettings,
): HomeProxySurface? {
    return listOf(
        HomeProxySurface(label = "HTTP", settings = surfaces.http),
        HomeProxySurface(label = "SOCKS5", settings = surfaces.socks),
        HomeProxySurface(label = "Mixed", settings = surfaces.mixed),
    ).firstOrNull { it.settings.enabled } ?: surfaces
        .takeIf { it.allowLanAccess }
        ?.let {
            HomeProxySurface(
                label = "HTTP",
                settings = it.http,
                lanOnly = true,
            )
        }
}

internal fun homePrimaryAction(state: HomeRouteUiState): HomePrimaryAction = homePrimaryAction(state.connection.state, state.reconnectRequired)

internal fun homePrimaryAction(
    state: ConnectionState,
    reconnectRequired: Boolean,
): HomePrimaryAction =
    when {
        state in setOf(ConnectionState.CONNECTED, ConnectionState.CONNECTING, ConnectionState.RECONNECTING) && reconnectRequired ->
            HomePrimaryAction.RECONNECT
        state in setOf(ConnectionState.CONNECTED, ConnectionState.CONNECTING, ConnectionState.RECONNECTING) ->
            HomePrimaryAction.STOP
        else -> HomePrimaryAction.START
    }

@Composable
internal fun homeConnectionLabel(
    state: ConnectionState,
    reconnectRequired: Boolean,
): String =
    when (homePrimaryAction(state, reconnectRequired)) {
        HomePrimaryAction.START -> stringResource(R.string.connect)
        HomePrimaryAction.STOP -> stringResource(R.string.disconnect)
        HomePrimaryAction.RECONNECT -> stringResource(R.string.reconnect)
    }

internal fun formatBytes(
    context: Context,
    bytes: Long,
): String = Formatter.formatShortFileSize(context, bytes)

internal fun formatRate(
    context: Context,
    bytesPerSecond: Long,
): String = "${formatBytes(context, bytesPerSecond)}/s"

internal fun countryEmoji(countryCode: String?): String {
    val code = countryCode?.uppercase().orEmpty()
    if (code.length != 2) {
        return "\uD83C\uDF10"
    }
    val first = Character.codePointAt(code, 0) - 0x41 + 0x1F1E6
    val second = Character.codePointAt(code, 1) - 0x41 + 0x1F1E6
    return String(Character.toChars(first)) + String(Character.toChars(second))
}

internal fun currentHomeModeOption(state: HomeRouteUiState): HomeModeOption =
    when {
        state.settings.traffic.mode == TrafficMode.PROXY -> HomeModeOption.PROXY
        state.settings.expert.perAppRoutingMode == PerAppRoutingMode.INCLUDE_SELECTED_APPS -> HomeModeOption.INCLUDE_SELECTED_APPS
        state.settings.expert.perAppRoutingMode == PerAppRoutingMode.EXCLUDE_SELECTED_APPS -> HomeModeOption.EXCLUDE_SELECTED_APPS
        else -> HomeModeOption.TUNNEL
    }

internal fun homeModeOptionIcon(option: HomeModeOption): ImageVector =
    when (option) {
        HomeModeOption.TUNNEL -> Icons.Outlined.Shield
        HomeModeOption.INCLUDE_SELECTED_APPS -> Icons.Outlined.Apps
        HomeModeOption.EXCLUDE_SELECTED_APPS -> Icons.Outlined.ArrowDownward
        HomeModeOption.PROXY -> Icons.Outlined.SwapVert
    }

internal fun homeModeChipLabel(option: HomeModeOption): String =
    when (option) {
        HomeModeOption.TUNNEL -> "TUN"
        HomeModeOption.INCLUDE_SELECTED_APPS -> "SELECT"
        HomeModeOption.EXCLUDE_SELECTED_APPS -> "EXCLUDE"
        HomeModeOption.PROXY -> "PROXY"
    }

@Composable
internal fun homeModeMenuLabel(option: HomeModeOption): String =
    when (option) {
        HomeModeOption.TUNNEL -> stringResource(R.string.per_app_mode_full_tunnel)
        HomeModeOption.INCLUDE_SELECTED_APPS -> stringResource(R.string.per_app_mode_include_selected)
        HomeModeOption.EXCLUDE_SELECTED_APPS -> stringResource(R.string.per_app_mode_exclude_selected)
        HomeModeOption.PROXY -> stringResource(R.string.traffic_mode_proxy)
    }

internal fun applyHomeModeSelection(
    mode: HomeModeOption,
    onTrafficModeSelected: (TrafficMode) -> Unit,
    onPerAppRoutingModeSelected: (PerAppRoutingMode) -> Unit,
) {
    when (mode) {
        HomeModeOption.TUNNEL -> {
            onTrafficModeSelected(TrafficMode.TUNNEL)
            onPerAppRoutingModeSelected(PerAppRoutingMode.FULL_TUNNEL)
        }
        HomeModeOption.INCLUDE_SELECTED_APPS -> {
            onTrafficModeSelected(TrafficMode.TUNNEL)
            onPerAppRoutingModeSelected(PerAppRoutingMode.INCLUDE_SELECTED_APPS)
        }
        HomeModeOption.EXCLUDE_SELECTED_APPS -> {
            onTrafficModeSelected(TrafficMode.TUNNEL)
            onPerAppRoutingModeSelected(PerAppRoutingMode.EXCLUDE_SELECTED_APPS)
        }
        HomeModeOption.PROXY -> onTrafficModeSelected(TrafficMode.PROXY)
    }
}

@Composable
internal fun buildCountryLine(ipInfo: IpInfo): String {
    return formatCountryLine(
        ipInfo = ipInfo,
        unknownCountry = stringResource(R.string.unknown_country),
    )
}

internal fun formatCountryLine(
    ipInfo: IpInfo,
    unknownCountry: String,
): String {
    val country = ipInfo.countryName ?: unknownCountry
    return "${countryEmoji(ipInfo.countryCode)} $country"
}

internal fun buildCityLine(ipInfo: IpInfo): String = ipInfo.city?.takeIf { it.isNotBlank() } ?: "-"

internal fun primaryVisibleIp(ipInfo: IpInfo): String = ipInfo.ipv4 ?: ipInfo.ip

internal fun secondaryVisibleIp(ipInfo: IpInfo): String? {
    val primary = primaryVisibleIp(ipInfo)
    return ipInfo.ipv6?.takeIf { it != primary }
}

internal fun remoteVisibleDnsServers(ipInfo: IpInfo?): List<String> = visibleDnsServers(ipInfo?.remoteDnsServers.orEmpty())

private fun visibleDnsServers(addresses: List<String>): List<String> =
    addresses
        .filterNot { it.contains(':') }
        .take(2)
