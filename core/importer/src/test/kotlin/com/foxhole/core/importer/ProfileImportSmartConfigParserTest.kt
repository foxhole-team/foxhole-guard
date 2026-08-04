package com.foxhole.core.importer

import com.foxhole.core.model.ProfileSourceType
import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.SubscriptionEntryStatus
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

internal class ProfileImportSmartConfigParserTest : ProfileImportParserTestSupport() {
    @Test
    fun `keeps wireguard and shadowsocks while reporting mtproto and amnezia as ignored`() {
        val parsed =
            parser.parseSubscriptionProfiles(
                """
                # === VLESS / profile_0 ===
                vless://11111111-1111-1111-1111-111111111111@vless.example.com:443?security=tls#profile_0

                # === Shadowsocks 2022 / profile_0 ===
                ss://aes-256-gcm:password@ss.example.com:8388#profile_0

                # === WireGuard / profile_0 ===
                [Interface]
                PrivateKey = wireguard-private-key
                Address = 10.80.0.2/32
                DNS = 1.1.1.1

                [Peer]
                PublicKey = wireguard-public-key
                AllowedIPs = 0.0.0.0/0, ::/0
                Endpoint = wg.example.com:51820

                # === MTProto / profile_0 ===
                mtproto://unsupported@mtproto.example.com:443

                # === AmneziaWG / profile_0 ===
                [Interface]
                PrivateKey = amnezia-private-key
                Address = 10.81.0.2/32
                Jc = 4
                Jmin = 40
                Jmax = 70

                [Peer]
                PublicKey = amnezia-public-key
                AllowedIPs = 0.0.0.0/0
                Endpoint = awg.example.com:51820
                """.trimIndent(),
                "remote",
            )

        val profile = parsed.profiles.single()

        assertEquals(
            listOf(ProtocolHint.VLESS, ProtocolHint.SHADOWSOCKS, ProtocolHint.WIREGUARD),
            profile.protocolOptions.map { option -> option.protocolHint },
        )
        assertEquals(
            listOf(
                "VLESS" to SubscriptionEntryStatus.ACCEPTED,
                "SHADOWSOCKS" to SubscriptionEntryStatus.ACCEPTED,
                "WIREGUARD" to SubscriptionEntryStatus.ACCEPTED,
                "MTPROTO" to SubscriptionEntryStatus.IGNORED_UNSUPPORTED,
                "AMNEZIAWG" to SubscriptionEntryStatus.IGNORED_UNSUPPORTED,
            ),
            parsed.entryReports.map { report -> report.protocolLabel to report.status },
        )
    }

    @Test
    fun `parses smart config payload into route profiles with per route protocol options`() {
        val parsed =
            parser.parseSubscriptionProfiles(
                buildSmartConfigPayload(),
                "Foxhole",
            )

        assertEquals("Foxhole Smart Config", parsed.displayName)
        assertEquals(2, parsed.profiles.size)
        assertEquals(1_779_563_665_000L, parsed.subscriptionExpiresAt)

        val directProfile = parsed.profiles[0]
        val torProfile = parsed.profiles[1]

        assertEquals("Foxhole smart direct", directProfile.displayName)
        assertEquals(ProtocolHint.VLESS, directProfile.protocolHint)
        assertEquals(6, directProfile.protocolOptions.size)
        assertEquals("vless", directProfile.selectedProtocolOptionId)
        assertEquals(1_779_563_665_000L, directProfile.subscriptionExpiresAt)
        assertEquals(
            listOf(
                ProtocolHint.VLESS,
                ProtocolHint.TROJAN,
                ProtocolHint.HYSTERIA2,
                ProtocolHint.SHADOWSOCKS,
                ProtocolHint.OUTLINE,
                ProtocolHint.WIREGUARD,
            ),
            directProfile.protocolOptions.map { it.protocolHint },
        )
        assertEquals(
            listOf(
                "VLESS",
                "TROJAN",
                "HYSTERIA2",
                "SHADOWSOCKS",
                "OUTLINE",
                "WIREGUARD",
            ),
            directProfile.protocolOptions.map { it.displayName },
        )

        assertEquals("Foxhole smart tor i2p", torProfile.displayName)
        assertEquals(ProtocolHint.VLESS, torProfile.protocolHint)
        assertEquals(6, torProfile.protocolOptions.size)
        assertEquals("vless", torProfile.selectedProtocolOptionId)
        assertEquals(1_779_563_665_000L, torProfile.subscriptionExpiresAt)
    }

