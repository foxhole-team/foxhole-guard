package com.foxhole.beta.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.foxhole.beta.R
import com.foxhole.beta.core.data.ProfileRepository
import com.foxhole.beta.core.data.RoutingRepository
import com.foxhole.beta.core.diagnostics.DiagnosticsLogger
import com.foxhole.beta.core.model.ConnectionSnapshot
import com.foxhole.beta.core.model.ConnectionState
import com.foxhole.beta.core.model.ConnectivityHealthState
import com.foxhole.beta.core.model.IpInfo
import com.foxhole.beta.core.model.Profile
import com.foxhole.beta.core.model.TrafficMode
import com.foxhole.beta.core.model.TrafficSnapshot
import com.foxhole.beta.core.network.IpInfoFetchMode
import com.foxhole.beta.core.network.IpInfoRepository
import com.foxhole.beta.core.settings.SettingsRepository
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import java.net.Socket

class FoxholeConnectionController(
    private val context: Context,
    private val profileRepository: ProfileRepository,
    private val settingsRepository: SettingsRepository,
    private val routingRepository: RoutingRepository,
    private val ipInfoRepository: IpInfoRepository,
    private val diagnosticsLogger: DiagnosticsLogger,
    private val runtimeConfigAssembler: RuntimeConfigAssembler,
) {
    private val connectivityManager by lazy {
        context.requireSystemServiceSafe<ConnectivityManager>("connectivity")
    }
    val snapshot: StateFlow<ConnectionSnapshot> = FoxholeVpnRuntimeBridge.snapshot
    val ipInfo: StateFlow<IpInfo?> = FoxholeVpnRuntimeBridge.ipInfo
    val deviceIpInfo: StateFlow<IpInfo?> = FoxholeVpnRuntimeBridge.deviceIpInfo
    val traffic: StateFlow<TrafficSnapshot> = FoxholeVpnRuntimeBridge.traffic
    internal val runtimeUiState: StateFlow<RuntimeUiState> = FoxholeVpnRuntimeBridge.runtimeUiState
    private val appliedRuntimeSignatureMutable = MutableStateFlow<Int?>(null)
    val appliedRuntimeSignature: StateFlow<Int?> = appliedRuntimeSignatureMutable
    private val lifecycle =
        FoxholeConnectionLifecycle(
            context = context,
            profileRepository = profileRepository,
            settingsRepository = settingsRepository,
            routingRepository = routingRepository,
            diagnosticsLogger = diagnosticsLogger,
            runtimeConfigAssembler = runtimeConfigAssembler,
            snapshot = snapshot,
            appliedRuntimeSignature = appliedRuntimeSignatureMutable,
            hasActiveVpnNetwork = { hasActiveVpnNetwork() },
        )
    private val validationGateway =
        TunnelValidationGateway(
            context = context,
            profileRepository = profileRepository,
            settingsRepository = settingsRepository,
            ipInfoRepository = ipInfoRepository,
            diagnosticsLogger = diagnosticsLogger,
            snapshot = snapshot,
            currentVpnNetwork = { currentVpnNetwork() },
            currentUpstreamNetwork = { currentUpstreamNetwork() },
        )
    private val telemetryProbe =
        ConnectionTelemetryProbe(
            settingsRepository = settingsRepository,
            ipInfoRepository = ipInfoRepository,
            snapshot = snapshot,
            currentVpnNetwork = { currentVpnNetwork() },
            currentUpstreamNetwork = { currentUpstreamNetwork() },
            currentVpnInterfaceName = { network -> connectivityManager.getLinkProperties(network)?.interfaceName },
            isVpnNetworkValidated = { network ->
                connectivityManager
                    .getNetworkCapabilities(network)
                    ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
            },
            activeServerPingTarget = { FoxholeVpnRuntimeBridge.activeServerPingTarget.value },
            protectDirectSocket = { socket -> FoxholeVpnRuntimeBridge.protectDirectSocket(socket) },
        )

    suspend fun connect(
        profileId: Long,
        protocolOptionId: String? = null,
        statusMessage: String? = null,
        isSmartStartConnection: Boolean = false,
        previousVpnNetworkHandle: Long? = null,
    ) = lifecycle.connect(
        profileId = profileId,
        protocolOptionId = protocolOptionId,
        statusMessage = statusMessage,
        isSmartStartConnection = isSmartStartConnection,
        previousVpnNetworkHandle = previousVpnNetworkHandle,
    )

    suspend fun connectTorOnly(statusMessage: String? = null) = lifecycle.connectTorOnly(statusMessage)

    fun disconnect(
        suppressLocalGuard: Boolean = false,
        preserveSmartStartAnalysis: Boolean = false,
    ) {
        lifecycle.disconnect(
            suppressLocalGuard = suppressLocalGuard,
            preserveSmartStartAnalysis = preserveSmartStartAnalysis,
        )
    }

    suspend fun currentRuntimeFingerprint(): Int = lifecycle.currentRuntimeFingerprint()

    suspend fun markCurrentRuntimeApplied() {
        lifecycle.markCurrentRuntimeApplied()
    }

    fun clearAppliedRuntime() {
        lifecycle.clearAppliedRuntime()
    }

    fun clearSmartStartAnalysisStatus() {
        val currentSnapshot = snapshot.value
        val analysisStatus = context.getString(R.string.notification_status_analysis)
        if (!currentSnapshot.isSmartStartConnection && currentSnapshot.message != analysisStatus) {
            return
        }
        FoxholeVpnRuntimeBridge.update(
            currentSnapshot.copy(
                message = null,
                isSmartStartConnection = false,
            ),
            refreshLastChangeAt = false,
        )
    }

    fun reload(profileId: Long? = snapshot.value.profileId): Boolean = lifecycle.reload(profileId)

    suspend fun syncLocalGuard() {
        val currentSnapshot = snapshot.value
        val localGuardSnapshot = currentSnapshot.profileId == FoxholeVpnService.LOCAL_GUARD_PROFILE_ID
        if (shouldDeferLocalGuardStartForActiveProfileRuntime(currentSnapshot, activeProfileSessionPresent = false)) {
            diagnosticsLogger.record("connection", "local guard sync deferred: active profile runtime")
            return
        }
        val mode = settingsRepository.current().localGuardModeOrNull()
        if (mode != null) {
            val localGuardRuntimeCurrent =
                appliedRuntimeSignature.value == currentRuntimeFingerprint()
            if (
                localGuardSnapshot &&
                hasActiveVpnNetwork() &&
                currentSnapshot.profileName == mode.runtimeProfileName() &&
                localGuardRuntimeCurrent
            ) {
                return
            }
            FoxholeConnectionServiceContract.startForegroundService(
                context = context,
                mode = TrafficMode.TUNNEL,
                action = FoxholeConnectionServiceContract.ACTION_START_LOCAL_GUARD,
                localGuardMode = mode,
            )
        } else if (localGuardSnapshot || hasActiveVpnNetwork()) {
            FoxholeConnectionServiceContract.startForegroundService(
                context = context,
                mode = TrafficMode.TUNNEL,
                action = FoxholeConnectionServiceContract.ACTION_DISCONNECT,
                suppressLocalGuard = true,
            )
        }
    }

    suspend fun refreshProfile(
        profileId: Long,
        excludeInsecureTlsOptions: Boolean = false,
        allowInsecureTlsForProfile: Boolean = false,
    ): Profile =
        profileRepository.refreshProfile(
            profileId = profileId,
            excludeInsecureTlsOptions = excludeInsecureTlsOptions,
            allowInsecureTlsForProfile = allowInsecureTlsForProfile,
        )

    suspend fun setActiveProfile(profileId: Long) {
        profileRepository.setActiveProfile(profileId)
    }

    suspend fun refreshIpInfo(fetchMode: IpInfoFetchMode = IpInfoFetchMode.FULL): IpInfo =
        validationGateway.refreshIpInfo(fetchMode)

    suspend fun measureCurrentConnectionLatency(timeoutMs: Long = LATENCY_PROBE_TIMEOUT_MS): Long =
        telemetryProbe.measureCurrentConnectionLatency(timeoutMs)

    suspend fun measureCurrentVpnServerPing(
        profileId: Long,
        protocolOptionId: String? = null,
        timeoutMs: Long = SERVER_PING_TIMEOUT_MS,
    ): Long =
        telemetryProbe.measureCurrentVpnServerPing(
            profileId = profileId,
            protocolOptionId = protocolOptionId,
            timeoutMs = timeoutMs,
        )

    fun hasActiveVpnNetwork(): Boolean = currentVpnNetwork() != null

    fun currentVpnNetworkHandle(): Long? = currentVpnNetwork()?.networkHandle

    suspend fun reconcileActiveVpnNetworkIfNeeded(): Boolean {
        val currentSnapshot = snapshot.value
        val vpnNetwork = currentVpnNetwork()
        return when {
            currentSnapshot.isStaleTunnelSnapshotWithoutVpn(vpnNetwork) -> {
                val restoredVpnNetwork = awaitVpnNetworkForActiveSnapshot()
                if (restoredVpnNetwork != null) {
                    diagnosticsLogger.record(
                        "connection",
                        "active tunnel snapshot kept after vpn network appeared during foreground grace",
                    )
                    false
                } else {
                    diagnosticsLogger.record(
                        "connection",
                        "active tunnel snapshot failed closed after foreground grace; vpn network missing",
                    )
                    failClosedMissingActiveVpnNetwork()
                    false
                }
            }

            currentSnapshot.state in ACTIVE_CONNECTION_STATES || vpnNetwork == null -> false

            settingsRepository.current().localGuardModeOrNull() != null -> {
                diagnosticsLogger.record(
                    "connection",
                    "active local guard vpn found with idle snapshot",
                )
                false
            }

            else -> restoreActiveVpnNetwork(vpnNetwork)
        }
    }

    private suspend fun failClosedMissingActiveVpnNetwork() {
        clearAppliedRuntime()
        FoxholeVpnRuntimeBridge.clearTransientState()
        FoxholeVpnRuntimeBridge.update(
            failClosedMissingActiveVpnNetworkSnapshot(
                trafficMode = settingsRepository.current().traffic.mode,
                message = context.getString(R.string.error_runtime_stopped),
            ),
        )
    }

    private suspend fun awaitVpnNetworkForActiveSnapshot(
        timeoutMs: Long = 1_500L,
        pollMs: Long = 100L,
    ): Network? {
        var vpnNetwork = currentVpnNetwork()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (vpnNetwork == null && System.currentTimeMillis() < deadline) {
            delay(pollMs)
            vpnNetwork = currentVpnNetwork()
        }
        return vpnNetwork
    }

    private suspend fun restoreActiveVpnNetwork(vpnNetwork: Network): Boolean {
        val activeProfile = profileRepository.getActiveProfile()
        diagnosticsLogger.record(
            "connection",
            "active vpn network found with idle snapshot; validating before restore",
        )
        FoxholeVpnRuntimeBridge.update(
            ConnectionSnapshot(
                state = ConnectionState.RECONNECTING,
                trafficMode = TrafficMode.TUNNEL,
                profileId = activeProfile?.id,
                profileName = activeProfile?.name,
                protocolHint = activeProfile?.protocolHint,
                protocolOptionId = activeProfile?.selectedProtocolOptionId,
                message = context.getString(R.string.status_reconnecting),
            ),
        )
        return validateRestoredVpnNetwork(vpnNetwork).fold(
            onSuccess = { validation ->
                diagnosticsLogger.record(
                    "connection",
                    when {
                        validation.ipInfo != null -> "active vpn restore passed vpn-bound ip validation"
                        else -> "active vpn restore passed vpn-bound endpoint validation"
                    },
                )
                validation.ipInfo?.let(FoxholeVpnRuntimeBridge::updateIpInfo)
                FoxholeVpnRuntimeBridge.update(
                    ConnectionSnapshot(
                        state = ConnectionState.CONNECTED,
                        trafficMode = TrafficMode.TUNNEL,
                        profileId = activeProfile?.id,
                        profileName = activeProfile?.name,
                        protocolHint = activeProfile?.protocolHint,
                        protocolOptionId = activeProfile?.selectedProtocolOptionId,
                    ),
                )
                true
            },
            onFailure = { error ->
                diagnosticsLogger.record(
                    "connection",
                    "active vpn restore failed vpn-bound validation: ${error.message.orEmpty()}",
                )
                clearAppliedRuntime()
                FoxholeVpnRuntimeBridge.clearTransientState()
                FoxholeVpnRuntimeBridge.update(
                    ConnectionSnapshot(
                        state = ConnectionState.ERROR,
                        trafficMode = settingsRepository.current().traffic.mode,
                        message = context.getString(R.string.error_dns_probe_failed),
                    ),
                )
                false
            },
        )
    }

    private suspend fun validateRestoredVpnNetwork(vpnNetwork: Network): Result<RestoredVpnValidation> =
        runCatching {
            RestoredVpnValidation(ipInfo = refreshRestoredVpnIpInfo(vpnNetwork))
        }.recoverCatching { ipError ->
            diagnosticsLogger.record(
                "connection",
                "active vpn restore ip validation failed, trying vpn-bound endpoint probe: ${ipError.message.orEmpty()}",
            )
            probeRestoredVpnConnectivityEndpoint(vpnNetwork)
            RestoredVpnValidation(ipInfo = null)
        }

    private suspend fun refreshRestoredVpnIpInfo(vpnNetwork: Network): IpInfo {
        val endpoint = settingsRepository.current().connection.ipInfoEndpoint
        val activeProfile = profileRepository.getActiveProfile()
        val session =
            activeProfile?.id?.let { profileId ->
                runCatching { profileRepository.getSession(profileId) }.getOrNull()
            }
        val preferIpv4Validation =
            shouldPreferIpv4TunnelValidation(
                protocolHint = activeProfile?.protocolHint,
                configJson = session?.configJson,
            )
        val info =
            fetchRestoredVpnIpInfoOnProcessPath(
                endpoint = endpoint,
                vpnNetwork = vpnNetwork,
                preferIpv4Validation = preferIpv4Validation,
            )
        return info.withDnsServers(
            localDnsServers = connectivityManager.dnsServerAddresses(vpnNetwork),
            remoteDnsServers = emptyList(),
        )
    }

    private suspend fun fetchRestoredVpnIpInfoOnProcessPath(
        endpoint: String,
        vpnNetwork: Network,
        preferIpv4Validation: Boolean,
    ): IpInfo {
        val requestNetwork = tunnelValidationRequestNetwork(vpnNetwork)
        val resolverNetwork = currentUpstreamNetwork()
        return if (preferIpv4Validation) {
            ipInfoRepository.fetchIpv4(
                endpoint = endpoint,
                callTimeoutMs = FoxholeVpnService.CONNECTIVITY_PROBE_CALL_TIMEOUT_MS,
                network = requestNetwork,
                resolverNetwork = resolverNetwork,
            ) ?: error("vpn ipv4 refresh failed")
        } else {
            ipInfoRepository.fetch(
                endpoint = endpoint,
                callTimeoutMs = FoxholeVpnService.CONNECTIVITY_PROBE_CALL_TIMEOUT_MS,
                network = requestNetwork,
                resolverNetwork = resolverNetwork,
                mode = IpInfoFetchMode.FULL,
            )
        }
    }

    private suspend fun probeRestoredVpnConnectivityEndpoint(vpnNetwork: Network) {
        val activeProfile = profileRepository.getActiveProfile()
        val session =
            activeProfile?.id?.let { profileId ->
                runCatching { profileRepository.getSession(profileId) }.getOrNull()
            }
        val preferIpv4Validation =
            shouldPreferIpv4TunnelValidation(
                protocolHint = activeProfile?.protocolHint,
                configJson = session?.configJson,
            )
        val requestNetwork = tunnelValidationRequestNetwork(vpnNetwork)
        val resolverNetwork = currentUpstreamNetwork()
        var lastFailure: Throwable? = null
        restoredVpnConnectivityProbeEndpoints().forEach { endpoint ->
            val result =
                runCatching {
                    if (preferIpv4Validation) {
                        ipInfoRepository.probeIpv4(
                            endpoint = endpoint,
                            callTimeoutMs = FoxholeVpnService.CONNECTIVITY_PROBE_CALL_TIMEOUT_MS,
                            network = requestNetwork,
                            resolverNetwork = resolverNetwork,
                        )
                    } else {
                        ipInfoRepository.probe(
                            endpoint = endpoint,
                            callTimeoutMs = FoxholeVpnService.CONNECTIVITY_PROBE_CALL_TIMEOUT_MS,
                            network = requestNetwork,
                            resolverNetwork = resolverNetwork,
                        )
                    }
                }.recoverCatching {
                    ipInfoRepository.probeIpv4(
                        endpoint = endpoint,
                        callTimeoutMs = FoxholeVpnService.CONNECTIVITY_PROBE_CALL_TIMEOUT_MS,
                        network = requestNetwork,
                        resolverNetwork = resolverNetwork,
                    )
                }
            if (result.isSuccess) {
                diagnosticsLogger.record("connection", "active vpn restore endpoint probe ok: $endpoint")
                return
            }
            lastFailure = result.exceptionOrNull()
            diagnosticsLogger.record(
                "connection",
                "active vpn restore endpoint probe failed: $endpoint reason=${lastFailure?.message.orEmpty()}",
            )
        }
        throw lastFailure ?: error("vpn endpoint validation failed")
    }

    private suspend fun restoredVpnConnectivityProbeEndpoints(): List<String> {
        val preferredEndpoint = settingsRepository.current().connection.ipInfoEndpoint.trim()
        return buildList {
            preferredEndpoint.takeIf(String::isNotBlank)?.let(::add)
            FoxholeVpnService.CONNECTIVITY_PROBE_ENDPOINTS.forEach { endpoint ->
                if (endpoint != preferredEndpoint) {
                    add(endpoint)
                }
            }
        }
    }

    private fun currentVpnNetwork(): Network? =
        ConnectivityNetworkRegistry.snapshot(context).firstOrNull { network ->
            connectivityManager.getNetworkCapabilities(network)?.isFoxholeVpnNetwork(context) == true
        }

    private fun currentUpstreamNetwork(): Network? =
        connectivityManager.preferredNonVpnInternetNetwork(
            candidates = ConnectivityNetworkRegistry.snapshot(context),
        )

}

