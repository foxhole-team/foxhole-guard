package com.foxhole.core.importer

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal class ProfileImportAmneziaTest : ProfileImportParserTestSupport() {
    @Test
    fun `a full obfuscation block reaches the endpoint from a conf file`() {
        val amnezia = checkNotNull(amneziaBlock(FULL_OBFUSCATION_CONF))

        assertEquals(4, amnezia["junk_packet_count"]?.jsonPrimitive?.content?.toInt())
        assertEquals(40, amnezia["junk_min_size"]?.jsonPrimitive?.content?.toInt())
        assertEquals(70, amnezia["junk_max_size"]?.jsonPrimitive?.content?.toInt())
        assertEquals(15, amnezia["init_junk_size"]?.jsonPrimitive?.content?.toInt())
        assertEquals(20, amnezia["response_junk_size"]?.jsonPrimitive?.content?.toInt())
        assertEquals(12, amnezia["cookie_junk_size"]?.jsonPrimitive?.content?.toInt())
        assertEquals(24, amnezia["transport_junk_size"]?.jsonPrimitive?.content?.toInt())
    }

    @Test
    fun `an awg link and the conf file for the same peer produce the same block`() {
        val fromLink =
            amneziaBlock(
                "awg://amnezia-private-key%3D@awg.example.com:51820" +
                    "?publickey=amnezia-public-key%3D&address=10.81.0.2%2F32&allowed_ips=0.0.0.0%2F0" +
                    "&mtu=1420&jc=4&jmin=40&jmax=70&s1=15&s2=20&s3=12&s4=24" +
                    "&h1=10-19&h2=20&h3=30-39&h4=40-49" +
                    "&i1=%3Cb%200xc0ffee%3E%3Cr%205%3E&rejectaftertime=60#amnezia",
            )

        assertEquals(amneziaBlock(FULL_OBFUSCATION_CONF), fromLink)
    }

    @Test
    fun `ranged and single headers both survive in the shape the core accepts`() {
        val amnezia = checkNotNull(amneziaBlock(FULL_OBFUSCATION_CONF))

        assertEquals("10-19", amnezia["header_initiation"]?.jsonPrimitive?.content)
        assertEquals("20", amnezia["header_response"]?.jsonPrimitive?.content)
        assertTrue(
            "a single value must stay a number, so a 1.5 document round-trips unchanged",
            amnezia["header_response"]?.jsonPrimitive?.isString == false,
        )
        assertEquals("30-39", amnezia["header_cookie"]?.jsonPrimitive?.content)
        assertEquals("40-49", amnezia["header_transport"]?.jsonPrimitive?.content)
    }

    @Test
    fun `an inverted or overlapping header range is refused`() {
        expectIllegalArgument { amneziaBlock(FULL_OBFUSCATION_CONF.replace("H1 = 10-19", "H1 = 19-10")) }
        expectIllegalArgument { amneziaBlock(FULL_OBFUSCATION_CONF.replace("H1 = 10-19", "H1 = 10-20")) }
    }

    @Test
    fun `an init packet template becomes typed tags`() {
        val tags =
            checkNotNull(amneziaBlock(FULL_OBFUSCATION_CONF))["init_packets"]
                ?.jsonArray
                ?.single()
                ?.jsonObject
                ?.get("tags")
                ?.jsonArray
                ?.map { it.jsonObject }

        assertEquals(
            listOf("bytes" to "c0ffee", "random" to "5"),
            tags?.map { tag ->
                tag["tag"]!!.jsonPrimitive.content to
                    (tag["hex"] ?: tag["len"])!!.jsonPrimitive.content
            },
        )
    }

    @Test
    fun `all eight init packet tags are recognised and an unknown one is refused`() {
        val spec = "<b 0xaabb><t><r 4><rc 6><rd 2><d><ds><dz 3>"
        val tags =
            checkNotNull(amneziaBlock(FULL_OBFUSCATION_CONF.replace("I1 = <b 0xc0ffee><r 5>", "I1 = $spec")))[
                "init_packets",
            ]?.jsonArray
                ?.single()
                ?.jsonObject
                ?.get("tags")
                ?.jsonArray
                ?.map { it.jsonObject["tag"]!!.jsonPrimitive.content }

        assertEquals(
            listOf(
                "bytes",
                "timestamp",
                "random",
                "random_letters",
                "random_digits",
                "payload",
                "payload_base64",
                "payload_size",
            ),
            tags,
        )
        expectIllegalArgument {
            amneziaBlock(FULL_OBFUSCATION_CONF.replace("I1 = <b 0xc0ffee><r 5>", "I1 = <b 0xaabb><wt 5>"))
        }
    }

    @Test
    fun `a template that renders no bytes is refused rather than sent as an empty datagram`() {
        expectIllegalArgument {
            amneziaBlock(FULL_OBFUSCATION_CONF.replace("I1 = <b 0xc0ffee><r 5>", "I1 = <d><ds>"))
        }
        assertTrue(
            amneziaBlock(FULL_OBFUSCATION_CONF.replace("I1 = <b 0xc0ffee><r 5>", "I1 = <d><b 0xaa><ds>"))
                ?.get("init_packets") != null,
        )
    }

    @Test
    fun `junk packets that would be empty are refused`() {
        expectIllegalArgument {
            amneziaBlock(FULL_OBFUSCATION_CONF.replace("Jmax = 70", "Jmax = 0").replace("Jmin = 40", "Jmin = 0"))
        }
    }

    @Test
    fun `junk at or above the profile MTU is refused and the boundary is exact`() {
        val atMtu =
            FULL_OBFUSCATION_CONF
                .replace("MTU = 1420", "MTU = 1280")
                .replace("Jmax = 70", "Jmax = 1280")
        expectIllegalArgument { amneziaBlock(atMtu) }

        assertEquals(
            1279,
            amneziaBlock(atMtu.replace("Jmax = 1280", "Jmax = 1279"))
                ?.get("junk_max_size")
                ?.jsonPrimitive
                ?.content
                ?.toInt(),
        )
    }

    @Test
    fun `a profile that states no MTU is bounded by the junk size cap first`() {
        val noMtu = FULL_OBFUSCATION_CONF.replace("MTU = 1420\n", "")

        val error =
            runCatching { amneziaBlock(noMtu.replace("Jmax = 70", "Jmax = 1281")) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message.orEmpty(), error?.message.orEmpty().contains("at most 1280"))

        assertEquals(
            1280,
            amneziaBlock(noMtu.replace("Jmax = 70", "Jmax = 1280"))
                ?.get("junk_max_size")
                ?.jsonPrimitive
                ?.content
                ?.toInt(),
        )
    }

    @Test
    fun `half an obfuscation block is refused and names what is missing`() {
        val error =
            runCatching { amneziaBlock(FULL_OBFUSCATION_CONF.replace("S1 = 15\n", "")) }
                .exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message.orEmpty(), error?.message.orEmpty().contains("missing: s1"))
    }

    @Test
    fun `a 2_0 file carrying only extensions is a complete block`() {
        val amnezia =
            checkNotNull(
                amneziaBlock(
                    """
                    [Interface]
                    PrivateKey = amnezia-private-key
                    Address = 10.81.0.2/32
                    I1 = <b 0xc0ffee><r 5>
                    RejectAfterTime = 60

                    [Peer]
                    PublicKey = amnezia-public-key
                    AllowedIPs = 0.0.0.0/0
                    Endpoint = awg.example.com:51820
                    """.trimIndent(),
                ),
            )

        assertEquals(0, amnezia["junk_packet_count"]?.jsonPrimitive?.content?.toInt())
        assertEquals("1", amnezia["header_initiation"]?.jsonPrimitive?.content)
        assertEquals("4", amnezia["header_transport"]?.jsonPrimitive?.content)
        assertEquals(1, amnezia["init_packets"]?.jsonArray?.size)
        assertEquals(
            "60",
            amnezia["timers"]?.jsonObject?.get("reject_after_time_s")?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `3_0 header encryption is refused rather than dropped`() {
        expectIllegalArgument {
            amneziaBlock(FULL_OBFUSCATION_CONF + "\nHeaderProtectionKey = 00000000000000000000000000000000")
        }
    }

    @Test
    fun `AdvancedSecurity off beside an obfuscation block is refused`() {
        expectIllegalArgument { amneziaBlock(FULL_OBFUSCATION_CONF + "\nAdvancedSecurity = off") }
        assertTrue(amneziaBlock(FULL_OBFUSCATION_CONF + "\nAdvancedSecurity = on") != null)
    }

    @Test
    fun `an amneziawg scheme without parameters stays plain wireguard`() {
        val parsed =
            parser.parseUserInput(
                "amneziawg://plain-private-key%3D@awg.example.com:51820" +
                    "?publickey=plain-public-key%3D&address=10.81.0.2%2F32&allowed_ips=0.0.0.0%2F0#plain",
            )

        assertNull(wireGuardEndpoint(parsed.normalizedConfigJson)["amnezia"])
    }

    @Test
    fun `init packets must be numbered from I1 without gaps`() {
        expectIllegalArgument {
            amneziaBlock(FULL_OBFUSCATION_CONF.replace("I1 = <b 0xc0ffee><r 5>", "I2 = <b 0xc0ffee><r 5>"))
        }
        expectIllegalArgument {
            amneziaBlock(
                FULL_OBFUSCATION_CONF.replace(
                    "I1 = <b 0xc0ffee><r 5>",
                    "I1 = <b 0xc0ffee><r 5>\nI3 = <b 0xaabb>",
                ),
            )
        }
    }

    @Test
    fun `a listen port is read and dropped rather than written into the endpoint`() {
        val endpoint =
            wireGuardEndpoint(
                parser
                    .parseUserInput(
                        FULL_OBFUSCATION_CONF.replace("MTU = 1420", "MTU = 1420\nListenPort = 51820"),
                    ).normalizedConfigJson,
            )

        assertNull(endpoint["listen_port"])
        assertEquals(
            70,
            endpoint["amnezia"]?.jsonObject?.get("junk_max_size")?.jsonPrimitive?.content?.toInt(),
        )
    }

    private fun amneziaBlock(raw: String): JsonObject? =
        wireGuardEndpoint(parser.parseUserInput(raw).normalizedConfigJson)["amnezia"]?.jsonObject

    private fun wireGuardEndpoint(normalizedConfigJson: String?): JsonObject =
        json
            .parseToJsonElement(checkNotNull(normalizedConfigJson))
            .jsonObject["endpoints"]!!
            .jsonArray
            .single()
            .jsonObject

    private companion object {
        val FULL_OBFUSCATION_CONF =
            """
            [Interface]
            PrivateKey = amnezia-private-key=
            Address = 10.81.0.2/32
            MTU = 1420
            Jc = 4
            Jmin = 40
            Jmax = 70
            S1 = 15
            S2 = 20
            S3 = 12
            S4 = 24
            H1 = 10-19
            H2 = 20
            H3 = 30-39
            H4 = 40-49
            I1 = <b 0xc0ffee><r 5>
            RejectAfterTime = 60

            [Peer]
            PublicKey = amnezia-public-key=
            AllowedIPs = 0.0.0.0/0
            Endpoint = awg.example.com:51820
            """.trimIndent()
    }
}
