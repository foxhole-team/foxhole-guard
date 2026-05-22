package com.foxhole.beta.vpn

import android.net.Network
import android.os.SystemClock
import com.foxhole.beta.core.data.ProfileRepository
import com.foxhole.beta.core.model.ConnectionSnapshot
import com.foxhole.beta.core.model.LatencyProbeMethod
import com.foxhole.beta.core.model.RuntimeFailureCode
import com.foxhole.beta.core.model.RuntimeFailureException
import com.foxhole.beta.core.model.Settings
import com.foxhole.beta.core.model.TrafficMode
import com.foxhole.beta.core.network.IpInfoRepository
import com.foxhole.beta.core.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlin.math.roundToLong

internal class ConnectionTelemetryProbe(
    private val profileRepository: ProfileRepository,
    private val settingsRepository: SettingsRepository,
    private val ipInfoRepository: IpInfoRepository,
    private val snapshot: StateFlow<ConnectionSnapshot>,
    private val currentVpnNetwork: () -> Network?,
    private val currentUpstreamNetwork: () -> Network?,
    private val currentVpnInterfaceName: (Network) -> String?,
) {
    @Suppress("CyclomaticComplexMethod")
    suspend fun measureCurrentConnectionLatency(timeoutMs: Long): Long {
        val settings = settingsRepository.current()
        val currentSnapshot = snapshot.value
        val trafficMode = activeTrafficModeForLatency(currentSnapshot)
        val tunnelConnected = trafficMode == TrafficMode.TUNNEL && currentSnapshot.state in ACTIVE_CONNECTION_STATES
        val useRuntimeProxyForTunnel =
            shouldUseRuntimeProxyForTunnelLatency(
                trafficMode = trafficMode,
                snapshot = currentSnapshot,
                settings = settings,
            )
        val proxyAccess =
            when {
                trafficMode == TrafficMode.PROXY -> settings.preferredAppProxyAccess()
                useRuntimeProxyForTunnel -> settings.tunnelRuntimeProxyAccess()
                else -> null
            }
        val methods =
            latencyProbeMethodOrder(
                trafficMode = trafficMode,
                configuredMethod = settings.connection.latencyProbeMethod,
                useRuntimeProxyForTunnel = useRuntimeProxyForTunnel,
            )
        val successfulLatencies = mutableListOf<Long>()
        var lastFailure: Throwable? = null
        methods.forEach { method ->
            latencyProbeEndpoints().forEach { endpoint ->
                val attempt =
                    runCatching {
                        when {
                            trafficMode == TrafficMode.TUNNEL && tunnelConnected && useRuntimeProxyForTunnel ->
                                ipInfoRepository.probeLatency(
                                    endpoint = endpoint,
                                    callTimeoutMs = timeoutMs,
                                    proxy = proxyAccess,
                                    resolverNetwork = currentUpstreamNetwork(),
                                )
                            trafficMode == TrafficMode.TUNNEL && tunnelConnected -> {
                                val vpnNetwork = requireVpnNetworkForLatency()
                                measureTunnelLatency(
                                    endpoint = endpoint,
                                    timeoutMs = timeoutMs,
                                    network = tunnelValidationRequestNetwork(vpnNetwork),
                                    resolverNetwork = currentUpstreamNetwork(),
                                    interfaceName = currentVpnInterfaceName(vpnNetwork),
                                    method = method,
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
            representativeLatencyMs(successfulLatencies)?.let { return it }
        }
        return representativeLatencyMs(successfulLatencies) ?: throw latencyProbeFailure(lastFailure)
    }

    private fun activeTrafficModeForLatency(currentSnapshot: ConnectionSnapshot): TrafficMode =
        currentSnapshot.state
            .takeIf { it in ACTIVE_CONNECTION_STATES }
            ?.let { currentSnapshot.trafficMode }
            ?: throw RuntimeFailureException(
                RuntimeFailureCode.ACTIVE_CONNECTION_REQUIRED,
                "active connection is required for latency measurement",
            )

    private fun requireVpnNetworkForLatency(): Network =
        currentVpnNetwork()
            ?: throw RuntimeFailureException(
                RuntimeFailureCode.VPN_NETWORK_MISSING,
                "vpn network unavailable",
            )

    private fun latencyProbeFailure(lastFailure: Throwable?): RuntimeFailureException =
        (lastFailure as? RuntimeFailureException)
            ?: RuntimeFailureException(
                RuntimeFailureCode.LATENCY_PROBE_FAILED,
                "latency probe failed",
                lastFailure,
            )

    private suspend fun measureTunnelLatency(
        endpoint: String,
        timeoutMs: Long,
        network: Network?,
        resolverNetwork: Network?,
        interfaceName: String?,
        method: LatencyProbeMethod,
    ): Long =
        when (method) {
            LatencyProbeMethod.HTTP ->
                ipInfoRepository.probeLatency(
                    endpoint = endpoint,
                    callTimeoutMs = timeoutMs,
                    network = network,
                    resolverNetwork = resolverNetwork,
                )
            LatencyProbeMethod.ICMP -> measureIcmpLatency(endpoint, timeoutMs, interfaceName)
            LatencyProbeMethod.TCP -> measureTcpConnectLatency(endpoint, timeoutMs, network, resolverNetwork)
        }

    private suspend fun measureTcpConnectLatency(
        endpoint: String,
        timeoutMs: Long,
        network: Network?,
        resolverNetwork: Network?,
    ): Long =
        withContext(Dispatchers.IO) {
            val url = endpoint.toHttpUrlOrNull() ?: error("latency endpoint is not a valid URL")
            val address = resolveServerPingAddress(url.host, resolverNetwork ?: network)
            val startedAt = SystemClock.elapsedRealtime()
            // Availability probe only: opens a bounded TCP connect to a public latency endpoint and sends no secrets.
            (network?.socketFactory?.createSocket() ?: Socket()).use { socket ->
                socket.soTimeout = timeoutMs.toInt()
                socket.connect(
                    InetSocketAddress(address, url.port),
                    timeoutMs.toInt(),
                )
            }
            (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(1L)
        }

    private suspend fun measureIcmpLatency(
        endpoint: String,
        timeoutMs: Long,
        interfaceName: String?,
    ): Long =
        withContext(Dispatchers.IO) {
            val host = endpoint.toHttpUrlOrNull()?.host?.takeIf(String::isNotBlank)
                ?: error("latency endpoint host is unavailable")
            val command = icmpPingCommand(host = host, timeoutMs = timeoutMs, interfaceName = interfaceName)
            val startedAt = SystemClock.elapsedRealtime()
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            val finished = process.waitFor(timeoutMs.coerceAtLeast(1L), TimeUnit.MILLISECONDS)
            val elapsedMs = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(1L)
            if (!finished) {
                process.destroyForcibly()
                error("icmp ping timed out")
            }
            val output = process.inputStream.bufferedReader().use { it.readText() }
            if (process.exitValue() != 0) {
                error("icmp ping failed")
            }
            parseIcmpPingLatencyMs(output) ?: elapsedMs
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
            // Availability probe only: opens a bounded TCP connect to the configured server target and sends no payload.
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
        network: Network?,
    ): InetAddress =
        network?.getAllByName(host)?.firstOrNull()
            ?: InetAddress.getByName(host)
}

internal fun effectiveLatencyProbeMethod(
    trafficMode: TrafficMode,
    configuredMethod: LatencyProbeMethod,
): LatencyProbeMethod =
    when (trafficMode) {
        TrafficMode.TUNNEL -> configuredMethod
        TrafficMode.PROXY -> LatencyProbeMethod.HTTP
    }

internal fun latencyProbeMethodOrder(
    trafficMode: TrafficMode,
    configuredMethod: LatencyProbeMethod,
    useRuntimeProxyForTunnel: Boolean = false,
): List<LatencyProbeMethod> {
    val primary = effectiveLatencyProbeMethod(trafficMode, configuredMethod)
    if (trafficMode == TrafficMode.PROXY || useRuntimeProxyForTunnel) {
        return listOf(LatencyProbeMethod.HTTP)
    }
    val fallbackMethods =
        listOf(
            LatencyProbeMethod.HTTP,
            LatencyProbeMethod.TCP,
        )
    return (listOf(primary) + fallbackMethods).distinct()
}

internal fun shouldUseRuntimeProxyForTunnelLatency(
    trafficMode: TrafficMode,
    snapshot: ConnectionSnapshot,
    settings: Settings,
): Boolean =
    trafficMode == TrafficMode.TUNNEL &&
        snapshot.state in ACTIVE_CONNECTION_STATES &&
        settings.requiresStrictRuntimeProxyIpRefresh(snapshot)

internal fun icmpPingCommand(
    host: String,
    timeoutMs: Long,
    interfaceName: String? = null,
): List<String> {
    val timeoutSeconds = ((timeoutMs.coerceAtLeast(1L) + 999L) / 1_000L).coerceAtLeast(1L)
    val normalizedInterface = interfaceName?.takeIf(::isSafeNetworkInterfaceName)
    return buildList {
        add("/system/bin/ping")
        add("-n")
        add("-c")
        add("1")
        add("-W")
        add(timeoutSeconds.toString())
        if (normalizedInterface != null) {
            add("-I")
            add(normalizedInterface)
        }
        add(host)
    }
}

internal fun isSafeNetworkInterfaceName(value: String): Boolean =
    value.isNotBlank() && value.length <= 32 && value.all { char ->
        char.isLetterOrDigit() || char == '_' || char == '-' || char == '.'
    }

internal fun parseIcmpPingLatencyMs(output: String): Long? {
    val value =
        Regex("""time[=<]([0-9]+(?:\.[0-9]+)?)\s*ms""", RegexOption.IGNORE_CASE)
            .find(output)
            ?.groupValues
            ?.getOrNull(1)
            ?.toDoubleOrNull()
            ?: return null
    return value.roundToLong().coerceAtLeast(1L)
}