private data class RestoredVpnValidation(
    val ipInfo: IpInfo?,
)

private fun ConnectionSnapshot.isStaleTunnelSnapshotWithoutVpn(vpnNetwork: Network?): Boolean =
    trafficMode == TrafficMode.TUNNEL &&
        state in STALE_VPN_SNAPSHOT_STATES &&
        vpnNetwork == null

internal fun failClosedMissingActiveVpnNetworkSnapshot(
    trafficMode: TrafficMode,
    message: String,
): ConnectionSnapshot =
    ConnectionSnapshot(
        state = ConnectionState.ERROR,
        trafficMode = trafficMode,
        message = message,
    )

internal val ACTIVE_CONNECTION_STATES =
    setOf(
        ConnectionState.CONNECTING,
        ConnectionState.CONNECTED,
        ConnectionState.RECONNECTING,
    )

private val STALE_VPN_SNAPSHOT_STATES =
    setOf(
        ConnectionState.CONNECTED,
        ConnectionState.RECONNECTING,
    )

internal const val LATENCY_PROBE_TIMEOUT_MS = 2_500L
internal const val SERVER_PING_TIMEOUT_MS = 2_500L
private val LATENCY_PROBE_ENDPOINTS = FoxholeVpnService.CONNECTIVITY_PROBE_ENDPOINTS

