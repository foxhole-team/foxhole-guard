@file:Suppress("MatchingDeclarationName")

package com.foxhole.guard.runtime

import android.net.Network
import com.foxhole.core.model.IpInfo
import com.foxhole.core.model.Settings
import com.foxhole.core.model.VpnSession
import com.foxhole.core.model.isDistinctTorRouteExit
import com.foxhole.core.model.withDnsServers
import com.foxhole.core.runtime.FoxholeVpnRuntimeBridge
import com.foxhole.core.runtime.TunnelValidationPolicyContext
import com.foxhole.core.runtime.TunnelValidationProbeKind
import com.foxhole.core.runtime.VpnDnsServerSelector
import com.foxhole.core.runtime.acceptsTunnelValidationProbe
import com.foxhole.core.runtime.dnsServerAddresses
import com.foxhole.core.runtime.network.IpInfoFetchMode
import com.foxhole.core.runtime.shouldPublishRuntimeProxyIpInfoToDashboard
import com.foxhole.core.runtime.tunnelRuntimeProxyAccess
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

internal data class RuntimeProxyEgressValidationResult(
    val kind: TunnelValidationProbeKind,
    val ipInfo: IpInfo? = null,
)

internal fun acceptsRuntimeProxyValidationResult(
    session: VpnSession?,
    result: RuntimeProxyEgressValidationResult,
): Boolean {
    if (session?.profileId != FoxholeVpnService.TOR_ONLY_PROFILE_ID) {
        return true
    }
    return result.kind == TunnelValidationProbeKind.TUNNEL_RUNTIME_PROXY_IP_REFRESH &&
        result.ipInfo != null
}

internal suspend fun FoxholeVpnService.validateRuntimeProxyEgressWithWarmup(
    settings: Settings,
    vpnNetwork: Network,
    session: VpnSession?,
    validationPolicyContext: TunnelValidationPolicyContext,
    preferIpv4Validation: Boolean,
    endpointCallTimeoutMs: Long,
    ipRefreshCallTimeoutMs: Long,
    totalTimeoutMs: Long,
    retryDelayMs: Long,
): RuntimeProxyEgressValidationResult {
    val startedAt = System.currentTimeMillis()
    val requiresVerifiedTorExit = session?.profileId == FoxholeVpnService.TOR_ONLY_PROFILE_ID
    var attempt = 0
    var lastFailure: Throwable? = null
    while (shouldContinueRuntimeProxyWarmup(startedAt, totalTimeoutMs)) {
        attempt += 1
        val ipRefresh =
            attemptRuntimeProxyIpRefreshValidation(
                settings = settings,
                vpnNetwork = vpnNetwork,
                session = session,
                validationPolicyContext = validationPolicyContext,
                callTimeoutMs = ipRefreshCallTimeoutMs,
                preferIpv4Validation = preferIpv4Validation,
                attempt = attempt,
                startedAt = startedAt,
            )
        if (ipRefresh.isSuccess) {
            return ipRefresh.getOrThrow()
        }
        val fallbackResult =
            if (requiresVerifiedTorExit) {
                logStrictTorValidationRetry(attempt)
                ipRefresh
            } else {
                attemptRuntimeProxyEndpointValidation(
                    settings = settings,
                    validationPolicyContext = validationPolicyContext,
                    callTimeoutMs = endpointCallTimeoutMs,
                    attempt = attempt,
                    startedAt = startedAt,
                )
            }
        if (fallbackResult.isSuccess) {
            return fallbackResult.getOrThrow()
        }
        lastFailure = fallbackResult.exceptionOrNull() ?: ipRefresh.exceptionOrNull()
        val elapsedMs = System.currentTimeMillis() - startedAt
        val remainingMs = totalTimeoutMs - elapsedMs
        if (remainingMs <= retryDelayMs) {
            break
        }
        delay(retryDelayMs.coerceAtMost(remainingMs))
    }
    lastFailure?.let { throw it }
    error("runtime proxy egress failed")
}

private fun FoxholeVpnService.logStrictTorValidationRetry(attempt: Int) {
    container.diagnosticsLogger.record(
        "dns",
        "verified tor exit unavailable on warmup attempt=$attempt; generic proxy endpoint is not accepted",
    )
}

