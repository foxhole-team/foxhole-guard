package com.foxhole.beta.core.profile

import com.foxhole.beta.core.importer.ProfileImportParser
import com.foxhole.beta.core.network.testRemoteHostResolver
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileConfigFormCodecTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
    private val parser = ProfileImportParser(json, remoteHostResolver = testRemoteHostResolver())
    private val codec = ProfileConfigFormCodec(json)

    @Test
    fun `decode and encode vless settings`() {
        val config =
            parser.parseUserInput(
                "vless://11111111-1111-1111-1111-111111111111@edge.example.com:443?type=ws&host=cdn.example.com&path=%2Fws&security=tls&sni=tls.example.com#Edge",
            ).normalizedConfigJson!!

        val draft = codec.decode(config)

        assertEquals("vless", draft.type)
        assertEquals("edge.example.com", draft.server)
        assertEquals("443", draft.port)
        assertEquals("11111111-1111-1111-1111-111111111111", draft.uuid)
        assertEquals("tls.example.com", draft.tls.serverName)
        assertEquals("ws", draft.transport.type)
        assertEquals("cdn.example.com", draft.transport.host)
        assertEquals("/ws", draft.transport.path)

        val updated =
            draft.copy(
                server = "fast.example.com",
                port = "8443",
                uuid = "22222222-2222-2222-2222-222222222222",
                tls = draft.tls.copy(serverName = "secure.example.com"),
                transport = draft.transport.copy(host = "cache.example.com", path = "/next"),
            )

        val encoded = json.parseToJsonElement(codec.encode(config, updated)).jsonObject
        val outbound = encoded["outbounds"]!!.jsonArray.first().jsonObject
        val transport = outbound["transport"]!!.jsonObject

        assertEquals("fast.example.com", outbound["server"]!!.jsonPrimitive.content)
        assertEquals("8443", outbound["server_port"]!!.jsonPrimitive.content)
        assertEquals("22222222-2222-2222-2222-222222222222", outbound["uuid"]!!.jsonPrimitive.content)
        assertEquals("secure.example.com", outbound["tls"]!!.jsonObject["server_name"]!!.jsonPrimitive.content)
        assertEquals("/next", transport["path"]!!.jsonPrimitive.content)
        assertEquals("cache.example.com", transport["headers"]!!.jsonObject["Host"]!!.jsonPrimitive.content)
        assertTrue(encoded["outbounds"]!!.jsonArray.any { it.jsonObject["type"]?.jsonPrimitive?.content == "selector" })
    }

    @Test
    fun `decode and encode wireguard settings`() {
        val config =
            parser.parseUserInput(
                """
                [Interface]
                PrivateKey = old-private
                Address = 10.0.0.2/32, fd00::2/128

                [Peer]
                PublicKey = old-public
                PresharedKey = old-shared
                AllowedIPs = 0.0.0.0/0, ::/0
                Endpoint = wg.example.com:51820
                PersistentKeepalive = 25
                """.trimIndent(),
            ).normalizedConfigJson!!

        val draft = codec.decode(config)

        assertEquals("wireguard", draft.type)
        assertEquals("wg.example.com", draft.server)
        assertEquals("51820", draft.port)
        assertEquals("old-private", draft.privateKey)
        assertEquals("old-public", draft.peerPublicKey)
        assertEquals("old-shared", draft.preSharedKey)
        assertEquals("10.0.0.2/32, fd00::2/128", draft.localAddress)
        assertEquals("0.0.0.0/0, ::/0", draft.allowedIps)
        assertEquals("25", draft.persistentKeepalive)

        val updated =
            draft.copy(
                server = "vpn.example.com",
                port = "51821",
                privateKey = "new-private",
                peerPublicKey = "new-public",
                preSharedKey = "",
                localAddress = "10.1.0.2/32",
                allowedIps = "10.1.0.0/16",
                persistentKeepalive = "30",
            )

        val encoded = json.parseToJsonElement(codec.encode(config, updated)).jsonObject
        val endpoint = encoded["endpoints"]!!.jsonArray.first().jsonObject
        val peer = endpoint["peers"]!!.jsonArray.first().jsonObject

        assertEquals("new-private", endpoint["private_key"]!!.jsonPrimitive.content)
        assertEquals("vpn.example.com", peer["address"]!!.jsonPrimitive.content)
        assertEquals("51821", peer["port"]!!.jsonPrimitive.content)
        assertEquals("new-public", peer["public_key"]!!.jsonPrimitive.content)
        assertEquals("10.1.0.2/32", endpoint["address"]!!.jsonArray.first().jsonPrimitive.content)
        assertEquals("10.1.0.0/16", peer["allowed_ips"]!!.jsonArray.first().jsonPrimitive.content)
        assertEquals("30", peer["persistent_keepalive_interval"]!!.jsonPrimitive.content)
        assertTrue(!peer.containsKey("pre_shared_key"))
    }
}
