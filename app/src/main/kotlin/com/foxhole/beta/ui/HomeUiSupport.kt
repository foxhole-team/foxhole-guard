package com.foxhole.beta.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.text.format.Formatter
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
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
import androidx.core.content.getSystemService
import com.foxhole.beta.R
import com.foxhole.beta.core.model.ConnectionSnapshot
import com.foxhole.beta.core.model.ConnectionState
import com.foxhole.beta.core.model.IpInfo
import com.foxhole.beta.core.model.LocalSurfaceSettings
import com.foxhole.beta.core.model.PerAppRoutingMode
import com.foxhole.beta.core.model.Profile
import com.foxhole.beta.core.model.ProfileProtocolOption
import com.foxhole.beta.core.model.ProfileSourceType
import com.foxhole.beta.core.model.ProtocolHint
import com.foxhole.beta.core.model.ProxyInboundSettings
import com.foxhole.beta.core.model.SecureDnsMode
import com.foxhole.beta.core.model.Settings
import com.foxhole.beta.core.model.TrafficMode
import com.foxhole.beta.core.model.isUdpTransport
import com.foxhole.beta.core.profile.MultiProtocolProfileSupport
import com.foxhole.beta.core.settings.needsSmartStartColdScan
import com.foxhole.beta.core.settings.smartProfilePreference
import com.foxhole.beta.core.settings.smartStartEnabledProtocolSetHash
import com.foxhole.beta.vpn.ACTIVE_CONNECTION_STATES
import com.foxhole.beta.vpn.FoxholeVpnService
import com.foxhole.beta.vpn.localGuardModeOrNull
import java.util.Locale

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

internal data class HomeDashboardProtocolModel(
    val presentation: HomeDashboardProtocolPresentation,
    val latencyPresentation: HomeDashboardLatencyPresentation,
    val latenciesByOptionId: Map<String, Long>,
    val downOptionIds: Set<String>,
    val latencyUnavailableOptionIds: Set<String>,
    val showSmartStartLatency: Boolean,
    val selectedServerPingMs: Long?,
    val selectedServerPingUnavailable: Boolean,
    val connectionDetailsReady: Boolean,
    val connectionMetricsLoading: Boolean,
)

internal data class HomeDashboardProfileModel(
    val activeProfileId: Long?,
    val isSmartDashboardProfile: Boolean,
)

internal fun isTrafficMapRuntimeAvailable(
    connection: ConnectionSnapshot,
    settings: Settings,
    activeVpnNetworkAvailable: Boolean,
): Boolean {
    val connected = connection.state == ConnectionState.CONNECTED
    val localGuardConnected = connected && connection.profileId == FoxholeVpnService.LOCAL_GUARD_PROFILE_ID
    val tunnelConnected = connected && connection.profileId != FoxholeVpnService.LOCAL_GUARD_PROFILE_ID
    val localGuardAvailable =
        settings.localGuardModeOrNull() != null &&
            (activeVpnNetworkAvailable || localGuardConnected)
    return settings.ui.trafficMapEnabled && (tunnelConnected || localGuardAvailable)
}

internal data class HomeDashboardNetworkModel(
    val visibleIpInfo: IpInfo?,
    val showLoading: Boolean,
    val showConnectionStatus: Boolean,
    val titleRes: Int,
)

internal data class HomeDashboardProxyModel(
    val modeOption: HomeModeOption,
    val proxySurface: HomeProxySurface?,
    val lanProxySurface: HomeProxySurface?,
    val dashboardProxySurface: HomeProxySurface?,
    val lanProxyActive: Boolean,
)

internal data class HomeDashboardTrafficModel(
    val totalBytes: Long,
    val totalDays: Long,
    val hasIncomingTraffic: Boolean,
    val hasOutgoingTraffic: Boolean,
)

internal enum class HomePrimaryAction {
    START,
    STOP,
    RECONNECT,
}

internal enum class HomeModeOption {
    TUNNEL,
    SPLIT,
    PROXY,
}

internal fun shouldAutoRefreshIpOnForeground(connectionState: ConnectionState): Boolean =
    connectionState != ConnectionState.CONNECTING && connectionState != ConnectionState.RECONNECTING

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
    appLoaded: Boolean,
): Boolean =
    when {
        visibleIpInfo != null -> false
        explicitLoading -> true
        deviceInternetAvailable == false -> false
        autoConnectRunning || connectionState in setOf(ConnectionState.CONNECTING, ConnectionState.RECONNECTING) -> false
        !appLoaded && connectionState == ConnectionState.IDLE -> true
        else -> false
    }

