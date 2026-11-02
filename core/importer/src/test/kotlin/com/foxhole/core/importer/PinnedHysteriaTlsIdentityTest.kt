package com.foxhole.core.importer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.util.Base64

class PinnedHysteriaTlsIdentityTest {
    private val pin = "07".repeat(32)
    private val identity = "router.lan"
    private fun parser(seen: MutableList<String> = mutableListOf()) = ProfileImportParser(Json { ignoreUnknownKeys = false },
        { name -> seen += name; listOf(InetAddress.getByAddress(byteArrayOf(1, 1, 1, 1))) })
    private fun uri(host: String = "1.1.1.1", fingerprint: String? = pin, name: String = identity) =
        "hysteria2://synthetic@$host:443?sni=$name" + (fingerprint?.let { "&pinSHA256=$it" } ?: "")
    private fun refused(input: String) {
        assertTrue(runCatching { parser().parseUserInput(input) }.exceptionOrNull() is IllegalArgumentException)
    }
    @Test fun certificatePinnedHysteriaIdentityIsNotResolvedOrUsedAsDestination() {
        val seen = mutableListOf<String>()
        val parsed = parser(seen).parseUserInput(uri())
        val tls = Json.parseToJsonElement(parsed.normalizedConfigJson!!).jsonObject["outbounds"]!!.jsonArray.first().jsonObject["tls"]!!.jsonObject
        assertEquals(identity, tls["server_name"]!!.jsonPrimitive.content)
        assertEquals(Base64.getEncoder().encodeToString(ByteArray(32) { 7 }), tls["certificate_sha256"]!!.jsonArray.single().jsonPrimitive.content)
        assertFalse(seen.contains(identity))
    }
    @Test fun pinlessAndMalformedPinCannotExemptLocalIdentity() {
        refused(uri(fingerprint = null)); refused(uri(fingerprint = "invalid-pin"))
    }
    @Test fun privateDialledHostIsStillRefusedWithCertificatePin() { refused(uri(host = "192.168.50.1")) }
    @Test fun otherProtocolLocalIdentityRemainsRefused() {
        refused("vless://00000000-0000-4000-8000-000000000001@1.1.1.1:443?security=tls&sni=$identity&pinSHA256=$pin")
    }
    @Test fun malformedOrOversizeIdentityCannotUseTheException() {
        refused(uri(name = "bad_name.lan")); refused(uri(name = "a".repeat(64) + ".lan"))
    }
    @Test fun sameLocalNameAsAnotherOutboundTransportHostRemainsRefused() {
        val b64 = Base64.getEncoder().encodeToString(ByteArray(32) { 7 })
        refused("""{"outbounds":[{"type":"hysteria2","tag":"h2","server":"1.1.1.1","server_port":443,"password":"synthetic","tls":{"enabled":true,"server_name":"router.lan","certificate_sha256":["$b64"]}},{"type":"vless","tag":"ws","server":"1.0.0.1","server_port":443,"uuid":"00000000-0000-4000-8000-000000000001","tls":{"enabled":true,"server_name":"example.org"},"transport":{"type":"ws","headers":{"Host":"router.lan"}}}]}""")
    }
}
