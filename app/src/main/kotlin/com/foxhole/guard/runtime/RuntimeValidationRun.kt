package com.foxhole.guard.runtime

import android.net.Network
import android.os.SystemClock
import com.foxhole.core.model.ConnectionSnapshot
import com.foxhole.core.model.IpInfo
import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.RuntimeFailureCode
import com.foxhole.core.model.RuntimeFailureException
import com.foxhole.core.model.Settings
import com.foxhole.core.model.VpnSession
import com.foxhole.core.model.isUdpTransport
import com.foxhole.core.model.withDnsServers
import com.foxhole.core.runtime.FoxholeVpnRuntimeBridge
import com.foxhole.core.runtime.PrivateDnsSettings
import com.foxhole.core.runtime.RuntimeHealthMetrics
import com.foxhole.core.runtime.TunnelConnectivityProbe
import com.foxhole.core.runtime.TunnelValidationEvidence
import com.foxhole.core.runtime.TunnelValidationPolicyContext
import com.foxhole.core.runtime.TunnelValidationProbeKind
import com.foxhole.core.runtime.VpnDnsServerSelector
import com.foxhole.core.runtime.acceptsTunnelValidationProbe
import com.foxhole.core.runtime.allowsRuntimeProxyTunnelValidation
import com.foxhole.core.runtime.dnsServerAddresses
import com.foxhole.core.runtime.network.IpInfoFetchMode
import com.foxhole.core.runtime.requiresStrictRuntimeProxyIpRefresh
import com.foxhole.core.runtime.selectTunnelValidationGracePolicy
import com.foxhole.core.runtime.shouldPreferIpv4TunnelValidation
import com.foxhole.core.runtime.tunnelValidationPolicyContextFor
import com.foxhole.core.runtime.tunnelValidationRequestNetwork
import com.foxhole.guard.R
import com.foxhole.guard.core.diagnostics.DiagnosticsLoggerRuntimeDiagnosticsSink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

internal data class RuntimeValidationRun(
    val validationStartedAt: Long,
    val deadlineAtElapsedRealtimeMs: Long,
    val currentSession: VpnSession?,
    val activeProtocolHint: ProtocolHint?,
    val baseValidationPolicyContext: TunnelValidationPolicyContext,
    val preferIpv4Validation: Boolean,
    val probePlan: RuntimeValidationProbePlan,
    val diagnostics: RuntimeValidationDiagnosticFields,
)

private data class RuntimeValidationNetworkContext(
    val vpnNetwork: Network,
    val requestNetwork: Network?,
    val resolverNetwork: Network?,
    val settings: Settings,
    val policyContext: TunnelValidationPolicyContext,
    val androidValidatedEarly: Boolean,
)

internal suspend fun FoxholeVpnService.prepareRuntimeValidationRun(
    validationStartedAt: Long,
    currentSession: VpnSession?,
): RuntimeValidationRun {
    val activeProtocolHint = currentSession?.protocolHint
    val probePlan = runtimeValidationProbePlan(currentSession)
    return RuntimeValidationRun(
        validationStartedAt = validationStartedAt,
        deadlineAtElapsedRealtimeMs =
        runtimeValidationDeadlineAt(
            startedAtElapsedRealtimeMs = SystemClock.elapsedRealtime(),
            totalTimeoutMs = probePlan.totalTimeoutMs,
        ),
        currentSession = currentSession,
        activeProtocolHint = activeProtocolHint,
        baseValidationPolicyContext = tunnelValidationPolicyContextFor(PrivateDnsSettings.current(this)),
        preferIpv4Validation = shouldPreferIpv4TunnelValidation(activeProtocolHint, currentSession?.configJson),
        probePlan = probePlan,
        diagnostics = runtimeValidationDiagnosticFields(currentSession),
    )
}