internal fun latencyProbeEndpoints(): List<String> = LATENCY_PROBE_ENDPOINTS

internal fun FoxholeVpnService.refreshDefaultNetworkAvailabilityInternal() {
    val activeNetwork = connectivityManager.activeNetwork
    val capabilities = activeNetwork?.let(connectivityManager::getNetworkCapabilities)
    defaultNetworkAvailable = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
}

internal fun FoxholeVpnService.onDefaultNetworkCapabilitiesChangedInternal(
    capabilities: NetworkCapabilities?,
    reason: String,
) {
    defaultNetworkAvailable = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    recordDefaultNetworkCapabilities(reason = reason, capabilities = capabilities)
    if (FoxholeVpnRuntimeBridge.snapshot.value.state !in FoxholeVpnService.NOTIFICATION_HEALTH_VISIBLE_STATES) {
        return
    }
    if (defaultNetworkAvailable) {
        updateNotificationConnectivityHealth(
            state = ConnectivityHealthState.CHECKING,
            resetFailures = true,
        )
    } else {
        markNotificationConnectivityOffline()
    }
}

internal fun FoxholeVpnService.markNotificationConnectivityOfflineInternal() {
    updateNotificationConnectivityHealth(
        state = ConnectivityHealthState.OFFLINE,
        force = true,
    )
    consecutiveNotificationHealthFailures =
        maxOf(consecutiveNotificationHealthFailures, FoxholeVpnService.NOTIFICATION_HEALTH_FAILURE_THRESHOLD)
}