internal fun shouldShowDashboardNetworkLoading(
    visibleIpInfo: IpInfo?,
    explicitLoading: Boolean,
    connectionState: ConnectionState,
    autoConnectRunning: Boolean,
    deviceInternetAvailable: Boolean?,
    appLoaded: Boolean,
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
            appLoaded = appLoaded,
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
    val selectedOptionId = activeProfile.selectedProtocolOptionId?.takeIf(String::isNotBlank)
    val connectedOptionId = connection.protocolOptionId?.takeIf(String::isNotBlank)
    if ((selectedOptionId != null || connectedOptionId != null) && selectedOptionId != connectedOptionId) {
        return true
    }
    val connectedProtocol = connection.protocolHint ?: return false
    return activeProfile.protocolHint != connectedProtocol
}

internal fun shouldAutoRefreshIpAfterConnect(
    previousState: ConnectionState?,
    currentState: ConnectionState,
): Boolean = previousState != ConnectionState.CONNECTED && currentState == ConnectionState.CONNECTED

internal fun shouldAutoRefreshIpAfterDisconnect(
    previousState: ConnectionState?,
    currentState: ConnectionState,
): Boolean =
    previousState in ACTIVE_CONNECTION_STATES &&
        currentState !in ACTIVE_CONNECTION_STATES

internal fun shouldShowAutoConnectAction(activeProfile: Profile?): Boolean =
    activeProfile?.let { profile ->
        MultiProtocolProfileSupport.hasMultipleSupportedOptions(profile) ||
            (
                profile.sourceType == ProfileSourceType.SUBSCRIPTION_URL &&
                    MultiProtocolProfileSupport.smartStartFullScanCandidates(profile).isNotEmpty()
            )
    } == true

internal fun shouldShowSmartStartFirstAnalysisInfo(state: HomeRouteUiState): Boolean {
    val profile = state.activeProfile?.takeIf(MultiProtocolProfileSupport::hasMultipleSupportedOptions)
    return profile?.let { smartProfile ->
        val candidates =
            MultiProtocolProfileSupport.smartStartFullScanCandidates(
                profile = smartProfile,
                allowInsecureTlsGlobally = state.settings.expert.allowInsecureTls,
                excludedOptionIds = state.activeProfileExcludedOptionIds,
                transportPriority = state.settings.connection.smartStartTransportPriority,
            )
        val enabledProtocolSetHash =
            smartStartEnabledProtocolSetHash(candidates.map { candidate -> candidate.optionId })
        candidates.isNotEmpty() &&
            state.settings.smartProfilePreference(smartProfile.id)?.needsSmartStartColdScan(enabledProtocolSetHash) != false
    } == true
}

internal fun shouldAwaitAutoConnectValidationGrace(
    connectionState: ConnectionState,
    vpnNetworkAvailable: Boolean,
): Boolean = connectionState == ConnectionState.CONNECTING && vpnNetworkAvailable

internal fun resolveHomeDashboardProxyModel(
    state: HomeRouteUiState,
    wifiLanAddress: String?,
): HomeDashboardProxyModel {
    val proxySurface = activeProxySurface(state)
    val lanProxySurface = activeLanProxySurface(state)
    return HomeDashboardProxyModel(
        modeOption = currentHomeModeOption(state),
        proxySurface = proxySurface,
        lanProxySurface = lanProxySurface,
        dashboardProxySurface = null,
        lanProxyActive = wifiLanAddress != null && lanProxySurface != null,
    )
}

