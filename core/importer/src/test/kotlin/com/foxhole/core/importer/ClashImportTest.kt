package com.foxhole.core.importer

import com.foxhole.core.model.ProtocolHint
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.util.Base64
import java.util.concurrent.CancellationException

class ClashImportTest {
    private val parser = ProfileImportParser(
        Json { ignoreUnknownKeys = false },
        remoteHostResolver = { listOf(InetAddress.getByAddress(byteArrayOf(8, 8, 8, 8))) },
    )
    private val body = """
        proxies:
          - {name: SS, type: ss, server: 1.1.1.1, port: 8388, cipher: aes-256-gcm, password: '00123'}
          - name: VLESS
            type: vless
            server: 8.8.8.8
            port: 443
            uuid: 11111111-1111-4111-8111-111111111111
            tls: true
            servername: example.com
            network: ws
            ws-opts:
              path: /path
              headers: {Host: example.com}
          - {name: HY2, type: hysteria2, server: 1.1.1.1, port: 443, password: synthetic, sni: example.com}
    """.trimIndent()
    private val jsonNode = """{"name":"JSON fixture","type":"vless","server":"203.0.113.10","port":443,"uuid":"11111111-1111-4111-8111-111111111111","tls":true,"servername":"tls.example"}"""

    @Test
    fun `top level JSON Clash uses the bounded importer for direct and subscription input`() {
        val raw = """ {"mode":"rule","proxies":[$jsonNode,{"name":"SS","type":"ss","server":"1.1.1.1","port":8388,"cipher":"aes-256-gcm","password":"00123","udp":false}],"rules":["MATCH,DIRECT"],"dns":{"enable":true,"nameserver":["1.1.1.1"]}} """
        val result = parser.parseUserInput(raw)
        assertEquals(
            listOf(ProtocolHint.VLESS, ProtocolHint.SHADOWSOCKS),
            result.protocolOptions.map { it.protocolHint }
        )
        val subscription = parser.parseSubscriptionProfiles(raw, "Fixture")
        assertEquals(2, subscription.profiles.size)
        val ss = Json.parseToJsonElement(subscription.profiles.last().normalizedConfigJson).jsonObject
            .getValue("outbounds").jsonArray.first {
                it.jsonObject["type"]?.jsonPrimitive?.content == "shadowsocks"
            }.jsonObject
        assertEquals("00123", ss.getValue("password").jsonPrimitive.content)
        assertEquals("tcp", ss.getValue("network").jsonPrimitive.content)
    }

    @Test
    fun `JSON Clash key escapes and formatting retain protocol choices`() {
        val raw = """{"\u0070roxies":[$jsonNode]}"""
        assertEquals(ProtocolHint.VLESS, parser.parseUserInput(raw).protocolHint)
        assertEquals(1, parser.parseSubscriptionProfiles(raw, "Fixture").profiles.size)
    }

    @Test
    fun `malformed duplicate and invalid JSON Clash definitions are refused`() {
        for (raw in listOf(
            """{"proxies":[""",
            """{"proxies":{}}""",
            """{"proxies":[]}""",
            """{"proxies":[null]}""",
            """{"proxies":[$jsonNode],"proxies":[$jsonNode]}""",
            """{"proxies":[{"type":"ss","server":"1.1.1.1","port":8388,"cipher":"aes-256-gcm","password":"first-secret","password":"second-secret"}]}""",
        )) {
            val failure = assertThrows(IllegalArgumentException::class.java) { parser.parseUserInput(raw) }
            assertFalse(failure.message.orEmpty().contains("first-secret"))
            assertFalse(failure.message.orEmpty().contains("second-secret"))
            assertThrows(IllegalArgumentException::class.java) { parser.parseSubscriptionProfiles(raw, "Fixture") }
        }
    }

    @Test
    fun `JSON Clash preserves insecure consent and refuses external providers`() {
        val insecure = """{"proxies":[{"type":"trojan","server":"1.1.1.1","port":443,"password":"synthetic","skip-cert-verify":true}]}"""
        assertThrows(IllegalArgumentException::class.java) { parser.parseUserInput(insecure) }
        assertEquals(ProtocolHint.TROJAN, parser.parseUserInput(insecure, allowInsecureTls = true).protocolHint)
        val providers = """{"proxies":[$jsonNode],"proxy-providers":{"other":{"url":"https://provider.example/sensitive-fixture"}}}"""
        assertThrows(IllegalArgumentException::class.java) { parser.parseUserInput(providers) }
        assertThrows(IllegalArgumentException::class.java) { parser.parseSubscriptionProfiles(providers, "Fixture") }
    }

