package com.foxhole.beta.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.core.content.getSystemService
import com.foxhole.beta.core.data.ProfileRepository
import com.foxhole.beta.core.data.RoutingRepository
import com.foxhole.beta.core.diagnostics.DiagnosticsLogger
import com.foxhole.beta.core.model.ConnectionSnapshot
import com.foxhole.beta.core.model.ConnectionState
import com.foxhole.beta.core.model.IpInfo
import com.foxhole.beta.core.model.Profile
import com.foxhole.beta.core.model.TrafficMode
import com.foxhole.beta.core.model.TrafficSnapshot
import com.foxhole.beta.core.network.IpInfoFetchMode
import com.foxhole.beta.core.network.IpInfoRepository
import com.foxhole.beta.core.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

class FoxholeConnectionController(
    private val context: Context,
    private val profileRepository: ProfileRepository,
    private val settingsRepository: SettingsRepository,
    private val routingRepository: RoutingRepository,
    private val ipInfoRepository: IpInfoRepository,
    private val diagnosticsLogger: DiagnosticsLogger,
    private val runtimeConfigAssembler: RuntimeConfigAssembler,
) {
    private val connectivityManager by lazy { context.getSystemService<ConnectivityManager>()!! }
    val snapshot: StateFlow<ConnectionSnapshot> = FoxholeVpnRuntimeBridge.snapshot
    val ipInfo: StateFlow<IpInfo?> = FoxholeVpnRuntimeBridge.ipInfo
    val traffic: StateFlow<TrafficSnapshot> = FoxholeVpnRuntimeBridge.traffic
    private val appliedRuntimeSignatureMutable = MutableStateFlow<Int?>(null)
    val appliedRuntimeSignature: StateFlow<Int?> = appliedRuntimeSignatureMutable

    suspend fun connect(
        profileId: Long,
        protocolOptionId: String? = null,
        statusMessage: String? = null,
        previousVpnNetworkHandle: Long? = null,
    ) {
        val profile = profileRepository.getProfile(profileId) ?: error("profile not found")
        val settings = settingsRepository.current()
        if (snapshot.value.state !in ACTIVE_CONNECTION_STATES) {
            FoxholeConnectionServiceContract.stopAllServices(context)
        }
        diagnosticsLogger.record("connection", "connect requested")
        FoxholeVpnRuntimeBridge.updateIpInfo(null)
        FoxholeVpnRuntimeBridge.update(
            ConnectionSnapshot(
                state = ConnectionState.CONNECTING,
                trafficMode = settings.traffic.mode,
                profileId = profile.id,
                profileName = profile.name,
                protocolHint = profile.protocolHint,
                message = statusMessage,
            ),
        )
        FoxholeConnectionServiceContract.startForegroundService(
            context = context,
            mode = settings.traffic.mode,
            action = FoxholeConnectionServiceContract.ACTION_CONNECT,
            profileId = profileId,
            protocolOptionId = protocolOptionId,
            previousVpnNetworkHandle = previousVpnNetworkHandle,
        )
    }

    fun disconnect() {
        clearAppliedRuntime()
        diagnosticsLogger.record("connection", "disconnect requested")
        val currentSnapshot = snapshot.value
        val disconnectMode = disconnectDispatchModeOrNull(currentSnapshot)
        if (disconnectMode == null) {
            diagnosticsLogger.record("connection", "disconnect skipped: no active runtime")
            FoxholeConnectionServiceContract.stopAllServices(context)
            FoxholeVpnRuntimeBridge.clearTransientState()
            FoxholeVpnRuntimeBridge.update(
                ConnectionSnapshot(
                    trafficMode = settingsRepository.settings.value.traffic.mode,
                ),
            )
            return
        }
        FoxholeConnectionServiceContract.startForegroundService(
            context = context,
            mode = disconnectMode,
            action = FoxholeConnectionServiceContract.ACTION_DISCONNECT,
        )
    }

    suspend fun currentRuntimeFingerprint(): Int =
        runtimeConfigAssembler.runtimeFingerprint(
            settingsRepository.current(),
            routingRepository.currentPresetForRuntime(),
        )

    suspend fun markCurrentRuntimeApplied() {
        appliedRuntimeSignatureMutable.value = currentRuntimeFingerprint()
    }

    fun clearAppliedRuntime() {
        appliedRuntimeSignatureMutable.value = null
    }

    fun reload(profileId: Long? = snapshot.value.profileId): Boolean {
        val targetProfileId = profileId ?: return false
        if (snapshot.value.state !in ACTIVE_CONNECTION_STATES || snapshot.value.profileId != targetProfileId) {
            return false
        }
        diagnosticsLogger.record("connection", "runtime reload requested")
        FoxholeConnectionServiceContract.startForegroundService(
            context = context,
            mode =
                FoxholeConnectionServiceContract.serviceMode(
                    snapshot = snapshot.value,
                    fallbackMode = settingsRepository.settings.value.traffic.mode,
                ),
            action = FoxholeConnectionServiceContract.ACTION_RELOAD,
            profileId = targetProfileId,
        )
        return true
    }

    suspend fun refreshProfile(profileId: Long): Profile = profileRepository.refreshProfile(profileId)

    suspend fun setActiveProfile(profileId: Long) {
        profileRepository.setActiveProfile(profileId)
    }

    suspend fun refreshIpInfo(fetchMode: IpInfoFetchMode = IpInfoFetchMode.FULL): IpInfo {
        val settings = settingsRepository.current()
        val endpoint = settings.connection.ipInfoEndpoint
        val trafficMode =
            snapshot.value.state
                .takeIf { it in ACTIVE_CONNECTION_STATES }
                ?.let { snapshot.value.trafficMode }
                ?: TrafficMode.TUNNEL
        val proxyAccess = if (trafficMode == TrafficMode.PROXY) settings.preferredAppProxyAccess() else null
        val tunnelConnected = trafficMode == TrafficMode.TUNNEL && snapshot.value.state in ACTIVE_CONNECTION_STATES
        val vpnNetwork = if (tunnelConnected) currentVpnNetwork() ?: error("vpn network unavailable") else null
        val upstreamNetwork = if (tunnelConnected) null else currentUpstreamNetwork()
        val dnsNetwork =
            when {
                trafficMode != TrafficMode.TUNNEL -> null
                tunnelConnected -> vpnNetwork
                else -> upstreamNetwork
            }
        val requestNetwork =
            when {
                trafficMode != TrafficMode.TUNNEL -> null
                tunnelConnected -> null
                else -> upstreamNetwork
            }
        val remoteDnsServers =
            snapshot.value.profileId
                ?.let { profileId ->
                    runCatching {
                        VpnDnsServerSelector.remoteDnsServerAddresses(profileRepository.getSession(profileId).configJson)
                    }.getOrDefault(emptyList())
                }.orEmpty()
        val info =
            when {
                trafficMode == TrafficMode.TUNNEL && tunnelConnected && vpnNetwork != null ->
                    fetchTunnelIpInfo(
                        endpoint = endpoint,
                        fetchMode = fetchMode,
                        vpnNetwork = vpnNetwork,
                    )
                else ->
                    fetchDeviceIpInfo(
                        endpoint = endpoint,
                        fetchMode = fetchMode,
                        requestNetwork = requestNetwork,
                        proxy = proxyAccess,
                    )
            }.withDnsServers(
                localDnsServers = connectivityManager.dnsServerAddresses(dnsNetwork),
                remoteDnsServers = remoteDnsServers,
            )
        return info
    }

    suspend fun measureCurrentConnectionLatency(timeoutMs: Long = LATENCY_PROBE_TIMEOUT_MS): Long {
        val settings = settingsRepository.current()
        val trafficMode =
            snapshot.value.state
                .takeIf { it in ACTIVE_CONNECTION_STATES }
                ?.let { snapshot.value.trafficMode }
                ?: error("active connection is required for latency measurement")
        val proxyAccess = if (trafficMode == TrafficMode.PROXY) settings.preferredAppProxyAccess() else null
        val tunnelConnected = trafficMode == TrafficMode.TUNNEL && snapshot.value.state in ACTIVE_CONNECTION_STATES
        val endpoints = latencyProbeEndpoints()
        val successfulLatencies = mutableListOf<Long>()
        var lastFailure: Throwable? = null
        endpoints.forEach { endpoint ->
            val attempt =
                runCatching {
                    when {
                        trafficMode == TrafficMode.TUNNEL && tunnelConnected -> {
                            val vpnNetwork = currentVpnNetwork() ?: error("vpn network unavailable")
                            ipInfoRepository.probeLatency(
                                endpoint = endpoint,
                                callTimeoutMs = timeoutMs,
                                network = boundNetworkForAppOwnedRequest(vpnNetwork),
                            )
                        }
                        else ->
                            ipInfoRepository.probeLatency(
                                endpoint = endpoint,
                                callTimeoutMs = timeoutMs,
                                proxy = proxyAccess,
                            )
                    }
                }
            if (attempt.isSuccess) {
                successfulLatencies += attempt.getOrThrow()
            } else {
                lastFailure = attempt.exceptionOrNull()
            }
        }
        return representativeLatencyMs(successfulLatencies)
            ?: throw (lastFailure ?: error("latency probe failed"))
    }

    fun hasActiveVpnNetwork(): Boolean = currentVpnNetwork() != null

    fun currentVpnNetworkHandle(): Long? = currentVpnNetwork()?.networkHandle

    private suspend fun fetchDeviceIpInfo(
        endpoint: String,
        fetchMode: IpInfoFetchMode,
        requestNetwork: Network?,
        proxy: com.foxhole.beta.core.network.HttpProxyAccess?,
    ): IpInfo {
        if (proxy != null) {
            return ipInfoRepository.fetch(
                endpoint = endpoint,
                proxy = proxy,
                mode = fetchMode,
            )
        }
        return ipInfoRepository.fetch(
            endpoint = endpoint,
            network = boundNetworkForAppOwnedRequest(requestNetwork),
            mode = fetchMode,
        )
    }

    private suspend fun fetchTunnelIpInfo(
        endpoint: String,
        fetchMode: IpInfoFetchMode,
        vpnNetwork: Network,
    ): IpInfo {
        return ipInfoRepository.fetch(
            endpoint = endpoint,
            network = boundNetworkForAppOwnedRequest(vpnNetwork),
            mode = fetchMode,
        )
            .also { diagnosticsLogger.record("ip", "dashboard ip refreshed after vpn network detected") }
    }

    private fun currentVpnNetwork(): Network? =
        ConnectivityNetworkRegistry.snapshot(context).firstOrNull { network ->
            connectivityManager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }

    private fun currentUpstreamNetwork(): Network? =
        ConnectivityNetworkRegistry.snapshot(context).firstOrNull { network ->
            connectivityManager.getNetworkCapabilities(network)?.let(::isUpstreamNetwork) == true
        } ?: connectivityManager.activeNetwork?.takeIf { network ->
            connectivityManager.getNetworkCapabilities(network)?.let(::isUpstreamNetwork) == true
        }

    private fun isUpstreamNetwork(capabilities: NetworkCapabilities): Boolean =
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED) &&
            !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
}

