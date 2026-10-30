package com.foxhole.guard.core.settings

import com.foxhole.core.model.CoreTuningSettings
import com.foxhole.core.model.ExpertSettings
import com.foxhole.core.model.Settings
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

internal class CoreTuningSettingsPersistenceTest : SettingsRepositoryTestSupport() {
    @Test
    fun `legacy omitted tuning adopts auto with other defaults unchanged`() {
        assertEquals(
            CoreTuningSettings(),
            json.decodeFromString<Settings>("""{"expert":{"sniff":false}}""").expert.core
        )
    }

    @Test
    fun `custom tuning survives encrypted payload normalization and safe mode`() {
        val tuning = CoreTuningSettings(
            workerThreads = 2,
            connectTimeoutMs = 7_000,
            handshakeTimeoutMs = 9_000,
            relayBufferBytes = 8_192,
            maxTcpFlows = 512,
            maxUdpFlows = 256,
            dnsTimeoutMs = 3_000,
            dnsCacheSize = 2_048
        )
        val settings = Settings(expert = ExpertSettings(core = tuning)).normalized()
        val restored = json.decodeFromString<Settings>(json.encodeToString(settings)).normalized()

        assertEquals(tuning, restored.expert.core)
        assertTrue(restored.connection.safeModeEnabled)
    }

    @Test
    fun `stored out of range tuning is clamped before any runtime consumes it`() {
        val settings = Settings(
            expert = ExpertSettings(
                core = CoreTuningSettings(
                    workerThreads = -1,
                    connectTimeoutMs = -1,
                    handshakeTimeoutMs = 400_000,
                    relayBufferBytes = 0,
                    maxTcpFlows = 40_000,
                    maxUdpFlows = 0,
                    dnsTimeoutMs = 0,
                    dnsCacheSize = 70_000,
                )
            )
        ).normalized()

        assertEquals(
            CoreTuningSettings(
                workerThreads = 1,
                connectTimeoutMs = 100,
                handshakeTimeoutMs = 300_000,
                relayBufferBytes = 4_096,
                maxTcpFlows = 32_768,
                maxUdpFlows = 1,
                dnsTimeoutMs = 100,
                dnsCacheSize = 65_536
            ),
            settings.expert.core
        )
    }

    @Test
    fun `saved explicit four workers survives payload and normalization`() {
        val restored = json.decodeFromString<Settings>(
            """{"expert":{"core":{"workerThreads":4}}}"""
        ).normalized()
        assertEquals(4, restored.expert.core.workerThreads)
        val roundTrip = json.decodeFromString<Settings>(json.encodeToString(restored)).normalized()
        assertEquals(4, roundTrip.expert.core.workerThreads)
    }

    @Test
    fun `saved auto sentinel remains auto after normalization`() {
        val restored = json.decodeFromString<Settings>(
            """{"expert":{"core":{"workerThreads":0}}}"""
        ).normalized()
        assertEquals(CoreTuningSettings.AUTO_WORKER_THREADS, restored.expert.core.workerThreads)
        assertTrue(restored.expert.core.isValid())
    }
}
