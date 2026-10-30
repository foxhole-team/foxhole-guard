package com.foxhole.core.model

import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreTuningSettingsTest {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    @Test
    fun newDefaultsUseAutoAndKeepAllOtherResourceDefaults() {
        val defaults = CoreTuningSettings()
        assertEquals(0, defaults.workerThreads)
        assertEquals(10_000, defaults.connectTimeoutMs)
        assertEquals(15_000, defaults.handshakeTimeoutMs)
        assertEquals(16_384, defaults.relayBufferBytes)
        assertEquals(1_024, defaults.maxTcpFlows)
        assertEquals(512, defaults.maxUdpFlows)
        assertEquals(5_000, defaults.dnsTimeoutMs)
        assertEquals(4_096, defaults.dnsCacheSize)
        assertTrue(defaults.isValid())
    }

    @Test
    fun autoAndPreviouslyValidManualWorkersRemainValid() {
        (0..4).forEach { workers ->
            val settings = CoreTuningSettings(workerThreads = workers)
            assertEquals(settings, settings.normalized())
            assertTrue(settings.isValid())
        }
    }

    @Test
    fun invalidWorkersRetainPriorManualClamping() {
        assertEquals(1, CoreTuningSettings(workerThreads = -1).normalized().workerThreads)
        assertEquals(4, CoreTuningSettings(workerThreads = 8).normalized().workerThreads)
    }

    @Test
    fun explicitManualFourAndAutoRoundTripWithBackupStyleDefaults() {
        listOf(0, 4).forEach { workers ->
            val original = CoreTuningSettings(workerThreads = workers)
            val restored = json.decodeFromString<CoreTuningSettings>(json.encodeToString(original)).normalized()
            assertEquals(original, restored)
        }
    }

    @Test
    fun missingWorkersAndUnknownFieldsUseAutoWithoutLosingExplicitFour() {
        assertEquals(0, json.decodeFromString<CoreTuningSettings>("""{"futureOption":true}""").workerThreads)
        assertEquals(
            4,
            json.decodeFromString<CoreTuningSettings>(
                """{"workerThreads":4,"futureOption":true}"""
            ).workerThreads,
        )
    }

    @Test
    fun malformedWorkerTypeIsNotSilentlyConvertedToAuto() {
        assertThrows(SerializationException::class.java) {
            json.decodeFromString<CoreTuningSettings>("""{"workerThreads":"invalid"}""")
        }
    }
}
