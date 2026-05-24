package com.foxhole.beta.vpn

import com.foxhole.beta.core.network.HttpProxyAccess
import com.foxhole.beta.core.network.ProxyAccessType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch

internal suspend fun FoxholeVpnService.probeHttpProxyPayloadEndpoints(
    proxy: HttpProxyAccess,
    callTimeoutMs: Long,
) {
    probeProxyEndpointsConcurrently(
        fallbackMessage = "proxy payload probe failed",
        probe = { endpoint ->
            container.ipInfoRepository.probeLatency(
                endpoint = endpoint,
                callTimeoutMs = callTimeoutMs,
                proxy = proxy,
            )
        },
        onSuccess = { endpoint, elapsedMs ->
            container.diagnosticsLogger.record(
                "health",
                "proxy payload ok: $endpoint elapsed_ms=$elapsedMs",
            )
        },
        onFailure = { endpoint, error ->
            container.diagnosticsLogger.record(
                "health",
                "proxy payload failed: $endpoint reason=${error.message.orEmpty()}",
            )
        },
    )
}

internal suspend fun FoxholeVpnService.probeProxyConnectivityEndpoints(
    proxy: HttpProxyAccess,
    callTimeoutMs: Long,
) {
    probeProxyEndpointsConcurrently(
        fallbackMessage = "proxy probe failed",
        probe = { endpoint ->
            container.ipInfoRepository.probe(
                endpoint = endpoint,
                callTimeoutMs = callTimeoutMs,
                proxy = proxy,
            )
        },
        onSuccess = { endpoint, _ ->
            container.diagnosticsLogger.record("health", "proxy probe ok: $endpoint")
        },
        onFailure = { endpoint, error ->
            container.diagnosticsLogger.record(
                "health",
                "proxy probe failed: $endpoint reason=${error.message.orEmpty()}",
            )
        },
    )
}

internal suspend fun FoxholeVpnService.probeConnectivityEndpointsOverLocalProxyInternal(
    proxy: HttpProxyAccess,
    callTimeoutMs: Long,
) {
    if (proxy.type == ProxyAccessType.HTTP) {
        probeHttpProxyPayloadEndpoints(proxy = proxy, callTimeoutMs = callTimeoutMs)
    } else {
        probeProxyConnectivityEndpoints(proxy = proxy, callTimeoutMs = callTimeoutMs)
    }
}

private suspend fun <T> FoxholeVpnService.probeProxyEndpointsConcurrently(
    fallbackMessage: String,
    probe: suspend (String) -> T,
    onSuccess: (String, T) -> Unit,
    onFailure: (String, Throwable) -> Unit,
): T {
    val endpoints = connectivityProbeEndpoints()
    if (endpoints.isEmpty()) {
        error(fallbackMessage)
    }
    return coroutineScope {
        val results = Channel<ProxyEndpointProbeResult<T>>(capacity = endpoints.size)
        endpoints.forEach { endpoint ->
            launch {
                val result =
                    runCatchingUnlessCancelled {
                        probe(endpoint)
                    }
                results.send(ProxyEndpointProbeResult(endpoint = endpoint, result = result))
            }
        }
        var lastFailure: Throwable? = null
        repeat(endpoints.size) {
            val endpointResult = results.receive()
            endpointResult.result
                .onSuccess { value ->
                    onSuccess(endpointResult.endpoint, value)
                    currentCoroutineContext().cancelChildren()
                    return@coroutineScope value
                }.onFailure { error ->
                    lastFailure = error
                    onFailure(endpointResult.endpoint, error)
                }
        }
        throwProxyProbeFailure(lastFailure = lastFailure, fallbackMessage = fallbackMessage)
    }
}

private data class ProxyEndpointProbeResult<T>(
    val endpoint: String,
    val result: Result<T>,
)

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