internal fun FoxholeVpnService.updateNotificationConnectivityHealthInternal(
    state: ConnectivityHealthState,
    resetFailures: Boolean = false,
    force: Boolean = false,
) {
    if (resetFailures) {
        consecutiveNotificationHealthFailures = 0
    }
    if (state == ConnectivityHealthState.ONLINE && resetFailures) {
        resetAutoReconnectState()
    }
    if (!force && notificationConnectivityHealthState == state) {
        return
    }
    notificationConnectivityHealthState = state
    updateNotification()
}

internal fun FoxholeVpnService.isUpstreamNetworkInternal(network: Network): Boolean {
    val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED) &&
        !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
}

internal fun FoxholeVpnService.recordDefaultNetworkCapabilitiesInternal(
    reason: String,
    capabilities: NetworkCapabilities?,
) {
    val summary = describeNetworkCapabilities(capabilities)
    if (reason == "default network changed" && summary == lastDefaultNetworkSummary) {
        return
    }
    lastDefaultNetworkSummary = summary
    container.diagnosticsLogger.recordStructured(
        "network",
        reason.replaceFirstChar(Char::uppercaseChar),
        summary,
    )
}

internal fun FoxholeVpnService.recordNetworkEventInternal(
    message: String,
    capabilities: NetworkCapabilities?,
) {
    container.diagnosticsLogger.recordStructured(
        "network",
        message.replaceFirstChar(Char::uppercaseChar),
        describeNetworkCapabilities(capabilities),
    )
}

