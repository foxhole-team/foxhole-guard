package com.foxhole.core.runtime

import com.foxhole.core.model.ProtocolHint
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

internal class FoxCoreAmneziaTranslationTest : FoxCoreConfigTranslatorTestSupport() {
    @Test
    fun `content padding numeric singletons and range strings reach FoxCore without narrowing`() {
        listOf(
            JsonPrimitive(0L),
            JsonPrimitive(65_536L),
            JsonPrimitive(4_294_967_295L),
            JsonPrimitive("0-4294967295"),
        ).forEach { cpa ->
            val outbound = translateAmnezia {
                putAll(FULL_BLOCK)
                put("content_padding_addition", cpa)
            }
            assertEquals(cpa, outbound.getValue("amnezia").jsonObject["content_padding_addition"])
            assertEquals(ZERO_WIREGUARD_KEY, outbound.getValue("private_key").jsonPrimitive.content)
            assertEquals(ONE_WIREGUARD_KEY, outbound.getValue("peer_public_key").jsonPrimitive.content)
        }
    }

    @Test
    fun `content padding absence and existing obfuscation fields retain their original translation`() {
        val old = translateAmnezia {
            putAll(FULL_BLOCK)
            put("random_trailers", true)
        }.getValue("amnezia").jsonObject
        assertNull(old["content_padding_addition"])
        val withCpa = translateAmnezia {
            putAll(FULL_BLOCK)
            put("random_trailers", true)
            put("content_padding_addition", "32-64")
        }.getValue("amnezia").jsonObject
        assertEquals(old, JsonObject(withCpa.filterKeys { it != "content_padding_addition" }))
        listOf("0-0" to 0L, "32-32" to 32L).forEach { (raw, expected) ->
            assertEquals(
                JsonPrimitive(expected),
                translateAmnezia {
                    put("content_padding_addition", raw)
                }.getValue("amnezia").jsonObject["content_padding_addition"],
            )
        }
    }

    @Test
    fun `content padding rejects noncanonical JSON and invalid uint32 ranges`() {
        listOf(
            "\"32\"", "true", "null", "32.0", "1e2", "-1", "4294967296", "[]", "{}",
            "\"2-1\"", "\"0-4294967296\"", "\"1-2-3\"",
        ).forEach { raw ->
            val failure = assertThrows(raw, FoxCoreConfigTranslationException::class.java) {
                translateAmnezia {
                    put("content_padding_addition", kotlinx.serialization.json.Json.parseToJsonElement(raw))
                }
            }
            assertEquals(raw, FoxCoreConfigRejection.INVALID_SHAPE, failure.rejection)
        }
    }

    @Test
    fun `CPA cannot make a malformed stored header protection key valid`() {
        val failure = assertThrows(FoxCoreConfigTranslationException::class.java) {
            translateAmnezia {
                put("content_padding_addition", 32)
                put("header_protection_key", "00")
            }
        }
        assertEquals(FoxCoreConfigRejection.INVALID_SHAPE, failure.rejection)
    }

    @Test
    fun `random trailers reach the native amnezia config as a boolean`() {
        val amnezia = translateAmnezia {
            putAll(
                FULL_BLOCK
            )
            put("random_trailers", true)
        }.getValue("amnezia").jsonObject
        assertEquals("true", amnezia.getValue("random_trailers").jsonPrimitive.content)
        assertFalse(amnezia.getValue("random_trailers").jsonPrimitive.isString)
    }

    @Test
    fun `random trailers must be a boolean in stored normalized configs`() {
        val failure = assertThrows(FoxCoreConfigTranslationException::class.java) {
            translateAmnezia {
                putAll(FULL_BLOCK)
                put("random_trailers", "true")
            }
        }
        assertEquals(FoxCoreConfigRejection.INVALID_SHAPE, failure.rejection)
    }

