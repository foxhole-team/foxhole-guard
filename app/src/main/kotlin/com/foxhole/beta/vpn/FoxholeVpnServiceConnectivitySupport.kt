package com.foxhole.beta.vpn

import android.net.Network
import android.net.NetworkCapabilities
import androidx.core.content.getSystemService
import com.foxhole.beta.R
import com.foxhole.beta.core.model.AutoConnectReasonCode
import com.foxhole.beta.core.model.ConnectionSnapshot
import com.foxhole.beta.core.model.ConnectionState
import com.foxhole.beta.core.model.ConnectivityHealthState
import com.foxhole.beta.core.model.IpInfo
import com.foxhole.beta.core.model.NotificationSnapshot
import com.foxhole.beta.core.model.ProtocolHint
import com.foxhole.beta.core.model.RuntimeFailureCode
import com.foxhole.beta.core.model.RuntimeFailureException
import com.foxhole.beta.core.model.TrafficMode
import com.foxhole.beta.core.model.TrafficSnapshot
import com.foxhole.beta.core.model.VpnSession
import com.foxhole.beta.core.model.isUdpTransport
import com.foxhole.beta.core.network.IpInfoFetchMode
import com.foxhole.beta.core.network.mergeIpInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

internal suspend fun FoxholeVpnService.refreshVpnIpInfoInternal(
    callTimeoutMs: Long,
    network: Network? = null,
    expectedFreshVpnNetworkHandle: Long? = null,
): IpInfo {
    val endpoint = container.settingsRepository.current().connection.ipInfoEndpoint
    val vpnNetwork =
        network
            ?: awaitVpnNetworkOrNull(
                FoxholeVpnService.VPN_NETWORK_WAIT_TIMEOUT_MS,
                excludedHandle = expectedFreshVpnNetworkHandle,
            )
            ?: throw RuntimeFailureException(RuntimeFailureCode.VPN_NETWORK_MISSING, "vpn network unavailable")
    val requestNetwork = network ?: tunnelValidationRequestNetwork(vpnNetwork)
    val resolverNetwork = currentUpstreamNetworkOrNull()
    val remoteDnsServers = VpnDnsServerSelector.remoteDnsServerAddresses(activeSession?.configJson)
    val info =
        if (shouldPreferIpv4TunnelValidation(activeSession?.protocolHint, activeSession?.configJson)) {
            container.ipInfoRepository.fetchIpv4(
                endpoint = endpoint,
                callTimeoutMs = callTimeoutMs,
                network = requestNetwork,
                resolverNetwork = resolverNetwork,
            ) ?: throw RuntimeFailureException(RuntimeFailureCode.DNS_FAILURE, "vpn ipv4 refresh failed")
        } else {
            container.ipInfoRepository.fetch(
                endpoint = endpoint,
                callTimeoutMs = callTimeoutMs,
                network = requestNetwork,
                resolverNetwork = resolverNetwork,
            )
        }
    return info.withDnsServers(
        localDnsServers = connectivityManager.dnsServerAddresses(vpnNetwork),
        remoteDnsServers = remoteDnsServers,
    )
}

internal suspend fun FoxholeVpnService.refreshConnectionIpInfoInternal(
    callTimeoutMs: Long,
    network: Network? = null,
): IpInfo =
    when (FoxholeVpnRuntimeBridge.snapshot.value.trafficMode) {
        TrafficMode.TUNNEL ->
            runCatchingUnlessCancelled {
                refreshTunnelRuntimeProxyIpInfo(callTimeoutMs = callTimeoutMs)
            }.getOrElse { proxyError ->
                container.diagnosticsLogger.record(
                    "ip",
                    "runtime local proxy ip refresh failed, retrying vpn process path: ${proxyError.message.orEmpty()}",
                )
                refreshVpnIpInfo(callTimeoutMs = callTimeoutMs, network = network)
            }
        TrafficMode.PROXY -> refreshProxyIpInfo(callTimeoutMs = callTimeoutMs)
    }

internal suspend fun FoxholeVpnService.refreshConnectionIpv4InfoInternal(
    callTimeoutMs: Long,
    network: Network? = null,
): IpInfo? =
    when (FoxholeVpnRuntimeBridge.snapshot.value.trafficMode) {
        TrafficMode.TUNNEL ->
            refreshTunnelRuntimeProxyIpv4Info(callTimeoutMs = callTimeoutMs)
                ?: refreshVpnIpv4Info(callTimeoutMs = callTimeoutMs, network = network)
        TrafficMode.PROXY -> refreshProxyIpv4Info(callTimeoutMs = callTimeoutMs)
    }

internal suspend fun FoxholeVpnService.refreshProxyIpInfoInternal(callTimeoutMs: Long): IpInfo {
    val settings = container.settingsRepository.current()
    val proxyAccess = settings.preferredAppProxyAccess() ?: error("proxy surface is unavailable")
    val remoteDnsServers = VpnDnsServerSelector.remoteDnsServerAddresses(activeSession?.configJson)
    return container.ipInfoRepository
        .fetch(
            endpoint = settings.connection.ipInfoEndpoint,
            callTimeoutMs = callTimeoutMs,
            proxy = proxyAccess,
        ).withDnsServers(
            localDnsServers = emptyList(),
            remoteDnsServers = remoteDnsServers,
        )
}

internal suspend fun FoxholeVpnService.refreshTunnelRuntimeProxyIpInfoInternal(callTimeoutMs: Long): IpInfo {
    val settings = container.settingsRepository.current()
    val remoteDnsServers = VpnDnsServerSelector.remoteDnsServerAddresses(activeSession?.configJson)
    return container.ipInfoRepository
        .fetch(
            endpoint = settings.connection.ipInfoEndpoint,
            callTimeoutMs = callTimeoutMs,
            proxy = settings.tunnelRuntimeProxyAccess(),
            resolverNetwork = currentUpstreamNetworkOrNull(),
        ).withDnsServers(
            localDnsServers = emptyList(),
            remoteDnsServers = remoteDnsServers,
        )
}