internal fun FoxholeVpnService.describeNetworkCapabilitiesInternal(capabilities: NetworkCapabilities?): String {
    if (capabilities == null) {
        return "unavailable"
    }
    val transport =
        when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            else -> "other"
        }
    val traits =
        buildList {
            if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) add("internet")
            if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) add("validated")
            if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) add("unmetered")
        }
    return if (traits.isEmpty()) {
        transport
    } else {
        "$transport • ${traits.joinToString(separator = " • ")}"
    }
}

internal fun FoxholeVpnService.currentVpnNetworkInternal(excludedHandle: Long? = null): Network =
    currentVpnNetworkOrNull(excludedHandle) ?: error("vpn network unavailable")

internal fun FoxholeVpnService.currentVpnNetworkOrNullInternal(excludedHandle: Long? = null): Network? =
    currentNetworkSnapshot().firstOrNull { network ->
        connectivityManager.getNetworkCapabilities(network)?.isFoxholeVpnNetwork(this) == true &&
            network.networkHandle != excludedHandle
    }

internal fun FoxholeVpnService.currentUpstreamNetworkOrNullInternal(excludedHandle: Long? = null): Network? {
    val snapshot = currentNetworkSnapshot()
    val trackedUpstreamNetworks =
        upstreamNetworkHandles.mapNotNull { handle ->
            snapshot.firstOrNull { network -> network.networkHandle == handle }
        }
    return connectivityManager.preferredNonVpnInternetNetwork(
        candidates = trackedUpstreamNetworks,
        excludedHandle = excludedHandle,
    ) ?: connectivityManager.preferredNonVpnInternetNetwork(
        candidates = snapshot,
        excludedHandle = excludedHandle,
    )
}

