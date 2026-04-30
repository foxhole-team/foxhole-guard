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
            profileRepository = profileRepository,
            settingsRepository = settingsRepository,
            ipInfoRepository = ipInfoRepository,
            snapshot = snapshot,
            currentVpnNetwork = { currentVpnNetwork() },
            currentUpstreamNetwork = { currentUpstreamNetwork() },
            currentVpnInterfaceName = { network -> connectivityManager.getLinkProperties(network)?.interfaceName },
        )

    suspend fun connect(
        profileId: Long,
        protocolOptionId: String? = null,
        statusMessage: String? = null,
        previousVpnNetworkHandle: Long? = null,
    ) = lifecycle.connect(
        profileId = profileId,
        protocolOptionId = protocolOptionId,
        statusMessage = statusMessage,
        previousVpnNetworkHandle = previousVpnNetworkHandle,
    )

    fun disconnect() {
        lifecycle.disconnect()
    }

    suspend fun currentRuntimeFingerprint(): Int = lifecycle.currentRuntimeFingerprint()

    suspend fun markCurrentRuntimeApplied() {
        lifecycle.markCurrentRuntimeApplied()
    }

    fun clearAppliedRuntime() {
        lifecycle.clearAppliedRuntime()
    }

    fun reload(profileId: Long? = snapshot.value.profileId): Boolean = lifecycle.reload(profileId)

    suspend fun refreshProfile(profileId: Long): Profile = profileRepository.refreshProfile(profileId)

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
        if (currentSnapshot.state in ACTIVE_CONNECTION_STATES || !hasActiveVpnNetwork()) {
            return false
        }
        val activeProfile = profileRepository.getActiveProfile()
        diagnosticsLogger.record(
            "connection",
            "active vpn network found with idle snapshot; restored connected state",
        )
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
        return true
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

internal val ACTIVE_CONNECTION_STATES =
    setOf(
        ConnectionState.CONNECTING,
        ConnectionState.CONNECTED,
        ConnectionState.RECONNECTING,
    )

internal const val LATENCY_PROBE_TIMEOUT_MS = 6_000L
internal const val SERVER_PING_TIMEOUT_MS = 3_000L
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
