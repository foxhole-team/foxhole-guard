package com.foxhole.core.runtime
import android.net.Network
import android.os.SystemClock
import com.foxhole.core.model.ACTIVE_CONNECTION_STATES
import com.foxhole.core.model.ConnectionSnapshot
import com.foxhole.core.model.LatencyProbeMethod
import com.foxhole.core.model.RuntimeFailureCode
import com.foxhole.core.model.RuntimeFailureException
import com.foxhole.core.model.Settings
import com.foxhole.core.model.TrafficMode
import com.foxhole.core.runtime.network.HttpProxyAccess
import com.foxhole.core.runtime.network.IpInfoRepository
import com.foxhole.core.runtime.network.PublicDnsFallback
import com.foxhole.core.runtime.network.PublicDohDnsFallback
import com.foxhole.core.runtime.network.PublicRemoteDns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import kotlin.math.roundToLong

class ConnectionTelemetryProbe(
    private val settingsRepository: RuntimeSettings,
    private val ipInfoRepository: IpInfoRepository,
    private val snapshot: StateFlow<ConnectionSnapshot>,
    private val currentVpnNetwork: () -> Network?,
    private val currentUpstreamNetwork: () -> Network?,
    private val currentVpnInterfaceName: (Network) -> String?,
    private val isVpnNetworkValidated: (Network) -> Boolean,
    private val activeServerPingTarget: () -> ActiveServerPingTarget?,
    private val protectDirectSocket: (Socket) -> Boolean,
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
        val upstreamNetwork = currentUpstreamNetwork()
        val vpnNetworkHandle = currentVpnNetwork()?.networkHandle
        if (upstreamNetwork != null) {
            require(shouldUseNetworkForDirectServerPing(upstreamNetwork.networkHandle, vpnNetworkHandle)) {
                "server ping upstream network is vpn"
            }
        }
        if (target.transport != VpnHealthProbeTransport.TCP) {
            // UDP transports (WireGuard, Hysteria2, TUIC) expose no TCP handshake to time — an
            // ICMP echo to the SAME server host over the upstream interface is the honest
            // equivalent, reusing the exact ping machinery of the ICMP tunnel-latency method.
            return withContext(Dispatchers.IO) {
                measureServerIcmpLatency(
                    host = target.host,
                    resolvedAddress = activeTarget.resolvedAddress,
                    timeoutMs = timeoutMs,
                    network = upstreamNetwork,
                )
            }
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

    private suspend fun measureServerIcmpLatency(
        host: String,
        resolvedAddress: InetAddress?,
        timeoutMs: Long,
        network: Network?,
    ): Long {
        val address =
            resolvedAddress
                ?: runCatching { resolveServerPingAddress(host, network) }.getOrNull()
                ?: error("server host is unresolvable for icmp ping")
        val interfaceName = network?.let { currentVpnInterfaceName(it) }
        val command =
            icmpPingCommand(
                host = address.hostAddress ?: host,
                timeoutMs = timeoutMs,
                interfaceName = interfaceName,
            )
        val startedAt = SystemClock.elapsedRealtime()
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val finished = process.waitFor(timeoutMs.coerceAtLeast(1L), TimeUnit.MILLISECONDS)
        val elapsedMs = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(1L)
        if (!finished) {
            process.destroyForcibly()
            error("server icmp ping timed out")
        }
        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (process.exitValue() != 0) {
            error("server icmp ping failed")
        }
        return parseIcmpPingLatencyMs(output) ?: elapsedMs
    }

    @Suppress("ThrowsCount")
    private fun measureServerTcpConnectLatency(
        host: String,
        port: Int,
        resolvedAddress: InetAddress?,
        timeoutMs: Long,
        network: Network?,
    ): Long {
        val resolvedAddresses =
            runCatching { resolveServerPingAddresses(host, network) }
                .getOrElse { error ->
                    if (resolvedAddress == null) {
                        throw error
                    }
                    emptyList()
                }
        val addressCandidates =
            serverPingAddressCandidates(
                resolvedAddress = resolvedAddress,
                resolvedAddresses = resolvedAddresses,
            )
        var firstFailure: Throwable? = null
        addressCandidates.forEach { address ->
            val attempt =
                runCatching {
                    measureServerTcpConnectLatencyCandidate(
                        address = address,
                        port = port,
                        timeoutMs = timeoutMs,
                        network = network,
                    )
                }
            attempt.getOrNull()?.let { return it }
            val failure = attempt.exceptionOrNull()
            if (failure !is IOException) {
                throw failure ?: error("server tcp ping failed")
            }
            if (firstFailure == null) {
                firstFailure = failure
            } else {
                firstFailure.addSuppressed(failure)
            }
        }
        throw firstFailure ?: error("server tcp ping target unavailable")
    }

    private fun measureServerTcpConnectLatencyCandidate(
        address: InetAddress,
        port: Int,
        timeoutMs: Long,
        network: Network?,
    ): Long {
        if (network != null) {
            val boundAttempt =
                runCatching {
                    measureServerTcpConnectLatencyAttempt(
                        address = address,
                        port = port,
                        timeoutMs = timeoutMs,
                        network = network,
                    )
                }
            boundAttempt.getOrNull()?.let { return it }
            val boundError = boundAttempt.exceptionOrNull()
            if (boundError !is IOException) {
                throw boundError ?: error("server tcp ping failed")
            }
            return runCatching {
                measureServerTcpConnectLatencyAttempt(
                    address = address,
                    port = port,
                    timeoutMs = timeoutMs,
                    network = null,
                )
            }.getOrElse { fallbackError ->
                fallbackError.addSuppressed(boundError)
                throw fallbackError
            }
        }
        return measureServerTcpConnectLatencyAttempt(
            address = address,
            port = port,
            timeoutMs = timeoutMs,
            network = null,
        )
    }

    private fun measureServerTcpConnectLatencyAttempt(
        address: InetAddress,
        port: Int,
        timeoutMs: Long,
        network: Network?,
    ): Long {
        val startedAt = SystemClock.elapsedRealtime()
        // Availability probe only: opens a bounded TCP connect to the configured server target and sends no payload.
        Socket().use { socket ->
            if (network == null) {
                check(protectDirectSocket(socket)) { "server tcp ping socket protect failed" }
            } else {
                network.bindSocket(socket)
            }
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
    resolveServerPingAddresses(host, network).first()

private fun resolveServerPingAddresses(
    host: String,
    network: Network?,
): List<InetAddress> =
    resolveServerPingAddresses(
        host = host,
        primaryResolver = { hostname ->
            network?.getAllByName(hostname)?.toList()
                ?: InetAddress.getAllByName(hostname).toList()
        },
        fallback = network?.let(::NetworkBoundPublicDnsFallback) ?: PublicDohDnsFallback,
    )

fun serverPingAddressCandidates(
    resolvedAddress: InetAddress?,
    resolvedAddresses: List<InetAddress>,
): List<InetAddress> =
    (listOfNotNull(resolvedAddress) + resolvedAddresses)
        .distinctBy { address -> address.hostAddress.orEmpty().ifBlank { address.hostName } }

fun resolveServerPingAddresses(
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

fun effectiveLatencyProbeMethod(
    trafficMode: TrafficMode,
    configuredMethod: LatencyProbeMethod,
): LatencyProbeMethod =
    when (trafficMode) {
        TrafficMode.TUNNEL -> configuredMethod
        TrafficMode.PROXY -> LatencyProbeMethod.HTTP
    }

fun latencyProbeMethodOrder(
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

fun shouldUseRuntimeProxyForTunnelLatency(
    trafficMode: TrafficMode,
    snapshot: ConnectionSnapshot,
    settings: Settings,
): Boolean =
    trafficMode == TrafficMode.TUNNEL &&
        snapshot.state in ACTIVE_CONNECTION_STATES &&
        settings.requiresStrictRuntimeProxyIpRefresh(snapshot)

fun shouldPreferVpnBoundTunnelLatency(
    settings: Settings,
    snapshot: ConnectionSnapshot,
    androidValidatedVpnNetwork: Boolean,
): Boolean =
    snapshot.trafficMode == TrafficMode.TUNNEL &&
        snapshot.state in ACTIVE_CONNECTION_STATES &&
        settings.shouldPreferVpnBoundIpRefresh(snapshot, androidValidatedVpnNetwork)

fun runtimeProxyTunnelLatencyCallTimeoutMs(timeoutMs: Long): Long =
    timeoutMs
        .coerceAtLeast(1L)
        .coerceAtMost(RUNTIME_PROXY_TUNNEL_LATENCY_CALL_TIMEOUT_MS)

fun shouldUseNetworkForDirectServerPing(
    upstreamNetworkHandle: Long?,
    vpnNetworkHandle: Long?,
): Boolean =
    upstreamNetworkHandle != null &&
        upstreamNetworkHandle != vpnNetworkHandle

fun icmpPingCommand(
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

fun parseIcmpPingLatencyMs(output: String): Long? {
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