@Suppress("DEPRECATION")
private fun FoxholeVpnService.currentNetworkSnapshot(): List<Network> =
    linkedSetOf<Network>().apply {
        connectivityManager.activeNetwork?.let(::add)
        addAll(connectivityManager.allNetworks)
        addAll(ConnectivityNetworkRegistry.snapshot(this@currentNetworkSnapshot))
    }.toList()

internal fun FoxholeVpnService.isVpnNetworkValidatedInternal(network: Network): Boolean =
    connectivityManager
        .getNetworkCapabilities(network)
        ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true

internal suspend fun FoxholeVpnService.awaitVpnNetworkOrNullInternal(
    timeoutMs: Long,
    excludedHandle: Long? = null,
): Network? =
    withTimeoutOrNull(timeoutMs) {
        while (currentCoroutineContext().isActive) {
            currentVpnNetworkOrNull(excludedHandle)?.let { return@withTimeoutOrNull it }
            delay(FoxholeVpnService.VPN_NETWORK_WAIT_POLL_DELAY_MS)
        }
        null
    }

internal fun representativeLatencyMs(latenciesMs: List<Long>): Long? {
    val normalized = latenciesMs.map { it.coerceAtLeast(1L) }.sorted()
    if (normalized.isEmpty()) {
        return null
    }
    val middleIndex = normalized.size / 2
    return if (normalized.size % 2 == 1) {
        normalized[middleIndex]
    } else {
        ((normalized[middleIndex - 1] + normalized[middleIndex]) / 2L).coerceAtLeast(1L)
    }
}

