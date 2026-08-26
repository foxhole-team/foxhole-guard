package com.foxhole.core.importer

import com.foxhole.core.network.ipv4TestAddress
import com.foxhole.core.network.testRemoteHostResolver
import kotlinx.serialization.json.Json
import org.junit.Assert.fail

internal open class ProfileImportParserTestSupport {
    protected val json =
        Json {
            prettyPrint = true
            explicitNulls = false
            ignoreUnknownKeys = true
        }

    protected val parser =
        ProfileImportParser(
            json,
            remoteHostResolver =
            testRemoteHostResolver(
                overrides =
                mapOf(
                    "vpn.example.com" to ipv4TestAddress("10.10.0.5"),
                    "wg-internal.example.com" to ipv4TestAddress("192.168.20.5"),
                    "dns-private.example.com" to ipv4TestAddress("172.16.10.5"),
                    "xray-dns.example.com" to ipv4TestAddress("127.0.0.1"),
                ),
            ),
        )

    protected fun expectIllegalArgument(block: () -> Unit) {
        try {
            block()
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }

    protected fun buildSmartConfigPayload(): String =
        """
        # === vless / direct ===
        vless://11111111-1111-1111-1111-111111111111@direct.example.com:8443?encryption=none&security=none&type=tcp#Foxhole%20smart%20direct

        # === trojan / direct ===
        trojan://secret-direct@trojan-direct.example.com:8444?security=tls&type=tcp#Foxhole%20smart%20direct

        # === hysteria2 / direct ===
        hysteria2://secret-direct@hy2-direct.example.com:8447/#Foxhole%20smart%20direct

        # === shadowsocks-2022 / direct ===
        ss://2022-blake3-aes-128-gcm:direct-password@ss-direct.example.com:8446#Foxhole%20smart%20direct

        # === outline / direct ===
        ${buildOutlineAccessKey(host = "outline-direct.example.com", port = 8448, name = "Foxhole smart direct")}

        # === wireguard / direct ===
        [Interface]
        PrivateKey = test-direct-private-key
        Address = 10.77.0.2/32
        DNS = 1.1.1.1
        MTU = 1280

        [Peer]
        PublicKey = test-direct-public-key
        PresharedKey = test-direct-preshared-key
        AllowedIPs = 0.0.0.0/0, ::/0
        Endpoint = wg-direct.example.com:51820
        PersistentKeepalive = 25
        # user_id=1; type=direct; profile_id=direct1234; expire=1779563665

        # === vless / tor+i2p ===
        vless://22222222-2222-2222-2222-222222222222@tor.example.com:9443?encryption=none&security=none&type=tcp#Foxhole%20smart%20tor%2Bi2p

        # === trojan / tor+i2p ===
        trojan://secret-tor@trojan-tor.example.com:9444?security=tls&type=tcp#Foxhole%20smart%20tor%2Bi2p

        # === hysteria2 / tor+i2p ===
        hysteria2://secret-tor@hy2-tor.example.com:9447/#Foxhole%20smart%20tor%2Bi2p

        # === shadowsocks-2022 / tor+i2p ===
        ss://2022-blake3-aes-128-gcm:tor-password@ss-tor.example.com:9446#Foxhole%20smart%20tor%2Bi2p

        # === outline / tor+i2p ===
        ${buildOutlineAccessKey(host = "outline-tor.example.com", port = 9448, name = "Foxhole smart tor+i2p")}

        # === wireguard / tor+i2p ===
        [Interface]
        PrivateKey = test-tor-private-key
        Address = 10.78.0.2/32
        DNS = 10.79.0.1
        MTU = 1280

        [Peer]
        PublicKey = test-tor-public-key
        PresharedKey = test-tor-preshared-key
        AllowedIPs = 0.0.0.0/0, ::/0
        Endpoint = wg-tor.example.com:51821
        PersistentKeepalive = 25
        # user_id=1; type=tor+i2p; profile_id=tor1234; expire=1779563665
        """.trimIndent()

    protected fun buildOutlineAccessKey(
        host: String,
        port: Int,
        name: String,
    ): String {
        val inner = "ss://2022-blake3-aes-128-gcm:outline-password@$host:$port#${name.replace(
            " ",
            "%20"
        ).replace("+", "%2B")}"
        val encoded = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(inner.toByteArray())
        return "outline://$encoded"
    }
}
