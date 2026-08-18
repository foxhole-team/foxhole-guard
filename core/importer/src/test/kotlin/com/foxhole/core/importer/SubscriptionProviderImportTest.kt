package com.foxhole.core.importer

import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.SubscriptionEntryStatus
import com.foxhole.core.network.RemoteHostResolver
import com.foxhole.core.network.ipv4TestAddress
import com.foxhole.core.network.testRemoteHostResolver
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.Base64

private const val UNRESOLVABLE_SNI = "magic.nodes.example.net"

class SubscriptionProviderImportTest {
    private val json =
        Json {
            prettyPrint = true
            explicitNulls = false
            ignoreUnknownKeys = true
        }

    private val parser =
        ProfileImportParser(
            json = json,
            remoteHostResolver = testRemoteHostResolver(),
        )

    @Test
    fun `imports every node of the provider payload as its own profile`() {
        val parsed = parser.parseSubscriptionProfiles(SubscriptionProviderSample.payload, "provider")

        assertEquals(SubscriptionProviderSample.nodeLines.size, parsed.profiles.size)
        assertEquals(
            List(SubscriptionProviderSample.nodeLines.size) { SubscriptionEntryStatus.ACCEPTED },
            parsed.entryReports.map { report -> report.status },
        )
        assertEquals(
            listOf(ProtocolHint.HYSTERIA2, ProtocolHint.HYSTERIA2, ProtocolHint.VLESS),
            parsed.profiles.take(3).map { profile -> profile.protocolHint },
        )
    }

    @Test
    fun `keeps emoji and non ascii node names as profile labels`() {
        val parsed = parser.parseSubscriptionProfiles(SubscriptionProviderSample.payload, "provider")

        assertEquals(SubscriptionProviderSample.SECOND_NODE_NAME, parsed.profiles[1].displayName)
        assertEquals(SubscriptionProviderSample.THIRD_NODE_NAME, parsed.profiles[2].displayName)
    }

    @Test
    fun `grouped fetch keeps every node and labels each option with the provider name`() {
        val parsed =
            parser.parseSubscriptionProfiles(
                SubscriptionProviderSample.payload,
                "provider",
                groupCompatibleSingleServerMultiProtocol = true,
            )

        val profile = parsed.profiles.single()
        assertEquals(SubscriptionProviderSample.nodeLines.size, profile.protocolOptions.size)
        assertEquals(SubscriptionProviderSample.SECOND_NODE_NAME, profile.protocolOptions[1].displayName)
        assertEquals(SubscriptionProviderSample.THIRD_NODE_NAME, profile.protocolOptions[2].displayName)
        assertEquals(
            SubscriptionProviderSample.nodeLines.size,
            profile.protocolOptions.map { option -> option.id }.distinct().size,
        )
    }

    @Test
    fun `maps reality grpc vless parameters onto the node model`() {
        val outbound = outboundAt(2)

        assertEquals("vless", outbound.string("type"))
        assertEquals("198.51.100.13", outbound.string("server"))
        assertEquals("15565", outbound.string("server_port"))
        assertEquals("00000000-0000-4000-8000-000000000003", outbound.string("uuid"))

        val tls = outbound["tls"]!!.jsonObject
        assertEquals("true", tls.string("enabled"))
        assertEquals("iv.example.net", tls.string("server_name"))
        assertEquals("qq", tls["utls"]!!.jsonObject.string("fingerprint"))
        assertNull(unsupportedUtlsFingerprint("qq"))
        assertEquals(
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA01",
            tls["reality"]!!.jsonObject.string("public_key"),
        )
        assertEquals("0000000000000001", tls["reality"]!!.jsonObject.string("short_id"))

        val transport = outbound["transport"]!!.jsonObject
        assertEquals("grpc", transport.string("type"))
        assertEquals("grpc", transport.string("service_name"))
    }