internal fun FoxholeVpnService.recordRuntimeValidationStarted(validationRun: RuntimeValidationRun) {
    container.diagnosticsLogger.recordStructured(
        "dns",
        "Tunnel validation started",
        validationRun.activeProtocolHint?.name?.lowercase(),
        if (validationRun.preferIpv4Validation) "address_family=ipv4" else null,
        "timeout_ms=${validationRun.probePlan.totalTimeoutMs}",
    )
    container.diagnosticsLogger.recordStructured(
        "runtime",
        "Runtime validation started",
        validationRun.diagnostics.protocolHint,
        validationRun.diagnostics.dnsShape,
        validationRun.diagnostics.probeTransport,
        "validation_result=pending",
    )
}

internal suspend fun FoxholeVpnService.runTunnelConnectivityProbe(
    validationRun: RuntimeValidationRun,
    expectedFreshVpnNetworkHandle: Long?,
    expectedFreshVpnInterfaceName: String?,
): Result<Network> =
    TunnelConnectivityProbe.run(
        attempts = validationRun.probePlan.attempts,
        initialDelayMs = validationRun.probePlan.initialDelayMs,
        retryDelayMs = validationRun.probePlan.retryDelayMs,
        timeoutMs =
        runtimeValidationWatchdogTimeoutMs(
            deadlineAtElapsedRealtimeMs = validationRun.deadlineAtElapsedRealtimeMs,
            nowElapsedRealtimeMs = SystemClock.elapsedRealtime(),
        ),
        onFailure = { attemptIndex, error ->
            container.diagnosticsLogger.recordFailure(
                "dns",
                "probe attempt ${attemptIndex + 1}/${validationRun.probePlan.attempts} failed: ${error.message.orEmpty()}",
            )
        },
        onAttemptCompleted = { attempt ->
            container.diagnosticsLogger.recordStructured(
                "runtime",
                "Runtime validation probe attempt",
                "attempt=${attempt.attemptNumber}/${validationRun.probePlan.attempts}",
                "attempt_elapsed_ms=${attempt.elapsedMs}",
                if (attempt.success) "attempt_result=success" else "attempt_result=failure",
                attempt.failure?.let { "failure_class=${it.javaClass.simpleName}" },
                attempt.failure?.rootCauseClassName()?.let { "failure_root=$it" },
            )
        },
    ) {
        runTunnelValidationAttempt(
            validationRun,
            expectedFreshVpnNetworkHandle,
            expectedFreshVpnInterfaceName,
        )
    }

private suspend fun FoxholeVpnService.runTunnelValidationAttempt(
    validationRun: RuntimeValidationRun,
    expectedFreshVpnNetworkHandle: Long?,
    expectedFreshVpnInterfaceName: String?,
): Network {
    val networkContext = awaitRuntimeValidationNetworkContext(
        validationRun,
        expectedFreshVpnNetworkHandle,
        expectedFreshVpnInterfaceName,
    )
    val runtimeProxyAccepted = tryRuntimeProxyTunnelValidation(validationRun, networkContext)
    if (validationRun.currentSession.requiresVerifiedTorExitForValidation()) {
        check(runtimeProxyAccepted) {
            "tor-carrying tunnel did not prove public egress through tor"
        }
        requireVpnBoundDnsResolution(networkContext.vpnNetwork)
        return networkContext.vpnNetwork
    }
    val earlyAccepted =
        runtimeProxyAccepted ||
            tryEarlyUdpLiteralValidation(validationRun, networkContext) ||
            tryDnsIndependentPreDnsValidation(validationRun, networkContext) ||
            tryEndpointPreIpValidation(validationRun, networkContext)
    if (earlyAccepted) {
        requireVpnBoundDnsResolution(networkContext.vpnNetwork)

        if (pendingTorRouteUpgradeSessionId == validationRun.currentSession?.correlationId) {
            val ipRefresh = refreshTunnelIpForValidation(validationRun, networkContext)

            publishSuccessfulValidationIpRefresh(validationRun, ipRefresh.getOrThrow())
            return networkContext.vpnNetwork
        }
        return networkContext.vpnNetwork
    }
    val ipRefresh = refreshTunnelIpForValidation(validationRun, networkContext)
    return if (ipRefresh.isSuccess) {
        publishSuccessfulValidationIpRefresh(validationRun, ipRefresh.getOrThrow())
        networkContext.vpnNetwork
    } else {
        handleFailedValidationIpRefresh(validationRun, networkContext, ipRefresh)
    }
}

