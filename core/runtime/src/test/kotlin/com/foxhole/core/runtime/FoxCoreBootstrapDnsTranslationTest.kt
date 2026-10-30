package com.foxhole.core.runtime

import com.foxhole.core.model.ProtocolHint
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

internal class FoxCoreBootstrapDnsTranslationTest : FoxCoreConfigTranslatorTestSupport() {
    private val bootstrap =
        Json.parseToJsonElement(
            """{"domains":["group.example"],"upstreams":[{"type":"dot","host":"resolver.example","port":1853,"server_ip":"203.0.113.53","insecure":false}],"timeout_ms":3000,"max_candidates":8}""",
        ).jsonObject

    private fun primary(ip: String? = null): JsonObject =
        buildJsonObject {
            put("type", "vless")
            put("tag", "node")
            put("server", "group.example")
            put("server_port", 443)
            put("uuid", "11111111-1111-4111-8111-111111111111")
            put("tls", tls(serverName = "tls.example"))
            ip?.let { put("server_ip", it) }
        }

    @Test fun bootstrapMetadataIsIndependentOfNormalTunnelDns() {
        val plain = engine(translate(ProtocolHint.VLESS, primary()))
        val scoped =
            engine(
                translate(
                    ProtocolHint.VLESS,
                    primary(),
                    rootExtension = { put("bootstrap_dns", bootstrap) },
                ),
            )
        assertEquals(bootstrap, scoped.getValue("bootstrap_dns"))
        assertEquals(plain.getValue("dns"), scoped.getValue("dns"))
        assertEquals(plain.getValue("outbound"), scoped.getValue("outbound"))
    }

    @Test fun pinnedNodeIpCannotBypassTheDeclaredGroupBootstrap() {
        val failure =
            assertThrows(FoxCoreConfigTranslationException::class.java) {
                translate(
                    ProtocolHint.VLESS,
                    primary("203.0.113.10"),
                    rootExtension = { put("bootstrap_dns", bootstrap) },
                )
            }
        assertEquals(FoxCoreConfigRejection.INVALID_SHAPE, failure.rejection)
        assertEquals("$.bootstrap_dns", failure.path)
    }
}