internal fun resolveHomeDashboardProtocolModel(state: HomeRouteUiState): HomeDashboardProtocolModel {
    val latenciesByOptionId =
        state.smartStartRememberedLatenciesByOptionId +
            state.protocolLatenciesByOptionId +
            state.autoConnect.options
                .mapNotNull { option ->
                    option.latencyMs
                        ?.takeIf {
                            option.status == AutoConnectProbeStatus.SUCCESS ||
                                option.status == AutoConnectProbeStatus.WINNER
                        }?.let { latencyMs -> option.optionId to latencyMs }
                }.toMap()
    val downOptionIds =
        state.protocolDownOptionIds +
            state.autoConnect.options
                .filter { option -> option.status == AutoConnectProbeStatus.FAILED }
                .map(AutoConnectProbeOptionUiState::optionId)
                .toSet()
    val latencyUnavailableOptionIds =
        state.protocolLatencyUnavailableOptionIds +
            state.autoConnect.options
                .filter { option ->
                    option.status in setOf(AutoConnectProbeStatus.SUCCESS, AutoConnectProbeStatus.WINNER) &&
                        option.latencyUnavailable
                }.map(AutoConnectProbeOptionUiState::optionId)
                .toSet()
    val latencyPresentation = resolveDashboardLatencyPresentation(state)
    val protocolPresentation =
        resolveHomeDashboardProtocolPresentation(
            activeProfile = state.activeProfile,
            connection = state.connection,
            autoConnect = state.autoConnect,
            pinSelectionToProfile = state.protocolMetricsRefreshing || state.reconnectRequired,
        )
    val selectedServerPingOptionId = resolveDashboardLatencyOptionId(state.activeProfile, state.connection)
    val selectedServerPingMs = selectedServerPingOptionId?.let(state.protocolServerPingsByOptionId::get)
    val selectedServerPingUnavailable =
        selectedServerPingOptionId != null &&
            selectedServerPingMs == null &&
            selectedServerPingOptionId in state.protocolServerPingUnavailableOptionIds
    val connectionMetricsLoading = state.dashboardConnectionMetricsLoading || state.reconnectInProgress
    return HomeDashboardProtocolModel(
        presentation = protocolPresentation,
        latencyPresentation = latencyPresentation,
        latenciesByOptionId = latenciesByOptionId,
        downOptionIds = downOptionIds,
        latencyUnavailableOptionIds = latencyUnavailableOptionIds,
        showSmartStartLatency =
            latenciesByOptionId.isNotEmpty() ||
                downOptionIds.isNotEmpty() ||
                latencyUnavailableOptionIds.isNotEmpty(),
        selectedServerPingMs = selectedServerPingMs,
        selectedServerPingUnavailable = selectedServerPingUnavailable,
        connectionDetailsReady =
            shouldRenderDashboardConnectionDetails(
                connectionState = state.connection.state,
                activeProfile = state.activeProfile,
                selectedLatencyMs = latencyPresentation.latencyMs,
                selectedLatencyDown = latencyPresentation.isDown,
                selectedLatencyUnavailable = latencyPresentation.isUnavailable,
                selectedServerPingMs = selectedServerPingMs,
                selectedServerPingUnavailable = selectedServerPingUnavailable,
                selectedServerPingUnsupported = protocolPresentation.protocolHint.isUdpTransport(),
            ),
        connectionMetricsLoading = connectionMetricsLoading,
    )
}

internal fun resolveHomeDashboardProfileModel(
    state: HomeRouteUiState,
): HomeDashboardProfileModel {
    val activeProfileId = state.activeProfile?.id
    return HomeDashboardProfileModel(
        activeProfileId = activeProfileId,
        isSmartDashboardProfile = state.activeProfile?.let(MultiProtocolProfileSupport::hasMultipleSupportedOptions) == true,
    )
}

internal fun resolveHomeDashboardNetworkModel(
    state: HomeRouteUiState,
    visibleIpInfo: IpInfo?,
    deviceInternetAvailable: Boolean?,
): HomeDashboardNetworkModel {
    val showConnectionStatus = state.hasRealTunnelConnectionStatus()
    val dashboardIpInfo = state.dashboardVisibleIpInfo(visibleIpInfo)
    return HomeDashboardNetworkModel(
        visibleIpInfo = dashboardIpInfo,
        showLoading =
            state.reconnectInProgress ||
                shouldShowDashboardNetworkLoading(
                    visibleIpInfo = dashboardIpInfo,
                    explicitLoading = state.ipInfoLoading,
                    connectionState = state.connection.state,
                    autoConnectRunning = state.autoConnect.running,
                    deviceInternetAvailable = deviceInternetAvailable,
                    appLoaded = state.profilesLoaded,
                ) ||
                (showConnectionStatus && state.dashboardConnectionMetricsLoading),
        showConnectionStatus = showConnectionStatus,
        titleRes =
            if (showConnectionStatus) {
                R.string.home_network_connection_info_title
            } else {
                R.string.home_network_current_ip_title
            },
    )
}

