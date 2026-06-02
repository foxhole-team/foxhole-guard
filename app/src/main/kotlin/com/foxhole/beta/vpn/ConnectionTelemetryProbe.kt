package com.foxhole.beta.vpn

import android.net.Network
import android.os.SystemClock
import com.foxhole.beta.core.model.ConnectionSnapshot
import com.foxhole.beta.core.model.LatencyProbeMethod
import com.foxhole.beta.core.model.RuntimeFailureCode
import com.foxhole.beta.core.model.RuntimeFailureException
import com.foxhole.beta.core.model.Settings
import com.foxhole.beta.core.model.TrafficMode
import com.foxhole.beta.core.network.HttpProxyAccess
import com.foxhole.beta.core.network.IpInfoRepository
import com.foxhole.beta.core.network.PublicDnsFallback
import com.foxhole.beta.core.network.PublicDohDnsFallback
import com.foxhole.beta.core.network.PublicRemoteDns
import com.foxhole.beta.core.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import kotlin.math.roundToLong

internal class ConnectionTelemetryProbe(
    private val settingsRepository: SettingsRepository,
    private val ipInfoRepository: IpInfoRepository,
    private val snapshot: StateFlow<ConnectionSnapshot>,
    private val currentVpnNetwork: () -> Network?,
    private val currentUpstreamNetwork: () -> Network?,
    private val currentVpnInterfaceName: (Network) -> String?,
    private val isVpnNetworkValidated: (Network) -> Boolean,
    private val activeServerPingTarget: () -> ActiveServerPingTarget?,
    private val protectDirectSocket: (Socket) -> Boolean = { true },
) {
    suspend fun measureCurrentConnectionLatency(timeoutMs: Long): Long {
        val settings = settingsRepository.current()
        val currentSnapshot = snapshot.value
        val trafficMode = activeTrafficModeForLatency(currentSnapshot)
        val tunnelConnected = isConnectedTunnel(trafficMode, currentSnapshot)
        val useRuntimeProxyForTunnel =
            shouldUseRuntimeProxyForTunnelLatency(
                trafficMode = trafficMode,
                snapshot = currentSnapshot,
                settings = settings,
            )
        val vpnNetwork = if (tunnelConnected) currentVpnNetwork() else null
        val preferVpnBoundLatency =
            vpnNetwork != null &&
                shouldPreferVpnBoundTunnelLatency(
                    settings = settings,
                    snapshot = currentSnapshot,
                    androidValidatedVpnNetwork = isVpnNetworkValidated(vpnNetwork),
                )
        val proxyAccess = latencyProxyAccess(settings, trafficMode, useRuntimeProxyForTunnel)
        return when {
            tunnelConnected && preferVpnBoundLatency -> {
                val methods =
                    latencyProbeMethodOrder(
                        trafficMode = TrafficMode.TUNNEL,
                        configuredMethod = settings.connection.latencyProbeMethod,
                        useRuntimeProxyForTunnel = false,
                    )
                measureVpnBoundLatencyMethods(
                    timeoutMs = timeoutMs,
                    vpnNetwork = checkNotNull(vpnNetwork),
                    methods = methods,
                    attempts = LatencyProbeAttempts(),
                )
            }
            tunnelConnected && useRuntimeProxyForTunnel ->
                measureRuntimeProxyTunnelLatencyWithFallback(
                    timeoutMs = timeoutMs,
                    settings = settings,
                    currentSnapshot = currentSnapshot,
                    proxyAccess = proxyAccess,
                )
            else ->
                measureLatencyWithConfiguredMethods(
                    timeoutMs = timeoutMs,
                    settings = settings,
                    trafficMode = trafficMode,
                    tunnelConnected = tunnelConnected,
                    proxyAccess = proxyAccess,
                )
        }
    }

    private suspend fun measureRuntimeProxyTunnelLatencyWithFallback(
        timeoutMs: Long,
        settings: Settings,
        currentSnapshot: ConnectionSnapshot,
        proxyAccess: HttpProxyAccess?,
    ): Long {
        val attempts = LatencyProbeAttempts()
        val runtimeProxyLatency =
            attempts.recordEndpointLatencies { endpoint ->
                ipInfoRepository.probeLatency(
                    endpoint = endpoint,
                    callTimeoutMs = runtimeProxyTunnelLatencyCallTimeoutMs(timeoutMs),
                    proxy = proxyAccess,
                    resolverNetwork = currentUpstreamNetwork(),
                )
            }
        return runtimeProxyLatency
            ?: measureVpnFallbackLatencyOrThrow(
                timeoutMs = timeoutMs,
                settings = settings,
                currentSnapshot = currentSnapshot,
                attempts = attempts,
            )
    }

    private suspend fun measureVpnFallbackLatencyOrThrow(
        timeoutMs: Long,
        settings: Settings,
        currentSnapshot: ConnectionSnapshot,
        attempts: LatencyProbeAttempts,
    ): Long {
        val vpnNetwork = currentVpnNetwork()
        val canFallback =
            vpnNetwork != null &&
                settings.canUseVpnBoundIpRefreshFallback(
                    snapshot = currentSnapshot,
                    androidValidatedVpnNetwork = isVpnNetworkValidated(vpnNetwork),
                )
        if (!canFallback) {
            throw latencyProbeFailure(attempts.lastFailure)
        }
        val fallbackMethods =
            latencyProbeMethodOrder(
                trafficMode = TrafficMode.TUNNEL,
                configuredMethod = settings.connection.latencyProbeMethod,
                useRuntimeProxyForTunnel = false,
            )
        return measureVpnBoundLatencyMethods(
            timeoutMs = runtimeProxyTunnelLatencyCallTimeoutMs(timeoutMs),
            vpnNetwork = checkNotNull(vpnNetwork),
            methods = fallbackMethods,
            attempts = attempts,
        )
    }

    private suspend fun measureLatencyWithConfiguredMethods(
        timeoutMs: Long,
        settings: Settings,
        trafficMode: TrafficMode,
        tunnelConnected: Boolean,
        proxyAccess: HttpProxyAccess?,
    ): Long {
        val methods =
            latencyProbeMethodOrder(
                trafficMode = trafficMode,
                configuredMethod = settings.connection.latencyProbeMethod,
            )
        val attempts = LatencyProbeAttempts()
        methods.forEach { method ->
            attempts.recordEndpointLatencies { endpoint ->
                measureLatencyEndpoint(
                    endpoint = endpoint,
                    timeoutMs = timeoutMs,
                    trafficMode = trafficMode,
                    tunnelConnected = tunnelConnected,
                    proxyAccess = proxyAccess,
                    method = method,
                )
            }?.let { return it }
        }
        throw latencyProbeFailure(attempts.lastFailure)
    }

    private suspend fun measureVpnBoundLatencyMethods(
        timeoutMs: Long,
        vpnNetwork: Network,
        methods: List<LatencyProbeMethod>,
        attempts: LatencyProbeAttempts,
    ): Long {
        methods.forEach { method ->
            attempts.recordEndpointLatencies { endpoint ->
                measureTunnelLatency(
                    endpoint = endpoint,
                    timeoutMs = timeoutMs,
                    network = tunnelValidationRequestNetwork(vpnNetwork),
                    resolverNetwork = currentUpstreamNetwork(),
                    interfaceName = currentVpnInterfaceName(vpnNetwork),
                    method = method,
                )
            }?.let { return it }
        }
        throw latencyProbeFailure(attempts.lastFailure)
    }

    private suspend fun measureLatencyEndpoint(
        endpoint: String,
        timeoutMs: Long,
        trafficMode: TrafficMode,
        tunnelConnected: Boolean,
        proxyAccess: HttpProxyAccess?,
        method: LatencyProbeMethod,
    ): Long =
        if (trafficMode == TrafficMode.TUNNEL && tunnelConnected) {
            val vpnNetwork = requireVpnNetworkForLatency()
            measureTunnelLatency(
                endpoint = endpoint,
                timeoutMs = timeoutMs,
                network = tunnelValidationRequestNetwork(vpnNetwork),
                resolverNetwork = currentUpstreamNetwork(),
                interfaceName = currentVpnInterfaceName(vpnNetwork),
                method = method,
            )
        } else {
            ipInfoRepository.probeLatency(
                endpoint = endpoint,
                callTimeoutMs = timeoutMs,
                proxy = proxyAccess,
            )
        }

    private fun requireVpnNetworkForLatency(): Network =
        currentVpnNetwork()
            ?: throw RuntimeFailureException(
                RuntimeFailureCode.VPN_NETWORK_MISSING,
                "vpn network unavailable",
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

    suspend fun measureCurrentVpnServerPing(
        profileId: Long,
        protocolOptionId: String?,
        timeoutMs: Long,
    ): Long {
        snapshot.value.state
            .takeIf { it in ACTIVE_CONNECTION_STATES }
            ?: error("active connection is required for server ping measurement")
        val activeTarget =
            activeServerPingTarget()
                ?.takeIf { activeTarget -> activeTarget.matchesRequest(profileId, protocolOptionId) }
                ?: error("active vpn server target unavailable")
        val target = activeTarget.target
        require(target.transport == VpnHealthProbeTransport.TCP) {
            "server ping unavailable for ${target.transport.name.lowercase()} transport"
        }
        val upstreamNetwork = currentUpstreamNetwork() ?: error("upstream network unavailable")
        val vpnNetworkHandle = currentVpnNetwork()?.networkHandle
        require(shouldUseNetworkForDirectServerPing(upstreamNetwork.networkHandle, vpnNetworkHandle)) {
            "server ping upstream network is vpn"
        }
        return withContext(Dispatchers.IO) {
            measureServerTcpConnectLatency(
                host = target.host,
                port = target.port,
                resolvedAddress = activeTarget.resolvedAddress,
                timeoutMs = timeoutMs,
                network = upstreamNetwork,
            )
        }
    }

    private fun measureServerTcpConnectLatency(
        host: String,
        port: Int,
        resolvedAddress: InetAddress?,
        timeoutMs: Long,
        network: Network?,
    ): Long {
        val address = resolvedAddress ?: resolveServerPingAddress(host, network)
        val startedAt = SystemClock.elapsedRealtime()
        // Availability probe only: opens a bounded TCP connect to the configured server target and sends no payload.
        Socket().use { socket ->
            check(protectDirectSocket(socket)) { "server tcp ping socket protect failed" }
            network?.bindSocket(socket)
            socket.soTimeout = timeoutMs.toInt()
            socket.connect(
                InetSocketAddress(address, port),
                timeoutMs.toInt(),
            )
        }
        return (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(1L)
    }

    private class LatencyProbeAttempts {
        private val successfulLatencies = mutableListOf<Long>()
        var lastFailure: Throwable? = null
            private set

        suspend fun recordEndpointLatencies(measure: suspend (String) -> Long): Long? {
            latencyProbeEndpoints().forEach { endpoint ->
                val attempt = runCatching { measure(endpoint) }
                if (attempt.isSuccess) {
                    successfulLatencies += attempt.getOrThrow()
                } else {
                    lastFailure = attempt.exceptionOrNull()
                }
            }
            return representativeLatencyMs(successfulLatencies)
        }
    }
}

private fun activeTrafficModeForLatency(currentSnapshot: ConnectionSnapshot): TrafficMode =
    currentSnapshot.state
        .takeIf { it in ACTIVE_CONNECTION_STATES }
        ?.let { currentSnapshot.trafficMode }
        ?: throw RuntimeFailureException(
            RuntimeFailureCode.ACTIVE_CONNECTION_REQUIRED,
            "active connection is required for latency measurement",
        )

private fun isConnectedTunnel(
    trafficMode: TrafficMode,
    currentSnapshot: ConnectionSnapshot,
): Boolean =
    trafficMode == TrafficMode.TUNNEL && currentSnapshot.state in ACTIVE_CONNECTION_STATES

private fun latencyProxyAccess(
    settings: Settings,
    trafficMode: TrafficMode,
    useRuntimeProxyForTunnel: Boolean,
): HttpProxyAccess? =
    when {
        trafficMode == TrafficMode.PROXY -> settings.preferredAppProxyAccess()
        useRuntimeProxyForTunnel -> settings.tunnelRuntimeProxyAccess()
        else -> null
    }

private fun latencyProbeFailure(lastFailure: Throwable?): RuntimeFailureException =
    (lastFailure as? RuntimeFailureException)
        ?: RuntimeFailureException(
            RuntimeFailureCode.LATENCY_PROBE_FAILED,
            "latency probe failed",
            lastFailure,
        )

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

private fun resolveServerPingAddress(
    host: String,
    network: Network?,
): InetAddress =
    resolveServerPingAddresses(
        host = host,
        primaryResolver = { hostname ->
            network?.getAllByName(hostname)?.toList()
                ?: InetAddress.getAllByName(hostname).toList()
        },
        fallback = network?.let(::NetworkBoundPublicDnsFallback) ?: PublicDohDnsFallback,
    ).first()

internal fun resolveServerPingAddresses(
    host: String,
    primaryResolver: (String) -> List<InetAddress>,
    fallback: PublicDnsFallback = PublicDohDnsFallback,
): List<InetAddress> =
    PublicRemoteDns(
        delegate = primaryResolver,
        fallback = fallback,
    ).lookup(host)

private class NetworkBoundPublicDnsFallback(
    private val network: Network,
) : PublicDnsFallback {
    override fun lookup(hostname: String): List<InetAddress> =
        PublicDohDnsFallback.lookupWithConnectionFactory(hostname) { url ->
            network.openConnection(url) as HttpsURLConnection
        }
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
            LatencyProbeMethod.ICMP,
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

internal fun shouldPreferVpnBoundTunnelLatency(
    settings: Settings,
    snapshot: ConnectionSnapshot,
    androidValidatedVpnNetwork: Boolean,
): Boolean =
    snapshot.trafficMode == TrafficMode.TUNNEL &&
        snapshot.state in ACTIVE_CONNECTION_STATES &&
        settings.shouldPreferVpnBoundIpRefresh(snapshot, androidValidatedVpnNetwork)

internal fun runtimeProxyTunnelLatencyCallTimeoutMs(timeoutMs: Long): Long =
    timeoutMs
        .coerceAtLeast(1L)
        .coerceAtMost(RUNTIME_PROXY_TUNNEL_LATENCY_CALL_TIMEOUT_MS)

internal fun shouldUseNetworkForDirectServerPing(
    upstreamNetworkHandle: Long?,
    vpnNetworkHandle: Long?,
): Boolean =
    upstreamNetworkHandle != null &&
        vpnNetworkHandle != null &&
        upstreamNetworkHandle != vpnNetworkHandle

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

private const val RUNTIME_PROXY_TUNNEL_LATENCY_CALL_TIMEOUT_MS = 2_000L