    @Test
    fun `splits repeated smart config route labels into separate display name profiles`() {
        val parsed =
            parser.parseSubscriptionProfiles(
                """
                # === vless / direct ===
                vless://11111111-1111-1111-1111-111111111111@alpha.example.com:443?security=tls&type=tcp#profile_alpha
                # === trojan / direct ===
                trojan://alpha-secret@alpha-trojan.example.com:443?security=tls&type=tcp#profile_alpha
                # === vless / direct ===
                vless://22222222-2222-2222-2222-222222222222@beta.example.com:443?security=tls&type=tcp#profile_beta
                # === trojan / direct ===
                trojan://beta-secret@beta-trojan.example.com:443?security=tls&type=tcp#profile_beta
                """.trimIndent(),
                "Foxhole",
            )

        assertEquals(2, parsed.profiles.size)
        assertEquals(listOf("profile_alpha", "profile_beta"), parsed.profiles.map { it.displayName })
        assertEquals(listOf(2, 2), parsed.profiles.map { it.protocolOptions.size })
        assertEquals(
            listOf(ProtocolHint.VLESS, ProtocolHint.TROJAN),
            parsed.profiles.first().protocolOptions.map { it.protocolHint },
        )
    }

    @Test
    fun `keeps single route smart config as one profile with protocol options`() {
        val parsed =
            parser.parseSubscriptionProfiles(
                """
                # === vless / direct ===
                vless://11111111-1111-1111-1111-111111111111@direct.example.com:8443?encryption=none&security=none&type=tcp#Foxhole%20vpn%20direct

                # === shadowsocks-2022 / direct ===
                ss://2022-blake3-aes-128-gcm:direct-password@ss-direct.example.com:8446#Foxhole%20vpn%20direct

                # === outline / direct ===
                ${buildOutlineAccessKey(host = "outline-direct.example.com", port = 8448, name = "Foxhole vpn direct")}
                """.trimIndent(),
                "Foxhole",
            )

        assertEquals(1, parsed.profiles.size)
        assertEquals(
            listOf(ProtocolHint.VLESS, ProtocolHint.SHADOWSOCKS, ProtocolHint.OUTLINE),
            parsed.profiles.single().protocolOptions.map { it.protocolHint },
        )
        assertEquals(3, parsed.profiles.single().protocolOptions.size)
    }

    @Test
    fun `marks every insecure smart config protocol option that supports insecure tls`() {
        val vmessJson =
            """
            {
              "v": "2",
              "ps": "Foxhole vpn direct",
              "add": "vmess-direct.example.com",
              "port": "8445",
              "id": "11111111-1111-1111-1111-111111111111",
              "aid": "0",
              "net": "tcp",
              "tls": "tls",
              "allowInsecure": true
            }
            """.trimIndent()
        val vmessUri =
            "vmess://" +
                java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(vmessJson.toByteArray())
        val parsed =
            parser.parseSubscriptionProfiles(
                """
                # === vless / direct ===
                vless://11111111-1111-1111-1111-111111111111@direct.example.com:8443?encryption=none&security=tls&type=tcp&allowInsecure=1#Foxhole%20vpn%20direct

                # === trojan / direct ===
                trojan://secret-direct@trojan-direct.example.com:8444?security=tls&type=tcp&allowInsecure=1#Foxhole%20vpn%20direct

                # === vmess / direct ===
                $vmessUri

                # === hysteria2 / direct ===
                hysteria2://secret-direct@hy2-direct.example.com:8447?insecure=1#Foxhole%20vpn%20direct
                """.trimIndent(),
                "Foxhole",
                allowInsecureTls = true,
            )

        val directProfile = parsed.profiles.single()

        assertEquals(4, directProfile.protocolOptions.size)
        assertEquals(
            listOf(ProtocolHint.VLESS, ProtocolHint.TROJAN, ProtocolHint.VMESS, ProtocolHint.HYSTERIA2),
            directProfile.protocolOptions.map { it.protocolHint },
        )
        assertEquals(
            listOf(true, true, true, true),
            directProfile.protocolOptions.map { option ->
                json.parseToJsonElement(option.normalizedConfigJson)
                    .jsonObject["outbounds"]!!.jsonArray.first()
                    .jsonObject["tls"]!!.jsonObject["insecure"]!!.jsonPrimitive.content.toBoolean()
            },
        )
    }