private val ACTIVE_CONNECTION_STATES =
    setOf(
        ConnectionState.CONNECTING,
        ConnectionState.CONNECTED,
        ConnectionState.RECONNECTING,
    )

internal fun disconnectDispatchModeOrNull(snapshot: ConnectionSnapshot): TrafficMode? =
    snapshot.trafficMode.takeIf { snapshot.state in ACTIVE_CONNECTION_STATES }

private const val LATENCY_PROBE_TIMEOUT_MS = 6_000L
private val LATENCY_PROBE_ENDPOINTS = FoxholeVpnService.CONNECTIVITY_PROBE_ENDPOINTS

internal fun latencyProbeEndpoints(): List<String> = LATENCY_PROBE_ENDPOINTS

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

object FoxholeVpnRuntimeBridge {
    private val snapshotMutable = MutableStateFlow(ConnectionSnapshot())
    private val ipInfoMutable = MutableStateFlow<IpInfo?>(null)
    private val trafficMutable = MutableStateFlow(TrafficSnapshot())
    private val highFrequencyTrafficUpdatesMutable = MutableStateFlow(false)
    private val immediateTrafficSampleRequestsMutable = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    val snapshot: StateFlow<ConnectionSnapshot> = snapshotMutable
    val ipInfo: StateFlow<IpInfo?> = ipInfoMutable
    val traffic: StateFlow<TrafficSnapshot> = trafficMutable
    val highFrequencyTrafficUpdates: StateFlow<Boolean> = highFrequencyTrafficUpdatesMutable
    val immediateTrafficSampleRequests: SharedFlow<Unit> = immediateTrafficSampleRequestsMutable

