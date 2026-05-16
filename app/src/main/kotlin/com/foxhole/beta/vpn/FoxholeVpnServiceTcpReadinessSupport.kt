package com.foxhole.beta.vpn

import com.foxhole.beta.core.model.VpnSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun tcpRuntimeReadinessTarget(session: VpnSession): VpnHealthProbeTarget? =
    VpnHealthProbeTargetSelector
        .select(session.configJson)
        ?.takeIf { target -> target.transport == VpnHealthProbeTransport.TCP }

internal suspend fun FoxholeVpnService.prepareTcpRuntimeReadiness(target: VpnHealthProbeTarget?): Result<Unit> {
    if (target == null) {
        return Result.success(Unit)
    }
    return withContext(Dispatchers.IO) {
        val upstreamNetwork = currentUpstreamNetworkOrNull()
        if (upstreamNetwork == null) {
            container.diagnosticsLogger.record("runtime", "tcp target preflight skipped: upstream network unavailable")
            return@withContext Result.success(Unit)
        }
        val probeResult = runCatching {
            probeSessionTarget(
                target = target,
                network = upstreamNetwork,
                timeoutMs = TCP_RUNTIME_PREFLIGHT_TIMEOUT_MS,
            )
            Unit
        }.onSuccess {
            container.diagnosticsLogger.record("runtime", "tcp target preflight passed")
        }.onFailure { error ->
            container.diagnosticsLogger.record(
                "runtime",
                "tcp target preflight failed, continuing runtime start: ${error.message.orEmpty()}",
            )
        }
        probeResult.fold(
            onSuccess = { Result.success(Unit) },
            onFailure = { Result.success(Unit) },
        )
    }
}

internal fun FoxholeVpnService.requestTcpRuntimeNetworkReset(target: VpnHealthProbeTarget?) {
    if (target == null) {
        return
    }
    container.diagnosticsLogger.record("runtime", "tcp runtime default network reset requested")
    runtime.onDefaultNetworkAvailable()
}

private const val TCP_RUNTIME_PREFLIGHT_TIMEOUT_MS = 1_500L