internal suspend fun FoxholeVpnService.refreshVpnIpv4InfoInternal(
    callTimeoutMs: Long,
    network: Network? = null,
): IpInfo? {
    val endpoint = container.settingsRepository.current().connection.ipInfoEndpoint
    val vpnNetwork = network ?: awaitVpnNetworkOrNull(FoxholeVpnService.VPN_NETWORK_WAIT_TIMEOUT_MS) ?: return null
    val requestNetwork = network ?: tunnelValidationRequestNetwork(vpnNetwork)
    val resolverNetwork = currentUpstreamNetworkOrNull()
    val remoteDnsServers = VpnDnsServerSelector.remoteDnsServerAddresses(activeSession?.configJson)
    val info =
        container.ipInfoRepository.fetchIpv4(
            endpoint = endpoint,
            callTimeoutMs = callTimeoutMs,
            network = requestNetwork,
            resolverNetwork = resolverNetwork,
        )
    return info?.withDnsServers(
        localDnsServers = connectivityManager.dnsServerAddresses(vpnNetwork),
        remoteDnsServers = remoteDnsServers,
    )
}

internal suspend fun FoxholeVpnService.refreshProxyIpv4InfoInternal(callTimeoutMs: Long): IpInfo? {
    val settings = container.settingsRepository.current()
    val proxyAccess = settings.preferredAppProxyAccess() ?: return null
    val remoteDnsServers = VpnDnsServerSelector.remoteDnsServerAddresses(activeSession?.configJson)
    return container.ipInfoRepository
        .fetchIpv4(
            endpoint = settings.connection.ipInfoEndpoint,
            callTimeoutMs = callTimeoutMs,
            proxy = proxyAccess,
        )?.withDnsServers(
            localDnsServers = emptyList(),
            remoteDnsServers = remoteDnsServers,
        )
}

internal suspend fun FoxholeVpnService.refreshTunnelRuntimeProxyIpv4InfoInternal(callTimeoutMs: Long): IpInfo? {
    val settings = container.settingsRepository.current()
    val remoteDnsServers = VpnDnsServerSelector.remoteDnsServerAddresses(activeSession?.configJson)
    return container.ipInfoRepository
        .fetchIpv4(
            endpoint = settings.connection.ipInfoEndpoint,
            callTimeoutMs = callTimeoutMs,
            proxy = settings.tunnelRuntimeProxyAccess(),
            resolverNetwork = currentUpstreamNetworkOrNull(),
        )?.withDnsServers(
            localDnsServers = emptyList(),
            remoteDnsServers = remoteDnsServers,
        )
}

internal fun FoxholeVpnService.scheduleValidationInternal(
    session: VpnSession,
    failOnFailure: Boolean,
    expectedFreshVpnNetworkHandle: Long? = null,
    onSuccess: (Network) -> Unit,
) {
    validationJob?.cancel()
    val job =
        scope.launch(Dispatchers.Main.immediate) {
            val validation = validateTunnelConnectivity(expectedFreshVpnNetworkHandle)
            if (activeSession?.profileId != session.profileId) {
                return@launch
            }
            if (validation.isSuccess) {
                container.diagnosticsLogger.record("dns", "post-start probe passed")
                onSuccess(validation.getOrThrow())
            } else {
                val message = validation.exceptionOrNull()?.message ?: getString(R.string.error_dns_probe_failed)
                container.diagnosticsLogger.record("dns", "post-start probe failed: $message")
                if (failOnFailure) {
                    fail(
                        message = message,
                        reasonCode = AutoConnectReasonCode.DNS_FAILURE,
                    )
                } else {
                    scheduleAutoReconnect(reason = "post_network_validation_failed")
                }
            }
        }
    validationJob = job
}