private fun HomeRouteUiState.hasRealTunnelConnectionStatus(): Boolean =
    reconnectInProgress ||
        (
            connection.state == ConnectionState.CONNECTED &&
                connection.profileId != FoxholeVpnService.LOCAL_GUARD_PROFILE_ID
        )

private fun HomeRouteUiState.dashboardVisibleIpInfo(visibleIpInfo: IpInfo?): IpInfo? {
    if (visibleIpInfo == null) {
        return visibleIpInfo
    }
    val protocolSearchRunning = autoConnect.running || protocolMetricsRefreshing
    val realTunnelActive =
        (reconnectInProgress || connection.state in ACTIVE_CONNECTION_STATES) &&
            connection.profileId != FoxholeVpnService.LOCAL_GUARD_PROFILE_ID
    if (realTunnelActive) {
        return visibleIpInfo.takeIf { info ->
            reconnectInProgress ||
                protocolSearchRunning ||
                info.fetchedAt >= connection.lastChangeAt
        }
    }
    return visibleIpInfo.takeIf { info -> info.fetchedAt >= connection.lastChangeAt }
}

internal fun resolveHomeDashboardTrafficModel(
    state: HomeRouteUiState,
    now: Long,
): HomeDashboardTrafficModel {
    val totals = visibleProfileTrafficTotals(state)
    val totalBytes = totals.sumOf { total -> total.rxTotalBytes + total.txTotalBytes }
    val totalDays = ((now - state.settings.usageTrackingStartedAt).coerceAtLeast(0L) / 86_400_000L) + 1L
    return HomeDashboardTrafficModel(
        totalBytes = totalBytes,
        totalDays = totalDays,
        hasIncomingTraffic = state.traffic.rxBytesPerSec > 0L,
        hasOutgoingTraffic = state.traffic.txBytesPerSec > 0L,
    )
}

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

internal fun resolveDashboardSelectedOptionId(
    activeProfile: Profile?,
    connection: ConnectionSnapshot,
): String? {
    val selectedProfileOptionId =
        activeProfile
            ?.selectedProtocolOptionId
            ?.takeIf { optionId -> activeProfile.protocolOptions.any { option -> option.id == optionId } }
    val connectedOptionId =
        connection.protocolOptionId
            ?.takeIf { connection.profileId == activeProfile?.id }
            ?.takeIf {
                connection.state in setOf(
                    ConnectionState.CONNECTED,
                    ConnectionState.CONNECTING,
                    ConnectionState.RECONNECTING,
                )
            }?.takeIf { optionId ->
                activeProfile?.protocolOptions?.any { option -> option.id == optionId } == true
            }
    val connectedProtocol =
        connection.protocolHint
            ?.takeIf { connection.profileId == activeProfile?.id }
            ?.takeIf {
                connection.state in setOf(
                    ConnectionState.CONNECTED,
                    ConnectionState.CONNECTING,
                    ConnectionState.RECONNECTING,
                )
            }
    val connectedProtocolOptionId =
        connectedProtocol
            ?.let { protocol ->
                activeProfile
                    ?.protocolOptions
                    ?.firstOrNull { option -> option.protocolHint == protocol }
                    ?.id
            }
    return connectedOptionId
        ?: connectedProtocolOptionId
        ?: selectedProfileOptionId
        ?: resolveDashboardSelectedOptionId(activeProfile)
}

internal fun homeTopStatusState(state: HomeRouteUiState): ConnectionState =
    when {
        state.reconnectInProgress -> ConnectionState.RECONNECTING
        state.autoConnect.running -> ConnectionState.CONNECTING
        else -> state.connection.state
    }

internal fun resolveDashboardLatencyOptionId(
    activeProfile: Profile?,
    connection: ConnectionSnapshot? = null,
): String? =
    connection?.let { resolveDashboardSelectedOptionId(activeProfile, it) }
        ?: resolveDashboardSelectedOptionId(activeProfile)
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

