@file:Suppress("MatchingDeclarationName")

package com.foxhole.guard.runtime

import android.net.Network
import android.os.SystemClock
import com.foxhole.core.model.IpInfo
import com.foxhole.core.model.Settings
import com.foxhole.core.model.VpnSession
import com.foxhole.core.model.isDistinctTorRouteExit
import com.foxhole.core.model.withDnsServers
import com.foxhole.core.runtime.FoxholeVpnRuntimeBridge
import com.foxhole.core.runtime.TorProbeProxyFailure
import com.foxhole.core.runtime.TorProbeProxyLease
import com.foxhole.core.runtime.TorProbeProxyOwner
import com.foxhole.core.runtime.TorProbeProxyUnavailableException
import com.foxhole.core.runtime.TunnelValidationPolicyContext
import com.foxhole.core.runtime.TunnelValidationProbeKind
import com.foxhole.core.runtime.VpnDnsServerSelector
import com.foxhole.core.runtime.acceptsTunnelValidationProbe
import com.foxhole.core.runtime.dnsServerAddresses
import com.foxhole.core.runtime.network.IpInfoFetchMode
import com.foxhole.core.runtime.network.ProxyAccessType
import com.foxhole.core.runtime.shouldPublishRuntimeProxyIpInfoToDashboard
import com.foxhole.core.runtime.tunnelRuntimeProxyAccess
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

internal data class RuntimeProxyEgressValidationResult(
    val kind: TunnelValidationProbeKind,
    val ipInfo: IpInfo? = null,
    val verifiedTorExit: Boolean = false,
)

internal data class RuntimeProxyWarmupPolicy(
    val endpointCallTimeoutMs: Long,
    val ipRefreshCallTimeoutMs: Long,
    val deadlineAtElapsedRealtimeMs: Long,
    val controlProbeReserveMs: Long,
    val retryDelayMs: Long,
    val maxAttempts: Int?,
) {
    init {
        require(maxAttempts == null || maxAttempts > 0)
    }

    fun permitsAttempt(attemptsCompleted: Int): Boolean =
        maxAttempts?.let { limit -> attemptsCompleted < limit } ?: true
}

internal data class RuntimeProxyEgressValidationRequest(
    val settings: Settings,
    val vpnNetwork: Network,
    val session: VpnSession?,
    val validationPolicyContext: TunnelValidationPolicyContext,
    val preferIpv4Validation: Boolean,
)

private data class RuntimeProxyWarmupAttemptContext(
    val request: RuntimeProxyEgressValidationRequest,
    val startedAtElapsedRealtimeMs: Long,
) {
    val requiresVerifiedTorExit: Boolean = request.session.requiresVerifiedTorExitForValidation()
}

internal fun VpnSession?.requiresVerifiedTorExitForValidation(): Boolean =
    this?.profileId == FoxholeVpnService.TOR_ONLY_PROFILE_ID ||
        this?.torActive == true ||
        this?.appliedTorRoute != null

internal fun acceptsRuntimeProxyValidationResult(
    session: VpnSession?,
    result: RuntimeProxyEgressValidationResult,
): Boolean {
    if (!session.requiresVerifiedTorExitForValidation()) {
        return true
    }
    return result.kind == TunnelValidationProbeKind.TUNNEL_RUNTIME_PROXY_IP_REFRESH &&
        result.ipInfo != null &&
        result.verifiedTorExit
}