private suspend fun FoxholeVpnService.requireVpnBoundDnsResolution(vpnNetwork: Network) {
    var lastFailure: Throwable? = null
    val resolved =
        withTimeoutOrNull(FoxholeVpnService.VPN_DNS_VALIDATION_TIMEOUT_MS) {
            while (true) {
                val result =
                    runCatching {
                        vpnNetwork
                            .getAllByName(FoxholeVpnService.LOCAL_GUARD_CONNECTIVITY_DNS_PROBE_HOST)
                            .isNotEmpty()
                    }
                if (result.getOrDefault(false)) {
                    return@withTimeoutOrNull true
                }
                lastFailure = result.exceptionOrNull()
                delay(FoxholeVpnService.VPN_DNS_VALIDATION_RETRY_DELAY_MS)
            }
        }
    if (resolved == true) {
        container.diagnosticsLogger.record("dns", "vpn network passed bound dns resolution")
        return
    }
    recordVpnDnsRuntimeCounters()
    throw RuntimeFailureException(
        code = RuntimeFailureCode.DNS_FAILURE,
        message = "vpn dns resolution failed",
        cause = lastFailure,
    )
}

private fun FoxholeVpnService.recordVpnDnsRuntimeCounters() {
    val snapshot =
        runtimeInstanceStore.current()?.runtimeStatsJson()?.let { encoded ->
            runCatching { JSONObject(encoded) }.getOrNull()
        } ?: return
    container.diagnosticsLogger.recordStructured(
        "dns",
        "vpn dns runtime counters",
        "queries=${snapshot.optLong("dns_queries", -1L)}",
        "blocked=${snapshot.optLong("dns_blocked", -1L)}",
        "blocked_flows=${snapshot.optLong("blocked_flows", -1L)}",
        "attribution_errors=${snapshot.optLong("attribution_errors", -1L)}",
        "dial_errors=${snapshot.optLong("dial_errors", -1L)}",
        "flow_errors=${snapshot.optLong("flow_errors", -1L)}",
        "udp_opened=${snapshot.optLong("udp_flows_opened", -1L)}",
        "udp_unsupported=${snapshot.optLong("udp_unsupported", -1L)}",
    )
}

private suspend fun FoxholeVpnService.awaitRuntimeValidationNetworkContext(
    validationRun: RuntimeValidationRun,
    expectedFreshVpnNetworkHandle: Long?,
    expectedFreshVpnInterfaceName: String?,
): RuntimeValidationNetworkContext {
    val vpnNetwork =
        profileRuntimeValidationStep("await_vpn_network") {
            awaitVpnNetworkOrNull(
                FoxholeVpnService.CONNECTIVITY_PROBE_NETWORK_WAIT_TIMEOUT_MS,
                excludedHandle = expectedFreshVpnNetworkHandle,
                excludedInterfaceName = expectedFreshVpnInterfaceName,
            ) ?: throw RuntimeFailureException(
                RuntimeFailureCode.VPN_NETWORK_MISSING,
                "vpn network unavailable",
            )
        }
    val settings = container.settingsRepository.current()
    val runtimeSnapshot = FoxholeVpnRuntimeBridge.snapshot.value
    val policyContext =
        validationRun.baseValidationPolicyContext.copy(
            allowRuntimeProxyTunnelValidation = settings.allowsRuntimeProxyTunnelValidation(
                snapshot = runtimeSnapshot,
                profileId = validationRun.currentSession?.profileId ?: runtimeSnapshot.profileId,
            ),
        )
    recordRuntimeProxyValidationPolicy(
        settings = settings,
        runtimeSnapshot = runtimeSnapshot,
        policyContext = policyContext,
        session = validationRun.currentSession,
    )
    val androidValidatedEarly = isVpnNetworkValidated(vpnNetwork)
    container.diagnosticsLogger.recordStructured(
        "runtime",
        "Runtime validation network selected",
        "vpn_handle=${vpnNetwork.networkHandle}",
        expectedFreshVpnNetworkHandle?.let { "excluded_handle=$it" },
        expectedFreshVpnInterfaceName?.let { "excluded_interface=$it" },
        "android_validated=$androidValidatedEarly",
    )
    recordAndroidValidatedNetworkPolicy(
        androidValidatedEarly = androidValidatedEarly,
        policyContext = policyContext,
        session = validationRun.currentSession,
    )
    return RuntimeValidationNetworkContext(
        vpnNetwork = vpnNetwork,
        requestNetwork = tunnelValidationRequestNetwork(vpnNetwork),
        resolverNetwork = currentUpstreamNetworkOrNull(),
        settings = settings,
        policyContext = policyContext,
        androidValidatedEarly = androidValidatedEarly,
    )
}