internal fun ConnectivityManager.dnsServerAddresses(network: Network?): List<String> =
    network
        ?.let(::getLinkProperties)
        ?.dnsServers
        .orEmpty()
        .mapNotNull { it.hostAddress?.takeIf(String::isNotBlank) }
        .distinct()

internal fun IpInfo.withDnsServers(
    localDnsServers: List<String>,
    remoteDnsServers: List<String>,
): IpInfo =
    copy(
        localDnsServers = localDnsServers,
        remoteDnsServers = remoteDnsServers,
    )

internal fun IpInfo.retainKnownLocationFrom(previous: IpInfo?): IpInfo {
    if (previous == null || !samePrimaryAddress(previous)) {
        return this
    }
    return copy(
        countryCode = countryCode ?: previous.countryCode,
        countryName = countryName ?: previous.countryName,
        city = city?.takeIf(String::isNotBlank) ?: previous.city,
        isp = isp?.takeIf(String::isNotBlank) ?: previous.isp,
    )
}

internal fun IpInfo.retainKnownDetailsFrom(previous: IpInfo?): IpInfo {
    val previousInfo = previous ?: return this
    if (!samePrimaryAddress(previousInfo)) {
        return this
    }
    return retainKnownLocationFrom(previousInfo).copy(
        ipv4 = ipv4 ?: previousInfo.ipv4,
        localDnsServers = localDnsServers.ifEmpty { previousInfo.localDnsServers },
        remoteDnsServers = remoteDnsServers.ifEmpty { previousInfo.remoteDnsServers },
    )
}

private fun IpInfo.samePrimaryAddress(other: IpInfo): Boolean =
    primaryAddressKey() == other.primaryAddressKey()

private fun IpInfo.primaryAddressKey(): String =
    ipv4 ?: ip

object FoxholeVpnRuntimeBridge {
    private val snapshotMutable = MutableStateFlow(ConnectionSnapshot())
    private val ipInfoMutable = MutableStateFlow<IpInfo?>(null)
    private val deviceIpInfoMutable = MutableStateFlow<IpInfo?>(null)
    private val trafficMutable = MutableStateFlow(TrafficSnapshot())
    private val activeServerPingTargetMutable = MutableStateFlow<ActiveServerPingTarget?>(null)
    private val highFrequencyTrafficUpdatesMutable = MutableStateFlow(false)
    private val immediateTrafficSampleRequestsMutable = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val runtimeUiStateMutable = MutableStateFlow(RuntimeUiState())
    @Volatile
    private var socketProtector: ((Socket) -> Boolean)? = null
    private var pendingIpRefreshReason: RuntimeIpRefreshReason? = null