internal suspend fun FoxholeVpnService.validateRuntimeProxyEgressWithWarmup(
    request: RuntimeProxyEgressValidationRequest,
    warmupPolicy: RuntimeProxyWarmupPolicy,
): RuntimeProxyEgressValidationResult {
    val attemptContext =
        RuntimeProxyWarmupAttemptContext(
            request = request,
            startedAtElapsedRealtimeMs = SystemClock.elapsedRealtime(),
        )
    val effectiveControlProbeReserveMs =
        warmupPolicy.effectiveControlProbeReserveMs(
            requiresVerifiedTorExit = attemptContext.requiresVerifiedTorExit,
            startedAtElapsedRealtimeMs = attemptContext.startedAtElapsedRealtimeMs,
        )

    var attempt = 0
    var lastFailure: Throwable? = null
    var ipRefreshCallBudgetMs =
        warmupPolicy.ipRefreshCallBudgetMs(effectiveControlProbeReserveMs)
    while (
        shouldAttemptRuntimeProxyWarmup(
            isActive = currentCoroutineContext().isActive,
            callBudgetMs = ipRefreshCallBudgetMs,
            attemptsCompleted = attempt,
            policy = warmupPolicy,
        )
    ) {
        attempt += 1
        val ipRefresh =
            attemptRuntimeProxyIpRefreshValidation(
                context = attemptContext,
                callTimeoutMs = ipRefreshCallBudgetMs,
                attempt = attempt,
            )
        if (ipRefresh.isSuccess) {
            return ipRefresh.getOrThrow()
        }
        val fallbackResult =
            attemptRuntimeProxyFallbackValidation(
                context = attemptContext,
                warmupPolicy = warmupPolicy,
                ipRefresh = ipRefresh,
                attempt = attempt,
            )
        if (fallbackResult.isSuccess) {
            return fallbackResult.getOrThrow()
        }
        lastFailure = fallbackResult.exceptionOrNull() ?: ipRefresh.exceptionOrNull()
        if (!warmupPolicy.permitsAttempt(attempt)) {
            break
        }
        ipRefreshCallBudgetMs =
            warmupPolicy.nextIpRefreshCallBudgetMs(effectiveControlProbeReserveMs)
    }
    val strictFailure = lastFailure ?: IllegalStateException("runtime proxy egress failed")
    if (attemptContext.requiresVerifiedTorExit) {
        classifyStrictTorControlProbe(
            session = requireNotNull(request.session),
            deadlineAtElapsedRealtimeMs = warmupPolicy.deadlineAtElapsedRealtimeMs,
            controlProbeReserveMs = effectiveControlProbeReserveMs,
        )
    }
    throw strictFailure
}

private fun RuntimeProxyWarmupPolicy.effectiveControlProbeReserveMs(
    requiresVerifiedTorExit: Boolean,
    startedAtElapsedRealtimeMs: Long,
): Long {
    if (!requiresVerifiedTorExit) {
        return 0L
    }
    return controlProbeReserveMs
        .coerceAtLeast(0L)
        .coerceAtMost(
            remainingRuntimeValidationMs(
                deadlineAtElapsedRealtimeMs,
                startedAtElapsedRealtimeMs,
            ),
        )
}

private fun RuntimeProxyWarmupPolicy.ipRefreshCallBudgetMs(
    effectiveControlProbeReserveMs: Long,
): Long =
    boundedRuntimeValidationCallTimeoutMs(
        requestedTimeoutMs = ipRefreshCallTimeoutMs,
        remainingMs =
        remainingRuntimeValidationProofMs(
            deadlineAtElapsedRealtimeMs = deadlineAtElapsedRealtimeMs,
            nowElapsedRealtimeMs = SystemClock.elapsedRealtime(),
            controlProbeReserveMs = effectiveControlProbeReserveMs,
        ),
    )

private fun RuntimeProxyWarmupPolicy.endpointCallBudgetMs(): Long =
    boundedRuntimeValidationCallTimeoutMs(
        requestedTimeoutMs = endpointCallTimeoutMs,
        remainingMs =
        remainingRuntimeValidationMs(
            deadlineAtElapsedRealtimeMs = deadlineAtElapsedRealtimeMs,
            nowElapsedRealtimeMs = SystemClock.elapsedRealtime(),
        ),
    )

private suspend fun RuntimeProxyWarmupPolicy.nextIpRefreshCallBudgetMs(
    effectiveControlProbeReserveMs: Long,
): Long {
    val remainingMs =
        remainingRuntimeValidationProofMs(
            deadlineAtElapsedRealtimeMs = deadlineAtElapsedRealtimeMs,
            nowElapsedRealtimeMs = SystemClock.elapsedRealtime(),
            controlProbeReserveMs = effectiveControlProbeReserveMs,
        )
    if (remainingMs <= retryDelayMs) {
        return 0L
    }
    delay(retryDelayMs.coerceAtMost(remainingMs))
    return ipRefreshCallBudgetMs(effectiveControlProbeReserveMs)
}

private fun shouldAttemptRuntimeProxyWarmup(
    isActive: Boolean,
    callBudgetMs: Long,
    attemptsCompleted: Int,
    policy: RuntimeProxyWarmupPolicy,
): Boolean =
    when {
        !isActive -> false
        callBudgetMs <= 0L -> false
        else -> policy.permitsAttempt(attemptsCompleted)
    }

private fun FoxholeVpnService.logStrictTorValidationRetry(attempt: Int) {
    container.diagnosticsLogger.record(
        "dns",
        "verified tor exit unavailable on warmup attempt=$attempt; generic proxy endpoint is not accepted",
    )
}