internal suspend fun FoxholeVpnService.validateTunnelConnectivityInternal(
    expectedFreshVpnNetworkHandle: Long? = null,
): Result<Network> =
    withContext(Dispatchers.IO) {
        val validationStartedAt = System.currentTimeMillis()
        val currentSession = activeSession
        val activeProtocolHint = currentSession?.protocolHint
        val validationPolicyContext = tunnelValidationPolicyContextFor(PrivateDnsSettings.current(this@validateTunnelConnectivityInternal))
        val preferIpv4Validation = shouldPreferIpv4TunnelValidation(activeProtocolHint, currentSession?.configJson)
        val validationTimeoutMs =
            FoxholeVpnService.CONNECTIVITY_PROBE_TOTAL_TIMEOUT_MS +
                maxTunnelValidationGraceTimeoutMs(activeProtocolHint)
        container.diagnosticsLogger.recordStructured(
            "dns",
            "Tunnel validation started",
            activeProtocolHint?.name?.lowercase(),
            if (preferIpv4Validation) "address_family=ipv4" else null,
            "timeout_ms=$validationTimeoutMs",
        )
        container.diagnosticsLogger.recordStructured(
            "runtime",
            "Runtime validation started",
            *runtimeValidationDiagnosticFields(currentSession),
            "validation_result=pending",
        )
        val result =
            TunnelConnectivityProbe.run(
                attempts = FoxholeVpnService.CONNECTIVITY_PROBE_ATTEMPTS,
                initialDelayMs = FoxholeVpnService.CONNECTIVITY_PROBE_INITIAL_DELAY_MS,
                retryDelayMs = FoxholeVpnService.CONNECTIVITY_PROBE_RETRY_DELAY_MS,
                timeoutMs = validationTimeoutMs,
                onFailure = { attemptIndex, error ->
                    container.diagnosticsLogger.record(
                        "dns",
                        "probe attempt ${attemptIndex + 1}/${FoxholeVpnService.CONNECTIVITY_PROBE_ATTEMPTS} failed: ${error.message.orEmpty()}",
                    )
                },
            ) {
                val vpnNetwork =
                    awaitVpnNetworkOrNull(
                        FoxholeVpnService.CONNECTIVITY_PROBE_NETWORK_WAIT_TIMEOUT_MS,
                        excludedHandle = expectedFreshVpnNetworkHandle,
                    ) ?: error("vpn network unavailable")
                val runtimeProxyProbe =
                    runCatchingUnlessCancelled {
                        probeConnectivityEndpointsOverLocalProxy(
                            proxy = container.settingsRepository.current().tunnelRuntimeProxyAccess(),
                            callTimeoutMs = FoxholeVpnService.CONNECTIVITY_PROBE_CALL_TIMEOUT_MS,
                        )
                    }
                if (runtimeProxyProbe.isSuccess) {
                    container.diagnosticsLogger.record("dns", "tunnel runtime proxy passed egress validation")
                    scope.launch(Dispatchers.IO) {
                        refreshValidatedTunnelIpInfoBestEffort(vpnNetwork)
                    }
                    return@run vpnNetwork
                }
                container.diagnosticsLogger.record(
                    "dns",
                    "tunnel runtime proxy egress validation failed: ${runtimeProxyProbe.exceptionOrNull()?.message.orEmpty()}",
                )
                if (FoxholeVpnRuntimeBridge.snapshot.value.trafficMode == TrafficMode.TUNNEL) {
                    throw (runtimeProxyProbe.exceptionOrNull() ?: IllegalStateException("runtime proxy egress failed"))
                }
                val requestNetwork = tunnelValidationRequestNetwork(vpnNetwork)
                val resolverNetwork = currentUpstreamNetworkOrNull()
                val androidValidatedEarly = isVpnNetworkValidated(vpnNetwork)
                if (androidValidatedEarly) {
                    container.diagnosticsLogger.record(
                        "dns",
                        "vpn network has Android validation; collecting tunnel evidence before connected state",
                    )
                    if (
                        tryAcceptEarlyAndroidValidatedVpnNetwork(
                            vpnNetwork = vpnNetwork,
                            validationStartedAt = validationStartedAt,
                            context = validationPolicyContext,
                        )
                    ) {
                        return@run vpnNetwork
                    }
                }
                if (
                    activeProtocolHint?.isUdpTransport() == true &&
                    tryAcceptEarlyValidatedVpnLiteralEndpoint(
                        vpnNetwork = vpnNetwork,
                        requestNetwork = requestNetwork,
                        validationStartedAt = validationStartedAt,
                        context = validationPolicyContext,
                    )
                ) {
                    return@run vpnNetwork
                }
                if (acceptsTunnelValidationProbe(
                        TunnelValidationProbeKind.DNS_INDEPENDENT_LITERAL_IP,
                        validationPolicyContext,
                    )
                ) {
                    val dnsIndependentValidation =
                        runCatchingUnlessCancelled {
                            probeDnsIndependentConnectivityFallback(
                                callTimeoutMs = FoxholeVpnService.CONNECTIVITY_PROBE_CALL_TIMEOUT_MS,
                                network = requestNetwork,
                            )
                        }
                    if (dnsIndependentValidation.isSuccess) {
                        container.diagnosticsLogger.record(
                            "dns",
                            "dns-independent public reachability probe accepted for strict private dns",
                        )
                        scope.launch(Dispatchers.IO) {
                            refreshValidatedTunnelIpInfoBestEffort(vpnNetwork)
                        }
                        return@run vpnNetwork
                    }
                    container.diagnosticsLogger.record(
                        "dns",
                        "dns-independent public reachability probe failed before dns validation: ${dnsIndependentValidation.exceptionOrNull()?.message.orEmpty()}",
                    )
                }
                val earlyEndpointProbe =
                    runCatchingUnlessCancelled {
                        probeConnectivityEndpoints(
                            callTimeoutMs = FoxholeVpnService.CONNECTIVITY_LITERAL_PROBE_CALL_TIMEOUT_MS,
                            network = requestNetwork,
                            resolverNetwork = resolverNetwork,
                            preferIpv4 = preferIpv4Validation,
                        )
                    }
                if (earlyEndpointProbe.isSuccess) {
                    container.diagnosticsLogger.record("dns", "vpn network passed validation endpoint probe")
                    scope.launch(Dispatchers.IO) {
                        refreshValidatedTunnelIpInfoBestEffort(vpnNetwork)
                    }
                    return@run vpnNetwork
                }
                container.diagnosticsLogger.record(
                    "dns",
                    "vpn-bound validation endpoint probe failed before ip refresh: ${earlyEndpointProbe.exceptionOrNull()?.message.orEmpty()}",
                )
                val ipRefresh =
                    runCatchingUnlessCancelled {
                        val endpoint = container.settingsRepository.current().connection.ipInfoEndpoint
                        val remoteDnsServers = VpnDnsServerSelector.remoteDnsServerAddresses(currentSession?.configJson)
                        val info =
                            if (preferIpv4Validation) {
                                container.ipInfoRepository.fetchIpv4(
                                    endpoint = endpoint,
                                    callTimeoutMs = FoxholeVpnService.CONNECTIVITY_PROBE_CALL_TIMEOUT_MS,
                                    network = requestNetwork,
                                    resolverNetwork = resolverNetwork,
                                ) ?: error("vpn ipv4 refresh failed")
                            } else {
                                container.ipInfoRepository.fetch(
                                    endpoint = endpoint,
                                    callTimeoutMs = FoxholeVpnService.CONNECTIVITY_PROBE_CALL_TIMEOUT_MS,
                                    network = requestNetwork,
                                    resolverNetwork = resolverNetwork,
                                    mode = IpInfoFetchMode.ENTRY_QUICK,
                                )
                            }
                        info.withDnsServers(
                            localDnsServers = connectivityManager.dnsServerAddresses(vpnNetwork),
                            remoteDnsServers = remoteDnsServers,
                        )
                    }.recoverCatchingUnlessCancelled { primaryError ->
                        val endpoint = container.settingsRepository.current().connection.ipInfoEndpoint
                        val remoteDnsServers = VpnDnsServerSelector.remoteDnsServerAddresses(currentSession?.configJson)
                        val ipv4Info =
                            container.ipInfoRepository.fetchIpv4(
                                endpoint = endpoint,
                                callTimeoutMs = FoxholeVpnService.CONNECTIVITY_PROBE_CALL_TIMEOUT_MS,
                                network = requestNetwork,
                                resolverNetwork = resolverNetwork,
                            ) ?: throw primaryError
                        ipv4Info.withDnsServers(
                            localDnsServers = connectivityManager.dnsServerAddresses(vpnNetwork),
                            remoteDnsServers = remoteDnsServers,
                        )
                    }
                if (ipRefresh.isSuccess) {
                    FoxholeVpnRuntimeBridge.updateIpInfo(ipRefresh.getOrThrow())
                    container.diagnosticsLogger.record("dns", "vpn network passed in-process ip refresh")
                    return@run vpnNetwork
                }
                val androidValidated = isVpnNetworkValidated(vpnNetwork)
                val evidence = inspectValidatedTunnelEvidence(validationStartedAt)
                val ipErrorMessage = ipRefresh.exceptionOrNull()?.message.orEmpty()
                container.diagnosticsLogger.record(
                    "dns",
                    if (androidValidated) {
                        "vpn network validated by android; vpn-bound ip refresh failed, probing validation endpoints: $ipErrorMessage"
                    } else {
                        "vpn-bound ip refresh failed before android validation; probing dns readiness: $ipErrorMessage"
                    },
                )
                val endpointProbe =
                    runCatchingUnlessCancelled {
                        probeConnectivityEndpoints(
                            callTimeoutMs = FoxholeVpnService.CONNECTIVITY_PROBE_CALL_TIMEOUT_MS,
                            network = requestNetwork,
                            resolverNetwork = resolverNetwork,
                            preferIpv4 = preferIpv4Validation,
                        )
                    }
                if (endpointProbe.isFailure) {
                    val dnsIndependentFallback =
                        runCatchingUnlessCancelled {
                            probeDnsIndependentConnectivityFallback(
                                callTimeoutMs = FoxholeVpnService.CONNECTIVITY_PROBE_CALL_TIMEOUT_MS,
                                network = requestNetwork,
                            )
                        }
                    if (dnsIndependentFallback.isSuccess) {
                        if (acceptsTunnelValidationProbe(
                                TunnelValidationProbeKind.DNS_INDEPENDENT_LITERAL_IP,
                                validationPolicyContext,
                            )
                        ) {
                            container.diagnosticsLogger.record(
                                "dns",
                                "dns-independent public reachability probe accepted for strict private dns",
                            )
                            scope.launch(Dispatchers.IO) {
                                refreshValidatedTunnelIpInfoBestEffort(vpnNetwork)
                            }
                            return@run vpnNetwork
                        } else if (acceptsValidatedVpnLiteralIpEndpointProbe(
                                androidValidated = androidValidated,
                                evidence = evidence,
                                context = validationPolicyContext,
                            )
                        ) {
                            container.diagnosticsLogger.record(
                                "dns",
                                "vpn-bound literal public endpoint accepted after android validation",
                            )
                            scope.launch(Dispatchers.IO) {
                                refreshValidatedTunnelIpInfoBestEffort(vpnNetwork)
                            }
                            return@run vpnNetwork
                        } else {
                            container.diagnosticsLogger.record(
                                "dns",
                                "dns-independent public reachability probe passed but is not accepted as tunnel validation",
                            )
                        }
                    } else {
                        container.diagnosticsLogger.record(
                            "dns",
                            "dns-independent public reachability probe failed: ${dnsIndependentFallback.exceptionOrNull()?.message.orEmpty()}",
                        )
                    }
                    val gracePolicy = selectTunnelValidationGracePolicy(activeProtocolHint, evidence)
                    if (gracePolicy != null) {
                        container.diagnosticsLogger.recordStructured(
                            "dns",
                            "Validated tunnel grace retry started",
                            activeProtocolHint?.name?.lowercase(),
                            "attempts=${gracePolicy.attempts}",
                            "window_ms=${gracePolicy.totalTimeoutMs}",
                        )
                        val graceResult =
                            retryValidatedTunnelConnectivityWithGrace(
                                vpnNetwork = vpnNetwork,
                                policy = gracePolicy,
                                preferIpv4 = preferIpv4Validation,
                            )
                        if (graceResult.isSuccess) {
                            container.diagnosticsLogger.record("dns", "validated tunnel grace retry passed")
                            return@run vpnNetwork
                        }
                        container.diagnosticsLogger.record(
                            "dns",
                            "validated tunnel grace retry failed: ${graceResult.exceptionOrNull()?.message.orEmpty()}",
                        )
                    }
                    if (!androidValidated) {
                        throw (ipRefresh.exceptionOrNull() ?: endpointProbe.exceptionOrNull() ?: IllegalStateException("vpn ip refresh failed"))
                    }
                    if (acceptsAndroidValidatedVpnNetwork(androidValidated, evidence, validationPolicyContext)) {
                        container.diagnosticsLogger.record(
                            "dns",
                            "android validated vpn network accepted with tunnel activity after endpoint probes failed",
                        )
                        scope.launch(Dispatchers.IO) {
                            refreshValidatedTunnelIpInfoBestEffort(vpnNetwork)
                        }
                        return@run vpnNetwork
                    }
                    evidence?.let {
                        container.diagnosticsLogger.record(
                            "dns",
                            "validated tunnel had libbox activity, but vpn-bound endpoint probe still failed; failing closed",
                        )
                    }
                    throw (endpointProbe.exceptionOrNull() ?: IllegalStateException("connectivity probe failed"))
                }
                scope.launch(Dispatchers.IO) {
                    refreshValidatedTunnelIpInfoBestEffort(vpnNetwork)
                }
                vpnNetwork
            }
        if (result.isSuccess) {
            container.diagnosticsLogger.recordStructured(
                "runtime",
                "Runtime validation result",
                *runtimeValidationDiagnosticFields(currentSession),
                "validation_result=success",
            )
            RuntimeHealthMetrics.recordValidation(
                owner = "vpn",
                success = true,
                elapsedMs = System.currentTimeMillis() - validationStartedAt,
                diagnosticsLogger = container.diagnosticsLogger,
            )
            container.diagnosticsLogger.record("dns", "vpn network passed tunnel validation")
            return@withContext result
        }
        val validationFailure = result.exceptionOrNull()
        container.diagnosticsLogger.recordStructured(
            "runtime",
            "Runtime validation result",
            *runtimeValidationDiagnosticFields(currentSession),
            "validation_result=failure",
            "failure_class=${validationFailure?.javaClass?.simpleName ?: "unknown"}",
            "endpoint_refusal=${validationFailure.isEndpointConnectRefusal()}",
        )
        RuntimeHealthMetrics.recordValidation(
            owner = "vpn",
            success = false,
            elapsedMs = System.currentTimeMillis() - validationStartedAt,
            diagnosticsLogger = container.diagnosticsLogger,
        )
        Result.failure(IllegalStateException(getString(R.string.error_dns_probe_failed)))
    }

