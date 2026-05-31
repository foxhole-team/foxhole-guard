package com.foxhole.beta.vpn

import android.net.Network
import com.foxhole.beta.R
import com.foxhole.beta.core.model.ConnectionSnapshot
import com.foxhole.beta.core.model.ConnectionState
import com.foxhole.beta.core.model.IpInfo
import com.foxhole.beta.core.model.ProtocolHint
import com.foxhole.beta.core.model.RuntimeFailureCode
import com.foxhole.beta.core.model.RuntimeFailureException
import com.foxhole.beta.core.model.TrafficMode
import com.foxhole.beta.core.model.VpnSession
import com.foxhole.beta.core.model.isUdpTransport
import com.foxhole.beta.core.network.IpInfoFetchMode
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
            if (!activeSession.matchesRuntimeValidationSession(session)) {
                container.diagnosticsLogger.record(
                    "dns",
                    "post-start probe ignored for stale session sessionId=${session.correlationId}",
                )
                return@launch
            }
            val validation = validateTunnelConnectivity(expectedFreshVpnNetworkHandle, session)
            if (!activeSession.matchesRuntimeValidationSession(session)) {
                container.diagnosticsLogger.record(
                    "dns",
                    "post-start probe ignored for stale session sessionId=${session.correlationId}",
                )
                return@launch
            }
            if (validation.isSuccess) {
                container.diagnosticsLogger.record("dns", "post-start probe passed")
                onSuccess(validation.getOrThrow())
            } else {
                val error = validation.exceptionOrNull()
                val message = error?.message ?: getString(R.string.error_dns_probe_failed)
                container.diagnosticsLogger.record("dns", "post-start probe failed: $message")
                if (failOnFailure) {
                    fail(
                        message = message,
                        reasonCode = runtimeValidationFailureReasonCode(
                            error = error,
                            dnsProbeFailedMessage = getString(R.string.error_dns_probe_failed),
                        ),
                    )
                } else {
                    scheduleAutoReconnect(reason = "post_network_validation_failed")
                }
            }
        }
    validationJob = job
}

