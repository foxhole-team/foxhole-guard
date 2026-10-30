package com.foxhole.core.runtime

import com.foxhole.core.model.CoreTuningBounds
import com.foxhole.core.model.CoreTuningSettings
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun foxCoreRuntimeConfig(
    tuning: CoreTuningSettings,
    localGuard: Boolean,
    controlProxy: JsonObject?,
    availableProcessors: Int = Runtime.getRuntime().availableProcessors(),
): JsonObject = buildJsonObject {
    val defaults = CoreTuningSettings()
    if (localGuard) put("local_guard", true)
    controlProxy?.let { put("control_proxy", it) }
    put("worker_threads", tuning.resolvedWorkerThreads(availableProcessors))
    if (tuning.connectTimeoutMs != defaults.connectTimeoutMs) put("connect_timeout_ms", tuning.connectTimeoutMs)
    if (tuning.handshakeTimeoutMs != defaults.handshakeTimeoutMs) put("handshake_timeout_ms", tuning.handshakeTimeoutMs)
    if (tuning.relayBufferBytes != defaults.relayBufferBytes) put("relay_buffer_bytes", tuning.relayBufferBytes)
    if (tuning.maxTcpFlows != defaults.maxTcpFlows) put("max_tcp_flows", tuning.maxTcpFlows)
    if (tuning.maxUdpFlows != defaults.maxUdpFlows) put("max_udp_flows", tuning.maxUdpFlows)
}

internal fun CoreTuningSettings.resolvedWorkerThreads(availableProcessors: Int): Int =
    if (workerThreads == CoreTuningSettings.AUTO_WORKER_THREADS) {
        availableProcessors.coerceIn(CoreTuningBounds.workerThreads)
    } else {
        workerThreads
    }

internal fun FoxCorePolicyTranslation.withCoreTuning(tuning: CoreTuningSettings): FoxCorePolicyTranslation {
    val defaults = CoreTuningSettings()
    if (tuning.dnsTimeoutMs == defaults.dnsTimeoutMs && tuning.dnsCacheSize == defaults.dnsCacheSize) return this
    val configured = JsonObject(
        dns + buildJsonObject {
            if (tuning.dnsTimeoutMs != defaults.dnsTimeoutMs) put("timeout_ms", tuning.dnsTimeoutMs)
            if (tuning.dnsCacheSize != defaults.dnsCacheSize) put("cache_size", tuning.dnsCacheSize)
        }
    )
    return copy(dns = configured, document = JsonObject(document + ("dns" to configured)))
}