private suspend fun FoxholeVpnService.attemptRuntimeProxyFallbackValidation(
    context: RuntimeProxyWarmupAttemptContext,
    warmupPolicy: RuntimeProxyWarmupPolicy,
    ipRefresh: Result<RuntimeProxyEgressValidationResult>,
    attempt: Int,
): Result<RuntimeProxyEgressValidationResult> {
    if (context.requiresVerifiedTorExit) {
        logStrictTorValidationRetry(attempt)
        return ipRefresh
    }
    val endpointCallBudgetMs = warmupPolicy.endpointCallBudgetMs()
    if (endpointCallBudgetMs <= 0L) {
        return ipRefresh
    }
    return attemptRuntimeProxyEndpointValidation(
        settings = context.request.settings,
        validationPolicyContext = context.request.validationPolicyContext,
        callTimeoutMs = endpointCallBudgetMs,
        attempt = attempt,
        startedAtElapsedRealtimeMs = context.startedAtElapsedRealtimeMs,
    )
}

private suspend fun FoxholeVpnService.attemptRuntimeProxyIpRefreshValidation(
    context: RuntimeProxyWarmupAttemptContext,
    callTimeoutMs: Long,
    attempt: Int,
): Result<RuntimeProxyEgressValidationResult> {
    val result =
        runCatchingUnlessCancelled {
            fetchRuntimeProxyValidationIpInfo(
                settings = context.request.settings,
                vpnNetwork = context.request.vpnNetwork,
                session = context.request.session,
                callTimeoutMs = callTimeoutMs,
                preferIpv4Validation = context.request.preferIpv4Validation,
            )
        }
    if (result.isSuccess) {
        if (
            context.requiresVerifiedTorExit ||
            acceptsTunnelValidationProbe(
                TunnelValidationProbeKind.TUNNEL_RUNTIME_PROXY_IP_REFRESH,
                context.request.validationPolicyContext,
            )
        ) {
            logRuntimeProxyWarmupRecovery(attempt, "runtime proxy ip refresh recovered after warmup attempt=$attempt")
            return Result.success(
                RuntimeProxyEgressValidationResult(
                    kind = TunnelValidationProbeKind.TUNNEL_RUNTIME_PROXY_IP_REFRESH,
                    ipInfo = result.getOrThrow(),
                    verifiedTorExit = context.requiresVerifiedTorExit,
                ),
            )
        }
        container.diagnosticsLogger.record(
            "dns",
            "runtime proxy ip refresh passed but is not accepted as tunnel validation",
        )
    }
    val elapsedMs = SystemClock.elapsedRealtime() - context.startedAtElapsedRealtimeMs
    container.diagnosticsLogger.recordFailure(
        "dns",
        "runtime proxy ip refresh warmup attempt failed attempt=$attempt elapsed_ms=$elapsedMs reason=${result.exceptionOrNull()?.message.orEmpty()}",
    )
    return Result.failure(result.exceptionOrNull() ?: IllegalStateException("runtime proxy ip refresh not accepted"))
}

private suspend fun FoxholeVpnService.attemptRuntimeProxyEndpointValidation(
    settings: Settings,
    validationPolicyContext: TunnelValidationPolicyContext,
    callTimeoutMs: Long,
    attempt: Int,
    startedAtElapsedRealtimeMs: Long,
): Result<RuntimeProxyEgressValidationResult> {
    val result =
        runCatchingUnlessCancelled {
            probeConnectivityEndpointsOverLocalProxy(
                proxy = settings.tunnelRuntimeProxyAccess(),
                callTimeoutMs = callTimeoutMs,
            )
        }
    if (result.isSuccess) {
        if (
            acceptsTunnelValidationProbe(
                TunnelValidationProbeKind.TUNNEL_RUNTIME_PROXY_VALIDATION_ENDPOINT,
                validationPolicyContext,
            )
        ) {
            logRuntimeProxyWarmupRecovery(
                attempt,
                "runtime proxy egress validation recovered after warmup attempt=$attempt",
            )
            return Result.success(
                RuntimeProxyEgressValidationResult(
                    kind = TunnelValidationProbeKind.TUNNEL_RUNTIME_PROXY_VALIDATION_ENDPOINT,
                ),
            )
        }
        container.diagnosticsLogger.record(
            "dns",
            "runtime proxy endpoint probe passed but is not accepted as tunnel validation",
        )
    }
    val elapsedMs = SystemClock.elapsedRealtime() - startedAtElapsedRealtimeMs
    container.diagnosticsLogger.recordFailure(
        "dns",
        "runtime proxy endpoint warmup attempt failed attempt=$attempt elapsed_ms=$elapsedMs reason=${result.exceptionOrNull()?.message.orEmpty()}",
    )
    return Result.failure(result.exceptionOrNull() ?: IllegalStateException("runtime proxy endpoint not accepted"))
}