@Suppress("LongMethod", "CyclomaticComplexMethod")
internal suspend fun FoxholeVpnService.validateTunnelConnectivityInternal(
    expectedFreshVpnNetworkHandle: Long? = null,
    session: VpnSession? = null,
): Result<Network> =
    withContext(Dispatchers.IO) {
        val validationStartedAt = System.currentTimeMillis()
        val currentSession = session ?: activeSession
        if (!canPublishValidationResult(currentSession)) {
            container.diagnosticsLogger.record(
                "dns",
                "runtime validation skipped for stale session sessionId=${currentSession?.correlationId.orEmpty()}",
            )
            return@withContext Result.failure(IllegalStateException("stale runtime validation session"))
        }
        val activeProtocolHint = currentSession?.protocolHint
        val validationPolicyContext =
            tunnelValidationPolicyContextFor(
                PrivateDnsSettings.current(this@validateTunnelConnectivityInternal),
            )
        val preferIpv4Validation = shouldPreferIpv4TunnelValidation(activeProtocolHint, currentSession?.configJson)
        val validationProbePlan = runtimeValidationProbePlan(currentSession)
        val validationTimeoutMs = validationProbePlan.totalTimeoutMs
        val validationDiagnostics = runtimeValidationDiagnosticFields(currentSession)
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
            validationDiagnostics.protocolHint,
            validationDiagnostics.dnsShape,
            validationDiagnostics.probeTransport,
            "validation_result=pending",
        )
        val result =
            TunnelConnectivityProbe.run(
                attempts = validationProbePlan.attempts,
                initialDelayMs = validationProbePlan.initialDelayMs,
                retryDelayMs = validationProbePlan.retryDelayMs,
                timeoutMs = validationTimeoutMs,
                onFailure = { attemptIndex, error ->
                    container.diagnosticsLogger.record(
                        "dns",
                        "probe attempt ${attemptIndex + 1}/${validationProbePlan.attempts} failed: ${error.message.orEmpty()}",
                    )
                },
                onAttemptCompleted = { attempt ->
                    container.diagnosticsLogger.recordStructured(
                        "runtime",
                        "Runtime validation probe attempt",
                        "attempt=${attempt.attemptNumber}/${validationProbePlan.attempts}",
                        "attempt_elapsed_ms=${attempt.elapsedMs}",
                        if (attempt.success) "attempt_result=success" else "attempt_result=failure",
                        attempt.failure?.let { "failure_class=${it.javaClass.simpleName}" },
                        attempt.failure?.rootCauseClassName()?.let { "failure_root=$it" },
                    )
                },
            ) {
                val vpnNetwork =
                    profileRuntimeValidationStep("await_vpn_network") {
                        awaitVpnNetworkOrNull(
                            FoxholeVpnService.CONNECTIVITY_PROBE_NETWORK_WAIT_TIMEOUT_MS,
                            excludedHandle = expectedFreshVpnNetworkHandle,
                        ) ?: error("vpn network unavailable")
                    }
                val settings = container.settingsRepository.current()
                if (settings.requiresStrictRuntimeProxyIpRefresh(FoxholeVpnRuntimeBridge.snapshot.value)) {
                    container.diagnosticsLogger.record(
                        "dns",
                        "runtime proxy egress kept out of tunnel acceptance; using vpn-bound tunnel validation",
                    )
                } else {
                    container.diagnosticsLogger.record(
                        "dns",
                        "runtime proxy egress skipped; using vpn-bound tunnel validation",
                    )
                }
                val requestNetwork = tunnelValidationRequestNetwork(vpnNetwork)
                val resolverNetwork = currentUpstreamNetworkOrNull()
                val androidValidatedEarly = isVpnNetworkValidated(vpnNetwork)
                container.diagnosticsLogger.recordStructured(
                    "runtime",
                    "Runtime validation network selected",
                    "vpn_handle=${vpnNetwork.networkHandle}",
                    expectedFreshVpnNetworkHandle?.let { "excluded_handle=$it" },
                    "android_validated=$androidValidatedEarly",
                )
                if (androidValidatedEarly) {
                    container.diagnosticsLogger.record(
                        "dns",
                        "vpn network has Android validation; still requiring vpn-bound tunnel validation",
                    )
                }
                if (
                    activeProtocolHint?.isUdpTransport() == true &&
                    tryAcceptEarlyValidatedVpnLiteralEndpoint(
                        vpnNetwork = vpnNetwork,
                        requestNetwork = requestNetwork,
                        validationStartedAt = validationStartedAt,
                        context = validationPolicyContext,
                        session = currentSession,
                    )
                ) {
                    return@run vpnNetwork
                }
                if (androidValidatedEarly && activeProtocolHint?.isUdpTransport() == true) {
                    val validatedLiteralEndpointProbe =
                        runCatchingUnlessCancelled {
                            probeDnsIndependentConnectivityFallback(
                                callTimeoutMs = FoxholeVpnService.CONNECTIVITY_LITERAL_PROBE_CALL_TIMEOUT_MS,
                                network = requestNetwork,
                            )
                        }
                    if (validatedLiteralEndpointProbe.isSuccess) {
                        container.diagnosticsLogger.record(
                            "dns",
                            "vpn-bound literal public endpoint passed before hostname validation but is not accepted as tunnel validation",
                        )
                    } else {
                        container.diagnosticsLogger.record(
                            "dns",
                            "vpn-bound literal public endpoint failed before hostname validation: ${validatedLiteralEndpointProbe.exceptionOrNull()?.message.orEmpty()}",
                        )
                    }
                }
                if (acceptsTunnelValidationProbe(
                        TunnelValidationProbeKind.DNS_INDEPENDENT_LITERAL_IP,
                        validationPolicyContext,
                    )
                ) {
                    val dnsIndependentValidation =
                        runCatchingUnlessCancelled {
                            profileRuntimeValidationStep("dns_independent_pre_dns") {
                                probeDnsIndependentConnectivityFallback(
                                    callTimeoutMs = validationProbePlan.callTimeoutMs,
                                    network = requestNetwork,
                                )
                            }
                        }
                    if (dnsIndependentValidation.isSuccess) {
                        container.diagnosticsLogger.record(
                            "dns",
                            "dns-independent public reachability probe accepted for strict private dns",
                        )
                        scope.launch(Dispatchers.IO) {
                            refreshValidatedTunnelIpInfoBestEffort(vpnNetwork, currentSession)
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
                        profileRuntimeValidationStep("endpoint_pre_ip") {
                            probeConnectivityEndpoints(
                                callTimeoutMs = validationProbePlan.literalCallTimeoutMs,
                                network = requestNetwork,
                                resolverNetwork = resolverNetwork,
                                preferIpv4 = preferIpv4Validation,
                            )
                        }
                    }
                if (earlyEndpointProbe.isSuccess) {
                    container.diagnosticsLogger.record("dns", "vpn network passed validation endpoint probe")
                    scope.launch(Dispatchers.IO) {
                        refreshValidatedTunnelIpInfoBestEffort(vpnNetwork, currentSession)
                    }
                    return@run vpnNetwork
                }
                container.diagnosticsLogger.record(
                    "dns",
                    "vpn-bound validation endpoint probe failed before ip refresh: ${earlyEndpointProbe.exceptionOrNull()?.message.orEmpty()}",
                )
                val ipRefresh =
                    runCatchingUnlessCancelled {
                        profileRuntimeValidationStep("ip_refresh_primary") {
                            val endpoint = container.settingsRepository.current().connection.ipInfoEndpoint
                            val remoteDnsServers =
                                VpnDnsServerSelector.remoteDnsServerAddresses(
                                    currentSession?.configJson,
                                )
                            val info =
                                if (preferIpv4Validation) {
                                    container.ipInfoRepository.fetchIpv4(
                                        endpoint = endpoint,
                                        callTimeoutMs = validationProbePlan.callTimeoutMs,
                                        network = requestNetwork,
                                        resolverNetwork = resolverNetwork,
                                    ) ?: error("vpn ipv4 refresh failed")
                                } else {
                                    container.ipInfoRepository.fetch(
                                        endpoint = endpoint,
                                        callTimeoutMs = validationProbePlan.callTimeoutMs,
                                        network = requestNetwork,
                                        resolverNetwork = resolverNetwork,
                                        mode = IpInfoFetchMode.ENTRY_QUICK,
                                    )
                                }
                            info.withDnsServers(
                                localDnsServers = connectivityManager.dnsServerAddresses(vpnNetwork),
                                remoteDnsServers = remoteDnsServers,
                            )
                        }
                    }.recoverCatchingUnlessCancelled { primaryError ->
                        profileRuntimeValidationStep("ip_refresh_ipv4_fallback") {
                            val endpoint = container.settingsRepository.current().connection.ipInfoEndpoint
                            val remoteDnsServers =
                                VpnDnsServerSelector.remoteDnsServerAddresses(
                                    currentSession?.configJson,
                                )
                            val ipv4Info =
                                container.ipInfoRepository.fetchIpv4(
                                    endpoint = endpoint,
                                    callTimeoutMs = validationProbePlan.callTimeoutMs,
                                    network = requestNetwork,
                                    resolverNetwork = resolverNetwork,
                                ) ?: throw primaryError
                            ipv4Info.withDnsServers(
                                localDnsServers = connectivityManager.dnsServerAddresses(vpnNetwork),
                                remoteDnsServers = remoteDnsServers,
                            )
                        }
                    }
                if (ipRefresh.isSuccess) {
                    if (canPublishValidationResult(currentSession)) {
                        FoxholeVpnRuntimeBridge.updateIpInfo(ipRefresh.getOrThrow())
                    } else {
                        container.diagnosticsLogger.record(
                            "ip",
                            "validated tunnel ip refresh ignored for stale session sessionId=${currentSession?.correlationId.orEmpty()}",
                        )
                    }
                    container.diagnosticsLogger.record("dns", "vpn network passed in-process ip refresh")
                    return@run vpnNetwork
                }
                val androidValidated = isVpnNetworkValidated(vpnNetwork)
                val evidence = inspectValidatedTunnelEvidence(validationStartedAt)
                val ipErrorMessage = ipRefresh.exceptionOrNull()?.message.orEmpty()
                container.diagnosticsLogger.record(
                    "dns",
                    if (androidValidated) {
                        "vpn network validated by android; vpn-bound ip refresh failed, probing literal public endpoints: $ipErrorMessage"
                    } else {
                        "vpn-bound ip refresh failed before android validation; probing literal public endpoints: $ipErrorMessage"
                    },
                )
                val literalEndpointProbe =
                    runCatchingUnlessCancelled {
                        profileRuntimeValidationStep("literal_after_ip") {
                            probeDnsIndependentConnectivityFallback(
                                callTimeoutMs = validationProbePlan.literalCallTimeoutMs,
                                network = requestNetwork,
                            )
                        }
                    }
                if (literalEndpointProbe.isSuccess) {
                    container.diagnosticsLogger.record(
                        "dns",
                        "vpn-bound literal public endpoint passed after ip refresh failed but is not accepted as tunnel validation",
                    )
                } else {
                    container.diagnosticsLogger.record(
                        "dns",
                        "vpn-bound literal public endpoint failed before validation endpoints: ${literalEndpointProbe.exceptionOrNull()?.message.orEmpty()}",
                    )
                }
                val endpointProbe =
                    runCatchingUnlessCancelled {
                        profileRuntimeValidationStep("endpoint_after_ip") {
                            probeConnectivityEndpoints(
                                callTimeoutMs = validationProbePlan.callTimeoutMs,
                                network = requestNetwork,
                                resolverNetwork = resolverNetwork,
                                preferIpv4 = preferIpv4Validation,
                            )
                        }
                    }
                if (endpointProbe.isFailure) {
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
                                session = currentSession,
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
                    container.diagnosticsLogger.record(
                        "dns",
                        "android validated vpn network is not accepted without vpn-bound reachability",
                    )
                    evidence?.let {
                        container.diagnosticsLogger.record(
                            "dns",
                            "validated tunnel had libbox activity, but vpn-bound endpoint probe still failed; failing closed",
                        )
                    }
                    throw (endpointProbe.exceptionOrNull() ?: IllegalStateException("connectivity probe failed"))
                }
                scope.launch(Dispatchers.IO) {
                    refreshValidatedTunnelIpInfoBestEffort(vpnNetwork, currentSession)
                }
                vpnNetwork
            }
        if (!canPublishValidationResult(currentSession)) {
            container.diagnosticsLogger.record(
                "dns",
                "runtime validation result ignored for stale session sessionId=${currentSession?.correlationId.orEmpty()}",
            )
            return@withContext Result.failure(IllegalStateException("stale runtime validation session"))
        }
        if (result.isSuccess) {
            container.diagnosticsLogger.recordStructured(
                "runtime",
                "Runtime validation result",
                validationDiagnostics.protocolHint,
                validationDiagnostics.dnsShape,
                validationDiagnostics.probeTransport,
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
            validationDiagnostics.protocolHint,
            validationDiagnostics.dnsShape,
            validationDiagnostics.probeTransport,
            "validation_result=failure",
            "failure_class=${validationFailure?.javaClass?.simpleName ?: "unknown"}",
            validationFailure.rootCauseClassName()?.let { "failure_root=$it" },
            validationFailure.tunnelConnectivityProbeTimeout()?.let {
                "timeout_attempts=${it.attemptsDone}/${validationProbePlan.attempts}"
            },
            "endpoint_refusal=${validationFailure.isEndpointConnectRefusal()}",
        )
        RuntimeHealthMetrics.recordValidation(
            owner = "vpn",
            success = false,
            elapsedMs = System.currentTimeMillis() - validationStartedAt,
            diagnosticsLogger = container.diagnosticsLogger,
        )
        Result.failure(IllegalStateException(getString(R.string.error_dns_probe_failed), validationFailure))
    }

@Suppress("ReturnCount")
internal suspend fun FoxholeVpnService.tryAcceptEarlyValidatedVpnLiteralEndpoint(
    vpnNetwork: Network,
    requestNetwork: Network?,
    validationStartedAt: Long,
    context: TunnelValidationPolicyContext,
    session: VpnSession? = null,
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
        refreshValidatedTunnelIpInfoBestEffort(vpnNetwork, session)
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
    session: VpnSession? = null,
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
            if (
                tryAcceptGraceDnsIndependentFallback(
                    vpnNetwork = vpnNetwork,
                    requestNetwork = requestNetwork,
                    policy = policy,
                    validationPolicyContext = validationPolicyContext,
                    session = session,
                )
            ) {
                return@run Unit
            }
            throw (endpointProbe.exceptionOrNull() ?: IllegalStateException("connectivity probe failed"))
        }
        endpointProbe.getOrThrow()
        refreshValidatedTunnelIpInfoBestEffort(vpnNetwork, session)
    }
}