private fun FoxholeVpnService.recordRuntimeProxyValidationPolicy(
    settings: Settings,
    runtimeSnapshot: ConnectionSnapshot,
    policyContext: TunnelValidationPolicyContext,
    session: VpnSession?,
) {
    container.diagnosticsLogger.record(
        "dns",
        when {
            session.requiresVerifiedTorExitForValidation() ->
                "service-owned verified tor exit required for tunnel validation"
            policyContext.allowRuntimeProxyTunnelValidation ->
                "runtime proxy egress accepted for include-app tunnel validation"
            settings.requiresStrictRuntimeProxyIpRefresh(runtimeSnapshot) ->
                "runtime proxy egress kept out of tunnel acceptance; using vpn-bound tunnel validation"
            else -> "runtime proxy egress skipped; using vpn-bound tunnel validation"
        },
    )
}

private fun FoxholeVpnService.recordAndroidValidatedNetworkPolicy(
    androidValidatedEarly: Boolean,
    policyContext: TunnelValidationPolicyContext,
    session: VpnSession?,
) {
    if (!androidValidatedEarly) {
        return
    }
    container.diagnosticsLogger.record(
        "dns",
        when {
            session.requiresVerifiedTorExitForValidation() ->
                "vpn network has Android validation; still requiring service-owned verified tor exit"
            policyContext.allowRuntimeProxyTunnelValidation ->
                "vpn network has Android validation; trying runtime proxy include-app tunnel validation"
            else ->
                "vpn network has Android validation; still requiring vpn-bound tunnel validation"
        },
    )
}

private suspend fun FoxholeVpnService.tryRuntimeProxyTunnelValidation(
    validationRun: RuntimeValidationRun,
    networkContext: RuntimeValidationNetworkContext,
): Boolean {
    val requiresVerifiedTorExit =
        validationRun.currentSession.requiresVerifiedTorExitForValidation()
    if (!networkContext.policyContext.allowRuntimeProxyTunnelValidation && !requiresVerifiedTorExit) {
        return false
    }
    val runtimeProxyValidation =
        runRuntimeValidationCatchingUnlessCancelled {
            profileRuntimeValidationStep("runtime_proxy_egress") {
                validateRuntimeProxyEgressWithWarmup(
                    request =
                    RuntimeProxyEgressValidationRequest(
                        settings = networkContext.settings,
                        vpnNetwork = networkContext.vpnNetwork,
                        session = validationRun.currentSession,
                        validationPolicyContext = networkContext.policyContext,
                        preferIpv4Validation = validationRun.preferIpv4Validation,
                    ),
                    warmupPolicy =
                    RuntimeProxyWarmupPolicy(
                        endpointCallTimeoutMs = validationRun.probePlan.literalCallTimeoutMs,
                        ipRefreshCallTimeoutMs = validationRun.probePlan.callTimeoutMs,
                        deadlineAtElapsedRealtimeMs = validationRun.deadlineAtElapsedRealtimeMs,
                        controlProbeReserveMs = validationRun.probePlan.controlProbeReserveMs,
                        retryDelayMs = validationRun.probePlan.retryDelayMs,
                        maxAttempts = validationRun.probePlan.maxRuntimeProxyWarmupAttempts,
                    ),
                )
            }
        }
    return if (runtimeProxyValidation.isFailure) {
        val failureMessage =
            runtimeProxyValidation
                .exceptionOrNull()
                ?.message
                .orEmpty()
        container.diagnosticsLogger.recordFailure(
            "dns",
            if (requiresVerifiedTorExit) {
                "verified tor egress validation failed without generic fallback: $failureMessage"
            } else {
                "runtime proxy egress validation failed before vpn-bound fallback: $failureMessage"
            },
        )
        false
    } else {
        val proxyResult = runtimeProxyValidation.getOrThrow()
        if (!acceptsRuntimeProxyValidationResult(validationRun.currentSession, proxyResult)) {
            container.diagnosticsLogger.record(
                "dns",
                "runtime proxy evidence rejected for strict tor validation kind=${proxyResult.kind.name.lowercase()}",
            )
            return false
        }
        proxyResult.ipInfo?.let { info ->
            publishRuntimeProxyValidatedIpInfo(info, validationRun.currentSession)
        }
        container.diagnosticsLogger.record(
            "dns",
            "runtime proxy egress accepted tunnel validation kind=${proxyResult.kind.name.lowercase()}",
        )
        true
    }
}