    @Test
    fun `parses outline and wireguard entries inside smart config content`() {
        val parsed =
            parser.parseSubscriptionProfiles(
                buildSmartConfigPayload(),
                "Foxhole",
            )

        assertEquals("Foxhole Smart Config", parsed.displayName)
        assertEquals(2, parsed.nodesCount)

        val directProfile = parsed.profiles.first { it.displayName == "Foxhole smart direct" }
        val outlineOption = directProfile.protocolOptions.first { it.protocolHint == ProtocolHint.OUTLINE }
        val wireguardOption = directProfile.protocolOptions.first { it.protocolHint == ProtocolHint.WIREGUARD }

        val outlineOutbound =
            json.parseToJsonElement(outlineOption.normalizedConfigJson).jsonObject["outbounds"]!!
                .jsonArray
                .first()
                .jsonObject
        val wireguardConfig = json.parseToJsonElement(wireguardOption.normalizedConfigJson).jsonObject
        val wireguardEndpoint =
            wireguardConfig["endpoints"]!!
                .jsonArray
                .first()
                .jsonObject
        val wireguardPeer = wireguardEndpoint["peers"]!!.jsonArray.first().jsonObject
        val wireguardDns =
            wireguardConfig["dns"]!!
                .jsonObject["servers"]!!
                .jsonArray
                .first { server -> server.jsonObject["tag"]!!.jsonPrimitive.content == "dns-wireguard" }
                .jsonObject

        assertEquals("shadowsocks", outlineOutbound["type"]!!.jsonPrimitive.content)
        assertEquals("outline-direct.example.com", outlineOutbound["server"]!!.jsonPrimitive.content)
        assertEquals("wireguard", wireguardEndpoint["type"]!!.jsonPrimitive.content)
        assertEquals("wg-direct.example.com", wireguardPeer["address"]!!.jsonPrimitive.content)
        assertTrue(wireguardEndpoint.containsKey("address"))
        assertEquals("udp", wireguardDns["type"]!!.jsonPrimitive.content)
        assertEquals("1.1.1.1", wireguardDns["server"]!!.jsonPrimitive.content)
        assertEquals("proxy", wireguardDns["detour"]!!.jsonPrimitive.content)
    }

    @Test
    fun `sample smart config file stays parseable`() {
        // The final server-issued shape: a flat share-URI list (no route-group headings) — one
        // profile whose protocol options cover the whole matrix, with the MTProto line the only
        // ignored entry (FoxCore has no MTProto outbound).
        val samplePath =
            listOf(
                Path.of("foxhole-sample-smart-config", "foxhole-smart-config.sample.txt"),
                Path.of("..", "foxhole-sample-smart-config", "foxhole-smart-config.sample.txt"),
                Path.of("..", "..", "foxhole-sample-smart-config", "foxhole-smart-config.sample.txt"),
            ).firstOrNull { candidate -> Files.exists(candidate) }
                ?: throw AssertionError("sample smart config file not found")
        val payload = String(Files.readAllBytes(samplePath))

        val parsed = parser.parseSubscriptionProfiles(payload, "Foxhole")

        // Nine accepted share URIs become nine profiles; only the MTProto (tg://) line is dropped,
        // since FoxCore ships no MTProto outbound.
        assertEquals(9, parsed.profiles.size)
        val ignored = parsed.entryReports.filter { report -> report.status == SubscriptionEntryStatus.IGNORED_UNSUPPORTED }
        assertEquals(listOf("MTPROTO"), ignored.map { report -> report.protocolLabel })
        assertEquals(9, parsed.entryReports.count { report -> report.status == SubscriptionEntryStatus.ACCEPTED })

        val allConfigs =
            parsed.profiles.mapNotNull { profile ->
                profile.normalizedConfigJson?.replace(" ", "")?.replace("\n", "")
            }
        val naiveConfig = allConfigs.single { config -> config.contains("\"type\":\"naive\"") }
        assertTrue(naiveConfig.contains("\"username\":\"sample-naive-user\""))
        val vlessConfig = allConfigs.single { config -> config.contains("\"reality\"") }
        assertTrue(vlessConfig.contains("\"packet_encoding\":\"xudp\""))
        assertTrue(allConfigs.any { config -> config.contains("\"type\":\"hysteria2\"") })
        assertTrue(allConfigs.any { config -> config.contains("\"type\":\"wireguard\"") })
        assertTrue(allConfigs.any { config -> config.contains("\"method\":\"2022-blake3-aes-256-gcm\"") })
    }

