package com.foxhole.beta.vpn

import com.foxhole.beta.core.network.HttpProxyAccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

internal suspend fun FoxholeVpnService.probeHttpProxyPayloadEndpoints(
    proxy: HttpProxyAccess,
    callTimeoutMs: Long,
) {
    var lastFailure: Throwable? = null
    connectivityProbeEndpoints().forEach { endpoint ->
        val result =
            runCatchingUnlessCancelled {
                container.ipInfoRepository.probeLatency(
                    endpoint = endpoint,
                    callTimeoutMs = callTimeoutMs,
                    proxy = proxy,
                )
            }
        if (result.isSuccess) {
            container.diagnosticsLogger.record(
                "health",
                "proxy payload ok: $endpoint elapsed_ms=${result.getOrThrow()}",
            )
            return
        }
        lastFailure = result.exceptionOrNull()
        container.diagnosticsLogger.record(
            "health",
            "proxy payload failed: $endpoint reason=${lastFailure?.message.orEmpty()}",
        )
    }
    throwProxyProbeFailure(lastFailure = lastFailure, fallbackMessage = "proxy payload probe failed")
}

internal suspend fun FoxholeVpnService.probeProxyConnectivityEndpoints(
    proxy: HttpProxyAccess,
    callTimeoutMs: Long,
) {
    var lastFailure: Throwable? = null
    connectivityProbeEndpoints().forEach { endpoint ->
        val result =
            runCatchingUnlessCancelled {
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
            throwProxyProbeFailure(lastFailure = lastFailure, fallbackMessage = "proxy probe cancelled")
        }
    }
    throwProxyProbeFailure(lastFailure = lastFailure, fallbackMessage = "proxy probe failed")
}

private suspend inline fun <T> runCatchingUnlessCancelled(crossinline block: suspend () -> T): Result<T> =
    runCatching { block() }
        .onFailure { error ->
            if (error is CancellationException) {
                throw error
            }
        }

private fun throwProxyProbeFailure(
    lastFailure: Throwable?,
    fallbackMessage: String,
): Nothing {
    lastFailure?.let { throw it }
    error(fallbackMessage)
}
