package com.foxhole.beta.vpn

import android.net.Network
import android.os.SystemClock
import com.foxhole.beta.core.data.ProfileRepository
import com.foxhole.beta.core.model.ConnectionSnapshot
import com.foxhole.beta.core.model.TrafficMode
import com.foxhole.beta.core.network.IpInfoRepository
import com.foxhole.beta.core.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.InetSocketAddress

internal class ConnectionTelemetryProbe(
    private val profileRepository: ProfileRepository,
    private val settingsRepository: SettingsRepository,
    private val ipInfoRepository: IpInfoRepository,
    private val snapshot: StateFlow<ConnectionSnapshot>,
    private val currentVpnNetwork: () -> Network?,
    private val currentUpstreamNetwork: () -> Network?,
) {
    suspend fun measureCurrentConnectionLatency(timeoutMs: Long): Long {
        val settings = settingsRepository.current()
        val trafficMode =
            snapshot.value.state
                .takeIf { it in ACTIVE_CONNECTION_STATES }
                ?.let { snapshot.value.trafficMode }
                ?: error("active connection is required for latency measurement")
        val proxyAccess = if (trafficMode == TrafficMode.PROXY) settings.preferredAppProxyAccess() else null
        val tunnelConnected = trafficMode == TrafficMode.TUNNEL && snapshot.value.state in ACTIVE_CONNECTION_STATES
        val successfulLatencies = mutableListOf<Long>()
        var lastFailure: Throwable? = null
        latencyProbeEndpoints().forEach { endpoint ->
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

    suspend fun measureCurrentVpnServerPing(
        profileId: Long,
        protocolOptionId: String?,
        timeoutMs: Long,
    ): Long {
        snapshot.value.state
            .takeIf { it in ACTIVE_CONNECTION_STATES }
            ?: error("active connection is required for server ping measurement")
        val session = profileRepository.getSession(profileId, protocolOptionId)
        val target = VpnHealthProbeTargetSelector.select(session.configJson)
            ?: error("vpn server target unavailable")
        require(target.transport == VpnHealthProbeTransport.TCP) {
            "server ping unavailable for ${target.transport.name.lowercase()} transport"
        }
        val upstreamNetwork = currentUpstreamNetwork() ?: error("upstream network unavailable")
        return withContext(Dispatchers.IO) {
            val address = resolveServerPingAddress(target.host, upstreamNetwork)
            val startedAt = SystemClock.elapsedRealtime()
            upstreamNetwork.socketFactory.createSocket().use { socket ->
                socket.soTimeout = timeoutMs.toInt()
                socket.connect(
                    InetSocketAddress(address, target.port),
                    timeoutMs.toInt(),
                )
            }
            (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(1L)
        }
    }

    private fun resolveServerPingAddress(
        host: String,
        network: Network,
    ): InetAddress =
        network.getAllByName(host).firstOrNull()
            ?: InetAddress.getByName(host)
}