    fun update(value: ConnectionSnapshot) {
        snapshotMutable.value = value.copy(lastChangeAt = System.currentTimeMillis())
    }

    fun updateIpInfo(value: IpInfo?) {
        ipInfoMutable.value =
            if (value == null) {
                null
            } else {
                val previous = ipInfoMutable.value
                value.copy(
                    ipv4 = value.ipv4 ?: previous?.ipv4,
                    localDnsServers = value.localDnsServers.ifEmpty { previous?.localDnsServers.orEmpty() },
                    remoteDnsServers = value.remoteDnsServers.ifEmpty { previous?.remoteDnsServers.orEmpty() },
                )
            }
    }

    fun updateTraffic(value: TrafficSnapshot) {
        trafficMutable.value = value
    }

    fun setHighFrequencyTrafficUpdates(enabled: Boolean) {
        highFrequencyTrafficUpdatesMutable.value = enabled
    }

    fun requestImmediateTrafficSample() {
        immediateTrafficSampleRequestsMutable.tryEmit(Unit)
    }

    fun clearTransientState(clearIpInfo: Boolean = true) {
        if (clearIpInfo) {
            ipInfoMutable.value = null
        }
        trafficMutable.value = TrafficSnapshot()
        highFrequencyTrafficUpdatesMutable.value = false
    }
}