private suspend fun shouldContinueRuntimeProxyWarmup(
    startedAt: Long,
    totalTimeoutMs: Long,
): Boolean =
    System.currentTimeMillis() - startedAt < totalTimeoutMs &&
        currentCoroutineContext().isActive

private suspend fun FoxholeVpnService.attemptRuntimeProxyIpRefreshValidation(
    settings: Settings,
    vpnNetwork: Network,
    session: VpnSession?,
    validationPolicyContext: TunnelValidationPolicyContext,
    callTimeoutMs: Long,
    preferIpv4Validation: Boolean,
    attempt: Int,
    startedAt: Long,
): Result<RuntimeProxyEgressValidationResult> {
    val result =
        runCatchingUnlessCancelled {
            fetchRuntimeProxyValidationIpInfo(
                settings = settings,
                vpnNetwork = vpnNetwork,
                session = session,
                callTimeoutMs = callTimeoutMs,
                preferIpv4Validation = preferIpv4Validation,
            )
        }
    if (result.isSuccess) {
        if (
            acceptsTunnelValidationProbe(
                TunnelValidationProbeKind.TUNNEL_RUNTIME_PROXY_IP_REFRESH,
                validationPolicyContext,
            )
        ) {
            logRuntimeProxyWarmupRecovery(attempt, "runtime proxy ip refresh recovered after warmup attempt=$attempt")
            return Result.success(
                RuntimeProxyEgressValidationResult(
                    kind = TunnelValidationProbeKind.TUNNEL_RUNTIME_PROXY_IP_REFRESH,
                    ipInfo = result.getOrThrow(),
                ),
            )
        }
        container.diagnosticsLogger.record(
            "dns",
            "runtime proxy ip refresh passed but is not accepted as tunnel validation",
        )
    }
    val elapsedMs = System.currentTimeMillis() - startedAt
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
    startedAt: Long,
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
    val elapsedMs = System.currentTimeMillis() - startedAt
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

private suspend fun FoxholeVpnService.fetchRuntimeProxyValidationIpInfo(
    settings: Settings,
    vpnNetwork: Network,
    session: VpnSession?,
    callTimeoutMs: Long,
    preferIpv4Validation: Boolean,
): IpInfo {
    val remoteDnsServers = VpnDnsServerSelector.remoteDnsServerAddresses(session?.configJson)
    // For a Tor-carrying session, lead the egress validation with the Tor Project exit check so a
    // slow/hostile Tor exit can't time this probe out (which used to fail the tunnel and tear the
    // Tor session down after bootstrap).
    val torExit = activeSessionCarriesTor()
    val info =
        if (session?.profileId == FoxholeVpnService.TOR_ONLY_PROFILE_ID) {
            container.ipInfoRepository.fetchVerifiedTorExit(
                callTimeoutMs = callTimeoutMs,
                proxy = settings.tunnelRuntimeProxyAccess(),
                resolverNetwork = currentUpstreamNetworkOrNull(),
            )
        } else if (preferIpv4Validation) {
            container.ipInfoRepository.fetchIpv4(
                endpoint = settings.connection.ipInfoEndpoint,
                callTimeoutMs = callTimeoutMs,
                proxy = settings.tunnelRuntimeProxyAccess(),
                resolverNetwork = currentUpstreamNetworkOrNull(),
                torExit = torExit,
            ) ?: error("runtime proxy ipv4 refresh failed")
        } else {
            container.ipInfoRepository.fetch(
                endpoint = settings.connection.ipInfoEndpoint,
                callTimeoutMs = callTimeoutMs,
                proxy = settings.tunnelRuntimeProxyAccess(),
                resolverNetwork = currentUpstreamNetworkOrNull(),
                mode = IpInfoFetchMode.ENTRY_QUICK,
                torExit = torExit,
            )
        }
    return info.withDnsServers(
        localDnsServers = connectivityManager.dnsServerAddresses(vpnNetwork),
        remoteDnsServers = remoteDnsServers,
    )
}

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
        publishRuntimeProxyValidatedIpInfoForCurrentSession(info)
    }
}

private suspend fun FoxholeVpnService.publishRuntimeProxyValidatedIpInfoForCurrentSession(info: IpInfo) {
    val settings = container.settingsRepository.current()
    val snapshot = FoxholeVpnRuntimeBridge.snapshot.value
    if (settings.shouldPublishRuntimeProxyIpInfoToDashboard(snapshot)) {
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
