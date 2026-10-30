package com.foxhole.core.importer

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

internal class ProfileImportAmneziaTest : ProfileImportParserTestSupport() {
    @Test
    fun `random trailers from conf and uri stay a typed boolean`() {
        val conf = FULL_OBFUSCATION_CONF.replace("[Peer]", "RandomTrailers = true\n[Peer]")
        assertEquals("true", checkNotNull(amneziaBlock(conf)).getValue("random_trailers").jsonPrimitive.content)
        val uri = "awg://amnezia-private-key%3D@awg.example.com:51820" +
            "?publickey=amnezia-public-key%3D&address=10.81.0.2%2F32&allowed_ips=0.0.0.0%2F0&randomtrailers=on"
        val fromUri = checkNotNull(amneziaBlock(uri)).getValue("random_trailers").jsonPrimitive
        assertEquals("true", fromUri.content)
        assertTrue(!fromUri.isString)
    }

    @Test
    fun `invalid random trailers and peer content padding cannot be silently dropped`() {
        expectIllegalArgument {
            amneziaBlock(
                FULL_OBFUSCATION_CONF.replace("[Peer]", "RandomTrailers = perhaps\n[Peer]")
            )
        }
        expectIllegalArgument { amneziaBlock(FULL_OBFUSCATION_CONF + "\nContentPaddingAddition = 32") }
    }

    @Test
    fun `content padding from conf and URI preserves the uint32 range without truncation`() {
        listOf(
            "0" to JsonPrimitive(0L),
            "0-0" to JsonPrimitive(0L),
            "32" to JsonPrimitive(32L),
            "65536" to JsonPrimitive(65_536L),
            "4294967295" to JsonPrimitive(4_294_967_295L),
            "0-4294967295" to JsonPrimitive("0-4294967295"),
            "65536-65536" to JsonPrimitive(65_536L),
        ).forEach { (raw, expected) ->
            val fromConf = checkNotNull(amneziaBlock(contentPaddingConf(raw)))
            val fromUri = checkNotNull(amneziaBlock(contentPaddingUri(raw)))
            assertEquals(raw, expected, fromConf["content_padding_addition"])
            assertEquals(raw, expected, fromUri["content_padding_addition"])
        }
    }

    @Test
    fun `CPA alone creates a complete amnezia block and retains WireGuard credentials`() {
        val endpoint = wireGuardEndpoint(parser.parseUserInput(contentPaddingConf("32")).normalizedConfigJson)
        val amnezia = endpoint.getValue("amnezia").jsonObject
        assertEquals(JsonPrimitive(32L), amnezia["content_padding_addition"])
        assertEquals(JsonPrimitive(0), amnezia["junk_packet_count"])
        assertEquals(JsonPrimitive(1L), amnezia["header_initiation"])
        assertEquals("amnezia-private-key=", endpoint.getValue("private_key").jsonPrimitive.content)
        val peer = endpoint.getValue("peers").jsonArray.single().jsonObject
        assertEquals("amnezia-public-key=", peer.getValue("public_key").jsonPrimitive.content)
        assertEquals("amnezia-psk=", peer.getValue("pre_shared_key").jsonPrimitive.content)
    }

    @Test
    fun `content padding malformed and oversized values are refused in conf and URI`() {
        listOf(
            "", " ", "-1", "+1", "1.0", "1e2", "4294967296", "18446744073709551615",
            "2-1", "0-4294967296", "1-", "1-2-3",
        ).forEach { raw ->
            expectIllegalArgument { amneziaBlock(contentPaddingConf(raw)) }
            expectIllegalArgument { amneziaBlock(contentPaddingUri(raw)) }
        }
    }