    @Test
    fun `parses naive share uri with naive protocol hint`() {
        val parsed = parser.parseUserInput("naive+https://naive-user:naive-pass@naive.example.com:443#naive-node")
        val outbound = json.parseToJsonElement(
            parsed.normalizedConfigJson!!,
        ).jsonObject["outbounds"]!!.jsonArray.first().jsonObject

        assertEquals(ProtocolHint.NAIVE, parsed.protocolHint)
        assertEquals("naive", outbound["type"]!!.jsonPrimitive.content)
        assertEquals("naive-user", outbound["username"]!!.jsonPrimitive.content)
    }

    @Test
    fun `parses tuic share uri into tuic outbound`() {
        val parsed =
            parser.parseUserInput(
                "tuic://11111111-1111-1111-1111-111111111111:tuic-pass@tuic.example.com:443" +
                    "?congestion_control=bbr&udp_relay_mode=native&sni=tuic.example.com#tuic-node",
            )
        val outbound = json.parseToJsonElement(
            parsed.normalizedConfigJson!!,
        ).jsonObject["outbounds"]!!.jsonArray.first().jsonObject

        assertEquals(ProtocolHint.TUIC, parsed.protocolHint)
        assertEquals("tuic", outbound["type"]!!.jsonPrimitive.content)
        assertEquals("11111111-1111-1111-1111-111111111111", outbound["uuid"]!!.jsonPrimitive.content)
        assertEquals("tuic-pass", outbound["password"]!!.jsonPrimitive.content)
        assertEquals("bbr", outbound["congestion_control"]!!.jsonPrimitive.content)
        assertEquals("tuic.example.com", outbound["tls"]!!.jsonObject["server_name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `parses anytls share uri into anytls outbound`() {
        val parsed =
            parser.parseUserInput("anytls://anytls-pass@anytls.example.com:8443?sni=anytls.example.com#anytls-node")
        val outbound = json.parseToJsonElement(
            parsed.normalizedConfigJson!!,
        ).jsonObject["outbounds"]!!.jsonArray.first().jsonObject

        assertEquals(ProtocolHint.ANYTLS, parsed.protocolHint)
        assertEquals("anytls", outbound["type"]!!.jsonPrimitive.content)
        assertEquals("anytls-pass", outbound["password"]!!.jsonPrimitive.content)
        assertEquals("anytls.example.com", outbound["tls"]!!.jsonObject["server_name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `parses outline access key`() {
        val parsed = parser.parseUserInput("ss://YWVzLTI1Ni1nY206cGFzc0BleGFtcGxlLmNvbTo4NDQz#outline")
        val root = json.parseToJsonElement(parsed.normalizedConfigJson!!).jsonObject
        val outbounds = root["outbounds"]!!.jsonArray

        assertEquals(ProtocolHint.OUTLINE, parsed.protocolHint)
        assertEquals("shadowsocks", outbounds.first().jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `splits direct shadowsocks and outline subscription payload into separate profiles`() {
        val parsed =
            parser.parseSubscriptionProfiles(
                """
                ss://aes-256-gcm:pass@ss.example.com:8388#plain-ss
                ${buildOutlineAccessKey(host = "outline.example.com", port = 8443, name = "outline-node")}
                """.trimIndent(),
                "Foxhole",
            )

        assertEquals(2, parsed.profiles.size)
        assertEquals(
            listOf(ProtocolHint.SHADOWSOCKS, ProtocolHint.OUTLINE),
            parsed.profiles.map { it.protocolHint },
        )
        assertEquals(listOf("plain-ss", "outline-node"), parsed.profiles.map { it.displayName })
    }

    @Test
    fun `collapses identical shadowsocks server advertised as plain ss and outline into one profile`() {
        val parsed =
            parser.parseSubscriptionProfiles(
                """
                ss://aes-256-gcm:pass@dup.example.com:8388#plain-node
                ss://aes-256-gcm:pass@dup.example.com:8388/?outline=1#outline-node
                """.trimIndent(),
                "Foxhole",
            )

        // Outline IS Shadowsocks — the same server imported both ways is one profile, not two.
        assertEquals(1, parsed.profiles.size)
        // The surviving representation prefers the plain Shadowsocks label over the cosmetic Outline one.
        assertEquals(ProtocolHint.SHADOWSOCKS, parsed.profiles.single().protocolHint)
        // Both source lines are still reported as accepted (nothing was a parse error).
        assertEquals(2, parsed.entryReports.count { report -> report.status == SubscriptionEntryStatus.ACCEPTED })
    }

    @Test
    fun `parses bracketed ipv6 shadowsocks uri`() {
        val parsed = parser.parseUserInput("ss://aes-256-gcm:pass@[2606:4700:4700::1111]:8388#ipv6")
        val outbound = json.parseToJsonElement(
            parsed.normalizedConfigJson!!
        ).jsonObject["outbounds"]!!.jsonArray.first().jsonObject

        assertEquals(ProtocolHint.SHADOWSOCKS, parsed.protocolHint)
        assertEquals("2606:4700:4700::1111", outbound["server"]!!.jsonPrimitive.content)
        assertEquals("8388", outbound["server_port"]!!.jsonPrimitive.content)
    }

    @Test
    fun `rejects raw normalized config with inbounds`() {
        expectIllegalArgument {
            parser.parseUserInput(
                """
                {
                  "inbounds": [
                    { "type": "socks", "listen": "127.0.0.1", "listen_port": 1080 }
                  ],
                  "outbounds": [
                    { "type": "direct", "tag": "direct" }
                  ]
                }
                """.trimIndent(),
            )
        }
    }

    @Test
    fun `rejects raw normalized config with private dns server hostname`() {
        expectIllegalArgument {
            parser.parseUserInput(
                """
                {
                  "outbounds": [
                    {
                      "type": "vless",
                      "tag": "proxy",
                      "server": "example.com",
                      "server_port": 443,
                      "uuid": "11111111-1111-1111-1111-111111111111"
                    }
                  ],
                  "dns": {
                    "servers": [
                      {
                        "type": "udp",
                        "server": "dns-private.example.com",
                        "server_port": 53
                      }
                    ]
                  }
                }
                """.trimIndent(),
            )
        }
    }

    @Test
    @Suppress("LongMethod")
    fun `parses xray json config into normalized config`() {
        val parsed =
            parser.parseUserInput(
                """
                {
                  "dns": {
                    "servers": [
                      "8.8.8.8"
                    ]
                  },
                  "inbounds": [
                    {
                      "listen": "127.0.0.1",
                      "port": 10808,
                      "protocol": "socks"
                    }
                  ],
                  "outbounds": [
                    {
                      "protocol": "vless",
                      "settings": {
                        "vnext": [
                          {
                            "address": "203.0.113.59",
                            "port": 43000,
                            "users": [
                              {
                                "flow": "xtls-rprx-vision",
                                "id": "00000000-0000-4000-8000-000000000011"
                              }
                            ]
                          }
                        ]
                      },
                      "streamSettings": {
                        "network": "tcp",
                        "realitySettings": {
                          "allowInsecure": false,
                          "alpn": [
                            "h2",
                            "http/1.1"
                          ],
                          "curvePreferences": [
                            "X25519",
                            "X25519MLKEM768",
                            "P256"
                          ],
                          "ech": {
                            "enabled": true
                          },
                          "fingerprint": "chrome",
                          "maxVersion": "1.3",
                          "minVersion": "1.2",
                          "publicKey": "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB",
                          "serverName": "api-maps.yandex.ru",
                          "shortId": "736acf61"
                        },
                        "security": "reality"
                      },
                      "tag": "proxy"
                    },
                    {
                      "protocol": "freedom",
                      "tag": "direct"
                    },
                    {
                      "protocol": "blackhole",
                      "tag": "block"
                    }
                  ],
                  "remarks": "Game White Internet",
                  "routing": {
                    "rules": [
                      {
                        "ip": [
                          "8.8.8.8"
                        ],
                        "outboundTag": "proxy",
                        "port": "53",
                        "type": "field"
                      },
                      {
                        "ip": [
                          "223.5.5.5"
                        ],
                        "outboundTag": "direct",
                        "port": "53",
                        "type": "field"
                      }
                    ]
                  }
                }
                """.trimIndent(),
            )

        val root = json.parseToJsonElement(parsed.normalizedConfigJson!!).jsonObject
        val outbounds = root["outbounds"]!!.jsonArray
        val primary = outbounds.first().jsonObject
        val tls = primary["tls"]!!.jsonObject
        val dnsServers = root["dns"]!!.jsonObject["servers"]!!.jsonArray
        val routeRules = root["route"]!!.jsonObject["rules"]!!.jsonArray

        assertEquals(ProfileSourceType.RAW_CONFIG_JSON, parsed.sourceType)
        assertEquals(ProtocolHint.VLESS, parsed.protocolHint)
        assertEquals("Game White Internet", parsed.displayName)
        assertEquals("tun", root["inbounds"]!!.jsonArray.first().jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("vless", primary["type"]!!.jsonPrimitive.content)
        assertEquals("203.0.113.59", primary["server"]!!.jsonPrimitive.content)
        assertEquals("43000", primary["server_port"]!!.jsonPrimitive.content)
        assertEquals("xtls-rprx-vision", primary["flow"]!!.jsonPrimitive.content)
        assertEquals("api-maps.yandex.ru", tls["server_name"]!!.jsonPrimitive.content)
        assertEquals("h2", tls["alpn"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("http/1.1", tls["alpn"]!!.jsonArray[1].jsonPrimitive.content)
        assertEquals("1.2", tls["min_version"]!!.jsonPrimitive.content)
        assertEquals("1.3", tls["max_version"]!!.jsonPrimitive.content)
        assertEquals("X25519", tls["curve_preferences"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("X25519MLKEM768", tls["curve_preferences"]!!.jsonArray[1].jsonPrimitive.content)
        assertEquals("P256", tls["curve_preferences"]!!.jsonArray[2].jsonPrimitive.content)
        assertEquals("true", tls["ech"]!!.jsonObject["enabled"]!!.jsonPrimitive.content)
        assertEquals("chrome", tls["utls"]!!.jsonObject["fingerprint"]!!.jsonPrimitive.content)
        assertEquals(
            "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB",
            tls["reality"]!!.jsonObject["public_key"]!!.jsonPrimitive.content
        )
        assertEquals("736acf61", tls["reality"]!!.jsonObject["short_id"]!!.jsonPrimitive.content)
        assertEquals("selector", outbounds[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("dns-direct", dnsServers[1].jsonObject["tag"]!!.jsonPrimitive.content)
        assertEquals("local", dnsServers[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("8.8.8.8", dnsServers[2].jsonObject["server"]!!.jsonPrimitive.content)
        assertEquals("proxy", dnsServers[2].jsonObject["detour"]!!.jsonPrimitive.content)
        assertEquals("proxy", routeRules[0].jsonObject["outbound"]!!.jsonPrimitive.content)
        assertEquals("8.8.8.8", routeRules[0].jsonObject["ip_cidr"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("direct", routeRules[1].jsonObject["outbound"]!!.jsonPrimitive.content)
        assertEquals("223.5.5.5", routeRules[1].jsonObject["ip_cidr"]!!.jsonArray[0].jsonPrimitive.content)
    }

    @Test
    fun `parses xray vless tls fingerprint into utls`() {
        val parsed =
            parser.parseUserInput(
                """
                {
                  "outbounds": [
                    {
                      "protocol": "vless",
                      "settings": {
                        "vnext": [
                          {
                            "address": "example.com",
                            "port": 443,
                            "users": [
                              {
                                "id": "11111111-1111-1111-1111-111111111111"
                              }
                            ]
                          }
                        ]
                      },
                      "streamSettings": {
                        "network": "tcp",
                        "security": "tls",
                        "tlsSettings": {
                          "fingerprint": "chrome",
                          "serverName": "edge.example.com"
                        }
                      },
                      "tag": "proxy"
                    }
                  ]
                }
                """.trimIndent(),
            )

        val root = json.parseToJsonElement(parsed.normalizedConfigJson!!).jsonObject
        val tls = root["outbounds"]!!.jsonArray.first().jsonObject["tls"]!!.jsonObject

        assertEquals("edge.example.com", tls["server_name"]!!.jsonPrimitive.content)
        assertEquals("chrome", tls["utls"]!!.jsonObject["fingerprint"]!!.jsonPrimitive.content)
        assertTrue(!tls.containsKey("reality"))
    }
}
