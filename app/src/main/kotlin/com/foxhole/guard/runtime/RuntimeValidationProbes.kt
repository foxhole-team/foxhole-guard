package com.foxhole.guard.runtime

import android.net.Network
import com.foxhole.core.model.CONNECTIVITY_PROBE_ENDPOINTS
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.TrafficMode
import com.foxhole.core.runtime.FoxholeVpnRuntimeBridge
import com.foxhole.core.runtime.VpnHealthProbeTarget
import com.foxhole.core.runtime.VpnHealthProbeTransport
import com.foxhole.core.runtime.dnsIndependentConnectivityProbeTargets
import com.foxhole.core.runtime.isAppOwnedNetworkBindingDenied
import com.foxhole.core.runtime.preferredAppProxyAccess
import com.foxhole.core.runtime.requiresStrictRuntimeProxyIpRefresh
import com.foxhole.core.runtime.shouldPreferIpv4TunnelValidation
import com.foxhole.core.runtime.tunnelRuntimeProxyAccess
import com.foxhole.core.runtime.tunnelValidationRequestNetwork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

// The probes validation runs against a live tunnel: endpoint reachability, the notification health
// check, the DNS-independent fallback, and resolving the session's own target.

internal suspend fun FoxholeVpnService.probeConnectivityEndpointsInternal(
    callTimeoutMs: Long = FoxholeVpnService.CONNECTIVITY_PROBE_CALL_TIMEOUT_MS,
    network: Network? = null,
    resolverNetwork: Network? = null,
    preferIpv4: Boolean = false,
) {
    var lastFailure: Throwable? = null
    connectivityProbeEndpoints().forEach { endpoint ->
        var usedIpv4 = preferIpv4
        val result =
            runRuntimeValidationCatchingUnlessCancelled {
                if (preferIpv4) {
                    container.ipInfoRepository.probeIpv4(
                        endpoint = endpoint,
                        callTimeoutMs = callTimeoutMs,
                        network = network,
                        resolverNetwork = resolverNetwork,
                    )
                } else {
                    container.ipInfoRepository.probe(
                        endpoint = endpoint,
                        callTimeoutMs = callTimeoutMs,
                        network = network,
                        resolverNetwork = resolverNetwork,
                    )
                }
            }.recoverRuntimeValidationCatchingUnlessCancelled {
                usedIpv4 = true
                container.ipInfoRepository.probeIpv4(
                    endpoint = endpoint,
                    callTimeoutMs = callTimeoutMs,
                    network = network,
                    resolverNetwork = resolverNetwork,
                )
            }
        if (result.isSuccess) {
            container.diagnosticsLogger.record(
                "dns",
                if (usedIpv4) {
                    "validation endpoint ipv4 ok: $endpoint"
                } else {
                    "validation endpoint ok: $endpoint"
                },
            )
            return
        }
        lastFailure = result.exceptionOrNull()
        container.diagnosticsLogger.recordFailure(
            "dns",
            "validation endpoint failed: $endpoint reason=${lastFailure?.message.orEmpty()}",
        )
        if (!currentCoroutineContext().isActive) {
            throw (lastFailure ?: error("connectivity probe cancelled"))
        }
    }
    throw (lastFailure ?: error("connectivity probe failed"))
}

internal suspend fun FoxholeVpnService.connectivityProbeEndpointsInternal(): List<String> =
    runtimeValidationProbeEndpoints(CONNECTIVITY_PROBE_ENDPOINTS)

