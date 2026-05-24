@file:Suppress("MatchingDeclarationName")

package com.foxhole.beta.vpn

import android.net.Network
import com.foxhole.beta.core.model.IpInfo
import com.foxhole.beta.core.model.Settings
import com.foxhole.beta.core.model.VpnSession
import com.foxhole.beta.core.network.IpInfoFetchMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

internal data class RuntimeProxyEgressValidationResult(
    val kind: TunnelValidationProbeKind,
    val ipInfo: IpInfo? = null,
)

internal suspend fun FoxholeVpnService.validateRuntimeProxyEgressWithWarmup(
    settings: Settings,
    vpnNetwork: Network,
    session: VpnSession?,
    preferIpv4Validation: Boolean,
    endpointCallTimeoutMs: Long,
    ipRefreshCallTimeoutMs: Long,
    totalTimeoutMs: Long,
    retryDelayMs: Long,
): RuntimeProxyEgressValidationResult {
    val startedAt = System.currentTimeMillis()
    var attempt = 0
    var lastFailure: Throwable? = null
    while (shouldContinueRuntimeProxyWarmup(startedAt, totalTimeoutMs)) {
        attempt += 1
        val ipRefresh =
            attemptRuntimeProxyIpRefreshValidation(
                settings = settings,
                vpnNetwork = vpnNetwork,
                session = session,
                callTimeoutMs = ipRefreshCallTimeoutMs,
                preferIpv4Validation = preferIpv4Validation,
                attempt = attempt,
                startedAt = startedAt,
            )
        if (ipRefresh.isSuccess) {
            return ipRefresh.getOrThrow()
        }
        lastFailure = ipRefresh.exceptionOrNull()
        val endpointProbe =
            attemptRuntimeProxyEndpointValidation(
                settings = settings,
                callTimeoutMs = endpointCallTimeoutMs,
                attempt = attempt,
                startedAt = startedAt,
            )
        if (endpointProbe.isSuccess) {
            return endpointProbe.getOrThrow()
        }
        lastFailure = endpointProbe.exceptionOrNull() ?: lastFailure
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
        if (acceptsTunnelValidationProbe(TunnelValidationProbeKind.TUNNEL_RUNTIME_PROXY_IP_REFRESH)) {
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
    container.diagnosticsLogger.record(
        "dns",
        "runtime proxy ip refresh warmup attempt failed attempt=$attempt elapsed_ms=$elapsedMs reason=${result.exceptionOrNull()?.message.orEmpty()}",
    )
    return Result.failure(result.exceptionOrNull() ?: IllegalStateException("runtime proxy ip refresh not accepted"))
}

private suspend fun FoxholeVpnService.attemptRuntimeProxyEndpointValidation(
    settings: Settings,
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
        if (acceptsTunnelValidationProbe(TunnelValidationProbeKind.TUNNEL_RUNTIME_PROXY_VALIDATION_ENDPOINT)) {
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
    container.diagnosticsLogger.record(
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
    val info =
        if (preferIpv4Validation) {
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
        FoxholeVpnRuntimeBridge.updateIpInfo(info)
        container.diagnosticsLogger.record("ip", "runtime proxy validation ip refresh published to dashboard")
    } else {
        container.diagnosticsLogger.record(
            "ip",
            "runtime proxy validation ip refresh kept out of dashboard while tor route is inside vpn",
        )
    }
}

private suspend inline fun <T> runCatchingUnlessCancelled(crossinline block: suspend () -> T): Result<T> =
    runCatching { block() }
        .onFailure { error ->
            if (error is CancellationException) {
                throw error
            }
        }