private suspend fun FoxholeVpnService.tryEarlyUdpLiteralValidation(
    validationRun: RuntimeValidationRun,
    networkContext: RuntimeValidationNetworkContext,
): Boolean {
    if (validationRun.activeProtocolHint?.isUdpTransport() != true) {
        return false
    }
    val accepted =
        tryAcceptEarlyValidatedVpnLiteralEndpoint(
            vpnNetwork = networkContext.vpnNetwork,
            requestNetwork = networkContext.requestNetwork,
            validationStartedAt = validationRun.validationStartedAt,
            context = networkContext.policyContext,
            session = validationRun.currentSession,
        )
    if (!accepted && networkContext.androidValidatedEarly) {
        val validatedLiteralEndpointProbe =
            runRuntimeValidationCatchingUnlessCancelled {
                probeDnsIndependentConnectivityFallback(
                    callTimeoutMs = FoxholeVpnService.CONNECTIVITY_LITERAL_PROBE_CALL_TIMEOUT_MS,
                    network = networkContext.requestNetwork,
                )
            }
        container.diagnosticsLogger.record(
            "dns",
            if (validatedLiteralEndpointProbe.isSuccess) {
                "vpn-bound literal public endpoint passed before hostname validation but is not accepted as tunnel validation"
            } else {
                "vpn-bound literal public endpoint failed before hostname validation: ${validatedLiteralEndpointProbe.exceptionOrNull()?.message.orEmpty()}"
            },
        )
    }
    return accepted
}

private suspend fun FoxholeVpnService.tryDnsIndependentPreDnsValidation(
    validationRun: RuntimeValidationRun,
    networkContext: RuntimeValidationNetworkContext,
): Boolean {
    if (
        !acceptsTunnelValidationProbe(
            TunnelValidationProbeKind.DNS_INDEPENDENT_LITERAL_IP,
            networkContext.policyContext,
        )
    ) {
        return false
    }
    val dnsIndependentValidation =
        runRuntimeValidationCatchingUnlessCancelled {
            profileRuntimeValidationStep("dns_independent_pre_dns") {
                probeDnsIndependentConnectivityFallback(
                    callTimeoutMs = validationRun.probePlan.callTimeoutMs,
                    network = networkContext.requestNetwork,
                )
            }
        }
    return if (dnsIndependentValidation.isFailure) {
        container.diagnosticsLogger.recordFailure(
            "dns",
            "dns-independent public reachability probe failed before dns validation: ${dnsIndependentValidation.exceptionOrNull()?.message.orEmpty()}",
        )
        false
    } else {
        container.diagnosticsLogger.record(
            "dns",
            "dns-independent public reachability probe accepted for strict private dns",
        )
        scope.launch(Dispatchers.IO) {
            refreshValidatedTunnelIpInfoBestEffort(networkContext.vpnNetwork, validationRun.currentSession)
        }
        true
    }
}