internal suspend fun FoxholeVpnService.runNotificationConnectivityProbeInternal(): Boolean =
    withContext(Dispatchers.IO) {
        val result =
            runRuntimeValidationCatchingUnlessCancelled {
                when (FoxholeVpnRuntimeBridge.snapshot.value.trafficMode) {
                    TrafficMode.TUNNEL -> {
                        val vpnNetwork =
                            runCatching { currentVpnNetwork() }
                                .getOrElse { error("vpn network unavailable") }
                        activeVpnNetworkHandle = vpnNetwork.networkHandle
                        val settings = container.settingsRepository.current()
                        val runtimeProxyProbe =
                            runRuntimeValidationCatchingUnlessCancelled {
                                probeConnectivityEndpointsOverLocalProxy(
                                    proxy = settings.tunnelRuntimeProxyAccess(),
                                    callTimeoutMs = FoxholeVpnService.NOTIFICATION_HEALTH_PROBE_TIMEOUT_MS,
                                )
                            }
                        if (runtimeProxyProbe.isSuccess) {
                            return@runRuntimeValidationCatchingUnlessCancelled
                        }
                        val requestNetwork = tunnelValidationRequestNetwork(vpnNetwork)
                        container.diagnosticsLogger.recordFailure(
                            "health",
                            "proxy notification probe failed, retrying vpn-bound literal public endpoint: ${runtimeProxyProbe.exceptionOrNull()?.message.orEmpty()}",
                        )
                        val literalProbe =
                            runRuntimeValidationCatchingUnlessCancelled {
                                probeDnsIndependentConnectivityFallback(
                                    callTimeoutMs = FoxholeVpnService.CONNECTIVITY_LITERAL_PROBE_CALL_TIMEOUT_MS,
                                    network = requestNetwork,
                                )
                            }
                        if (literalProbe.isSuccess) {
                            container.diagnosticsLogger.recordFailure(
                                "health",
                                "vpn-bound literal public endpoint accepted after proxy notification probe failed",
                            )
                            return@runRuntimeValidationCatchingUnlessCancelled
                        }
                        container.diagnosticsLogger.recordFailure(
                            "health",
                            "vpn-bound literal public endpoint failed after proxy notification probe failed: ${literalProbe.exceptionOrNull()?.message.orEmpty()}",
                        )
                        if (settings.requiresStrictRuntimeProxyIpRefresh(FoxholeVpnRuntimeBridge.snapshot.value)) {
                            val currentSnapshot = FoxholeVpnRuntimeBridge.snapshot.value
                            val androidValidated = isVpnNetworkValidated(vpnNetwork)
                            val literalFailure = literalProbe.exceptionOrNull()
                            if (
                                androidValidated &&
                                currentSnapshot.state == ConnectionState.CONNECTED &&
                                isAppOwnedNetworkBindingDenied(literalFailure)
                            ) {
                                container.diagnosticsLogger.record(
                                    "health",
                                    "notification health accepted after app-owned vpn bind denial on already validated tunnel",
                                )
                                return@runRuntimeValidationCatchingUnlessCancelled
                            }
                            if (androidValidated) {
                                container.diagnosticsLogger.recordFailure(
                                    "health",
                                    "proxy notification probe failed despite android validated vpn network",
                                )
                            }
                            val strictProbeError =
                                literalFailure
                                    ?: runtimeProxyProbe.exceptionOrNull()
                                    ?: IllegalStateException("runtime proxy probe failed")
                            throw strictProbeError
                        }
                        container.diagnosticsLogger.recordFailure(
                            "health",
                            "proxy notification probe failed, retrying vpn-bound endpoint: ${runtimeProxyProbe.exceptionOrNull()?.message.orEmpty()}",
                        )
                        val preferIpv4Validation =
                            shouldPreferIpv4TunnelValidation(
                                activeSession?.protocolHint,
                                activeSession?.configJson,
                            )
                        probeConnectivityEndpoints(
                            callTimeoutMs = FoxholeVpnService.NOTIFICATION_HEALTH_PROBE_TIMEOUT_MS,
                            network = requestNetwork,
                            resolverNetwork = currentUpstreamNetworkOrNull(),
                            preferIpv4 = preferIpv4Validation,
                        )
                    }
                    TrafficMode.PROXY -> {
                        val proxyAccess = container.settingsRepository.current().preferredAppProxyAccess()
                            ?: error("proxy surface is unavailable")
                        probeConnectivityEndpointsOverLocalProxy(
                            proxy = proxyAccess,
                            callTimeoutMs = FoxholeVpnService.NOTIFICATION_HEALTH_PROBE_TIMEOUT_MS,
                        )
                    }
                }
            }
        result
            .onFailure { error ->
                container.diagnosticsLogger.recordFailure(
                    "health",
                    "notification probe failed: ${error.message.orEmpty()} endpoint_refusal=${error.isEndpointConnectRefusal()}",
                )
            }.isSuccess
    }