    @Test
    fun `JSON Clash unsupported protocols stay visible without accepting an unknown only profile`() {
        val unknown = """{"name":"Unknown","type":"unknown-protocol","password":"sensitive-fixture"}"""
        val result = parser.parseUserInput("""{"proxies":[$jsonNode,$unknown]}""")
        assertEquals(1, result.nodesCount)
        assertTrue(
            result.entryReports.any {
                it.status == com.foxhole.core.model.SubscriptionEntryStatus.IGNORED_UNSUPPORTED
            }
        )
        val unknownOnly = """{"proxies":[$unknown]}"""
        assertThrows(IllegalArgumentException::class.java) { parser.parseUserInput(unknownOnly) }
        assertThrows(IllegalArgumentException::class.java) { parser.parseSubscriptionProfiles(unknownOnly, "Fixture") }
    }

    @Test
    fun `JSON Clash cannot drop bootstrap metadata or unsafe known node options`() {
        for (extra in listOf(
            """, "bootstrap_dns":null""",
            """, "bootstrap_dns":{"domains":["group.example"],"upstreams":[{"type":"udp","address":"203.0.113.53:53"}]}""",
            """, "outbounds":[]""",
            """, "outbounds":null""",
            """, "endpoints":{}""",
            """, "route":{"final":"proxy"}""",
            """, "schema":"foxhole","configs":[]""",
            """, "servers":[]""",
        )) {
            val raw = """{"proxies":[$jsonNode]$extra}"""
            assertThrows(IllegalArgumentException::class.java) { parser.parseUserInput(raw) }
            assertThrows(IllegalArgumentException::class.java) { parser.parseSubscriptionProfiles(raw, "Fixture") }
        }
        for (node in listOf(
            jsonNode.replace("203.0.113.10", "127.0.0.1"),
            jsonNode.dropLast(1) + ",\"bootstrap_dns\":{}}"
        )) {
            assertThrows(IllegalArgumentException::class.java) { parser.parseUserInput("""{"proxies":[$node]}""") }
        }
    }

    @Test
    fun `JSON Clash retains parser bounds and owner cancellation`() {
        for (extra in listOf(
            "[".repeat(33) + "0" + "]".repeat(33),
            "\"" + "x".repeat(16385) + "\"",
            "\"" + "x".repeat(1024 * 1024) + "\""
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                parser.parseUserInput("""{"proxies":[$jsonNode],"extra":$extra}""")
            }
        }
        val cancelled = ProfileImportParser(Json, remoteHostResolver = { throw CancellationException("owned-cancel") })
        assertThrows(CancellationException::class.java) {
            cancelled.parseUserInput("""{"proxies":[${jsonNode.replace("203.0.113.10", "public.example")}]}""")
        }
    }

    @Test
    fun `Outline and Clash document mixtures are refused without ignoring credentials`() {
        for (outline in listOf(
            """"server":"127.0.0.1","server_port":8388,"method":"aes-256-gcm","password":"outline-secret"""",
            """"server":null,"server_port":{},"method":[],"password":null""",
        )) {
            val raw = """{"proxies":[$jsonNode],$outline}"""
            val failure = assertThrows(IllegalArgumentException::class.java) { parser.parseUserInput(raw) }
            assertFalse(failure.message.orEmpty().contains("outline-secret"))
            assertThrows(IllegalArgumentException::class.java) { parser.parseSubscriptionProfiles(raw, "Fixture") }
        }
    }

    @Test
    fun `normalized JSON subscriptions retain all mixed format guards`() {
        val clean = """{"mode":"rule","proxies":[$jsonNode],"dns":{"enable":true},"external-ui-url":"https://ui.example/fixture"}"""
        for (raw in listOf(
            clean,
            "\uFEFF$clean",
            Base64.getEncoder().encodeToString(clean.toByteArray(Charsets.UTF_8))
        )) {
            assertEquals(1, parser.parseSubscriptionProfiles(raw, "Fixture").profiles.size)
        }
        for (extra in listOf(
            """"outbounds":[]""",
            """"endpoints":null""",
            """"bootstrap_dns":null""",
            """"schema":"foxhole","configs":[]""",
            """"server":"127.0.0.1","server_port":8388,"method":"aes-256-gcm","password":"outline-secret"""",
        )) {
            val mixed = """{"proxies":[$jsonNode],"external-ui-url":"https://ui.example/fixture",$extra}"""
            assertThrows(IllegalArgumentException::class.java) { parser.parseUserInput("\uFEFF$mixed") }
            for (raw in listOf(
                "\uFEFF$mixed",
                Base64.getEncoder().encodeToString(mixed.toByteArray(Charsets.UTF_8))
            )) {
                assertThrows(IllegalArgumentException::class.java) { parser.parseSubscriptionProfiles(raw, "Fixture") }
            }
        }
    }