private suspend fun FoxholeVpnService.tryEndpointPreIpValidation(
    validationRun: RuntimeValidationRun,
    networkContext: RuntimeValidationNetworkContext,
): Boolean {
    val earlyEndpointProbe =
        runRuntimeValidationCatchingUnlessCancelled {
            profileRuntimeValidationStep("endpoint_pre_ip") {
                probeConnectivityEndpoints(
                    callTimeoutMs = validationRun.probePlan.literalCallTimeoutMs,
                    network = networkContext.requestNetwork,
                    resolverNetwork = networkContext.resolverNetwork,
                    preferIpv4 = validationRun.preferIpv4Validation,
                )
            }
        }
    if (earlyEndpointProbe.isFailure) {
        container.diagnosticsLogger.recordFailure(
            "dns",
            "vpn-bound validation endpoint probe failed before ip refresh: ${earlyEndpointProbe.exceptionOrNull()?.message.orEmpty()}",
        )
        return false
    }
    container.diagnosticsLogger.record("dns", "vpn network passed validation endpoint probe")
    scope.launch(Dispatchers.IO) {
        refreshValidatedTunnelIpInfoBestEffort(networkContext.vpnNetwork, validationRun.currentSession)
    }
    return true
}

private suspend fun FoxholeVpnService.refreshTunnelIpForValidation(
    validationRun: RuntimeValidationRun,
    networkContext: RuntimeValidationNetworkContext,
): Result<IpInfo> =
    runRuntimeValidationCatchingUnlessCancelled {
        profileRuntimeValidationStep("ip_refresh_primary") {
            fetchTunnelIpInfoForValidation(validationRun, networkContext)
        }
    }.recoverRuntimeValidationCatchingUnlessCancelled { primaryError ->
        profileRuntimeValidationStep("ip_refresh_ipv4_fallback") {
            fetchTunnelIpv4InfoForValidation(validationRun, networkContext, primaryError)
        }
    }

private suspend fun FoxholeVpnService.fetchTunnelIpInfoForValidation(
    validationRun: RuntimeValidationRun,
    networkContext: RuntimeValidationNetworkContext,
): IpInfo {
    val endpoint = container.settingsRepository.current().connection.ipInfoEndpoint
    val remoteDnsServers = VpnDnsServerSelector.remoteDnsServerAddresses(validationRun.currentSession?.configJson)
    val info =
        if (validationRun.preferIpv4Validation) {
            container.ipInfoRepository.fetchIpv4(
                endpoint = endpoint,
                callTimeoutMs = validationRun.probePlan.callTimeoutMs,
                network = networkContext.requestNetwork,
                resolverNetwork = networkContext.resolverNetwork,
            ) ?: error("vpn ipv4 refresh failed")
        } else {
            container.ipInfoRepository.fetch(
                endpoint = endpoint,
                callTimeoutMs = validationRun.probePlan.callTimeoutMs,
                network = networkContext.requestNetwork,
                resolverNetwork = networkContext.resolverNetwork,
                mode = IpInfoFetchMode.ENTRY_QUICK,
            )
        }
    return info.withDnsServers(
        localDnsServers = connectivityManager.dnsServerAddresses(networkContext.vpnNetwork),
        remoteDnsServers = remoteDnsServers,
    )
}

private suspend fun FoxholeVpnService.fetchTunnelIpv4InfoForValidation(
    validationRun: RuntimeValidationRun,
    networkContext: RuntimeValidationNetworkContext,
    primaryError: Throwable,
): IpInfo {
    val endpoint = container.settingsRepository.current().connection.ipInfoEndpoint
    val remoteDnsServers = VpnDnsServerSelector.remoteDnsServerAddresses(validationRun.currentSession?.configJson)
    val ipv4Info =
        container.ipInfoRepository.fetchIpv4(
            endpoint = endpoint,
            callTimeoutMs = validationRun.probePlan.callTimeoutMs,
            network = networkContext.requestNetwork,
            resolverNetwork = networkContext.resolverNetwork,
        ) ?: throw primaryError
    return ipv4Info.withDnsServers(
        localDnsServers = connectivityManager.dnsServerAddresses(networkContext.vpnNetwork),
        remoteDnsServers = remoteDnsServers,
    )
}

