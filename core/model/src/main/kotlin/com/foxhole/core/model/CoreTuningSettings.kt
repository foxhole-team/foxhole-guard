package com.foxhole.core.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

@Serializable
@Immutable
data class CoreTuningSettings(
    val workerThreads: Int = AUTO_WORKER_THREADS,
    val connectTimeoutMs: Int = 10_000,
    val handshakeTimeoutMs: Int = 15_000,
    val relayBufferBytes: Int = 16_384,
    val maxTcpFlows: Int = 1_024,
    val maxUdpFlows: Int = 512,
    val dnsTimeoutMs: Int = 5_000,
    val dnsCacheSize: Int = 4_096,
) {
    fun normalized(): CoreTuningSettings = copy(
        workerThreads = if (workerThreads == AUTO_WORKER_THREADS) {
            AUTO_WORKER_THREADS
        } else {
            workerThreads.coerceIn(CoreTuningBounds.workerThreads)
        },
        connectTimeoutMs = connectTimeoutMs.coerceIn(CoreTuningBounds.connectTimeoutMs),
        handshakeTimeoutMs = handshakeTimeoutMs.coerceIn(CoreTuningBounds.handshakeTimeoutMs),
        relayBufferBytes = relayBufferBytes.coerceIn(CoreTuningBounds.relayBufferBytes),
        maxTcpFlows = maxTcpFlows.coerceIn(CoreTuningBounds.flowCount),
        maxUdpFlows = maxUdpFlows.coerceIn(CoreTuningBounds.flowCount),
        dnsTimeoutMs = dnsTimeoutMs.coerceIn(CoreTuningBounds.dnsTimeoutMs),
        dnsCacheSize = dnsCacheSize.coerceIn(CoreTuningBounds.dnsCacheSize),
    )

    fun isValid(): Boolean = this == normalized()

    companion object {
        const val AUTO_WORKER_THREADS = 0
    }
}

object CoreTuningBounds {
    val workerThreads = 1..4
    val connectTimeoutMs = 100..120_000
    val handshakeTimeoutMs = 100..300_000
    val relayBufferBytes = 4_096..262_144
    val flowCount = 1..32_768
    val dnsTimeoutMs = 100..30_000
    val dnsCacheSize = 1..65_536
}