    @Test
    fun `maps reality tcp vless flow and leaves udp enabled`() {
        val outbound = outboundAt(3)

        assertEquals("xtls-rprx-vision", outbound.string("flow"))
        assertEquals("api-maps.example.net", outbound["tls"]!!.jsonObject.string("server_name"))
        assertEquals("chrome", outbound["tls"]!!.jsonObject["utls"]!!.jsonObject.string("fingerprint"))
        assertFalse(outbound.containsKey("transport"))
        assertFalse(outbound.containsKey("network"))
    }

    @Test
    fun `maps hysteria2 sni and alpn`() {
        val plain = outboundAt(0)
        assertEquals("hysteria2", plain.string("type"))
        assertEquals("magic.nodes.example.net", plain["tls"]!!.jsonObject.string("server_name"))

        val withAlpn = outboundAt(8)
        assertEquals("serv1.example.net", withAlpn["tls"]!!.jsonObject.string("server_name"))
        assertEquals(
            listOf("h3"),
            withAlpn["tls"]!!.jsonObject["alpn"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `imports the payload when the camouflage sni has no address record`() {
        listOf(unresolvableSniResolver { throw UnknownHostException(it) }, unresolvableSniResolver { emptyList() })
            .forEach { resolver ->
                val strictParser = ProfileImportParser(json = json, remoteHostResolver = resolver)

                val parsed = strictParser.parseSubscriptionProfiles(SubscriptionProviderSample.payload, "provider")

                assertEquals(SubscriptionProviderSample.nodeLines.size, parsed.profiles.size)
                val outbound = json
                    .parseToJsonElement(parsed.profiles.first().normalizedConfigJson)
                    .jsonObject["outbounds"]!!
                    .jsonArray
                    .map { it.jsonObject }
                    .first { it.string("type") == "hysteria2" }
                assertEquals(UNRESOLVABLE_SNI, outbound["tls"]!!.jsonObject.string("server_name"))
            }
    }

    @Test
    fun `still refuses a presented name that resolves into the local network`() {
        val strictParser =
            ProfileImportParser(
                json = json,
                remoteHostResolver = testRemoteHostResolver(
                    overrides = mapOf("cdn.example.net" to ipv4TestAddress("10.10.0.5")),
                ),
            )

        val error =
            runCatching {
                strictParser.parseSubscriptionProfiles(
                    "vless://00000000-0000-4000-8000-000000000003@198.51.100.13:443" +
                        "?security=tls&type=ws&host=cdn.example.net#node",
                    "provider",
                )
            }.exceptionOrNull()

        assertTrue(error!!.message.orEmpty().contains("private or loopback"))
    }

    private fun unresolvableSniResolver(onMiss: (String) -> List<InetAddress>): RemoteHostResolver =
        { host ->
            if (host.equals(UNRESOLVABLE_SNI, ignoreCase = true)) onMiss(host) else testRemoteHostResolver()(host)
        }

    @Test
    fun `detects the payload as a uri list whatever the content type claims`() {
        assertEquals("text/html; charset=utf-8", SubscriptionProviderSample.header("content-type"))
        assertEquals(
            SubscriptionPayloadFormat.URI_LIST,
            detectSubscriptionPayloadFormat(SubscriptionProviderSample.payload) { null },
        )
    }

    @Test
    fun `tolerates bom crlf blank lines and trailing whitespace`() {
        val noisy =
            "\uFEFF\r\n" +
                SubscriptionProviderSample.nodeLines.joinToString(separator = "\r\n\r\n") { line -> "$line   " } +
                "\r\n\r\n   \r\n"

        val parsed = parser.parseSubscriptionProfiles(noisy, "provider")

        assertEquals(SubscriptionProviderSample.nodeLines.size, parsed.profiles.size)
        assertEquals(SubscriptionProviderSample.SECOND_NODE_NAME, parsed.profiles[1].displayName)
    }

    @Test
    fun `imports the same payload when the provider wraps it in base64`() {
        val encoded =
            Base64
                .getEncoder()
                .encodeToString(SubscriptionProviderSample.payload.toByteArray(Charsets.UTF_8))
                .chunked(76)
                .joinToString(separator = "\n")

        val parsed = parser.parseSubscriptionProfiles(encoded, "provider")

        assertEquals(SubscriptionProviderSample.nodeLines.size, parsed.profiles.size)
        assertEquals(SubscriptionProviderSample.SECOND_NODE_NAME, parsed.profiles[1].displayName)
    }

    @Test
    fun `reads title update interval announcements and urls from response headers`() {
        val metadata = SubscriptionMetadataParser.parseHeaderMetadata(SubscriptionProviderSample::header)

        assertEquals(SubscriptionProviderSample.EXPECTED_TITLE, metadata.title)
        assertEquals(12, metadata.updateIntervalHours)
        assertEquals(SubscriptionProviderSample.EXPECTED_ANNOUNCEMENT, metadata.announcement)
        assertEquals("https://console.example.net/", metadata.announcementUrl)
        assertEquals("https://support.example.net/bot", metadata.supportUrl)
        assertEquals("https://console.example.net/", metadata.webPageUrl)
        assertEquals(SubscriptionProviderSample.header("etag"), metadata.etag)
    }

    @Test
    fun `reads userinfo and treats a zero total as unlimited`() {
        val userInfo = SubscriptionMetadataParser.parseHeaderMetadata(SubscriptionProviderSample::header).userInfo!!

        assertEquals(0L, userInfo.uploadBytes)
        assertEquals(SubscriptionProviderSample.EXPECTED_DOWNLOAD_BYTES, userInfo.downloadBytes)
        assertNull(userInfo.totalBytes)
        assertNull(userInfo.remainingBytes)
        assertEquals(SubscriptionProviderSample.EXPECTED_EXPIRES_AT, userInfo.expiresAt)
    }

    @Test
    fun `computes remaining quota when the provider reports a real total`() {
        val userInfo =
            SubscriptionMetadataParser.parseSubscriptionUserInfo("upload=10; download=90; total=1000; expire=0")!!

        assertEquals(100L, userInfo.usedBytes)
        assertEquals(1_000L, userInfo.totalBytes)
        assertEquals(900L, userInfo.remainingBytes)
        assertNull(userInfo.expiresAt)
    }

    @Test
    fun `clamps a hostile update interval instead of trusting it`() {
        assertEquals(1, SubscriptionMetadataParser.parseUpdateIntervalHours("0.2"))
        assertEquals(168, SubscriptionMetadataParser.parseUpdateIntervalHours("100000"))
        assertNull(SubscriptionMetadataParser.parseUpdateIntervalHours("0"))
        assertNull(SubscriptionMetadataParser.parseUpdateIntervalHours("-5"))
        assertNull(SubscriptionMetadataParser.parseUpdateIntervalHours("soon"))
    }

    @Test
    fun `rejects metadata urls that are not http`() {
        assertNull(SubscriptionMetadataParser.parseMetadataUrl("javascript:alert(1)"))
        assertNull(SubscriptionMetadataParser.parseMetadataUrl("intent://x"))
        assertEquals("https://ok.example.net/", SubscriptionMetadataParser.parseMetadataUrl("https://ok.example.net/"))
    }

    @Test
    fun `accepts base64url metadata headers without padding`() {
        assertEquals(
            SubscriptionProviderSample.EXPECTED_ANNOUNCEMENT,
            SubscriptionMetadataParser.decodeMetadataHeader(
                "base64:Tm90aWNlIOKEuSByb3RhdGUgbm9kZXMg4oCUIGNhZsOpIPCflIQ",
            ),
        )
    }

    @Test
    fun `surfaces a reason instead of dropping an unparsable supported entry`() {
        val broken =
            SubscriptionProviderSample.nodeLines.take(2).joinToString(separator = "\n") +
                "\nvless://@198.51.100.99:443?security=reality&sni=iv.example.net"

        val error =
            runCatching { parser.parseSubscriptionProfiles(broken, "provider") }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error!!.message.orEmpty().contains("line 3"))
    }

    @Test
    fun `reports an unsupported protocol line rather than ignoring it silently`() {
        val mixed =
            SubscriptionProviderSample.nodeLines.take(2).joinToString(separator = "\n") +
                "\nssr://secret@198.51.100.99:443#legacy"

        val parsed = parser.parseSubscriptionProfiles(mixed, "provider")

        val ignored = parsed.entryReports.single { report -> report.status == SubscriptionEntryStatus.IGNORED_UNSUPPORTED }
        assertEquals("SSR", ignored.protocolLabel)
        assertEquals(3, ignored.sourceLine)
        assertEquals("unsupported protocol", ignored.reason)
    }

    @Test
    fun `refuses a vless encryption sing box cannot honour`() {
        val error =
            runCatching {
                parser.parseSubscriptionProfiles(
                    "vless://00000000-0000-4000-8000-000000000003@198.51.100.13:443?encryption=mlkem768x25519plus&security=tls#node",
                    "provider",
                )
            }.exceptionOrNull()

        assertTrue(error!!.message.orEmpty().contains("unsupported vless encryption"))
    }

    @Test
    fun `bounds an oversized payload`() {
        val huge = SubscriptionProviderSample.nodeLines.first() + "\n".repeat(MAX_SUBSCRIPTION_PAYLOAD_CHARS)

        val error = runCatching { parser.parseSubscriptionProfiles(huge, "provider") }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error!!.message.orEmpty().contains("too large"))
    }

    @Test
    fun `bounds the number of subscription entries`() {
        val many = List(MAX_SUBSCRIPTION_ENTRY_LINES + 1) { SubscriptionProviderSample.nodeLines.first() }

        val error =
            runCatching {
                parser.parseSubscriptionProfiles(many.joinToString(separator = "\n"), "provider")
            }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error!!.message.orEmpty().contains("more than $MAX_SUBSCRIPTION_ENTRY_LINES entries"))
    }