private val RUNTIME_VALIDATION_BOOTSTRAP_ENDPOINTS =
    listOf(
        "https://cp.cloudflare.com/generate_204",
        "https://www.gstatic.com/generate_204",
        "https://1.1.1.1/cdn-cgi/trace",
    )

internal fun runtimeValidationProbeEndpoints(fallbackEndpoints: List<String>): List<String> =
    buildList {
        RUNTIME_VALIDATION_BOOTSTRAP_ENDPOINTS.forEach { endpoint ->
            if (none { it.equals(endpoint, ignoreCase = true) }) {
                add(endpoint)
            }
        }
        fallbackEndpoints.forEach { endpoint ->
            if (none { it.equals(endpoint, ignoreCase = true) }) {
                add(endpoint)
            }
        }
    }

internal suspend fun FoxholeVpnService.probeDnsIndependentConnectivityFallbackInternal(
    callTimeoutMs: Long,
    network: Network? = null,
) {
    var lastFailure: Throwable? = null
    dnsIndependentConnectivityProbeTargets().forEach { target ->
        val result =
            runRuntimeValidationCatchingUnlessCancelled {
                probeSessionTarget(
                    target = target,
                    network = network,
                    timeoutMs = callTimeoutMs,
                )
            }
        if (result.isSuccess) {
            container.diagnosticsLogger.record(
                "dns",
                "dns-independent public reachability probe ok: ${target.host}:${target.port}",
            )
            return
        }
        lastFailure = result.exceptionOrNull()
        container.diagnosticsLogger.recordFailure(
            "dns",
            "dns-independent public reachability probe failed: ${target.host}:${target.port} reason=${lastFailure?.message.orEmpty()} timeout_ms=$callTimeoutMs",
        )
        if (!currentCoroutineContext().isActive) {
            throw (lastFailure ?: error("dns-independent connectivity probe cancelled"))
        }
    }
    throw (lastFailure ?: error("dns-independent connectivity probe failed"))
}

internal fun FoxholeVpnService.probeSessionTargetInternal(
    target: VpnHealthProbeTarget,
    network: Network? = null,
    timeoutMs: Long = FoxholeVpnService.NOTIFICATION_HEALTH_PROBE_TIMEOUT_MS,
) {
    val address = resolveProbeAddress(target.host, network)
    val timeout = timeoutMs.toInt()
    when (target.transport) {
        VpnHealthProbeTransport.TCP -> {
            // Availability probe only: bounded TCP connect to the selected public runtime target, with no secrets sent.
            (network?.socketFactory?.createSocket() ?: Socket()).use {
                it.soTimeout = timeout
                it.connect(
                    InetSocketAddress(address, target.port),
                    timeout,
                )
            }
        }

        VpnHealthProbeTransport.UDP -> {
            // Availability probe only: sends a static zero-secret readiness datagram to the selected runtime target.
            DatagramSocket().use { socket ->
                network?.bindSocket(socket)
                socket.soTimeout = timeout
                socket.connect(address, target.port)
                socket.send(
                    DatagramPacket(
                        FoxholeVpnService.UDP_HEALTH_PROBE_PAYLOAD,
                        FoxholeVpnService.UDP_HEALTH_PROBE_PAYLOAD.size,
                    ),
                )
            }
        }
    }
}

internal fun FoxholeVpnService.resolveProbeAddressInternal(
    host: String,
    network: Network? = null,
): InetAddress {
    if (host.isProbeIpLiteral) {
        return InetAddress.getByName(host)
    }
    val addresses =
        if (network != null) {
            network.getAllByName(host).toList()
        } else {
            InetAddress.getAllByName(host).toList()
        }
    return addresses.firstOrNull() ?: error("probe target unavailable")
}

private val String.isProbeIpLiteral: Boolean
    get() = contains(':') || PROBE_IPV4_REGEX.matches(this)

private val PROBE_IPV4_REGEX =
    Regex(
        pattern = """^(25[0-5]|2[0-4]\d|1?\d?\d)(\.(25[0-5]|2[0-4]\d|1?\d?\d)){3}$""",
    )