    @Test
    fun `unrelated canonical JSON still uses its existing import path`() {
        val original =
            requireNotNull(
                parser.parseUserInput(
                    "vless://11111111-1111-4111-8111-111111111111@203.0.113.10:443?security=tls&sni=tls.example#Canonical"
                ).normalizedConfigJson
            )
        val reparsed = parser.parseUserInput(original)
        assertEquals(ProtocolHint.VLESS, reparsed.protocolHint)
        assertEquals(
            Json.parseToJsonElement(original),
            Json.parseToJsonElement(requireNotNull(reparsed.normalizedConfigJson))
        )
    }

    @Test
    fun `Clash provider nodes import as independent protocol choices`() {
        val result = parser.parseUserInput(body)
        assertEquals(3, result.nodesCount)
        assertEquals(
            listOf(ProtocolHint.SHADOWSOCKS, ProtocolHint.VLESS, ProtocolHint.HYSTERIA2),
            result.protocolOptions.map { it.protocolHint }
        )
        val subscription = parser.parseSubscriptionProfiles(body, "Provider")
        assertEquals(3, subscription.profiles.size)
    }

    @Test
    fun `Clash insecure TLS needs the same explicit consent`() {
        val insecure = "proxies:\n  - {name: TLS, type: trojan, server: 1.1.1.1, port: 443, password: synthetic, skip-cert-verify: true}"
        assertThrows(IllegalArgumentException::class.java) { parser.parseUserInput(insecure) }
        assertEquals(ProtocolHint.TROJAN, parser.parseUserInput(insecure, allowInsecureTls = true).protocolHint)
    }

    @Test
    fun `aliases duplicate fields unsafe tags and private nodes are refused`() {
        for (input in listOf(
            "proxies: &nodes [*nodes]",
            "proxies: !!java/object {}",
            "proxies:\n - {name: A, type: ss, server: 1.1.1.1, port: 8388, cipher: aes-256-gcm, password: first, password: second}",
            body.replace("1.1.1.1", "127.0.0.1"),
        )) {
            assertThrows(IllegalArgumentException::class.java) { parser.parseUserInput(input) }
        }
    }

    @Test
    fun `Shadowsocks UDP setting and numeric password spelling survive import`() {
        val input = "proxies:\n - {name: SS, type: ss, server: 1.1.1.1, port: 8388, cipher: aes-256-gcm, password: 00123, udp: false}"
        val root = Json.parseToJsonElement(parser.parseUserInput(input).normalizedConfigJson!!).jsonObject
        val ss = root["outbounds"]!!.jsonArray.first {
            it.jsonObject["type"]?.jsonPrimitive?.content == "shadowsocks"
        }.jsonObject
        assertEquals("00123", ss["password"]!!.jsonPrimitive.content)
        assertEquals("tcp", ss["network"]!!.jsonPrimitive.content)
        val copied = exportPortableConfigs("SS", listOf(root.toString()))
        val restored = Json.parseToJsonElement(parser.parseUserInput(copied).normalizedConfigJson!!).jsonObject
        val restoredSs = restored["outbounds"]!!.jsonArray.first {
            it.jsonObject["type"]?.jsonPrimitive?.content == "shadowsocks"
        }.jsonObject
        assertEquals("tcp", restoredSs["network"]!!.jsonPrimitive.content)
    }

    @Test
    fun `known transport and TLS settings cannot silently disappear`() {
        val base = "proxies:\n - {name: V, type: vless, server: 1.1.1.1, port: 443, uuid: 11111111-1111-4111-8111-111111111111"
        for (options in listOf(
            ", reality-opts: invalid}",
            ", tls: true, sni: first.example, servername: second.example}",
            ", tls: false, sni: example.com}",
            ", tls: true, network: ws, ws-opts: {headers: {Host: first.example, host: second.example}}}",
        )) assertThrows(IllegalArgumentException::class.java) { parser.parseUserInput(base + options) }
        val hy2 = "proxies:\n - {type: hysteria2, server: 1.1.1.1, port: 443, password: synthetic, client-fingerprint: chrome}"
        assertThrows(IllegalArgumentException::class.java) { parser.parseUserInput(hy2) }
    }

    @Test
    fun `unsupported proxies and client settings are reported without importing policy`() {
        val input = "rules: [MATCH,DIRECT]\n" + body + "\n  - {name: Unsupported, type: unknown-protocol, password: sensitive-fixture}"
        val result = parser.parseUserInput(input)
        assertEquals(3, result.nodesCount)
        assertEquals(
            2,
            result.entryReports.count {
                it.status == com.foxhole.core.model.SubscriptionEntryStatus.IGNORED_UNSUPPORTED
            }
        )
    }

    @Test
    fun `large deep multiple documents and external providers are refused`() {
        for (input in listOf(
            body + "\n---\n" + body,
            "proxy-providers: {other: {url: https://example.com/private}}\n" + body,
            "extra: " + "[".repeat(33) + "x" + "]".repeat(33) + "\n" + body,
            body + "\nextra: " + "x".repeat(16385),
        )) assertThrows(IllegalArgumentException::class.java) { parser.parseUserInput(input) }
    }
}