    @Test
    fun `names the payload shape when it cannot be imported`() {
        val clash =
            """
            proxies:
              - name: node
                type: vmess
            """.trimIndent()

        val error = runCatching { parser.parseSubscriptionProfiles(clash, "provider") }.exceptionOrNull()

        assertTrue(error!!.message.orEmpty().contains("clash yaml"))
    }

    @Test
    fun `imports a sip008 subscription document`() {
        val document =
            """
            {
              "version": 1,
              "servers": [
                {
                  "id": "00000000-0000-4000-8000-000000000001",
                  "remarks": "${SubscriptionProviderSample.SECOND_NODE_NAME}",
                  "server": "198.51.100.10",
                  "server_port": 8388,
                  "password": "fake-password",
                  "method": "aes-256-gcm"
                }
              ]
            }
            """.trimIndent()

        val parsed = parser.parseSubscriptionProfiles(document, "provider")

        val profile = parsed.profiles.single()
        assertEquals(ProtocolHint.SHADOWSOCKS, profile.protocolHint)
        assertEquals(SubscriptionProviderSample.SECOND_NODE_NAME, profile.displayName)
        assertEquals(SubscriptionEntryStatus.ACCEPTED, parsed.entryReports.single().status)
    }

    private fun outboundAt(index: Int): JsonObject {
        val parsed = parser.parseSubscriptionProfiles(SubscriptionProviderSample.payload, "provider")
        val config = json.parseToJsonElement(parsed.profiles[index].normalizedConfigJson).jsonObject
        return config["outbounds"]!!
            .jsonArray
            .map { it.jsonObject }
            .first { outbound -> outbound.string("type") !in setOf("selector", "direct", "block") }
    }

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.content
}