@Suppress("ReturnCount")
internal suspend fun FoxholeVpnService.tryAcceptEarlyAndroidValidatedVpnNetwork(
    vpnNetwork: Network,
    validationStartedAt: Long,
    context: TunnelValidationPolicyContext,
): Boolean {
    val evidence =
        withTimeoutOrNull(FoxholeVpnService.CONNECTIVITY_LITERAL_PROBE_EARLY_WINDOW_MS) {
            while (currentCoroutineContext().isActive) {
                val currentEvidence = inspectValidatedTunnelEvidence(validationStartedAt)
                if (
                    acceptsAndroidValidatedVpnNetwork(
                        androidValidated = isVpnNetworkValidated(vpnNetwork),
                        evidence = currentEvidence,
                        context = context,
                    )
                ) {
                    return@withTimeoutOrNull currentEvidence
                }
                delay(FoxholeVpnService.CONNECTIVITY_LITERAL_PROBE_POLL_MS)
            }
            null
        } ?: return false

    if (!acceptsAndroidValidatedVpnNetwork(androidValidated = true, evidence = evidence, context = context)) {
        return false
    }
    container.diagnosticsLogger.record(
        "dns",
        "android validated vpn network accepted with tunnel activity",
    )
    scope.launch(Dispatchers.IO) {
        refreshValidatedTunnelIpInfoBestEffort(vpnNetwork)
    }
    return true
}