    val snapshot: StateFlow<ConnectionSnapshot> = snapshotMutable
    val ipInfo: StateFlow<IpInfo?> = ipInfoMutable
    val deviceIpInfo: StateFlow<IpInfo?> = deviceIpInfoMutable
    val traffic: StateFlow<TrafficSnapshot> = trafficMutable
    internal val activeServerPingTarget: StateFlow<ActiveServerPingTarget?> = activeServerPingTargetMutable
    val highFrequencyTrafficUpdates: StateFlow<Boolean> = highFrequencyTrafficUpdatesMutable
    val immediateTrafficSampleRequests: SharedFlow<Unit> = immediateTrafficSampleRequestsMutable
    internal val runtimeUiState: StateFlow<RuntimeUiState> = runtimeUiStateMutable

    fun update(
        value: ConnectionSnapshot,
        refreshLastChangeAt: Boolean = true,
    ) {
        snapshotMutable.value =
            if (refreshLastChangeAt) {
                value.copy(lastChangeAt = System.currentTimeMillis())
            } else {
                value
            }
        publishRuntimeUiState()
    }

    fun updateIpInfo(value: IpInfo?) {
        pendingIpRefreshReason = null
        ipInfoMutable.value =
            if (value == null) {
                null
            } else {
                val previous = ipInfoMutable.value
                value.retainKnownDetailsFrom(previous)
            }
        publishRuntimeUiState()
    }

    fun markIpInfoRefreshPending() {
        markIpInfoRefreshPending(RuntimeIpRefreshReason.POST_CONNECT)
    }

    internal fun markIpInfoRefreshPending(reason: RuntimeIpRefreshReason) {
        pendingIpRefreshReason = reason
        publishRuntimeUiState()
    }

    fun updateDeviceIpInfo(
        value: IpInfo?,
        allowNewAddress: Boolean = true,
    ): Boolean {
        val previous = deviceIpInfoMutable.value
        if (value != null && !allowNewAddress && (previous == null || !value.samePrimaryAddress(previous))) {
            return false
        }
        deviceIpInfoMutable.value =
            if (value == null) {
                null
            } else {
                value.retainKnownDetailsFrom(previous)
            }
        publishRuntimeUiState()
        return true
    }

    fun updateTraffic(value: TrafficSnapshot) {
        trafficMutable.value = value
        publishRuntimeUiState()
    }

    internal fun updateActiveServerPingTarget(value: ActiveServerPingTarget?) {
        activeServerPingTargetMutable.value = value
    }

    internal fun updateSocketProtector(value: ((Socket) -> Boolean)?) {
        socketProtector = value
    }

    internal fun protectDirectSocket(socket: Socket): Boolean =
        socketProtector?.invoke(socket) ?: true

    fun setHighFrequencyTrafficUpdates(enabled: Boolean) {
        highFrequencyTrafficUpdatesMutable.value = enabled
    }

    fun requestImmediateTrafficSample() {
        immediateTrafficSampleRequestsMutable.tryEmit(Unit)
    }

    fun clearTransientState(clearIpInfo: Boolean = true) {
        pendingIpRefreshReason = null
        if (clearIpInfo) {
            ipInfoMutable.value = null
        }
        activeServerPingTargetMutable.value = null
        trafficMutable.value = TrafficSnapshot()
        highFrequencyTrafficUpdatesMutable.value = false
        publishRuntimeUiState()
    }

    private fun publishRuntimeUiState() {
        runtimeUiStateMutable.value =
            runtimeUiStateFromBridge(
                previous = runtimeUiStateMutable.value,
                snapshot = snapshotMutable.value,
                ipInfo = ipInfoMutable.value,
                deviceIpInfo = deviceIpInfoMutable.value,
                traffic = trafficMutable.value,
                pendingIpRefreshReason = pendingIpRefreshReason,
            )
    }
}

internal data class ActiveServerPingTarget(
    val profileId: Long,
    val protocolOptionId: String?,
    val target: VpnHealthProbeTarget,
    val resolvedAddress: InetAddress? = null,
) {
    fun matchesRequest(
        requestedProfileId: Long,
        requestedProtocolOptionId: String?,
    ): Boolean =
        profileId == requestedProfileId &&
            (
                requestedProtocolOptionId.isNullOrBlank() ||
                    protocolOptionId.isNullOrBlank() ||
                    protocolOptionId == requestedProtocolOptionId
                )
}