private fun FoxholeVpnService.publishSuccessfulValidationIpRefresh(
    validationRun: RuntimeValidationRun,
    ipInfo: IpInfo,
) {
    if (canPublishValidationResult(validationRun.currentSession)) {
        bridgeWriter.updateIpInfo(ipInfo)
    } else {
        container.diagnosticsLogger.record(
            "ip",
            "validated tunnel ip refresh ignored for stale session sessionId=${validationRun.currentSession?.correlationId.orEmpty()}",
        )
    }
    container.diagnosticsLogger.record("dns", "vpn network passed in-process ip refresh")
}

private suspend fun FoxholeVpnService.handleFailedValidationIpRefresh(
    validationRun: RuntimeValidationRun,
    networkContext: RuntimeValidationNetworkContext,
    ipRefresh: Result<IpInfo>,
): Network {
    val androidValidated = isVpnNetworkValidated(networkContext.vpnNetwork)
    val evidence = inspectValidatedTunnelEvidence(validationRun.validationStartedAt)
    recordValidationIpRefreshFailure(androidValidated, ipRefresh)
    probeLiteralEndpointAfterIpFailure(validationRun, networkContext)
    val endpointProbe = probeEndpointAfterIpFailure(validationRun, networkContext)
    if (endpointProbe.isSuccess) {
        scope.launch(Dispatchers.IO) {
            refreshValidatedTunnelIpInfoBestEffort(networkContext.vpnNetwork, validationRun.currentSession)
        }
        return networkContext.vpnNetwork
    }
    retryValidationGraceIfEligible(validationRun, networkContext, evidence)?.let { return it }
    if (!androidValidated) {
        throw (ipRefresh.exceptionOrNull() ?: endpointProbe.exceptionOrNull() ?: IllegalStateException("vpn ip refresh failed"))
    }
    container.diagnosticsLogger.record(
        "dns",
        "android validated vpn network is not accepted without vpn-bound reachability",
    )
    evidence?.let {
        container.diagnosticsLogger.recordFailure(
            "dns",
            "validated tunnel had FoxCore activity, but vpn-bound endpoint probe still failed; failing closed",
        )
    }
    throw (endpointProbe.exceptionOrNull() ?: IllegalStateException("connectivity probe failed"))
}

private fun FoxholeVpnService.recordValidationIpRefreshFailure(
    androidValidated: Boolean,
    ipRefresh: Result<IpInfo>,
) {
    val ipErrorMessage = ipRefresh.exceptionOrNull()?.message.orEmpty()
    container.diagnosticsLogger.record(
        "dns",
        if (androidValidated) {
            "vpn network validated by android; vpn-bound ip refresh failed, probing literal public endpoints: $ipErrorMessage"
        } else {
            "vpn-bound ip refresh failed before android validation; probing literal public endpoints: $ipErrorMessage"
        },
    )
}

private suspend fun FoxholeVpnService.probeLiteralEndpointAfterIpFailure(
    validationRun: RuntimeValidationRun,
    networkContext: RuntimeValidationNetworkContext,
) {
    val literalEndpointProbe =
        runRuntimeValidationCatchingUnlessCancelled {
            profileRuntimeValidationStep("literal_after_ip") {
                probeDnsIndependentConnectivityFallback(
                    callTimeoutMs = validationRun.probePlan.literalCallTimeoutMs,
                    network = networkContext.requestNetwork,
                )
            }
        }
    container.diagnosticsLogger.record(
        "dns",
        if (literalEndpointProbe.isSuccess) {
            "vpn-bound literal public endpoint passed after ip refresh failed but is not accepted as tunnel validation"
        } else {
            "vpn-bound literal public endpoint failed before validation endpoints: ${literalEndpointProbe.exceptionOrNull()?.message.orEmpty()}"
        },
    )
}