private fun FoxholeVpnService.logRuntimeProxyWarmupRecovery(
    attempt: Int,
    message: String,
) {
    if (attempt > 1) {
        container.diagnosticsLogger.record("dns", message)
    }
}

private suspend fun FoxholeVpnService.classifyStrictTorControlProbe(
    session: VpnSession,
    deadlineAtElapsedRealtimeMs: Long,
    controlProbeReserveMs: Long,
) {
    val callTimeoutMs =
        boundedRuntimeValidationCallTimeoutMs(
            requestedTimeoutMs = controlProbeReserveMs,
            remainingMs =
            remainingRuntimeValidationMs(
                deadlineAtElapsedRealtimeMs = deadlineAtElapsedRealtimeMs,
                nowElapsedRealtimeMs = SystemClock.elapsedRealtime(),
            ),
        )
    val result =
        if (callTimeoutMs <= 0L) {
            Result.failure(IllegalStateException("strict tor control probe deadline exhausted"))
        } else {
            runCatchingUnlessCancelled {
                probeServiceOwnedTorControlEndpoint(
                    session = session,
                    callTimeoutMs = callTimeoutMs,
                )
            }
        }
    container.diagnosticsLogger.recordStructured(
        "dns",
        "strict_tor_control_probe",
        if (result.isSuccess) "classified=reachable" else "classified=unavailable",
        result.exceptionOrNull()?.let { failure -> "failure_class=${failure.javaClass.simpleName}" },
    )
}

private suspend fun FoxholeVpnService.probeServiceOwnedTorControlEndpoint(
    session: VpnSession,
    callTimeoutMs: Long,
) {
    withAuthenticatedServiceOwnedTorProbeLease(session) { lease ->
        container.ipInfoRepository.probe(
            endpoint = STRICT_TOR_CONTROL_PROBE_ENDPOINT,
            callTimeoutMs = callTimeoutMs,
            proxy = lease.access,
        )
    }
}

private suspend fun FoxholeVpnService.fetchRuntimeProxyValidationIpInfo(
    settings: Settings,
    vpnNetwork: Network,
    session: VpnSession?,
    callTimeoutMs: Long,
    preferIpv4Validation: Boolean,
): IpInfo {
    val remoteDnsServers = VpnDnsServerSelector.remoteDnsServerAddresses(session?.configJson)
    val info =
        if (session.requiresVerifiedTorExitForValidation()) {
            fetchServiceOwnedVerifiedTorExit(
                session = requireNotNull(session),
                callTimeoutMs = callTimeoutMs,
            )
        } else if (preferIpv4Validation) {
            container.ipInfoRepository.fetchIpv4(
                endpoint = settings.connection.ipInfoEndpoint,
                callTimeoutMs = callTimeoutMs,
                proxy = settings.tunnelRuntimeProxyAccess(),
                resolverNetwork = currentUpstreamNetworkOrNull(),
            ) ?: error("runtime proxy ipv4 refresh failed")
        } else {
            container.ipInfoRepository.fetch(
                endpoint = settings.connection.ipInfoEndpoint,
                callTimeoutMs = callTimeoutMs,
                proxy = settings.tunnelRuntimeProxyAccess(),
                resolverNetwork = currentUpstreamNetworkOrNull(),
                mode = IpInfoFetchMode.ENTRY_QUICK,
            )
        }
    return info.withDnsServers(
        localDnsServers = connectivityManager.dnsServerAddresses(vpnNetwork),
        remoteDnsServers = remoteDnsServers,
    )
}

private suspend fun FoxholeVpnService.fetchServiceOwnedVerifiedTorExit(
    session: VpnSession,
    callTimeoutMs: Long,
): IpInfo =
    withAuthenticatedServiceOwnedTorProbeLease(session) { lease ->
        container.ipInfoRepository.fetchVerifiedTorExit(
            callTimeoutMs = callTimeoutMs,
            proxy = lease.access,
            resolverNetwork = currentUpstreamNetworkOrNull(),
        )
    }