    @Test
    fun `every parameter family survives translation in the shape foxcore-api accepts`() {
        val outbound = translateAmnezia { putAll(FULL_BLOCK) }

        assertEquals("wireguard", outbound.getValue("type").jsonPrimitive.content)
        val amnezia = outbound.getValue("amnezia").jsonObject
        assertEquals("4", amnezia.getValue("junk_packet_count").jsonPrimitive.content)
        assertEquals("40", amnezia.getValue("junk_min_size").jsonPrimitive.content)
        assertEquals("70", amnezia.getValue("junk_max_size").jsonPrimitive.content)
        assertEquals("15", amnezia.getValue("init_junk_size").jsonPrimitive.content)
        assertEquals("20", amnezia.getValue("response_junk_size").jsonPrimitive.content)
        assertEquals("12", amnezia.getValue("cookie_junk_size").jsonPrimitive.content)
        assertEquals("24", amnezia.getValue("transport_junk_size").jsonPrimitive.content)
        assertEquals("10-19", amnezia.getValue("header_initiation").jsonPrimitive.content)
        assertTrue(amnezia.getValue("header_initiation").jsonPrimitive.isString)
        assertEquals("20", amnezia.getValue("header_response").jsonPrimitive.content)
        assertFalse(amnezia.getValue("header_response").jsonPrimitive.isString)
        assertEquals(2, amnezia.getValue("init_packets").jsonArray.size)
        assertEquals(
            "60",
            amnezia.getValue("timers").jsonObject.getValue("reject_after_time_s").jsonPrimitive.content,
        )
    }

    @Test
    fun `a plain wireguard endpoint gains no obfuscation`() {
        assertNull(translateAmnezia(amnezia = null)["amnezia"])
    }

    @Test
    fun `absent headers become the standard message types rather than being dropped`() {
        val amnezia =
            translateAmnezia {
                put("init_packets", FULL_BLOCK.getValue("init_packets"))
            }.getValue("amnezia").jsonObject

        assertEquals(
            listOf("1", "2", "3", "4"),
            listOf("header_initiation", "header_response", "header_cookie", "header_transport")
                .map { key -> amnezia.getValue(key).jsonPrimitive.content },
        )
    }

    @Test
    fun `a raw config cannot smuggle a junk shape past the translator`() {
        val cases =
            listOf(
                "empty junk datagrams" to
                    amneziaOverride {
                        put("junk_packet_count", 4)
                        put("junk_min_size", 0)
                        put("junk_max_size", 0)
                    },
                "inverted junk range" to
                    amneziaOverride {
                        put("junk_min_size", 80)
                        put("junk_max_size", 70)
                    },
                "junk at the profile MTU" to
                    amneziaOverride(mtu = 1280) {
                        put("junk_max_size", 1280)
                    },
                "junk above the size cap" to amneziaOverride { put("junk_max_size", 1281) },
                "more junk packets than the reference allows" to
                    amneziaOverride { put("junk_packet_count", 129) },
            )

        cases.forEach { (name, endpoint) ->
            val failure =
                assertThrows(name, FoxCoreConfigTranslationException::class.java) {
                    translateEndpoint(endpoint)
                }
            assertEquals(name, FoxCoreConfigRejection.INVALID_SHAPE, failure.rejection)
        }
    }

    @Test
    fun `an init packet template that renders nothing is refused`() {
        val failure =
            assertThrows(FoxCoreConfigTranslationException::class.java) {
                translateEndpoint(
                    amneziaOverride {
                        put(
                            "init_packets",
                            buildJsonArray {
                                add(initPacket(tag("payload"), tag("payload_base64")))
                            },
                        )
                    },
                )
            }

        assertEquals(FoxCoreConfigRejection.INVALID_SHAPE, failure.rejection)
        val kept =
            translateAmnezia {
                putAll(FULL_BLOCK)
                put(
                    "init_packets",
                    buildJsonArray {
                        add(initPacket(tag("payload"), bytesTag("aa"), tag("payload_base64")))
                    },
                )
            }.getValue("amnezia").jsonObject
        assertEquals(1, kept.getValue("init_packets").jsonArray.size)
    }

    @Test
    fun `overlapping headers and malformed ranges are refused`() {
        val cases =
            listOf(
                "overlapping intervals" to
                    amneziaOverride {
                        put("header_initiation", "10-20")
                        put("header_response", "20-30")
                    },
                "inverted interval" to amneziaOverride { put("header_initiation", "19-10") },
                "a range that is not two numbers" to
                    amneziaOverride { put("header_initiation", "ten-nineteen") },
                "a bare numeric string" to amneziaOverride { put("header_initiation", "10") },
            )

        cases.forEach { (name, endpoint) ->
            val failure =
                assertThrows(name, FoxCoreConfigTranslationException::class.java) {
                    translateEndpoint(endpoint)
                }
            assertEquals(name, FoxCoreConfigRejection.INVALID_SHAPE, failure.rejection)
        }
    }