    @Test
    fun `content padding retains old fields and rejects malformed header protection`() {
        val conf = FULL_OBFUSCATION_CONF.replace("[Peer]", "RandomTrailers = true\n[Peer]")
        val old = checkNotNull(amneziaBlock(conf))
        assertNull(old["content_padding_addition"])
        val withCpa = checkNotNull(amneziaBlock(conf.replace("[Peer]", "ContentPaddingAddition = 32-64\n[Peer]")))
        assertEquals(old, JsonObject(withCpa.filterKeys { it != "content_padding_addition" }))
        expectIllegalArgument {
            amneziaBlock(
                contentPaddingConf("32").replace(
                    "[Peer]",
                    "HeaderProtectionKey = not-a-key\n[Peer]",
                )
            )
        }
        expectIllegalArgument { amneziaBlock(contentPaddingUri("32") + "&headerprotectionkey=00") }
    }

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
    fun `peer header encryption declaration is refused rather than dropped`() {
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

    @Test
    fun `minimal HPK configs default absent junk and headers`() {
        val key = "AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA="
        val conf = contentPaddingConf("13").replace(
            "[Peer]",
            "S1 = 12\nS2 = 12\nS3 = 12\nS4 = 12\nHeaderProtectionKey = $key\n[Peer]",
        )
        val block = checkNotNull(amneziaBlock(conf))
        assertEquals(JsonPrimitive(key), block["header_protection_key"])
        assertEquals(JsonPrimitive(13L), block["content_padding_addition"])
        assertEquals(JsonPrimitive(0), block["junk_packet_count"])
        assertEquals(JsonPrimitive(1L), block["header_initiation"])
        listOf("S1", "S2", "S3", "S4").forEach { field ->
            expectIllegalArgument { amneziaBlock(conf.replace("$field = 12\n", "")) }
        }
        expectIllegalArgument { amneziaBlock(conf.replace("[Peer]", "H1 = 2\n[Peer]")) }
    }

    @Test
    fun `header keys from URI preserve escaped plus slash equals and refuse empty input`() {
        val key = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 0xfb.toByte() })
        assertTrue(key.contains('+') && key.contains('/') && key.endsWith('='))
        val base = contentPaddingUri("13") + "&s1=12&s2=12&s3=12&s4=12"
        val encoded = java.net.URLEncoder.encode(key, "UTF-8")
        assertEquals(
            JsonPrimitive(key),
            checkNotNull(amneziaBlock("$base&headerprotectionkey=$encoded"))["header_protection_key"],
        )
        expectIllegalArgument { amneziaBlock("$base&headerprotectionkey=") }
        expectIllegalArgument { amneziaBlock("$base&headerprotectionkey=not-a-key") }
    }

    @Test
    fun `zero header keys use legacy defaults and keys remain interface only`() {
        val zero = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
        val conf = contentPaddingConf("0").replace("[Peer]", "HeaderProtectionKey = $zero\n[Peer]")
        val block = checkNotNull(amneziaBlock(conf))
        assertEquals(JsonPrimitive(zero), block["header_protection_key"])
        assertEquals(JsonPrimitive(0), block["transport_junk_size"])
        expectIllegalArgument { amneziaBlock(contentPaddingConf("0") + "\nHeaderProtectionKey = $zero") }
    }

    @Test
    fun `blank Interface HPK in a plain conf cannot disappear`() {
        expectIllegalArgument { amneziaBlock(blankHeaderProtectionConf("", peer = false)) }
    }

    @Test
    fun `whitespace Interface HPK in a plain conf cannot disappear`() {
        expectIllegalArgument { amneziaBlock(blankHeaderProtectionConf(" \t ", peer = false)) }
    }

    @Test
    fun `blank Peer HPK in a plain conf cannot disappear`() {
        expectIllegalArgument { amneziaBlock(blankHeaderProtectionConf("", peer = true)) }
    }

    @Test
    fun `whitespace Peer HPK in a plain conf cannot disappear`() {
        expectIllegalArgument { amneziaBlock(blankHeaderProtectionConf(" \t ", peer = true)) }
    }

    @Test
    fun `typed interface preparation preserves the first validation failure`() {
        val peerFailure = assertThrows(IllegalArgumentException::class.java) {
            amneziaBlockFromInterface(
                mapOf("HeaderProtectionKey" to listOf("bad-key")),
                mapOf("HeaderProtectionKey" to listOf("")),
                1420,
            )
        }
        assertEquals("AmneziaWG HeaderProtectionKey must be declared in Interface", peerFailure.message)
        val malformed = assertThrows(IllegalArgumentException::class.java) {
            amneziaBlockFromInterface(
                mapOf("HeaderProtectionKey" to listOf("bad-key"), "Jc" to listOf("1")),
                emptyMap(),
                1420,
            )
        }
        assertEquals("AmneziaWG HeaderProtectionKey must be 32 canonical base64-encoded bytes", malformed.message)
        val prefix = assertThrows(IllegalArgumentException::class.java) {
            amneziaBlockFromInterface(
                mapOf(
                    "HeaderProtectionKey" to listOf("AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA="),
                    "S1" to listOf("11"),
                    "S2" to listOf("12"),
                    "S3" to listOf("12"),
                    "S4" to listOf("12"),
                    "ContentPaddingAddition" to listOf("not-a-range"),
                ),
                emptyMap(),
                1420,
            )
        }
        assertEquals("AmneziaWG nonzero HeaderProtectionKey requires S1..S4 of at least 12 bytes", prefix.message)
    }

    private fun blankHeaderProtectionConf(raw: String, peer: Boolean): String {
        val plain = contentPaddingConf("0").replace("ContentPaddingAddition = 0\n", "")
        assertTrue(!plain.contains("ContentPaddingAddition"))
        assertNull(amneziaBlock(plain))
        return if (peer) {
            plain + "\nHeaderProtectionKey = $raw"
        } else {
            plain.replace("[Peer]", "HeaderProtectionKey = $raw\n[Peer]")
        }
    }

    private fun amneziaBlock(raw: String): JsonObject? =
        wireGuardEndpoint(parser.parseUserInput(raw).normalizedConfigJson)["amnezia"]?.jsonObject

    private fun contentPaddingConf(raw: String): String =
        """
        [Interface]
        PrivateKey = amnezia-private-key=
        Address = 10.81.0.2/32
        ContentPaddingAddition = $raw

        [Peer]
        PublicKey = amnezia-public-key=
        PresharedKey = amnezia-psk=
        AllowedIPs = 0.0.0.0/0
        Endpoint = awg.example.com:51820
        """.trimIndent()

    private fun contentPaddingUri(raw: String): String =
        "awg://amnezia-private-key%3D@awg.example.com:51820" +
            "?publickey=amnezia-public-key%3D&address=10.81.0.2%2F32&allowed_ips=0.0.0.0%2F0" +
            "&contentpaddingaddition=${java.net.URLEncoder.encode(raw, "UTF-8")}"

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
