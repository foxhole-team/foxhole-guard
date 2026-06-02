package com.foxhole.beta.vpn

import android.os.SystemClock
import com.foxhole.beta.core.model.VpnSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.InetSocketAddress

internal fun tcpRuntimeReadinessTarget(session: VpnSession): VpnHealthProbeTarget? =
    VpnHealthProbeTargetSelector
        .select(session.configJson)
        ?.takeIf { target -> target.transport == VpnHealthProbeTransport.TCP }

internal fun activeServerPingTarget(
    session: VpnSession,
    target: VpnHealthProbeTarget? = tcpRuntimeReadinessTarget(session),
    readiness: TcpRuntimeReadinessResult? = null,
): ActiveServerPingTarget? =
    target?.let {
        ActiveServerPingTarget(
            profileId = session.profileId,
            protocolOptionId = session.protocolOptionId,
            target = it,
            resolvedAddress = readiness?.address,
        )
    }

internal data class TcpRuntimeReadinessResult(
    val address: InetAddress,
    val latencyMs: Long,
)

internal suspend fun FoxholeVpnService.prepareTcpRuntimeReadiness(
    target: VpnHealthProbeTarget?,
): Result<TcpRuntimeReadinessResult?> {
    if (target == null) {
        return Result.success(null)
    }
    return withContext(Dispatchers.IO) {
        val upstreamNetwork = currentUpstreamNetworkOrNull()
        if (upstreamNetwork == null) {
            container.diagnosticsLogger.record("runtime", "tcp target preflight skipped: upstream network unavailable")
            return@withContext Result.success(null)
        }
        val probeResult = runCatching {
            val address = resolveProbeAddress(target.host, upstreamNetwork)
            val startedAt = SystemClock.elapsedRealtime()
            upstreamNetwork.socketFactory.createSocket().use { socket ->
                check(protect(socket)) { "tcp target preflight socket protect failed" }
                socket.soTimeout = TCP_RUNTIME_PREFLIGHT_TIMEOUT_MS.toInt()
                socket.connect(
                    InetSocketAddress(address, target.port),
                    TCP_RUNTIME_PREFLIGHT_TIMEOUT_MS.toInt(),
                )
            }
            TcpRuntimeReadinessResult(
                address = address,
                latencyMs = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(1L),
            )
        }.onSuccess {
            container.diagnosticsLogger.record("runtime", "tcp target preflight passed")
        }.onFailure { error ->
            container.diagnosticsLogger.record(
                "runtime",
                "tcp target preflight failed, continuing runtime start: ${error.message.orEmpty()}",
            )
        }
        probeResult.fold(
            onSuccess = { result -> Result.success(result) },
            onFailure = { Result.success(null) },
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