@Suppress("ReturnCount")
internal suspend fun FoxholeVpnService.tryAcceptEarlyValidatedVpnLiteralEndpoint(
    vpnNetwork: Network,
    requestNetwork: Network?,
    validationStartedAt: Long,
    context: TunnelValidationPolicyContext,
): Boolean {
    val evidence =
        withTimeoutOrNull(FoxholeVpnService.CONNECTIVITY_LITERAL_PROBE_EARLY_WINDOW_MS) {
            while (currentCoroutineContext().isActive) {
                val currentEvidence = inspectValidatedTunnelEvidence(validationStartedAt)
                if (
                    acceptsValidatedVpnLiteralIpEndpointProbe(
                        androidValidated = isVpnNetworkValidated(vpnNetwork),
                        evidence = currentEvidence,
                        context = context,
                    )
                ) {
                    return@withTimeoutOrNull currentEvidence
                }
                delay(FoxholeVpnService.CONNECTIVITY_LITERAL_PROBE_POLL_MS)
            }
            null
        } ?: return false

    val literalProbe =
        runCatchingUnlessCancelled {
            probeDnsIndependentConnectivityFallback(
                callTimeoutMs = FoxholeVpnService.CONNECTIVITY_LITERAL_PROBE_CALL_TIMEOUT_MS,
                network = requestNetwork,
            )
        }
    if (literalProbe.isFailure) {
        container.diagnosticsLogger.record(
            "dns",
            "early vpn-bound literal public endpoint probe failed: ${literalProbe.exceptionOrNull()?.message.orEmpty()}",
        )
        return false
    }
    if (!acceptsValidatedVpnLiteralIpEndpointProbe(androidValidated = true, evidence = evidence, context = context)) {
        container.diagnosticsLogger.record(
            "dns",
            "early vpn-bound literal public endpoint probe passed but is not accepted as tunnel validation",
        )
        return false
    }
    container.diagnosticsLogger.record(
        "dns",
        "vpn-bound literal public endpoint accepted after android validation",
    )
    scope.launch(Dispatchers.IO) {
        refreshValidatedTunnelIpInfoBestEffort(vpnNetwork)
    }
    return true
}

@Suppress("TooGenericExceptionCaught")
private suspend inline fun <T> runCatchingUnlessCancelled(crossinline block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        Result.failure(error)
    }

@Suppress("ReturnCount")
private suspend inline fun <T> Result<T>.recoverCatchingUnlessCancelled(
    crossinline transform: suspend (Throwable) -> T,
): Result<T> {
    if (isSuccess) {
        return this
    }
    val failure = exceptionOrNull() ?: return this
    return runCatchingUnlessCancelled { transform(failure) }
}

internal suspend fun FoxholeVpnService.retryValidatedTunnelConnectivityWithGraceInternal(
    vpnNetwork: Network,
    policy: TunnelValidationGracePolicy,
    preferIpv4: Boolean = false,
): Result<Unit> {
    val validationPolicyContext = tunnelValidationPolicyContextFor(PrivateDnsSettings.current(this))
    return TunnelConnectivityProbe.run(
        attempts = policy.attempts,
        initialDelayMs = policy.initialDelayMs,
        retryDelayMs = policy.retryDelayMs,
        timeoutMs = policy.totalTimeoutMs,
        onFailure = { attemptIndex, error ->
            container.diagnosticsLogger.record(
                "dns",
                "grace retry ${attemptIndex + 1}/${policy.attempts} failed: ${error.message.orEmpty()}",
            )
        },
    ) {
        val requestNetwork = tunnelValidationRequestNetwork(vpnNetwork)
        val resolverNetwork = currentUpstreamNetworkOrNull()
        val ipRefresh =
            runCatchingUnlessCancelled {
                refreshVpnIpInfo(
                    callTimeoutMs = policy.callTimeoutMs,
                    network = requestNetwork,
                )
            }
        if (ipRefresh.isSuccess) {
            ipRefresh.getOrThrow()
            return@run Unit
        }
        val endpointProbe =
            runCatchingUnlessCancelled {
                probeConnectivityEndpoints(
                    callTimeoutMs = policy.callTimeoutMs,
                    network = requestNetwork,
                    resolverNetwork = resolverNetwork,
                    preferIpv4 = preferIpv4,
                )
            }
        if (endpointProbe.isFailure) {
            val dnsIndependentFallback =
                runCatchingUnlessCancelled {
                    probeDnsIndependentConnectivityFallback(
                        callTimeoutMs = policy.callTimeoutMs,
                        network = requestNetwork,
                    )
                }
            if (
                dnsIndependentFallback.isSuccess &&
                !acceptsTunnelValidationProbe(
                    TunnelValidationProbeKind.DNS_INDEPENDENT_LITERAL_IP,
                    validationPolicyContext,
                )
            ) {
                container.diagnosticsLogger.record(
                    "dns",
                    "grace retry dns-independent probe passed but is not accepted as tunnel validation",
                )
            }
            throw (endpointProbe.exceptionOrNull() ?: IllegalStateException("connectivity probe failed"))
        }
        endpointProbe.getOrThrow()
        refreshValidatedTunnelIpInfoBestEffort(vpnNetwork)
    }
}

internal fun FoxholeVpnService.inspectValidatedTunnelEvidenceInternal(validationStartedAt: Long): TunnelValidationEvidence? {
    val evidence =
        TunnelValidationEvidenceClassifier.classify(
            entries = container.diagnosticsLogger.entries.value,
            sinceMs = validationStartedAt,
        )
    evidence.fatalRuntimeMessage?.let { fatalMessage ->
        container.diagnosticsLogger.record(
            "dns",
            "validated tunnel evidence rejected due to runtime error: $fatalMessage",
        )
        return null
    }
    return evidence.takeIf(TunnelValidationEvidence::hasSuccessfulTunnelActivity)
}

