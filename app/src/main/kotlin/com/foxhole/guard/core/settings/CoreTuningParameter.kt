package com.foxhole.guard.core.settings

import com.foxhole.core.model.CoreTuningBounds
import com.foxhole.core.model.CoreTuningSettings

internal enum class CoreTuningParameter(val bounds: IntRange, val presets: List<Int>) {
    WORKERS(CoreTuningBounds.workerThreads, listOf(CoreTuningSettings.AUTO_WORKER_THREADS, 1, 2, 4)),
    CONNECT_TIMEOUT(CoreTuningBounds.connectTimeoutMs, listOf(3_000, 5_000, 10_000, 20_000, 30_000)),
    HANDSHAKE_TIMEOUT(CoreTuningBounds.handshakeTimeoutMs, listOf(5_000, 10_000, 15_000, 30_000, 60_000)),
    RELAY_BUFFER(CoreTuningBounds.relayBufferBytes, listOf(4_096, 8_192, 16_384, 32_768, 65_536)),
    TCP_FLOWS(CoreTuningBounds.flowCount, listOf(256, 512, 1_024, 2_048, 4_096)),
    UDP_FLOWS(CoreTuningBounds.flowCount, listOf(128, 256, 512, 1_024, 2_048)),
    DNS_TIMEOUT(CoreTuningBounds.dnsTimeoutMs, listOf(1_000, 3_000, 5_000, 10_000)),
    DNS_CACHE(CoreTuningBounds.dnsCacheSize, listOf(512, 1_024, 4_096, 8_192, 16_384)),
    ;

    fun value(settings: CoreTuningSettings): Int = when (this) {
        WORKERS -> settings.workerThreads
        CONNECT_TIMEOUT -> settings.connectTimeoutMs
        HANDSHAKE_TIMEOUT -> settings.handshakeTimeoutMs
        RELAY_BUFFER -> settings.relayBufferBytes
        TCP_FLOWS -> settings.maxTcpFlows
        UDP_FLOWS -> settings.maxUdpFlows
        DNS_TIMEOUT -> settings.dnsTimeoutMs
        DNS_CACHE -> settings.dnsCacheSize
    }

    fun withValue(settings: CoreTuningSettings, value: Int): CoreTuningSettings {
        require(value in bounds || (this == WORKERS && value == CoreTuningSettings.AUTO_WORKER_THREADS)) {
            "core setting is outside the supported range"
        }
        return when (this) {
            WORKERS -> settings.copy(workerThreads = value)
            CONNECT_TIMEOUT -> settings.copy(connectTimeoutMs = value)
            HANDSHAKE_TIMEOUT -> settings.copy(handshakeTimeoutMs = value)
            RELAY_BUFFER -> settings.copy(relayBufferBytes = value)
            TCP_FLOWS -> settings.copy(maxTcpFlows = value)
            UDP_FLOWS -> settings.copy(maxUdpFlows = value)
            DNS_TIMEOUT -> settings.copy(dnsTimeoutMs = value)
            DNS_CACHE -> settings.copy(dnsCacheSize = value)
        }
    }
}

internal suspend fun SettingsRepository.updateCoreTuning(parameter: CoreTuningParameter, value: Int) = update { current ->
    current.copy(expert = current.expert.copy(core = parameter.withValue(current.expert.core, value)))
}

internal suspend fun SettingsRepository.resetCoreTuning() = update { current ->
    current.copy(expert = current.expert.copy(core = CoreTuningSettings()))
}