private suspend fun FoxholeVpnService.tryAcceptGraceDnsIndependentFallback(
    vpnNetwork: Network,
    requestNetwork: Network?,
    policy: TunnelValidationGracePolicy,
    validationPolicyContext: TunnelValidationPolicyContext,
    session: VpnSession? = null,
): Boolean {
    val dnsIndependentFallback =
        runCatchingUnlessCancelled {
            probeDnsIndependentConnectivityFallback(
                callTimeoutMs = policy.callTimeoutMs,
                network = requestNetwork,
            )
        }
    return when {
        dnsIndependentFallback.isFailure -> {
            container.diagnosticsLogger.record(
                "dns",
                "grace retry dns-independent probe failed: ${dnsIndependentFallback.exceptionOrNull()?.message.orEmpty()}",
            )
            false
        }
        !acceptsTunnelValidationProbe(
            TunnelValidationProbeKind.DNS_INDEPENDENT_LITERAL_IP,
            validationPolicyContext,
        ) -> {
            container.diagnosticsLogger.record(
                "dns",
                "grace retry dns-independent probe passed but is not accepted as tunnel validation",
            )
            false
        }
        else -> {
            container.diagnosticsLogger.record(
                "dns",
                "grace retry dns-independent probe accepted for strict private dns",
            )
            refreshValidatedTunnelIpInfoBestEffort(vpnNetwork, session)
            true
        }
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

internal suspend fun FoxholeVpnService.refreshValidatedTunnelIpInfoBestEffortInternal(
    vpnNetwork: Network,
    session: VpnSession? = null,
) {
    val sessionSnapshot = session ?: activeSession
    if (!canPublishValidationResult(sessionSnapshot)) {
        container.diagnosticsLogger.record(
            "ip",
            "validated tunnel ip refresh ignored for stale session sessionId=${sessionSnapshot?.correlationId.orEmpty()}",
        )
        return
    }
    runCatchingUnlessCancelled {
        val settings = container.settingsRepository.current()
        val snapshot = FoxholeVpnRuntimeBridge.snapshot.value
        val androidValidatedVpnNetwork = isVpnNetworkValidated(vpnNetwork)
        val endpoint = activeTunnelIpRefreshEndpoint(
            configuredEndpoint = settings.connection.ipInfoEndpoint,
            androidValidatedVpnNetwork = androidValidatedVpnNetwork,
        )
        val remoteDnsServers = VpnDnsServerSelector.remoteDnsServerAddresses(sessionSnapshot?.configJson)
        val preferIpv4Validation =
            shouldPreferIpv4TunnelValidation(sessionSnapshot?.protocolHint, sessionSnapshot?.configJson)
        val info = if (settings.shouldPreferVpnBoundIpRefresh(snapshot, androidValidatedVpnNetwork)) {
            val requestNetwork = tunnelValidationRequestNetwork(vpnNetwork)
            val resolverNetwork = currentUpstreamNetworkOrNull()
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
        } else if (preferIpv4Validation) {
            container.ipInfoRepository.fetchIpv4(
                endpoint = endpoint,
                callTimeoutMs = FoxholeVpnService.CONNECTIVITY_PROBE_CALL_TIMEOUT_MS,
                proxy = settings.tunnelRuntimeProxyAccess(),
                resolverNetwork = currentUpstreamNetworkOrNull(),
            ) ?: error("runtime proxy ipv4 refresh failed")
        } else {
            container.ipInfoRepository.fetch(
                endpoint = endpoint,
                callTimeoutMs = FoxholeVpnService.CONNECTIVITY_PROBE_CALL_TIMEOUT_MS,
                proxy = settings.tunnelRuntimeProxyAccess(),
                resolverNetwork = currentUpstreamNetworkOrNull(),
                mode = IpInfoFetchMode.ENTRY_QUICK,
            )
        }
        info.withDnsServers(
            localDnsServers = connectivityManager.dnsServerAddresses(vpnNetwork),
            remoteDnsServers = remoteDnsServers,
        )
    }.recoverCatchingUnlessCancelled { primaryError ->
        val settings = container.settingsRepository.current()
        if (
            settings.requiresStrictRuntimeProxyIpRefresh(FoxholeVpnRuntimeBridge.snapshot.value) &&
            !isVpnNetworkValidated(vpnNetwork)
        ) {
            throw primaryError
        }
        val requestNetwork = tunnelValidationRequestNetwork(vpnNetwork)
        val resolverNetwork = currentUpstreamNetworkOrNull()
        val endpoint = settings.connection.ipInfoEndpoint
        val remoteDnsServers = VpnDnsServerSelector.remoteDnsServerAddresses(sessionSnapshot?.configJson)
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
            if (canPublishValidationResult(sessionSnapshot)) {
                val settings = container.settingsRepository.current()
                val snapshot = FoxholeVpnRuntimeBridge.snapshot.value
                if (settings.shouldPublishRuntimeProxyIpInfoToDashboard(snapshot)) {
                    FoxholeVpnRuntimeBridge.updateIpInfo(info)
                    container.diagnosticsLogger.record("ip", "validated tunnel ip refresh published to dashboard")
                } else {
                    container.diagnosticsLogger.record(
                        "ip",
                        "validated tunnel ip refresh kept out of dashboard while tor route is inside vpn",
                    )
                }
            } else {
                container.diagnosticsLogger.record(
                    "ip",
                    "validated tunnel ip refresh ignored for stale session sessionId=${sessionSnapshot?.correlationId.orEmpty()}",
                )
            }
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
            runCatchingUnlessCancelled {
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
            }.recoverCatchingUnlessCancelled {
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
            throw (lastFailure ?: error("connectivity probe cancelled"))
        }
    }
    throw (lastFailure ?: error("connectivity probe failed"))
}

internal fun shouldPreferIpv4TunnelValidation(
    protocolHint: ProtocolHint?,
    configJson: String?,
): Boolean =
    when {
        protocolHint != ProtocolHint.WIREGUARD -> false
        configJson.isNullOrBlank() -> true
        else ->
            runCatching {
                val root = tunnelValidationJson.parseToJsonElement(configJson).jsonObject
                val wireGuardEndpoints =
                    root["endpoints"]
                        ?.jsonArray
                        .orEmpty()
                        .map { it.jsonObject }
                        .filter { endpoint ->
                            endpoint["type"]?.jsonPrimitive?.contentOrNull.equals("wireguard", ignoreCase = true)
                        }
                wireGuardEndpoints.isEmpty() || wireGuardEndpoints.none(JsonObject::hasIpv6WireGuardAddress)
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
            val dnsObject = tunnelValidationJson.parseToJsonElement(configJson).jsonObject["dns"]?.jsonObject
            val servers = dnsObject?.get("servers")?.jsonArray
            if (servers.isNullOrEmpty()) {
                "missing"
            } else {
                servers
                    .map { server -> server.jsonObject.redactedDnsServerShape() }
                    .joinToString(separator = "|")
            }
        }
    }.getOrDefault("unparseable")

private fun JsonObject.hasIpv6WireGuardAddress(): Boolean =
    this["address"]
        ?.jsonArray
        .orEmpty()
        .mapNotNull { it.jsonPrimitive.contentOrNull }
        .any { address -> address.substringBefore('/').contains(':') }

private fun JsonObject.redactedDnsServerShape(): String =
    listOf(
        redactedDnsTag(),
        redactedDnsType(),
        redactedDnsPort(),
        redactedDnsDetour(),
    ).joinToString(separator = ":")

private fun JsonObject.redactedDnsTag(): String =
    when (this["tag"]?.jsonPrimitive?.contentOrNull) {
        "dns-local" -> "dns-local"
        "dns-direct" -> "dns-direct"
        "dns-remote" -> "dns-remote"
        null -> "untagged"
        else -> "custom"
    }

private fun JsonObject.redactedDnsType(): String =
    when (this["type"]?.jsonPrimitive?.contentOrNull ?: this["address"]?.jsonPrimitive?.contentOrNull) {
        "local" -> "platform"
        "udp" -> "udp"
        "tcp" -> "tcp"
        "https" -> "https"
        null -> "default"
        else -> "custom"
    }

private fun JsonObject.redactedDnsPort(): String =
    this["server_port"]
        ?.jsonPrimitive
        ?.contentOrNull
        ?.takeIf { value -> value.all(Char::isDigit) }
        ?: "default"

private fun JsonObject.redactedDnsDetour(): String =
    when (this["detour"]?.jsonPrimitive?.contentOrNull) {
        "proxy" -> "proxy"
        "direct" -> "direct"
        null -> "no_detour"
        else -> "custom_detour"
    }

private data class RuntimeValidationDiagnosticFields(
    val protocolHint: String?,
    val dnsShape: String,
    val probeTransport: String,
)

private fun runtimeValidationDiagnosticFields(session: VpnSession?): RuntimeValidationDiagnosticFields {
    val probeTransport =
        VpnHealthProbeTargetSelector
            .select(session?.configJson)
            ?.transport
            ?.name
            ?.lowercase()
            ?.let { "probe_transport=$it" }
            ?: "probe_transport=unavailable"
    return RuntimeValidationDiagnosticFields(
        protocolHint = session?.protocolHint?.name?.lowercase()?.let { "protocol_hint=$it" },
        dnsShape = "dns_shape=${redactedRuntimeDnsShape(session?.configJson)}",
        probeTransport = probeTransport,
    )
}

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

internal suspend fun FoxholeVpnService.connectivityProbeEndpointsInternal(): List<String> =
    runtimeValidationProbeEndpoints(FoxholeVpnService.CONNECTIVITY_PROBE_ENDPOINTS)

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
                        val settings = container.settingsRepository.current()
                        val runtimeProxyProbe =
                            runCatchingUnlessCancelled {
                                probeConnectivityEndpointsOverLocalProxy(
                                    proxy = settings.tunnelRuntimeProxyAccess(),
                                    callTimeoutMs = FoxholeVpnService.NOTIFICATION_HEALTH_PROBE_TIMEOUT_MS,
                                )
                            }
                        if (runtimeProxyProbe.isSuccess) {
                            return@runCatchingUnlessCancelled
                        }
                        val requestNetwork = tunnelValidationRequestNetwork(vpnNetwork)
                        container.diagnosticsLogger.record(
                            "health",
                            "proxy notification probe failed, retrying vpn-bound literal public endpoint: ${runtimeProxyProbe.exceptionOrNull()?.message.orEmpty()}",
                        )
                        val literalProbe =
                            runCatchingUnlessCancelled {
                                probeDnsIndependentConnectivityFallback(
                                    callTimeoutMs = FoxholeVpnService.CONNECTIVITY_LITERAL_PROBE_CALL_TIMEOUT_MS,
                                    network = requestNetwork,
                                )
                            }
                        if (literalProbe.isSuccess) {
                            container.diagnosticsLogger.record(
                                "health",
                                "vpn-bound literal public endpoint accepted after proxy notification probe failed",
                            )
                            return@runCatchingUnlessCancelled
                        }
                        container.diagnosticsLogger.record(
                            "health",
                            "vpn-bound literal public endpoint failed after proxy notification probe failed: ${literalProbe.exceptionOrNull()?.message.orEmpty()}",
                        )
                        if (settings.requiresStrictRuntimeProxyIpRefresh(FoxholeVpnRuntimeBridge.snapshot.value)) {
                            if (isVpnNetworkValidated(vpnNetwork)) {
                                container.diagnosticsLogger.record(
                                    "health",
                                    "proxy notification probe failed despite android validated vpn network",
                                )
                            }
                            val strictProbeError =
                                literalProbe.exceptionOrNull()
                                    ?: runtimeProxyProbe.exceptionOrNull()
                                    ?: IllegalStateException("runtime proxy probe failed")
                            throw strictProbeError
                        }
                        container.diagnosticsLogger.record(
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
                container.diagnosticsLogger.record(
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
            runCatchingUnlessCancelled {
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

internal fun FoxholeVpnService.onConnectionStartedInternal(
    session: VpnSession,
    trafficMode: TrafficMode,
) {
    val previousSnapshot = FoxholeVpnRuntimeBridge.snapshot.value
    val analysisStatus = getString(R.string.notification_status_analysis)
    resetAutoReconnectState()
    RuntimeResumeStateStore.markProfileRuntime(this, trafficMode, session)
    runtimeNetworkActivityLoggingSuspended = false
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
    if (!activeSession.matchesRuntimeValidationSession(session)) {
        container.diagnosticsLogger.record(
            "dns",
            "validated tunnel ignored for stale session sessionId=${session.correlationId}",
        )
        return
    }
    activeVpnNetworkHandle = vpnNetwork.networkHandle
    registerVpnNetworkCallbackIfNeeded()
    if (FoxholeVpnRuntimeBridge.snapshot.value.state != ConnectionState.CONNECTED) {
        onConnectionStarted(session, TrafficMode.TUNNEL)
    }
    startGeoRefresh(vpnNetwork)
}

internal fun VpnSession?.matchesRuntimeValidationSession(session: VpnSession): Boolean =
    this != null &&
        profileId == session.profileId &&
        correlationId == session.correlationId &&
        protocolOptionId == session.protocolOptionId

internal fun FoxholeVpnService.canPublishValidationResult(session: VpnSession?): Boolean =
    session != null && activeSession.matchesRuntimeValidationSession(session)

private val String.isProbeIpLiteral: Boolean
    get() = contains(':') || PROBE_IPV4_REGEX.matches(this)

private val PROBE_IPV4_REGEX =
    Regex(
        pattern = """^(25[0-5]|2[0-4]\d|1?\d?\d)(\.(25[0-5]|2[0-4]\d|1?\d?\d)){3}$""",
    )