internal suspend fun FoxholeVpnService.refreshValidatedTunnelIpInfoBestEffortInternal(vpnNetwork: Network) {
    runCatchingUnlessCancelled {
        val endpoint = container.settingsRepository.current().connection.ipInfoEndpoint
        val remoteDnsServers = VpnDnsServerSelector.remoteDnsServerAddresses(activeSession?.configJson)
        val info =
            if (shouldPreferIpv4TunnelValidation(activeSession?.protocolHint, activeSession?.configJson)) {
                refreshTunnelRuntimeProxyIpv4Info(
                    callTimeoutMs = FoxholeVpnService.CONNECTIVITY_PROBE_CALL_TIMEOUT_MS,
                ) ?: error("runtime proxy ipv4 refresh failed")
            } else {
                container.ipInfoRepository.fetch(
                    endpoint = endpoint,
                    callTimeoutMs = FoxholeVpnService.CONNECTIVITY_PROBE_CALL_TIMEOUT_MS,
                    proxy = container.settingsRepository.current().tunnelRuntimeProxyAccess(),
                    resolverNetwork = currentUpstreamNetworkOrNull(),
                    mode = IpInfoFetchMode.ENTRY_QUICK,
                )
            }
        info.withDnsServers(
            localDnsServers = connectivityManager.dnsServerAddresses(vpnNetwork),
            remoteDnsServers = remoteDnsServers,
        )
    }.recoverCatchingUnlessCancelled { primaryError ->
        val requestNetwork = tunnelValidationRequestNetwork(vpnNetwork)
        val resolverNetwork = currentUpstreamNetworkOrNull()
        val endpoint = container.settingsRepository.current().connection.ipInfoEndpoint
        val remoteDnsServers = VpnDnsServerSelector.remoteDnsServerAddresses(activeSession?.configJson)
        val ipv4Info =
            container.ipInfoRepository.fetchIpv4(
                endpoint = endpoint,
                callTimeoutMs = FoxholeVpnService.CONNECTIVITY_PROBE_CALL_TIMEOUT_MS,
                network = requestNetwork,
                resolverNetwork = resolverNetwork,
            ) ?: throw primaryError
        ipv4Info.withDnsServers(
            localDnsServers = connectivityManager.dnsServerAddresses(vpnNetwork),
            remoteDnsServers = remoteDnsServers,
        )
    }
        .onSuccess { info ->
            FoxholeVpnRuntimeBridge.updateIpInfo(info)
            container.diagnosticsLogger.record("ip", "validated tunnel ip refresh published to dashboard")
        }
        .onFailure { error ->
            container.diagnosticsLogger.record(
                "ip",
                "validated tunnel ip refresh deferred: ${error.message.orEmpty()}",
            )
        }
}

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
            runCatching {
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
            }.recoverCatching {
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
        container.diagnosticsLogger.record(
            "dns",
            "validation endpoint failed: $endpoint reason=${lastFailure?.message.orEmpty()}",
        )
        if (!currentCoroutineContext().isActive) {
            throw lastFailure ?: IllegalStateException("connectivity probe cancelled")
        }
    }
    throw lastFailure ?: IllegalStateException("connectivity probe failed")
}

internal fun shouldPreferIpv4TunnelValidation(
    protocolHint: ProtocolHint?,
    configJson: String?,
): Boolean {
    if (protocolHint != ProtocolHint.WIREGUARD) {
        return false
    }
    if (configJson.isNullOrBlank()) {
        return true
    }
    return runCatching {
        val root = tunnelValidationJson.parseToJsonElement(configJson).jsonObject
        val wireGuardEndpoints =
            root["endpoints"]
                ?.jsonArray
                .orEmpty()
                .map { it.jsonObject }
                .filter { endpoint ->
                    endpoint["type"]?.jsonPrimitive?.contentOrNull.equals("wireguard", ignoreCase = true)
                }
        if (wireGuardEndpoints.isEmpty()) {
            true
        } else {
            wireGuardEndpoints.none { endpoint ->
                endpoint["address"]
                    ?.jsonArray
                    .orEmpty()
                    .mapNotNull { it.jsonPrimitive.contentOrNull }
                    .any { address -> address.substringBefore('/').contains(':') }
            }
        }
    }.getOrDefault(true)
}

private val tunnelValidationJson =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

internal fun redactedRuntimeDnsShape(configJson: String?): String =
    runCatching {
        if (configJson.isNullOrBlank()) {
            "unavailable"
        } else {
            val servers = tunnelValidationJson.parseToJsonElement(configJson).jsonObject["dns"]?.jsonObject?.get("servers")?.jsonArray
            if (servers.isNullOrEmpty()) {
                "missing"
            } else {
                servers
                    .map { server -> server.jsonObject.redactedDnsServerShape() }
                    .joinToString(separator = "|")
            }
        }
    }.getOrDefault("unparseable")

private fun JsonObject.redactedDnsServerShape(): String {
    val tag =
        when (this["tag"]?.jsonPrimitive?.contentOrNull) {
            "dns-local" -> "dns-local"
            "dns-direct" -> "dns-direct"
            "dns-remote" -> "dns-remote"
            null -> "untagged"
            else -> "custom"
        }
    val type =
        when (this["type"]?.jsonPrimitive?.contentOrNull ?: this["address"]?.jsonPrimitive?.contentOrNull) {
            "local" -> "platform"
            "udp" -> "udp"
            "tcp" -> "tcp"
            "https" -> "https"
            null -> "default"
            else -> "custom"
        }
    val port = this["server_port"]?.jsonPrimitive?.contentOrNull?.takeIf { value -> value.all(Char::isDigit) } ?: "default"
    val detour =
        when (this["detour"]?.jsonPrimitive?.contentOrNull) {
            "proxy" -> "proxy"
            "direct" -> "direct"
            null -> "no_detour"
            else -> "custom_detour"
        }
    return "$tag:$type:$port:$detour"
}

private fun runtimeValidationDiagnosticFields(session: VpnSession?): Array<String?> =
    arrayOf(
        session?.protocolHint?.name?.lowercase()?.let { "protocol_hint=$it" },
        "dns_shape=${redactedRuntimeDnsShape(session?.configJson)}",
        VpnHealthProbeTargetSelector
            .select(session?.configJson)
            ?.transport
            ?.name
            ?.lowercase()
            ?.let { "probe_transport=$it" }
            ?: "probe_transport=unavailable",
    )

