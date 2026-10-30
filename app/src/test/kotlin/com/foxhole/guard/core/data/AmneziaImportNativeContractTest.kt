package com.foxhole.guard.core.data

import com.foxhole.core.importer.ProfileImportParser
import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.Settings
import com.foxhole.core.model.VpnSession
import com.foxhole.core.network.testRemoteHostResolver
import com.foxhole.core.runtime.FoxCoreConfigTranslator
import com.foxhole.core.runtime.RuntimeConfigAssembler
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.URLEncoder
import java.util.Base64

class AmneziaImportNativeContractTest {
    private val json = Json {
        explicitNulls = false
        ignoreUnknownKeys = true
    }
    private val parser = ProfileImportParser(json, remoteHostResolver = testRemoteHostResolver())
    private val privateKey = Base64.getEncoder().encodeToString(ByteArray(32))
    private val publicKey = Base64.getEncoder().encodeToString(ByteArray(32) { 1 })
    private val conf = """
        [Interface]
        PrivateKey = $privateKey
        Address = 10.81.0.2/32
        DNS = 10.81.0.1
        MTU = 1420
        Jc = 4
        Jmin = 40
        Jmax = 70
        S1 = 15
        S2 = 20
        H1 = 10
        H2 = 20
        H3 = 30
        H4 = 40
        [Peer]
        PublicKey = $publicKey
        AllowedIPs = 0.0.0.0/0
        Endpoint = awg.example.com:51820
    """.trimIndent()

    @Test
    fun `existing awg import pipeline preserves its default packet shape`() {
        assertNull(nativeAmnezia(conf)["random_trailers"])
    }

    @Test
    fun `conf and url random trailers survive import assembly and native session translation`() {
        val fromConf = nativeAmnezia(conf.replace("[Peer]", "RandomTrailers = true\n[Peer]"))
        val uri = "awg://${encode(privateKey)}@awg.example.com:51820" +
            "?publickey=${encode(publicKey)}&address=10.81.0.2%2F32&allowed_ips=0.0.0.0%2F0" +
            "&dns=10.81.0.1&mtu=1420&jc=4&jmin=40&jmax=70&s1=15&s2=20&h1=10&h2=20&h3=30&h4=40&randomtrailers=true"
        val fromUri = nativeAmnezia(uri)
        assertEquals(fromConf, fromUri)
        assertEquals("true", fromConf.getValue("random_trailers").jsonPrimitive.content)
        assertFalse(fromConf.getValue("random_trailers").jsonPrimitive.isString)
    }

    private fun nativeAmnezia(raw: String) = parser.parseUserInput(raw).let { imported ->
        val assembled = RuntimeConfigAssembler(json).assemble(
            checkNotNull(imported.normalizedConfigJson),
            Settings(),
            activePreset = null,
            vpnProtocolHint = ProtocolHint.WIREGUARD,
        )
        val session = VpnSession(
            1,
            "fixture",
            ProtocolHint.WIREGUARD,
            configJson = assembled,
            correlationId = "awg-fixture"
        )
        json.parseToJsonElement(FoxCoreConfigTranslator(json).translate(session).engineConfigJson)
            .jsonObject.getValue("outbound").jsonObject.getValue("amnezia").jsonObject
    }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
}