internal fun dashboardTransportTypeLabel(protocol: ProtocolHint): String =
    when {
        protocol in setOf(ProtocolHint.UNKNOWN, ProtocolHint.SING_BOX) -> "-"
        protocol.isUdpTransport() -> "UDP"
        else -> "TCP"
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
internal fun homeStatusLabel(
    routeState: HomeRouteUiState,
    state: ConnectionState,
): String =
    if (
        state == ConnectionState.CONNECTED &&
        routeState.connection.profileId == FoxholeVpnService.LOCAL_GUARD_PROFILE_ID &&
        routeState.settings.expert.firewallEnabled
    ) {
        stringResource(R.string.notification_status_firewall)
    } else {
        homeStatusLabel(state)
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
    return state.settings.expert.localSurfaces.proxySurface()
}

internal fun activeLanProxySurface(state: HomeRouteUiState): HomeProxySurface? {
    if (!state.settings.expert.localSurfaces.allowLanAccess) {
        return null
    }
    return state.settings.expert.localSurfaces.lanProxySurface()
}

internal fun resolveHomeDashboardProtocolPresentation(
    activeProfile: Profile?,
    connection: ConnectionSnapshot = ConnectionSnapshot(),
    autoConnect: AutoConnectUiState,
    pinSelectionToProfile: Boolean = false,
): HomeDashboardProtocolPresentation {
    if (activeProfile == null) {
        return HomeDashboardProtocolPresentation(
            protocolHint = ProtocolHint.UNKNOWN,
            protocolOptions = emptyList(),
            selectedProtocolOptionId = null,
        )
    }
    val selectedProtocolOptionId =
        when {
            pinSelectionToProfile -> resolveDashboardSelectedOptionId(activeProfile)
            autoConnect.running && !pinSelectionToProfile ->
                autoConnect.currentOptionId ?: activeProfile.selectedProtocolOptionId
            else -> resolveDashboardSelectedOptionId(activeProfile, connection)
        }
    val protocolOptions =
        if (autoConnect.running && !pinSelectionToProfile && autoConnect.options.isNotEmpty()) {
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

internal fun shouldRenderDashboardConnectionDetails(
    connectionState: ConnectionState,
    activeProfile: Profile?,
    selectedLatencyMs: Long?,
    selectedLatencyDown: Boolean,
    selectedLatencyUnavailable: Boolean,
    selectedServerPingMs: Long?,
    selectedServerPingUnavailable: Boolean,
    selectedServerPingUnsupported: Boolean,
): Boolean {
    if (connectionState != ConnectionState.CONNECTED || activeProfile == null) {
        return true
    }
    val latencyReady = selectedLatencyMs != null || selectedLatencyDown || selectedLatencyUnavailable
    val serverPingReady = selectedServerPingMs != null || selectedServerPingUnavailable || selectedServerPingUnsupported
    return latencyReady && serverPingReady
}

internal fun resolveDashboardLatencyPresentation(state: HomeRouteUiState): HomeDashboardLatencyPresentation {
    if (state.autoConnect.running && !state.protocolMetricsRefreshing) {
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
        return HomeDashboardLatencyPresentation()
    }
    return if (state.connection.state in setOf(ConnectionState.CONNECTED, ConnectionState.CONNECTING, ConnectionState.RECONNECTING)) {
        val selectedOptionId = resolveDashboardLatencyOptionId(state.activeProfile, state.connection)
        when {
            selectedOptionId != null && selectedOptionId in state.protocolDownOptionIds ->
                HomeDashboardLatencyPresentation(isDown = true)
            state.selectedProtocolLatencyUnavailable ->
                HomeDashboardLatencyPresentation(isUnavailable = true)
            state.selectedProtocolLatencyMs != null ->
                HomeDashboardLatencyPresentation(latencyMs = state.selectedProtocolLatencyMs)
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

private fun LocalSurfaceSettings.proxySurface(): HomeProxySurface =
    when (proxyMode) {
        com.foxhole.beta.core.model.ProxySurfaceMode.SOCKS5 -> HomeProxySurface(label = "SOCKS5", settings = socks)
        com.foxhole.beta.core.model.ProxySurfaceMode.HTTP -> HomeProxySurface(label = "HTTP", settings = http)
        com.foxhole.beta.core.model.ProxySurfaceMode.ALL -> HomeProxySurface(label = "ALL", settings = mixed)
    }

private fun LocalSurfaceSettings.lanProxySurface(): HomeProxySurface =
    when (lanProxyMode) {
        com.foxhole.beta.core.model.ProxySurfaceMode.SOCKS5 -> HomeProxySurface(label = "SOCKS5", settings = socks, lanOnly = true)
        com.foxhole.beta.core.model.ProxySurfaceMode.HTTP -> HomeProxySurface(label = "HTTP", settings = http, lanOnly = true)
        com.foxhole.beta.core.model.ProxySurfaceMode.ALL -> HomeProxySurface(label = "ALL", settings = mixed, lanOnly = true)
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
    currentHomeModeOption(state.settings)

internal fun currentHomeModeOption(settings: Settings): HomeModeOption =
    when {
        settings.traffic.mode == TrafficMode.PROXY -> HomeModeOption.PROXY
        settings.expert.perAppRoutingMode != PerAppRoutingMode.FULL_TUNNEL &&
            settings.expert.selectedPackages.isNotEmpty() -> HomeModeOption.SPLIT
        else -> HomeModeOption.TUNNEL
    }

internal fun homeModeOptionIcon(option: HomeModeOption): ImageVector =
    when (option) {
        HomeModeOption.TUNNEL -> Icons.Outlined.Shield
        HomeModeOption.SPLIT -> Icons.Outlined.Apps
        HomeModeOption.PROXY -> Icons.Outlined.SwapVert
    }

internal fun homeModeChipLabel(option: HomeModeOption): String =
    when (option) {
        HomeModeOption.TUNNEL -> "TUN"
        HomeModeOption.SPLIT -> "SPLIT"
        HomeModeOption.PROXY -> "PROXY"
    }

@Composable
internal fun homeModeMenuLabel(option: HomeModeOption): String =
    when (option) {
        HomeModeOption.TUNNEL -> stringResource(R.string.per_app_mode_full_tunnel)
        HomeModeOption.SPLIT -> stringResource(R.string.traffic_mode_split)
        HomeModeOption.PROXY -> stringResource(R.string.traffic_mode_proxy)
    }

internal fun applyHomeModeSelection(
    mode: HomeModeOption,
    onTrafficModeSelected: (TrafficMode) -> Unit,
    onPerAppRoutingModeSelected: (PerAppRoutingMode) -> Unit,
    selectedPackages: List<String>,
    currentPerAppRoutingMode: PerAppRoutingMode,
) {
    when (mode) {
        HomeModeOption.TUNNEL -> {
            onPerAppRoutingModeSelected(PerAppRoutingMode.FULL_TUNNEL)
            onTrafficModeSelected(TrafficMode.TUNNEL)
        }
        HomeModeOption.SPLIT -> {
            onTrafficModeSelected(TrafficMode.TUNNEL)
            if (selectedPackages.isNotEmpty()) {
                onPerAppRoutingModeSelected(
                    currentPerAppRoutingMode.takeIf { it != PerAppRoutingMode.FULL_TUNNEL }
                        ?: PerAppRoutingMode.INCLUDE_SELECTED_APPS,
                )
            }
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

@Composable
internal fun dashboardDnsModeLine(
    ipInfo: IpInfo?,
    secureMode: SecureDnsMode,
): String {
    val mode =
        when (secureMode) {
            SecureDnsMode.DOH -> stringResource(R.string.dns_secure_mode_doh)
            SecureDnsMode.DOT -> stringResource(R.string.dns_secure_mode_dot)
            SecureDnsMode.PLAIN -> stringResource(R.string.home_network_dns_default_mode)
        }
    val countryCode = ipInfo?.countryCode?.trim()?.uppercase(Locale.US)?.takeIf { it.length == 2 }
    return if (countryCode != null) {
        "${countryEmoji(countryCode)} $countryCode · $mode"
    } else {
        mode
    }
}

internal fun secondaryVisibleIp(ipInfo: IpInfo): String? {
    val primary = primaryVisibleIp(ipInfo)
    return ipInfo.ipv6?.takeIf { it != primary }
}

internal fun remoteVisibleDnsServers(ipInfo: IpInfo?): List<String> = visibleDnsServers(ipInfo?.remoteDnsServers.orEmpty())

private fun visibleDnsServers(addresses: List<String>): List<String> =
    addresses
        .filterNot { it.contains(':') }
        .take(2)