    @Test
    fun `an unknown key in the block and an unknown init tag are both refused`() {
        val unknownKey =
            assertThrows(FoxCoreConfigTranslationException::class.java) {
                translateEndpoint(amneziaOverride { put("junk_packet_delay", 5) })
            }
        assertEquals(FoxCoreConfigRejection.UNSUPPORTED_FIELD, unknownKey.rejection)

        val unknownTag =
            assertThrows(FoxCoreConfigTranslationException::class.java) {
                translateEndpoint(
                    amneziaOverride {
                        put("init_packets", buildJsonArray { add(initPacket(tag("wait_timeout"))) })
                    },
                )
            }
        assertEquals(FoxCoreConfigRejection.PROTOCOL_UNSUPPORTED, unknownTag.rejection)
    }

    @Test
    fun `more than five init packets is refused because the reference stops at I5`() {
        val failure =
            assertThrows(FoxCoreConfigTranslationException::class.java) {
                translateEndpoint(
                    amneziaOverride {
                        put(
                            "init_packets",
                            buildJsonArray {
                                repeat(6) { add(initPacket(bytesTag("aabb"))) }
                            },
                        )
                    },
                )
            }

        assertEquals(FoxCoreConfigRejection.INVALID_SHAPE, failure.rejection)
    }

    @Test
    fun `a stored listen port is dropped rather than refused or forwarded`() {
        val outbound =
            translateEndpoint(
                amneziaEndpoint(mtu = 1420, amnezia = FULL_BLOCK) { put("listen_port", 51820) },
            )

        assertEquals("wireguard", outbound.getValue("type").jsonPrimitive.content)
        assertNull(outbound["listen_port"])
        assertEquals("70", outbound.getValue("amnezia").jsonObject.getValue("junk_max_size").jsonPrimitive.content)
    }

    @Test
    fun `HPK and CPA reach native JSON without changing WireGuard keys`() {
        val key = "AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA="
        val outbound = translateAmnezia {
            put("header_protection_key", key)
            put("init_junk_size", 12)
            put("response_junk_size", 12)
            put("cookie_junk_size", 12)
            put("transport_junk_size", 12)
            put("content_padding_addition", "7-13")
            put("random_trailers", true)
        }
        val block = outbound.getValue("amnezia").jsonObject
        assertEquals(JsonPrimitive(key), block["header_protection_key"])
        assertEquals(JsonPrimitive("7-13"), block["content_padding_addition"])
        assertEquals(JsonPrimitive(true), block["random_trailers"])
        assertEquals(ZERO_WIREGUARD_KEY, outbound.getValue("private_key").jsonPrimitive.content)
        listOf("init_junk_size", "response_junk_size", "cookie_junk_size", "transport_junk_size").forEach { field ->
            val failure = assertThrows(FoxCoreConfigTranslationException::class.java) {
                translateAmnezia {
                    putAll(block)
                    put(field, 11)
                }
            }
            assertEquals(FoxCoreConfigRejection.INVALID_SHAPE, failure.rejection)
            assertFalse(failure.message.orEmpty().contains(key))
        }
    }

    @Test
    fun `zero HPK stays declared while absent HPK stays absent`() {
        assertNull(translateAmnezia { }.getValue("amnezia").jsonObject["header_protection_key"])
        val block = translateAmnezia {
            put("header_protection_key", ZERO_WIREGUARD_KEY)
        }.getValue("amnezia").jsonObject
        assertEquals(JsonPrimitive(ZERO_WIREGUARD_KEY), block["header_protection_key"])
        assertEquals(JsonPrimitive(0), block["transport_junk_size"])
    }

    @Test
    fun `malformed stored header keys are refused with redacted shape errors`() {
        listOf("null", "42", "true", "[]", "{}", "\"bad-secret\"").forEach { raw ->
            val failure = assertThrows(FoxCoreConfigTranslationException::class.java) {
                translateAmnezia {
                    put("header_protection_key", kotlinx.serialization.json.Json.parseToJsonElement(raw))
                }
            }
            assertEquals(FoxCoreConfigRejection.INVALID_SHAPE, failure.rejection)
            assertFalse(failure.message.orEmpty().contains("bad-secret"))
        }
    }

