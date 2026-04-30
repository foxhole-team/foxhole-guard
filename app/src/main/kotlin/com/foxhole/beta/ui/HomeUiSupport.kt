package com.foxhole.beta.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.text.format.Formatter
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
import androidx.core.content.getSystemService
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
import com.foxhole.beta.core.model.TrafficMode
import com.foxhole.beta.core.model.isUdpTransport
import com.foxhole.beta.core.profile.MultiProtocolProfileSupport
import com.foxhole.beta.core.settings.SMART_START_FULL_REFRESH_STALE_MS
import com.foxhole.beta.core.settings.needsSmartStartColdScan
import com.foxhole.beta.core.settings.smartProfilePreference
import com.foxhole.beta.core.settings.smartStartEnabledProtocolSetHash
import com.foxhole.beta.core.model.Settings as FoxholeSettings

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
    val showSmartStartRefreshReminder: Boolean,
)

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
    appLoaded: Boolean,
): Boolean =
    when {
        visibleIpInfo != null -> false
        explicitLoading -> true
        deviceInternetAvailable == false -> false
        autoConnectRunning || connectionState in setOf(ConnectionState.CONNECTING, ConnectionState.RECONNECTING) -> false
        else -> !appLoaded
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

internal fun shouldShowAutoConnectAction(activeProfile: Profile?): Boolean =
    activeProfile?.let(MultiProtocolProfileSupport::hasMultipleSupportedOptions) == true

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
        dashboardProxySurface = proxySurface ?: lanProxySurface,
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
            pinSelectionToProfile = state.protocolMetricsRefreshing,
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
    dismissedSmartStartReminderProfileId: Long?,
): HomeDashboardProfileModel {
    val activeProfileId = state.activeProfile?.id
    return HomeDashboardProfileModel(
        activeProfileId = activeProfileId,
        isSmartDashboardProfile = state.activeProfile?.let(MultiProtocolProfileSupport::hasMultipleSupportedOptions) == true,
        showSmartStartRefreshReminder =
            activeProfileId != null &&
                dismissedSmartStartReminderProfileId != activeProfileId &&
                shouldShowSmartStartRefreshReminder(
                    activeProfile = state.activeProfile,
                    settings = state.settings,
                ),
    )
}

internal fun resolveHomeDashboardNetworkModel(
    state: HomeRouteUiState,
    visibleIpInfo: IpInfo?,
    deviceInternetAvailable: Boolean?,
): HomeDashboardNetworkModel {
    val showConnectionStatus = state.connection.state == ConnectionState.CONNECTED || state.reconnectInProgress
    return HomeDashboardNetworkModel(
        visibleIpInfo = visibleIpInfo,
        showLoading =
            state.reconnectInProgress ||
                shouldShowDashboardNetworkLoading(
                    visibleIpInfo = visibleIpInfo,
                    explicitLoading = state.ipInfoLoading,
                    connectionState = state.connection.state,
                    autoConnectRunning = state.autoConnect.running,
                    deviceInternetAvailable = deviceInternetAvailable,
                    appLoaded = state.profilesLoaded,
                ) ||
                state.dashboardConnectionMetricsLoading,
        showConnectionStatus = showConnectionStatus,
        titleRes =
            if (showConnectionStatus) {
                R.string.home_network_connection_info_title
            } else {
                R.string.home_network_current_ip_title
            },
    )
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
    if (selectedProfileOptionId != null) {
        return selectedProfileOptionId
    }
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
    if (connectedOptionId != null) {
        return connectedOptionId
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
    return connectedProtocol
        ?.let { protocol ->
            activeProfile
                ?.protocolOptions
                ?.firstOrNull { option -> option.protocolHint == protocol }
                ?.id
        }
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
