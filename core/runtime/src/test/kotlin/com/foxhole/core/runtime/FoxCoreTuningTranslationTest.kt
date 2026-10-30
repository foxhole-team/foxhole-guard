package com.foxhole.core.runtime

import com.foxhole.core.model.CoreTuningSettings
import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.Settings
import com.foxhole.core.model.VpnSession
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

internal class FoxCoreTuningTranslationTest : FoxCoreConfigTranslatorTestSupport() {
    private fun session(tuning: CoreTuningSettings = CoreTuningSettings()) = VpnSession(
        profileId = 7,
        profileName = "tuning fixture",
        protocolHint = ProtocolHint.SHADOWSOCKS,
        configJson = legacyConfig(
            primary = primary("shadowsocks") {
                put("server", "edge.example")
                put("server_port", 443)
                put("method", "aes-128-gcm")
                put("password", "fixture-password")
            }
        ).toString(),
        correlationId = "tuning-fixture",
        coreTuning = tuning,
    )

    @Test
    fun `custom core values reach native runtime and both dns policy documents`() {
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
        val translated = translator.translate(session(tuning))
        val engine = engine(translated)
        val runtime = engine.getValue("runtime").jsonObject
        assertEquals("2", runtime.getValue("worker_threads").jsonPrimitive.content)
        assertEquals("7000", runtime.getValue("connect_timeout_ms").jsonPrimitive.content)
        assertEquals("9000", runtime.getValue("handshake_timeout_ms").jsonPrimitive.content)
        assertEquals("8192", runtime.getValue("relay_buffer_bytes").jsonPrimitive.content)
        assertEquals("512", runtime.getValue("max_tcp_flows").jsonPrimitive.content)
        assertEquals("256", runtime.getValue("max_udp_flows").jsonPrimitive.content)
        val dns = engine.getValue("dns").jsonObject
        assertEquals("3000", dns.getValue("timeout_ms").jsonPrimitive.content)
        assertEquals("2048", dns.getValue("cache_size").jsonPrimitive.content)
        assertEquals(dns, policy(translated).getValue("dns"))
    }

    @Test
    fun `out of range raw session tuning is refused at translation`() {
        assertThrows(FoxCoreConfigTranslationException::class.java) {
            translator.translate(session(CoreTuningSettings(workerThreads = -1)))
        }
    }

    @Test
    fun `auto resolves cpu count and always emits explicit native workers`() {
        val expected = mapOf(-1 to 1, 0 to 1, 1 to 1, 2 to 2, 4 to 4, 8 to 4, 64 to 4)
        expected.forEach { (cpus, workers) ->
            val runtime = foxCoreRuntimeConfig(CoreTuningSettings(), false, null, cpus)
            assertEquals(workers.toString(), runtime.getValue("worker_threads").jsonPrimitive.content)
            assertEquals(setOf("worker_threads"), runtime.keys)
        }
    }

    @Test
    fun `saved manual workers stay explicit regardless of available cpus`() {
        (1..4).forEach { workers ->
            val runtime = foxCoreRuntimeConfig(CoreTuningSettings(workerThreads = workers), false, null, 1)
            assertEquals(workers.toString(), runtime.getValue("worker_threads").jsonPrimitive.content)
        }
    }

    @Test
    fun `ordinary translation accepts auto and emits bounded workers`() {
        val runtime = engine(translator.translate(session())).getValue("runtime").jsonObject
        assertEquals(
            Runtime.getRuntime().availableProcessors().coerceIn(1..4).toString(),
            runtime.getValue("worker_threads").jsonPrimitive.content,
        )
    }

    @Test
    fun `runtime tuning changes the immutable engine fingerprint requiring restart`() {
        val original = translator.translate(session())
        val changed = translator.translate(session(CoreTuningSettings(relayBufferBytes = 32_768)))
        val starter = FoxCoreNativeSessionStarter(
            JniFoxCoreNativeApi,
            object : RuntimeDiagnosticsSink {
                override fun record(tag: String, message: String) = Unit
                override fun recordStructured(tag: String, headline: String, vararg details: String?) = Unit
            }
        )
        assertNotEquals(
            starter.immutableEngineFingerprint(original.engineConfigJson, null),
            starter.immutableEngineFingerprint(changed.engineConfigJson, null)
        )
    }

    @Test
    fun `core tuning participates in ordinary and local guard applied fingerprints`() {
        val assembler = RuntimeConfigAssembler(json)
        val original = Settings()
        val changed = original.copy(expert = original.expert.copy(core = CoreTuningSettings(workerThreads = 2)))
        assertNotEquals(assembler.runtimeFingerprint(original, null), assembler.runtimeFingerprint(changed, null))
        assertNotEquals(
            assembler.localGuardRuntimeFingerprint(original, LocalGuardMode.FIREWALL, null),
            assembler.localGuardRuntimeFingerprint(changed, LocalGuardMode.FIREWALL, null)
        )
    }
}