    @Test
    fun `typed translation helpers retain rejection order and paths`() {
        val junk = assertThrows(FoxCoreConfigTranslationException::class.java) {
            translateAmnezia {
                put("junk_packet_count", 1)
                put("junk_min_size", 80)
                put("junk_max_size", 70)
                put("header_protection_key", "bad-key")
            }
        }
        assertTrue(junk.path.endsWith(".amnezia.junk_max_size"))
        val contentPadding = assertThrows(FoxCoreConfigTranslationException::class.java) {
            translateAmnezia {
                put("content_padding_addition", "not-a-range")
                put("header_protection_key", "bad-key")
            }
        }
        assertTrue(contentPadding.path.endsWith(".amnezia.content_padding_addition"))
        val headerProtection = assertThrows(FoxCoreConfigTranslationException::class.java) {
            translateAmnezia {
                put("header_protection_key", "AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA=")
                put("init_junk_size", 11)
                put("response_junk_size", 12)
                put("cookie_junk_size", 12)
                put("transport_junk_size", 12)
                put("init_packets", JsonPrimitive(42))
            }
        }
        assertTrue(headerProtection.path.endsWith(".amnezia.header_protection_key"))
    }

    private fun translateAmnezia(
        mtu: Int = 1420,
        amnezia: (JsonObjectBuilder.() -> Unit)? = { putAll(FULL_BLOCK) },
    ): JsonObject =
        translateEndpoint(
            amneziaEndpoint(
                mtu = mtu,
                amnezia = amnezia?.let { buildJsonObject(it) },
            ),
        )

    private fun translateEndpoint(endpoint: JsonObject): JsonObject =
        engine(
            translate(
                hint = ProtocolHint.WIREGUARD,
                primary = endpoint,
                dnsServers = managedDnsServers() + wireGuardDnsServer(),
            ),
        ).getValue("outbound").jsonObject

    private fun amneziaOverride(
        mtu: Int = 1420,
        override: JsonObjectBuilder.() -> Unit,
    ): JsonObject =
        amneziaEndpoint(
            mtu = mtu,
            amnezia =
            buildJsonObject {
                putAll(FULL_BLOCK)
                override()
            },
        )

    private fun amneziaEndpoint(
        mtu: Int,
        amnezia: JsonObject?,
        extension: JsonObjectBuilder.() -> Unit = {},
    ): JsonObject =
        primary("wireguard") {
            put("private_key", ZERO_WIREGUARD_KEY)
            put("address", JsonArray(listOf(JsonPrimitive("10.81.0.2/32"))))
            put("mtu", mtu)
            amnezia?.let { put("amnezia", it) }
            put(
                "peers",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("address", "203.0.113.20")
                            put("port", 51820)
                            put("public_key", ONE_WIREGUARD_KEY)
                            put("allowed_ips", JsonArray(listOf(JsonPrimitive("0.0.0.0/0"))))
                        },
                    )
                },
            )
            extension()
        }

    private fun JsonObjectBuilder.putAll(source: JsonObject) {
        source.forEach { (key, value) -> put(key, value) }
    }

    private companion object {
        const val ZERO_WIREGUARD_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
        const val ONE_WIREGUARD_KEY = "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE="

        fun tag(name: String): JsonObject = buildJsonObject { put("tag", name) }

        fun bytesTag(hex: String): JsonObject =
            buildJsonObject {
                put("tag", "bytes")
                put("hex", hex)
            }

        fun initPacket(vararg tags: JsonObject): JsonObject =
            buildJsonObject {
                put("tags", JsonArray(tags.toList()))
            }

        val FULL_BLOCK: JsonObject =
            buildJsonObject {
                put("junk_packet_count", 4)
                put("junk_min_size", 40)
                put("junk_max_size", 70)
                put("init_junk_size", 15)
                put("response_junk_size", 20)
                put("cookie_junk_size", 12)
                put("transport_junk_size", 24)
                put("header_initiation", "10-19")
                put("header_response", 20)
                put("header_cookie", "30-39")
                put("header_transport", "40-49")
                put(
                    "init_packets",
                    buildJsonArray {
                        add(
                            initPacket(
                                bytesTag("c0ffee"),
                                buildJsonObject {
                                    put("tag", "random")
                                    put("len", 5)
                                },
                            ),
                        )
                        add(initPacket(tag("timestamp")))
                    },
                )
                put("timers", buildJsonObject { put("reject_after_time_s", "60") })
            }
    }
}