private suspend fun <T> FoxholeVpnService.withAuthenticatedServiceOwnedTorProbeLease(
    session: VpnSession,
    block: suspend (TorProbeProxyLease) -> T,
): T {
    val expectedOwner =
        TorProbeProxyOwner(
            sessionId = session.correlationId,
            runtimeGeneration = runtimeSupervisor.currentGeneration(),
        )
    requireCurrentTorValidationSession(session, expectedOwner)
    torProbeOwnerEnabled = true
    syncTorProbeProxy()
    val lease =
        runtime.torProbeProxyLease()
            ?: throw TorProbeProxyUnavailableException(
                runtime.torProbeProxyIssue()?.failure ?: TorProbeProxyFailure.NOT_READY,
            )
    lease.requireAuthenticatedTorValidationLease(expectedOwner)
    val result = block(lease)
    requireCurrentTorValidationSession(session, expectedOwner)
    val currentLease = runtime.torProbeProxyLease()
    currentLease?.requireAuthenticatedTorValidationLease(expectedOwner)
    if (currentLease == null || currentLease.access != lease.access) {
        throw TorProbeProxyUnavailableException(TorProbeProxyFailure.STALE_GENERATION)
    }
    requireCurrentTorValidationSession(session, expectedOwner)
    return result
}

private fun FoxholeVpnService.requireCurrentTorValidationSession(
    session: VpnSession,
    expectedOwner: TorProbeProxyOwner,
) {
    if (
        !activeSession.matchesRuntimeValidationSession(session) ||
        runtimeSupervisor.currentGeneration() != expectedOwner.runtimeGeneration
    ) {
        throw TorProbeProxyUnavailableException(TorProbeProxyFailure.STALE_GENERATION)
    }
}

private fun TorProbeProxyLease.requireAuthenticatedTorValidationLease(
    expectedOwner: TorProbeProxyOwner,
) {
    val failure =
        when {
            owner != expectedOwner -> TorProbeProxyFailure.STALE_GENERATION
            access.type != ProxyAccessType.HTTP ||
                access.host != "127.0.0.1" ||
                access.port !in 1..65535 -> TorProbeProxyFailure.INVALID_LOOPBACK_ADDRESS
            access.username.isNullOrBlank() || access.password.isNullOrBlank() ->
                TorProbeProxyFailure.START_REFUSED
            else -> null
        }
    if (failure != null) {
        throw TorProbeProxyUnavailableException(failure)
    }
}

private const val STRICT_TOR_CONTROL_PROBE_ENDPOINT = "https://cp.cloudflare.com/generate_204"

internal suspend fun FoxholeVpnService.publishRuntimeProxyValidatedIpInfo(
    info: IpInfo,
    session: VpnSession?,
) {
    if (!canPublishValidationResult(session)) {
        container.diagnosticsLogger.record(
            "ip",
            "runtime proxy validation ip refresh ignored for stale session sessionId=${session?.correlationId.orEmpty()}",
        )
    } else {
        publishRuntimeProxyValidatedIpInfoForCurrentSession(info, session)
    }
}

private suspend fun FoxholeVpnService.publishRuntimeProxyValidatedIpInfoForCurrentSession(
    info: IpInfo,
    session: VpnSession?,
) {
    val settings = container.settingsRepository.current()
    val snapshot = FoxholeVpnRuntimeBridge.snapshot.value
    if (session?.profileId == FoxholeVpnService.TOR_ONLY_PROFILE_ID) {
        bridgeWriter.updateIpInfo(info)
        bridgeWriter.updateTorRouteIpInfo(info)
        container.diagnosticsLogger.record("ip", "runtime proxy validation ip refresh published to dashboard")
    } else if (session.requiresVerifiedTorExitForValidation()) {
        bridgeWriter.updateTorRouteIpInfo(info)
        container.diagnosticsLogger.record(
            "ip",
            "verified tor route exit published from service-owned validation probe",
        )
    } else if (settings.shouldPublishRuntimeProxyIpInfoToDashboard(snapshot)) {
        bridgeWriter.updateIpInfo(info)
        container.diagnosticsLogger.record("ip", "runtime proxy validation ip refresh published to dashboard")
    } else if (info.isDistinctTorRouteExit(FoxholeVpnRuntimeBridge.ipInfo.value)) {
        bridgeWriter.updateTorRouteIpInfo(info)
        container.diagnosticsLogger.record(
            "ip",
            "tor route exit published from runtime proxy validation while tor route is inside vpn",
        )
    } else {
        container.diagnosticsLogger.record(
            "ip",
            "runtime proxy validation ip refresh kept out of dashboard while tor route is inside vpn",
        )
    }
}