private suspend fun FoxholeVpnService.probeEndpointAfterIpFailure(
    validationRun: RuntimeValidationRun,
    networkContext: RuntimeValidationNetworkContext,
): Result<Unit> =
    runRuntimeValidationCatchingUnlessCancelled {
        profileRuntimeValidationStep("endpoint_after_ip") {
            probeConnectivityEndpoints(
                callTimeoutMs = validationRun.probePlan.callTimeoutMs,
                network = networkContext.requestNetwork,
                resolverNetwork = networkContext.resolverNetwork,
                preferIpv4 = validationRun.preferIpv4Validation,
            )
        }
    }

private suspend fun FoxholeVpnService.retryValidationGraceIfEligible(
    validationRun: RuntimeValidationRun,
    networkContext: RuntimeValidationNetworkContext,
    evidence: TunnelValidationEvidence?,
): Network? {
    val gracePolicy = selectTunnelValidationGracePolicy(evidence) ?: return null
    container.diagnosticsLogger.recordStructured(
        "dns",
        "Validated tunnel grace retry started",
        validationRun.activeProtocolHint?.name?.lowercase(),
        "attempts=${gracePolicy.attempts}",
        "window_ms=${gracePolicy.totalTimeoutMs}",
    )
    val graceResult =
        retryValidatedTunnelConnectivityWithGrace(
            vpnNetwork = networkContext.vpnNetwork,
            policy = gracePolicy,
            preferIpv4 = validationRun.preferIpv4Validation,
            session = validationRun.currentSession,
        )
    return if (graceResult.isSuccess) {
        container.diagnosticsLogger.record("dns", "validated tunnel grace retry passed")
        networkContext.vpnNetwork
    } else {
        container.diagnosticsLogger.recordFailure(
            "dns",
            "validated tunnel grace retry failed: ${graceResult.exceptionOrNull()?.message.orEmpty()}",
        )
        null
    }
}

internal fun FoxholeVpnService.finishRuntimeValidation(
    validationRun: RuntimeValidationRun,
    result: Result<Network>,
): Result<Network> {
    if (result.isSuccess) {
        container.diagnosticsLogger.recordStructured(
            "runtime",
            "Runtime validation result",
            validationRun.diagnostics.protocolHint,
            validationRun.diagnostics.dnsShape,
            validationRun.diagnostics.probeTransport,
            "validation_result=success",
        )
        RuntimeHealthMetrics.recordValidation(
            owner = "vpn",
            success = true,
            elapsedMs = System.currentTimeMillis() - validationRun.validationStartedAt,
            diagnosticsLogger = DiagnosticsLoggerRuntimeDiagnosticsSink(container.diagnosticsLogger),
        )
        container.diagnosticsLogger.record("dns", "vpn network passed tunnel validation")
        return result
    }
    val validationFailure = result.exceptionOrNull()
    container.diagnosticsLogger.recordStructured(
        "runtime",
        "Runtime validation result",
        validationRun.diagnostics.protocolHint,
        validationRun.diagnostics.dnsShape,
        validationRun.diagnostics.probeTransport,
        "validation_result=failure",
        "failure_class=${validationFailure?.javaClass?.simpleName ?: "unknown"}",
        validationFailure.rootCauseClassName()?.let { "failure_root=$it" },
        validationFailure.tunnelConnectivityProbeTimeout()?.let {
            "timeout_attempts=${it.attemptsDone}/${validationRun.probePlan.attempts}"
        },
        "endpoint_refusal=${validationFailure.isEndpointConnectRefusal()}",
    )
    RuntimeHealthMetrics.recordValidation(
        owner = "vpn",
        success = false,
        elapsedMs = System.currentTimeMillis() - validationRun.validationStartedAt,
        diagnosticsLogger = DiagnosticsLoggerRuntimeDiagnosticsSink(container.diagnosticsLogger),
    )
    return Result.failure(IllegalStateException(getString(R.string.error_dns_probe_failed), validationFailure))
}