private fun Throwable?.isEndpointConnectRefusal(): Boolean {
    var cursor = this
    while (cursor != null) {
        if (cursor.message?.contains("Connection refused", ignoreCase = true) == true) {
            return true
        }
        cursor = cursor.cause
    }
    return false
}

internal suspend fun FoxholeVpnService.connectivityProbeEndpointsInternal(): List<String> {
    val preferredEndpoint = container.settingsRepository.current().connection.ipInfoEndpoint.trim()
    return buildList {
        preferredEndpoint.takeIf { it.isNotBlank() }?.let(::add)
        FoxholeVpnService.CONNECTIVITY_PROBE_ENDPOINTS.forEach { endpoint ->
            if (endpoint != preferredEndpoint) {
                add(endpoint)
            }
        }
    }
}

internal suspend fun FoxholeVpnService.runNotificationConnectivityProbeInternal(): Boolean =
    withContext(Dispatchers.IO) {
        val result =
            runCatchingUnlessCancelled {
                when (FoxholeVpnRuntimeBridge.snapshot.value.trafficMode) {
                    TrafficMode.TUNNEL -> {
                        val vpnNetwork =
                            runCatching { currentVpnNetwork() }
                                .getOrElse { error("vpn network unavailable") }
                        activeVpnNetworkHandle = vpnNetwork.networkHandle
                        probeConnectivityEndpointsOverLocalProxy(
                            proxy = container.settingsRepository.current().tunnelRuntimeProxyAccess(),
                            callTimeoutMs = FoxholeVpnService.NOTIFICATION_HEALTH_PROBE_TIMEOUT_MS,
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
                container.diagnosticsLogger.record(
                    "health",
                    "notification probe failed: ${error.message.orEmpty()} endpoint_refusal=${error.isEndpointConnectRefusal()}",
                )
            }.isSuccess
    }

internal suspend fun FoxholeVpnService.probeConnectivityEndpointsOverLocalProxyInternal(
    proxy: com.foxhole.beta.core.network.HttpProxyAccess,
    callTimeoutMs: Long,
) {
    var lastFailure: Throwable? = null
    connectivityProbeEndpoints().forEach { endpoint ->
        val result =
            runCatching {
                container.ipInfoRepository.probe(
                    endpoint = endpoint,
                    callTimeoutMs = callTimeoutMs,
                    proxy = proxy,
                )
            }
        if (result.isSuccess) {
            container.diagnosticsLogger.record("health", "proxy probe ok: $endpoint")
            return
        }
        lastFailure = result.exceptionOrNull()
        container.diagnosticsLogger.record(
            "health",
            "proxy probe failed: $endpoint reason=${lastFailure?.message.orEmpty()}",
        )
        if (!currentCoroutineContext().isActive) {
            throw lastFailure ?: IllegalStateException("proxy probe cancelled")
        }
    }
    throw lastFailure ?: IllegalStateException("proxy probe failed")
}

internal suspend fun FoxholeVpnService.probeDnsIndependentConnectivityFallbackInternal(
    callTimeoutMs: Long,
    network: Network? = null,
) {
    var lastFailure: Throwable? = null
    dnsIndependentConnectivityProbeTargets().forEach { target ->
        val result =
            runCatching {
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
        container.diagnosticsLogger.record(
            "dns",
            "dns-independent public reachability probe failed: ${target.host}:${target.port} reason=${lastFailure?.message.orEmpty()} timeout_ms=$callTimeoutMs",
        )
        if (!currentCoroutineContext().isActive) {
            throw lastFailure ?: IllegalStateException("dns-independent connectivity probe cancelled")
        }
    }
    throw lastFailure ?: IllegalStateException("dns-independent connectivity probe failed")
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
    if (host.isProbeIpLiteral()) {
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
    consecutiveNotificationHealthFailures = FoxholeVpnService.NOTIFICATION_HEALTH_FAILURE_THRESHOLD
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
        connectivityManager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true &&
            network.networkHandle != excludedHandle
    }

internal fun FoxholeVpnService.currentUpstreamNetworkOrNullInternal(): Network? =
    connectivityManager.activeNetwork
        ?.takeIf(::isUpstreamNetwork)
        ?: currentNetworkSnapshot().firstOrNull(::isUpstreamNetwork)

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

internal fun FoxholeVpnService.onConnectionStartedInternal(
    session: VpnSession,
    trafficMode: TrafficMode,
) {
    val previousSnapshot = FoxholeVpnRuntimeBridge.snapshot.value
    val analysisStatus = getString(R.string.notification_status_analysis)
    resetAutoReconnectState()
    RuntimeResumeStateStore.markProfileRuntime(this, trafficMode, session)
    if (trafficJob == null) {
        trafficSampler.start()
        startTrafficUpdates()
    }
    startAppTrafficStatsUpdates()
    FoxholeVpnRuntimeBridge.update(
        ConnectionSnapshot(
            state = ConnectionState.CONNECTED,
            trafficMode = trafficMode,
            profileId = session.profileId,
            profileName = session.profileName,
            protocolHint = session.protocolHint,
            protocolOptionId = session.protocolOptionId,
            message = previousSnapshot.message
                .takeIf { previousSnapshot.isSmartStartConnection && it == analysisStatus },
            isSmartStartConnection = previousSnapshot.isSmartStartConnection,
        ),
    )
    updateNotification()
    startGeoRefresh()
}

internal fun FoxholeVpnService.onTunnelValidatedInternal(
    session: VpnSession,
    vpnNetwork: Network,
) {
    activeVpnNetworkHandle = vpnNetwork.networkHandle
    registerVpnNetworkCallbackIfNeeded()
    if (FoxholeVpnRuntimeBridge.snapshot.value.state != ConnectionState.CONNECTED) {
        onConnectionStarted(session, TrafficMode.TUNNEL)
    }
    startGeoRefresh(vpnNetwork)
}

internal fun FoxholeVpnService.currentNotificationSnapshotInternal(): NotificationSnapshot {
    val connection = FoxholeVpnRuntimeBridge.snapshot.value
    val ipInfo = FoxholeVpnRuntimeBridge.ipInfo.value
    val traffic = FoxholeVpnRuntimeBridge.traffic.value
    val localGuardMode = activeLocalGuardMode
    if (localGuardMode != null) {
        val analysisStatus = getString(R.string.notification_status_analysis)
        val analysisMessage =
            connection.message
                .takeIf { connection.isSmartStartConnection && it == analysisStatus }
        return NotificationSnapshot(
            state = ConnectionState.CONNECTED,
            statusMessage = analysisMessage ?: localGuardMode.name,
            updatedAt = connection.lastChangeAt,
            isSmartStartConnection = analysisMessage != null,
        )
    }
    return NotificationSnapshot(
        profileName = connection.profileName,
        state = connection.state,
        statusMessage = connection.message,
        connectivityHealthState = notificationConnectivityHealthState,
        ipAddress = ipInfo?.ipv4 ?: ipInfo?.ip,
        countryCode = ipInfo?.countryCode,
        countryName = ipInfo?.countryName,
        trafficAvailable = traffic.available,
        txRate = traffic.txBytesPerSec,
        rxRate = traffic.rxBytesPerSec,
        txTotal = traffic.txTotalBytes,
        rxTotal = traffic.rxTotalBytes,
        updatedAt = maxOf(connection.lastChangeAt, ipInfo?.fetchedAt ?: 0L, traffic.sampledAt),
        isSmartStartConnection = connection.isSmartStartConnection,
    )
}

internal fun FoxholeVpnService.notificationCollapsedTextInternal(snapshot: NotificationSnapshot): String =
    buildNotificationStatusText(snapshot, notificationHealthText(snapshot))

internal fun FoxholeVpnService.notificationExpandedTextInternal(snapshot: NotificationSnapshot): String? =
    buildNotificationStatusText(snapshot, notificationHealthText(snapshot))

internal fun FoxholeVpnService.notificationHealthTextInternal(snapshot: NotificationSnapshot): String? =
    notificationBodyRes(snapshot)?.let(::getString)

internal suspend fun FoxholeVpnService.persistProfileTrafficInternal(
    session: VpnSession,
    traffic: TrafficSnapshot,
) {
    if (!traffic.available && traffic.rxTotalBytes <= 0L && traffic.txTotalBytes <= 0L) {
        return
    }
    container.settingsRepository.accumulateProfileTraffic(
        profileId = session.profileId,
        profileName = session.profileName,
        protocolHint = session.protocolHint,
        protocolOptionId = session.protocolOptionId,
        transport = session.runtimeTransportProtocol(),
        rxBytes = traffic.rxTotalBytes,
        txBytes = traffic.txTotalBytes,
        updatedAt = System.currentTimeMillis(),
    )
}

internal fun FoxholeVpnService.notificationStateLabelInternal(snapshot: NotificationSnapshot): String =
    when {
        snapshot.statusMessage == getString(R.string.notification_status_analysis) ->
            getString(R.string.notification_status_analysis)
        localGuardFirewallNotificationActive() -> getString(R.string.notification_status_firewall)
        activeLocalGuardMode == LocalGuardMode.JOURNAL -> getString(R.string.notification_status_journal)
        activeLocalGuardMode == LocalGuardMode.DNS -> getString(R.string.notification_status_dns_guard)
        snapshot.state == ConnectionState.CONNECTED ->
            if (snapshot.isSmartStartConnection) {
                getString(R.string.notification_status_connected_smart)
            } else {
                getString(R.string.notification_status_connected)
            }
        snapshot.state == ConnectionState.CONNECTING -> getString(R.string.notification_status_connecting)
        snapshot.state == ConnectionState.RECONNECTING -> getString(R.string.notification_status_reconnecting)
        snapshot.state == ConnectionState.ERROR -> getString(R.string.notification_status_error)
        else -> getString(R.string.notification_status_disconnected)
    }

private fun FoxholeVpnService.buildNotificationStatusText(
    snapshot: NotificationSnapshot,
    baseText: String?,
): String =
    when {
        activeLocalGuardMode != null -> baseText.orEmpty()
        snapshot.state == ConnectionState.CONNECTED && snapshot.isSmartStartConnection ->
            listOfNotNull(baseText, getString(R.string.smart_profile_tag))
                .filter(String::isNotBlank)
                .joinToString(separator = " • ")
        else -> baseText.orEmpty()
    }

private fun FoxholeVpnService.localGuardFirewallNotificationActive(): Boolean {
    val firewallGuardActive = activeLocalGuardMode == LocalGuardMode.FIREWALL
    val journalFirewallActive =
        activeLocalGuardMode == LocalGuardMode.JOURNAL &&
            container.settingsRepository.settings.value.expert.firewallEnabled
    return firewallGuardActive || journalFirewallActive
}

private fun FoxholeVpnService.localGuardNotificationBodyRes(): Int? =
    when {
        localGuardFirewallNotificationActive() -> R.string.notification_body_firewall
        activeLocalGuardMode == LocalGuardMode.JOURNAL -> R.string.notification_body_journal
        activeLocalGuardMode == LocalGuardMode.DNS -> R.string.notification_body_dns_guard
        else -> null
    }

private fun FoxholeVpnService.notificationBodyRes(snapshot: NotificationSnapshot): Int? =
    if (snapshot.statusMessage == getString(R.string.notification_status_analysis)) {
        R.string.notification_body_validating
    } else {
        localGuardNotificationBodyRes() ?: when (snapshot.state) {
            ConnectionState.CONNECTED ->
                when (snapshot.connectivityHealthState) {
                    ConnectivityHealthState.CHECKING -> R.string.notification_body_validating
                    ConnectivityHealthState.ONLINE -> R.string.notification_body_connected
                    ConnectivityHealthState.OFFLINE -> R.string.notification_body_waiting
                }
            ConnectionState.CONNECTING ->
                R.string.notification_body_waiting
            ConnectionState.RECONNECTING ->
                if (snapshot.statusMessage == getString(R.string.status_smart_start_reconnecting)) {
                    R.string.notification_body_smart_start_reconnecting
                } else {
                    R.string.notification_body_reconnecting
                }
            ConnectionState.IDLE,
            ConnectionState.ERROR,
            -> null
        }
    }

private fun String.isProbeIpLiteral(): Boolean = contains(':') || PROBE_IPV4_REGEX.matches(this)

private val PROBE_IPV4_REGEX =
    Regex(
        pattern = """^(25[0-5]|2[0-4]\d|1?\d?\d)(\.(25[0-5]|2[0-4]\d|1?\d?\d)){3}$""",
    )
