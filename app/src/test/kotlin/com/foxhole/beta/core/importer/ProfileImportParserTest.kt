package com.foxhole.beta.core.importer

import com.foxhole.beta.core.data.requiresInsecureTls
import com.foxhole.beta.core.model.ProfileSourceType
import com.foxhole.beta.core.model.ProtocolHint
import com.foxhole.beta.core.network.ipv4TestAddress
import com.foxhole.beta.core.network.testRemoteHostResolver
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class ProfileImportParserTest {
    private val json =
        Json {
            prettyPrint = true
            explicitNulls = false
            ignoreUnknownKeys = true
        }

    private val parser =
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

    @Test
    fun `parses https subscription url`() {
        val parsed = parser.parseUserInput("https://example.org/subscription")

        assertEquals(ProfileSourceType.SUBSCRIPTION_URL, parsed.sourceType)
        assertEquals("example.org", parsed.displayName)
        assertEquals(null, parsed.normalizedConfigJson)
    }

    @Test
    fun `parses https subscription url with leading format characters`() {
        val parsed = parser.parseUserInput("\u200B\u200Ehttps://example.org/subscription\u00A0")

        assertEquals(ProfileSourceType.SUBSCRIPTION_URL, parsed.sourceType)
        assertEquals("example.org", parsed.displayName)
        assertEquals("https://example.org/subscription", parsed.sourceUrl)
    }

    @Test
    fun `extracts subscription url from formatted clipboard text`() {
        val parsed = parser.parseUserInput("Here is the link: https://example.org/subscription")

        assertEquals(ProfileSourceType.SUBSCRIPTION_URL, parsed.sourceType)
        assertEquals("https://example.org/subscription", parsed.sourceUrl)
    }

    @Test
    fun `rejects non https subscription url`() {
        expectIllegalArgument {
            parser.parseUserInput("http://example.org/subscription")
        }
    }

    @Test
    fun `extracts subscription expiry from config query metadata`() {
        val parsed =
            parser.parseSubscriptionProfiles(
                "vless://11111111-1111-1111-1111-111111111111@example.com:443?security=tls&expire=1700000000#edge",
                "remote",
            )

        assertEquals(1_700_000_000_000L, parsed.subscriptionExpiresAt)
        assertEquals(1_700_000_000_000L, parsed.profiles.first().subscriptionExpiresAt)
    }

    @Test
    fun `parses naive proxy share uri with quic and ech`() {
        val parsed =
            parser.parseUserInput(
                "naive+https://user:pass@example.com:443?quic=true&udpOverTcp=1&quicCongestionControl=cubic&ech=on&sni=front.example.com#Naive",
            )

        val root = json.parseToJsonElement(parsed.normalizedConfigJson!!).jsonObject
        val outbound = root["outbounds"]!!.jsonArray.first().jsonObject
        val tls = outbound["tls"]!!.jsonObject

        assertEquals(ProfileSourceType.SHARE_URI, parsed.sourceType)
        assertEquals(ProtocolHint.SING_BOX, parsed.protocolHint)
        assertEquals("naive", outbound["type"]!!.jsonPrimitive.content)
        assertEquals("example.com", outbound["server"]!!.jsonPrimitive.content)
        assertEquals("user", outbound["username"]!!.jsonPrimitive.content)
        assertEquals("pass", outbound["password"]!!.jsonPrimitive.content)
        assertEquals("true", outbound["quic"]!!.jsonPrimitive.content)
        assertEquals("true", outbound["udp_over_tcp"]!!.jsonPrimitive.content)
        assertEquals("cubic", outbound["quic_congestion_control"]!!.jsonPrimitive.content)
        assertEquals("front.example.com", tls["server_name"]!!.jsonPrimitive.content)
        assertEquals("true", tls["ech"]!!.jsonObject["enabled"]!!.jsonPrimitive.content)
    }

    @Test
    fun `rejects html subscription response without vpn configs`() {
        val error =
            runCatching {
                parser.parseSubscriptionProfiles(
                    rawContent = "<html><body>not a vpn profile</body></html>",
                    fallbackName = "remote",
                )
            }.exceptionOrNull()

        assertNotNull(error)
        assertTrue(error is IllegalArgumentException)
        assertTrue(error!!.message.orEmpty().contains("unsupported subscription"))
    }

    @Test
    fun `rejects bare subscription url as fetched config payload`() {
        val error =
            runCatching {
                parser.parseSubscriptionProfiles(
                    rawContent = "https://example.org/not-a-config",
                    fallbackName = "remote",
                )
            }.exceptionOrNull()

        assertNotNull(error)
        assertTrue(error is IllegalArgumentException)
        assertTrue(error!!.message.orEmpty().contains("unsupported subscription"))
    }

    @Test
    fun `rejects private subscription url`() {
        expectIllegalArgument {
            parser.parseUserInput("https://127.0.0.1/subscription")
        }
    }

    @Test
    fun `rejects subscription url hostname that resolves to private address`() {
        expectIllegalArgument {
            parser.parseUserInput("https://vpn.example.com/subscription")
        }
    }

    @Test
    fun `rejects share uri with private outbound host by default`() {
        expectIllegalArgument {
            parser.parseUserInput(
                "vless://11111111-1111-1111-1111-111111111111@127.0.0.1:443?security=tls#loopback",
            )
        }
    }

    @Test
    fun `allows share uri with private outbound host under explicit override`() {
        val parsed =
            parser.parseUserInput(
                "vless://11111111-1111-1111-1111-111111111111@127.0.0.1:443?security=tls#loopback",
                allowPrivateOutboundHosts = true,
            )

        val root = json.parseToJsonElement(parsed.normalizedConfigJson!!).jsonObject
        val outbound = root["outbounds"]!!.jsonArray.first().jsonObject

        assertEquals("127.0.0.1", outbound["server"]!!.jsonPrimitive.content)
    }

    @Test
    fun `rejects share uri whose transport host resolves to private address`() {
        expectIllegalArgument {
            parser.parseUserInput(
                "vless://11111111-1111-1111-1111-111111111111@example.com:443?security=tls&type=ws&host=vpn.example.com#edge",
            )
        }
    }

    @Test
    fun `parses vless uri into normalized sing-box config`() {
        val parsed =
            parser.parseUserInput(
                "vless://11111111-1111-1111-1111-111111111111@example.com:443?security=tls&type=ws&path=%2Fws&host=cdn.example.com#edge",
            )

        val root = json.parseToJsonElement(parsed.normalizedConfigJson!!).jsonObject
        val outbounds = root["outbounds"]!!.jsonArray
        val dns = root["dns"]!!.jsonObject
        val route = root["route"]!!.jsonObject
        val dnsServers = dns["servers"]!!.jsonArray

        assertEquals(ProfileSourceType.SHARE_URI, parsed.sourceType)
        assertEquals(ProtocolHint.VLESS, parsed.protocolHint)
        assertEquals("vless", outbounds.first().jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("selector", outbounds[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(false, root["inbounds"]!!.jsonArray[0].jsonObject.containsKey("sniff"))
        assertEquals("dns-remote", dns["final"]!!.jsonPrimitive.content)
        assertEquals("local", dnsServers[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(false, dnsServers[0].jsonObject.containsKey("detour"))
        assertEquals("dns-direct", dnsServers[1].jsonObject["tag"]!!.jsonPrimitive.content)
        assertEquals("local", dnsServers[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(false, dnsServers[1].jsonObject.containsKey("server"))
        assertEquals(false, dnsServers[1].jsonObject.containsKey("detour"))
        assertEquals("dns-remote", dnsServers[2].jsonObject["tag"]!!.jsonPrimitive.content)
        assertEquals("https", dnsServers[2].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("1.1.1.1", dnsServers[2].jsonObject["server"]!!.jsonPrimitive.content)
        assertEquals("443", dnsServers[2].jsonObject["server_port"]!!.jsonPrimitive.content)
        assertEquals("/dns-query", dnsServers[2].jsonObject["path"]!!.jsonPrimitive.content)
        assertEquals("proxy", dnsServers[2].jsonObject["detour"]!!.jsonPrimitive.content)
        assertEquals("dns-direct", route["default_domain_resolver"]!!.jsonPrimitive.content)
        assertEquals("true", route["auto_detect_interface"]!!.jsonPrimitive.content)
        assertEquals("sniff", route["rules"]!!.jsonArray[0].jsonObject["action"]!!.jsonPrimitive.content)
        assertEquals("hijack-dns", route["rules"]!!.jsonArray[1].jsonObject["action"]!!.jsonPrimitive.content)
        assertEquals("53", route["rules"]!!.jsonArray[1].jsonObject["port"]!!.jsonPrimitive.content)
        assertEquals(false, route["rules"]!!.jsonArray[1].jsonObject["port"]!!.jsonPrimitive.isString)
        assertEquals("hijack-dns", route["rules"]!!.jsonArray[2].jsonObject["action"]!!.jsonPrimitive.content)
        assertEquals("dns", route["rules"]!!.jsonArray[2].jsonObject["protocol"]!!.jsonPrimitive.content)
    }

    @Test
    fun `parses vless grpc uri`() {
        val parsed =
            parser.parseUserInput(
                "vless://11111111-1111-1111-1111-111111111111@example.com:443?security=tls&type=grpc&serviceName=edge-grpc&sni=edge.example.com#grpc",
            )

        val root = json.parseToJsonElement(parsed.normalizedConfigJson!!).jsonObject
        val transport = root["outbounds"]!!.jsonArray.first().jsonObject["transport"]!!.jsonObject

        assertEquals("grpc", transport["type"]!!.jsonPrimitive.content)
        assertEquals("edge-grpc", transport["service_name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `rejects unsupported share uri transport instead of silently dropping to tcp`() {
        val error =
            runCatching {
                parser.parseUserInput(
                    "vless://11111111-1111-1111-1111-111111111111@example.com:443?security=tls&type=quic&sni=edge.example.com#quic",
                )
            }.exceptionOrNull()

        assertNotNull(error)
        assertTrue(error!!.message.orEmpty().contains("unsupported transport type: quic"))
    }

    @Test
    fun `parses vless reality uri`() {
        val parsed =
            parser.parseUserInput(
                "vless://11111111-1111-1111-1111-111111111111@example.com:443?security=reality&sni=edge.example.com&pbk=pubkey&sid=abcd&fp=chrome#reality",
            )

        val root = json.parseToJsonElement(parsed.normalizedConfigJson!!).jsonObject
        val tls = root["outbounds"]!!.jsonArray.first().jsonObject["tls"]!!.jsonObject

        assertEquals(true.toString(), tls["enabled"]!!.jsonPrimitive.content)
        assertEquals("edge.example.com", tls["server_name"]!!.jsonPrimitive.content)
        assertEquals("pubkey", tls["reality"]!!.jsonObject["public_key"]!!.jsonPrimitive.content)
        assertEquals("abcd", tls["reality"]!!.jsonObject["short_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `parses vless tls uri fingerprint and snake case packet encoding`() {
        val parsed =
            parser.parseUserInput(
                "vless://11111111-1111-1111-1111-111111111111@example.com:443?security=tls&sni=edge.example.com&fp=chrome&packet_encoding=xudp#tls",
            )

        val root = json.parseToJsonElement(parsed.normalizedConfigJson!!).jsonObject
        val outbound = root["outbounds"]!!.jsonArray.first().jsonObject
        val tls = outbound["tls"]!!.jsonObject

        assertEquals("xudp", outbound["packet_encoding"]!!.jsonPrimitive.content)
        assertEquals("edge.example.com", tls["server_name"]!!.jsonPrimitive.content)
        assertEquals("chrome", tls["utls"]!!.jsonObject["fingerprint"]!!.jsonPrimitive.content)
        assertTrue(!tls.containsKey("reality"))
    }

    @Test
    fun `parses vless uri query parameters case insensitively`() {
        val parsed =
            parser.parseUserInput(
                "vless://11111111-1111-1111-1111-111111111111@example.com:443?Security=TLS&SNI=edge.example.com&FP=Chrome&Flow=xtls-rprx-vision&PacketEncoding=xudp&Type=WS&Host=cdn.example.com&Path=%2Fedge#tls",
            )

        val root = json.parseToJsonElement(parsed.normalizedConfigJson!!).jsonObject
        val outbound = root["outbounds"]!!.jsonArray.first().jsonObject
        val tls = outbound["tls"]!!.jsonObject
        val transport = outbound["transport"]!!.jsonObject

        assertEquals("xtls-rprx-vision", outbound["flow"]!!.jsonPrimitive.content)
        assertEquals("xudp", outbound["packet_encoding"]!!.jsonPrimitive.content)
        assertEquals("edge.example.com", tls["server_name"]!!.jsonPrimitive.content)
        assertEquals("chrome", tls["utls"]!!.jsonObject["fingerprint"]!!.jsonPrimitive.content)
        assertEquals("ws", transport["type"]!!.jsonPrimitive.content)
        assertEquals("/edge", transport["path"]!!.jsonPrimitive.content)
        assertEquals("cdn.example.com", transport["headers"]!!.jsonObject["Host"]!!.jsonPrimitive.content)
    }

    @Test
    fun `parses real single hysteria2 uri`() {
        val parsed =
            parser.parseUserInput(
                "hysteria2://RLS3MLv81RhMPNr5xHRnqGmEPUkIFxxI1fTbAqzmZ+s=@axn666.nl:8443?sni=axn666.nl",
            )

        val root = json.parseToJsonElement(parsed.normalizedConfigJson!!).jsonObject
        val outbound = root["outbounds"]!!.jsonArray.first().jsonObject
        val tls = outbound["tls"]!!.jsonObject

        assertEquals(ProfileSourceType.SHARE_URI, parsed.sourceType)
        assertEquals(ProtocolHint.HYSTERIA2, parsed.protocolHint)
        assertEquals("axn666.nl", outbound["server"]!!.jsonPrimitive.content)
        assertEquals("8443", outbound["server_port"]!!.jsonPrimitive.content)
        assertEquals("axn666.nl", tls["server_name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `parses real single vless reality uri`() {
        val parsed =
            parser.parseUserInput(
                "vless://ea754f117e56c0a02cf9ba24de677bc7@axn666.nl:8447?type=tcp&encryption=none&security=reality&sni=www.microsoft.com&pbk=WG2E71GvJTVvpUigKJ7UgC0-XyarAVTkvPQMbH8h2iM&sid=03d0b309d56c352b&fp=chrome&spx=/#axnet_secure_core_direct_62afe4",
            )

        val root = json.parseToJsonElement(parsed.normalizedConfigJson!!).jsonObject
        val outbound = root["outbounds"]!!.jsonArray.first().jsonObject
        val tls = outbound["tls"]!!.jsonObject

        assertEquals(ProfileSourceType.SHARE_URI, parsed.sourceType)
        assertEquals(ProtocolHint.VLESS, parsed.protocolHint)
        assertEquals("axn666.nl", outbound["server"]!!.jsonPrimitive.content)
        assertEquals("8447", outbound["server_port"]!!.jsonPrimitive.content)
        assertEquals("www.microsoft.com", tls["server_name"]!!.jsonPrimitive.content)
        assertEquals(
            "WG2E71GvJTVvpUigKJ7UgC0-XyarAVTkvPQMbH8h2iM",
            tls["reality"]!!.jsonObject["public_key"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `extracts vless uri from formatted text document`() {
        val parsed =
            parser.parseUserInput(
                """
                ASAX private-proxy VLESS Reality client config

                URI:
                vless://11111111-1111-1111-1111-111111111111@axn666.nl:8443?type=tcp&security=reality&encryption=none&flow=xtls-rprx-vision&sni=www.cloudflare.com&fp=chrome&pbk=public-key&sid=3025ae2cbf30b735&spx=%2F#asax-private-proxy-vless-20260525

                Client fields:
                address=axn666.nl
                port=8443
                """.trimIndent(),
            )

        val root = json.parseToJsonElement(parsed.normalizedConfigJson!!).jsonObject
        val outbound = root["outbounds"]!!.jsonArray.first().jsonObject
        val tls = outbound["tls"]!!.jsonObject

        assertEquals(ProfileSourceType.SHARE_URI, parsed.sourceType)
        assertEquals(ProtocolHint.VLESS, parsed.protocolHint)
        assertEquals("asax-private-proxy-vless-20260525", parsed.displayName)
        assertEquals("axn666.nl", outbound["server"]!!.jsonPrimitive.content)
        assertEquals("8443", outbound["server_port"]!!.jsonPrimitive.content)
        assertEquals("xtls-rprx-vision", outbound["flow"]!!.jsonPrimitive.content)
        assertEquals("www.cloudflare.com", tls["server_name"]!!.jsonPrimitive.content)
        assertEquals("public-key", tls["reality"]!!.jsonObject["public_key"]!!.jsonPrimitive.content)
    }

    @Test
    fun `parses hysteria2 client yaml document`() {
        val parsed =
            parser.parseUserInput(
                """
                # ASAX private-proxy Hysteria2 client config.
                server: axn666.nl:443
                auth: client-secret

                tls:
                  sni: axn666.nl
                  insecure: false
                  pinSHA256: 95:20:0B:4C:DF:80:5E:D1:58:C3:33:30:42:37:D5:96:51:F4:3E:17:4C:8B:D5:C6:E6:2E:60:0A:75:32:C1:49

                obfs:
                  type: salamander
                  salamander:
                    password: obfs-secret

                transport:
                  type: udp

                congestion:
                  type: bbr
                """.trimIndent(),
            )

        val root = json.parseToJsonElement(parsed.normalizedConfigJson!!).jsonObject
        val outbound = root["outbounds"]!!.jsonArray.first().jsonObject
        val tls = outbound["tls"]!!.jsonObject
        val obfs = outbound["obfs"]!!.jsonObject

        assertEquals(ProfileSourceType.SHARE_URI, parsed.sourceType)
        assertEquals(ProtocolHint.HYSTERIA2, parsed.protocolHint)
        assertEquals("axn666.nl", parsed.displayName)
        assertEquals("hysteria2", outbound["type"]!!.jsonPrimitive.content)
        assertEquals("axn666.nl", outbound["server"]!!.jsonPrimitive.content)
        assertEquals("443", outbound["server_port"]!!.jsonPrimitive.content)
        assertEquals("client-secret", outbound["password"]!!.jsonPrimitive.content)
        assertEquals("udp", outbound["network"]!!.jsonPrimitive.content)
        assertEquals("axn666.nl", tls["server_name"]!!.jsonPrimitive.content)
        assertEquals(
            listOf("lSALTN+AXtFYwzMwQjfVllH0PhdMi9XG5i5gCnUywUk="),
            tls["certificate_public_key_sha256"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(false, tls.containsKey("insecure"))
        assertEquals("salamander", obfs["type"]!!.jsonPrimitive.content)
        assertEquals("obfs-secret", obfs["password"]!!.jsonPrimitive.content)
        assertEquals(false, outbound.containsKey("obfs_password"))
    }

    @Test
    fun `rejects unsupported xray transport instead of silently dropping to tcp`() {
        val error =
            runCatching {
                parser.parseUserInput(
                    """
                    {
                      "outbounds": [
                        {
                          "protocol": "vless",
                          "settings": {
                            "vnext": [
                              {
                                "address": "37.139.40.59",
                                "port": 43000,
                                "users": [
                                  {
                                    "id": "11111111-1111-1111-1111-111111111111",
                                    "encryption": "none"
                                  }
                                ]
                              }
                            ]
                          },
                          "streamSettings": {
                            "network": "kcp",
                            "security": "none"
                          },
                          "tag": "proxy"
                        }
                      ]
                    }
                    """.trimIndent(),
                )
            }.exceptionOrNull()

        assertNotNull(error)
        assertTrue(error!!.message.orEmpty().contains("unsupported xray transport type: kcp"))
    }

    @Test
    fun `parses vless uri with raw unicode fragment`() {
        val parsed =
            parser.parseUserInput(
                "vless://8f62e533-a264-41a3-8e74-c483705f7a34@37.139.32.253:39537?security=none&type=tcp&seed=4a47333ab40b2ac1#💫 Игровой белый интернет",
            )

        val root = json.parseToJsonElement(parsed.normalizedConfigJson!!).jsonObject
        val outbound = root["outbounds"]!!.jsonArray.first().jsonObject

        assertEquals(ProfileSourceType.SHARE_URI, parsed.sourceType)
        assertEquals(ProtocolHint.VLESS, parsed.protocolHint)
        assertEquals("💫 Игровой белый интернет", parsed.displayName)
        assertEquals("vless", outbound["type"]!!.jsonPrimitive.content)
        assertEquals("37.139.32.253", outbound["server"]!!.jsonPrimitive.content)
        assertEquals("39537", outbound["server_port"]!!.jsonPrimitive.content)
        assertEquals(false, outbound.containsKey("tls"))
        assertEquals(false, outbound.containsKey("transport"))
    }

    @Test
    fun `parses trojan security none without forcing tls`() {
        val parsed =
            parser.parseUserInput(
                "trojan://secret@example.org:8444?security=none&type=tcp#trojan",
            )

        val root = json.parseToJsonElement(parsed.normalizedConfigJson!!).jsonObject
        val outbound = root["outbounds"]!!.jsonArray.first().jsonObject

        assertEquals(ProtocolHint.TROJAN, parsed.protocolHint)
        assertEquals("trojan", outbound["type"]!!.jsonPrimitive.content)
        assertEquals("8444", outbound["server_port"]!!.jsonPrimitive.content)
        assertEquals(false, outbound.containsKey("tls"))
    }

    @Test
    fun `parses multi line subscription list`() {
        val parsed =
            parser.parseSubscriptionContent(
                """
                trojan://secret@example.org:443?security=tls#trojan
                vless://11111111-1111-1111-1111-111111111111@example.com:443?security=tls&type=ws&path=%2Fws&host=cdn.example.com#edge
                """.trimIndent(),
                "remote",
            )
        val root = json.parseToJsonElement(parsed.normalizedConfigJson!!).jsonObject
        val outbounds = root["outbounds"]!!.jsonArray

        assertEquals(ProfileSourceType.SUBSCRIPTION_URL, parsed.sourceType)
        assertEquals(2, parsed.nodesCount)
        assertEquals(5, outbounds.size)
    }

    @Test
    fun `rejects partially invalid subscription list instead of silently dropping nodes`() {
        expectIllegalArgument {
            parser.parseSubscriptionContent(
                """
                trojan://secret@example.org:443?security=tls#trojan
                not-a-supported-node
                """.trimIndent(),
                "remote",
            )
        }
    }

    @Test
    fun `parses base64 encoded multi node subscription payload`() {
        val payload =
            java.util.Base64.getEncoder().encodeToString(
                """
                trojan://secret@example.org:443?security=tls#trojan
                vless://11111111-1111-1111-1111-111111111111@example.com:443?security=tls&type=ws&path=%2Fws&host=cdn.example.com#edge
                """.trimIndent().toByteArray(),
            )

        val parsed = parser.parseSubscriptionContent(payload, "remote")

        assertEquals(ProfileSourceType.SUBSCRIPTION_URL, parsed.sourceType)
        assertEquals(2, parsed.nodesCount)
    }

    @Test
    fun `parses subscription payload into all individual profiles`() {
        val parsed =
            parser.parseSubscriptionProfiles(
                """
                trojan://secret@example.org:443?security=tls#trojan
                vless://11111111-1111-1111-1111-111111111111@example.com:443?security=tls&type=ws&path=%2Fws&host=cdn.example.com#edge
                """.trimIndent(),
                "remote",
            )

        assertEquals("remote", parsed.displayName)
        assertEquals(2, parsed.nodesCount)
        assertEquals("trojan", parsed.profiles[0].displayName)
        assertEquals(ProtocolHint.TROJAN, parsed.profiles[0].protocolHint)
        assertEquals("edge", parsed.profiles[1].displayName)
        assertEquals(ProtocolHint.VLESS, parsed.profiles[1].protocolHint)
        assertNotNull(parsed.profiles[0].normalizedConfigJson)
        assertNotNull(parsed.profiles[1].normalizedConfigJson)
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
            directProfile.protocolOptions.map { option -> option.normalizedConfigJson.requiresInsecureTls(json) },
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
        val samplePath =
            listOf(
                Path.of("foxhole-sample-smart-config", "foxhole-smart-config.sample.txt"),
                Path.of("..", "foxhole-sample-smart-config", "foxhole-smart-config.sample.txt"),
            ).firstOrNull { candidate -> Files.exists(candidate) }
                ?: throw AssertionError("sample smart config file not found")
        val payload = String(Files.readAllBytes(samplePath))

        val parsed = parser.parseSubscriptionProfiles(payload, "Foxhole")

        assertEquals("Foxhole Smart Config", parsed.displayName)
        assertEquals(2, parsed.profiles.size)
        assertEquals(
            listOf("profile_0", "profile_1"),
            parsed.profiles.map { it.displayName },
        )
        assertTrue(parsed.profiles.all { it.protocolOptions.size == 6 })
        assertEquals(
            listOf("VLESS", "TROJAN", "HYSTERIA2", "SHADOWSOCKS", "OUTLINE", "WIREGUARD"),
            parsed.profiles.first().protocolOptions.map { it.displayName },
        )
    }

    @Test
    fun `parses stealthsurf style subscription payload`() {
        val parsed =
            parser.parseSubscriptionContent(
                "vless://8f62e533-a264-41a3-8e74-c483705f7a34@37.139.32.253:39537?security=none&type=tcp&seed=4a47333ab40b2ac1#💫 Игровой белый интернет",
                "connect.stealthsurf.app",
            )

        assertEquals(ProfileSourceType.SUBSCRIPTION_URL, parsed.sourceType)
        assertEquals(ProtocolHint.VLESS, parsed.protocolHint)
        assertEquals("💫 Игровой белый интернет", parsed.displayName)
        assertEquals(1, parsed.nodesCount)
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
    fun `parses bracketed ipv6 shadowsocks uri`() {
        val parsed = parser.parseUserInput("ss://aes-256-gcm:pass@[2606:4700:4700::1111]:8388#ipv6")
        val outbound = json.parseToJsonElement(parsed.normalizedConfigJson!!).jsonObject["outbounds"]!!.jsonArray.first().jsonObject

        assertEquals(ProtocolHint.SHADOWSOCKS, parsed.protocolHint)
        assertEquals("2606:4700:4700::1111", outbound["server"]!!.jsonPrimitive.content)
        assertEquals("8388", outbound["server_port"]!!.jsonPrimitive.content)
    }

    @Test
    fun `rejects raw sing-box config with inbounds`() {
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
    fun `rejects raw sing-box config with private dns server hostname`() {
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
    fun `parses xray json config into normalized sing-box config`() {
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
                            "address": "37.139.40.59",
                            "port": 43000,
                            "users": [
                              {
                                "flow": "xtls-rprx-vision",
                                "id": "1ab729d2-fd63-493d-bf9c-2ee83c01ee3b"
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
                          "publicKey": "IiUOQtR3zqUg32FfqorRXwUVSz9e1CSPFsJnafcFVmE",
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

        assertEquals(ProfileSourceType.RAW_SINGBOX_JSON, parsed.sourceType)
        assertEquals(ProtocolHint.VLESS, parsed.protocolHint)
        assertEquals("Game White Internet", parsed.displayName)
        assertEquals("tun", root["inbounds"]!!.jsonArray.first().jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("vless", primary["type"]!!.jsonPrimitive.content)
        assertEquals("37.139.40.59", primary["server"]!!.jsonPrimitive.content)
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
        assertEquals("IiUOQtR3zqUg32FfqorRXwUVSz9e1CSPFsJnafcFVmE", tls["reality"]!!.jsonObject["public_key"]!!.jsonPrimitive.content)
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

    @Test
    fun `parses exact user xray json with reality and emoji remarks`() {
        val parsed =
            parser.parseUserInput(
                """
                {
                  "dns": {
                    "hosts": {
                      "domain:googleapis.cn": "googleapis.com",
                      "dot.pub": [
                        "1.12.12.12",
                        "120.53.53.53"
                      ],
                      "dns.alidns.com": [
                        "223.5.5.5",
                        "223.6.6.6",
                        "2400:3200::1",
                        "2400:3200:baba::1"
                      ],
                      "one.one.one.one": [
                        "1.1.1.1",
                        "1.0.0.1",
                        "2606:4700:4700::1111",
                        "2606:4700:4700::1001"
                      ],
                      "dns.cloudflare.com": [
                        "104.16.132.229",
                        "104.16.133.229",
                        "2606:4700::6810:84e5",
                        "2606:4700::6810:85e5"
                      ],
                      "cloudflare-dns.com": [
                        "104.16.248.249",
                        "104.16.249.249",
                        "2606:4700::6810:f8f9",
                        "2606:4700::6810:f9f9"
                      ],
                      "dns.google": [
                        "8.8.8.8",
                        "8.8.4.4",
                        "2001:4860:4860::8888",
                        "2001:4860:4860::8844"
                      ],
                      "dns.quad9.net": [
                        "9.9.9.9",
                        "149.112.112.112",
                        "2620:fe::fe",
                        "2620:fe::9"
                      ],
                      "common.dot.dns.yandex.net": [
                        "77.88.8.8",
                        "77.88.8.1",
                        "2a02:6b8::feed:0ff",
                        "2a02:6b8:0:1::feed:0ff"
                      ]
                    },
                    "servers": [
                      "8.8.8.8"
                    ]
                  },
                  "inbounds": [
                    {
                      "listen": "127.0.0.1",
                      "port": 10808,
                      "protocol": "socks",
                      "settings": {
                        "auth": "noauth",
                        "udp": true,
                        "userLevel": 8
                      },
                      "sniffing": {
                        "destOverride": [
                          "http",
                          "tls"
                        ],
                        "enabled": true,
                        "routeOnly": false
                      },
                      "tag": "socks"
                    },
                    {
                      "listen": "127.0.0.1",
                      "port": 10809,
                      "protocol": "http",
                      "settings": {
                        "userLevel": 8
                      },
                      "tag": "http"
                    }
                  ],
                  "log": {
                    "loglevel": "warning"
                  },
                  "outbounds": [
                    {
                      "mux": {
                        "concurrency": -1,
                        "enabled": false
                      },
                      "protocol": "vless",
                      "settings": {
                        "vnext": [
                          {
                            "address": "37.139.40.59",
                            "port": 43000,
                            "users": [
                              {
                                "encryption": "none",
                                "flow": "xtls-rprx-vision",
                                "id": "1ab729d2-fd63-493d-bf9c-2ee83c01ee3b",
                                "level": 8
                              }
                            ]
                          }
                        ]
                      },
                      "streamSettings": {
                        "network": "tcp",
                        "realitySettings": {
                          "allowInsecure": false,
                          "fingerprint": "chrome",
                          "publicKey": "IiUOQtR3zqUg32FfqorRXwUVSz9e1CSPFsJnafcFVmE",
                          "serverName": "api-maps.yandex.ru",
                          "shortId": "736acf61",
                          "show": false,
                          "spiderX": "/"
                        },
                        "security": "reality",
                        "tcpSettings": {
                          "header": {
                            "type": "none"
                          }
                        }
                      },
                      "tag": "proxy"
                    },
                    {
                      "protocol": "freedom",
                      "settings": {
                        "domainStrategy": "UseIP"
                      },
                      "tag": "direct"
                    },
                    {
                      "protocol": "blackhole",
                      "settings": {
                        "response": {
                          "type": "http"
                        }
                      },
                      "tag": "block"
                    }
                  ],
                  "policy": {
                    "levels": {
                      "8": {
                        "connIdle": 300,
                        "downlinkOnly": 1,
                        "handshake": 4,
                        "uplinkOnly": 1
                      }
                    },
                    "system": {
                      "statsOutboundUplink": true,
                      "statsOutboundDownlink": true
                    }
                  },
                  "remarks": "💫 Игровой белый интернет",
                  "routing": {
                    "domainStrategy": "AsIs",
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
                  },
                  "stats": {}
                }
                """.trimIndent(),
            )

        val root = json.parseToJsonElement(parsed.normalizedConfigJson!!).jsonObject
        val primary = root["outbounds"]!!.jsonArray.first().jsonObject
        val tls = primary["tls"]!!.jsonObject

        assertEquals(ProfileSourceType.RAW_SINGBOX_JSON, parsed.sourceType)
        assertEquals(ProtocolHint.VLESS, parsed.protocolHint)
        assertEquals("💫 Игровой белый интернет", parsed.displayName)
        assertEquals("37.139.40.59", primary["server"]!!.jsonPrimitive.content)
        assertEquals("43000", primary["server_port"]!!.jsonPrimitive.content)
        assertEquals("api-maps.yandex.ru", tls["server_name"]!!.jsonPrimitive.content)
        assertEquals("IiUOQtR3zqUg32FfqorRXwUVSz9e1CSPFsJnafcFVmE", tls["reality"]!!.jsonObject["public_key"]!!.jsonPrimitive.content)
        assertEquals("736acf61", tls["reality"]!!.jsonObject["short_id"]!!.jsonPrimitive.content)
        assertEquals(false, root["route"]!!.jsonObject["rules"]!!.jsonArray[0].jsonObject["port"]!!.jsonPrimitive.isString)
    }

    @Test
    fun `rejects xray dns server hostname that resolves to private address`() {
        expectIllegalArgument {
            parser.parseUserInput(
                """
                {
                  "dns": {
                    "servers": [
                      {
                        "address": "https://xray-dns.example.com/dns-query"
                      }
                    ]
                  },
                  "outbounds": [
                    {
                      "protocol": "vless",
                      "settings": {
                        "vnext": [
                          {
                            "address": "37.139.40.59",
                            "port": 43000,
                            "users": [
                              {
                                "id": "1ab729d2-fd63-493d-bf9c-2ee83c01ee3b"
                              }
                            ]
                          }
                        ]
                      },
                      "tag": "proxy"
                    }
                  ]
                }
                """.trimIndent(),
            )
        }
    }

    @Test
    fun `rejects raw sing-box config with clash api`() {
        expectIllegalArgument {
            parser.parseUserInput(
                """
                {
                  "outbounds": [
                    { "type": "direct", "tag": "direct" }
                  ],
                  "experimental": {
                    "clash_api": {
                      "external_controller": "127.0.0.1:9090"
                    }
                  }
                }
                """.trimIndent(),
            )
        }
    }

    @Test
    fun `rejects raw sing-box config with v2ray api`() {
        expectIllegalArgument {
            parser.parseUserInput(
                """
                {
                  "outbounds": [
                    { "type": "direct", "tag": "direct" }
                  ],
                  "experimental": {
                    "v2ray_api": {
                      "listen": "127.0.0.1:8080"
                    }
                  }
                }
                """.trimIndent(),
            )
        }
    }

    @Test
    fun `parses hysteria2 uri`() {
        val parsed =
            parser.parseUserInput(
                "hy2://password@example.org:443" +
                    "?sni=edge.example.org&insecure=0&mport=8443-8445&hop_interval=30s" +
                    "&pinSHA256=95:20:0B:4C:DF:80:5E:D1:58:C3:33:30:42:37:D5:96" +
                    ":51:F4:3E:17:4C:8B:D5:C6:E6:2E:60:0A:75:32:C1:49#h2",
            )

        val root = json.parseToJsonElement(parsed.normalizedConfigJson!!).jsonObject
        val outbounds = root["outbounds"]!!.jsonArray
        val outbound = outbounds.first().jsonObject
        val tls = outbound["tls"]!!.jsonObject

        assertEquals(ProtocolHint.HYSTERIA2, parsed.protocolHint)
        assertEquals("hysteria2", outbound["type"]!!.jsonPrimitive.content)
        assertEquals(null, outbound["server_port"])
        assertEquals(listOf("8443:8445"), outbound["server_ports"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("30s", outbound["hop_interval"]!!.jsonPrimitive.content)
        assertEquals(
            listOf("lSALTN+AXtFYwzMwQjfVllH0PhdMi9XG5i5gCnUywUk="),
            tls["certificate_public_key_sha256"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `allows insecure tls in share uri when override enabled`() {
        val parsed =
            parser.parseUserInput(
                "vless://11111111-1111-1111-1111-111111111111@example.com:443?security=tls&allowInsecure=1&expire=1700000000#edge",
                allowInsecureTls = true,
            )

        val root = json.parseToJsonElement(parsed.normalizedConfigJson!!).jsonObject
        val tls = root["outbounds"]!!.jsonArray.first().jsonObject["tls"]!!.jsonObject

        assertEquals("true", tls["insecure"]!!.jsonPrimitive.content)
        assertEquals(1_700_000_000_000L, parsed.subscriptionExpiresAt)
    }

    @Test
    fun `rejects insecure tls in share uri when override disabled`() {
        expectIllegalArgument {
            parser.parseUserInput(
                "vless://11111111-1111-1111-1111-111111111111@example.com:443?security=tls&allowInsecure=1#edge",
                allowInsecureTls = false,
            )
        }
    }

    @Test
    fun `rejects insecure tls in share uri by default`() {
        expectIllegalArgument {
            parser.parseUserInput(
                "vless://11111111-1111-1111-1111-111111111111@example.com:443?security=tls&allowInsecure=1#edge",
            )
        }
    }

    @Test
    fun `sanitizes insecure tls in raw sing-box config when override enabled`() {
        val parsed =
            parser.parseUserInput(
                """
                {
                  "outbounds": [
                    {
                      "type": "vless",
                      "tag": "proxy",
                      "server": "example.com",
                      "server_port": 443,
                      "uuid": "11111111-1111-1111-1111-111111111111",
                      "tls": {
                        "enabled": true,
                        "allowInsecure": true
                      }
                    }
                  ]
                }
                """.trimIndent(),
                allowInsecureTls = true,
            )

        val root = json.parseToJsonElement(parsed.normalizedConfigJson!!).jsonObject
        val tls = root["outbounds"]!!.jsonArray.first().jsonObject["tls"]!!.jsonObject

        assertEquals("true", tls["insecure"]!!.jsonPrimitive.content)
    }

    @Test
    fun `rejects insecure tls in raw sing-box config when override disabled`() {
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
                      "uuid": "11111111-1111-1111-1111-111111111111",
                      "tls": {
                        "enabled": true,
                        "insecure": true
                      }
                    }
                  ]
                }
                """.trimIndent(),
                allowInsecureTls = false,
            )
        }
    }

    @Test
    fun `parses wireguard config`() {
        val parsed =
            parser.parseUserInput(
                """
                [Interface]
                PrivateKey = private
                Address = 10.0.0.2/32

                [Peer]
                PublicKey = public
                Endpoint = wg.example.com:51820
                AllowedIPs = 0.0.0.0/0, ::/0
                """.trimIndent(),
            )

        val root = json.parseToJsonElement(parsed.normalizedConfigJson!!).jsonObject
        val tunAddresses =
            root["inbounds"]!!
                .jsonArray
                .first()
                .jsonObject["address"]!!
                .jsonArray
                .map { it.jsonPrimitive.content }
        val endpoints = root["endpoints"]!!.jsonArray
        val peer = endpoints.first().jsonObject["peers"]!!.jsonArray.first().jsonObject

        assertEquals(ProtocolHint.WIREGUARD, parsed.protocolHint)
        assertEquals(listOf("172.19.0.1/30"), tunAddresses)
        assertEquals("wireguard", endpoints.first().jsonObject["type"]!!.jsonPrimitive.content)
        assertNotNull(endpoints.first().jsonObject["address"])
        assertEquals("wg.example.com", peer["address"]!!.jsonPrimitive.content)
        assertEquals("51820", peer["port"]!!.jsonPrimitive.content)
        assertEquals(
            listOf("0.0.0.0/0"),
            peer["allowed_ips"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `keeps dual stack tun inbound for dual stack wireguard config`() {
        val parsed =
            parser.parseUserInput(
                """
                [Interface]
                PrivateKey = private
                Address = 10.0.0.2/32, fd00::2/128

                [Peer]
                PublicKey = public
                Endpoint = wg.example.com:51820
                AllowedIPs = 0.0.0.0/0, ::/0
                """.trimIndent(),
            )

        val root = json.parseToJsonElement(parsed.normalizedConfigJson!!).jsonObject
        val tunAddresses =
            root["inbounds"]!!
                .jsonArray
                .first()
                .jsonObject["address"]!!
                .jsonArray
                .map { it.jsonPrimitive.content }
        val peer =
            root["endpoints"]!!
                .jsonArray
                .first()
                .jsonObject["peers"]!!
                .jsonArray
                .first()
                .jsonObject

        assertEquals(listOf("172.19.0.1/30", "fdfe:dcba:9876::1/126"), tunAddresses)
        assertEquals(
            listOf("0.0.0.0/0", "::/0"),
            peer["allowed_ips"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `parses bracketed ipv6 wireguard endpoint`() {
        val parsed =
            parser.parseUserInput(
                """
                [Interface]
                PrivateKey = private
                Address = 10.0.0.2/32

                [Peer]
                PublicKey = public
                Endpoint = [2606:4700:4700::1111]:51820
                AllowedIPs = 0.0.0.0/0, ::/0
                """.trimIndent(),
            )

        val peer =
            json.parseToJsonElement(parsed.normalizedConfigJson!!).jsonObject["endpoints"]!!
                .jsonArray
                .first()
                .jsonObject["peers"]!!
                .jsonArray
                .first()
                .jsonObject

        assertEquals("2606:4700:4700::1111", peer["address"]!!.jsonPrimitive.content)
        assertEquals("51820", peer["port"]!!.jsonPrimitive.content)
    }

    @Test
    fun `sanitizes resolved config with tun inbound`() {
        val sanitized =
            parser.sanitizeResolvedConfig(
                """
                {
                  "inbounds": [
                    { "type": "tun", "tag": "tun-in" }
                  ],
                  "outbounds": [
                    { "type": "direct", "tag": "direct" }
                  ],
                  "route": {
                    "final": "direct"
                  }
                }
                """.trimIndent(),
            )

        val root = json.parseToJsonElement(sanitized).jsonObject
        assertEquals("tun", root["inbounds"]!!.jsonArray.first().jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("direct", root["route"]!!.jsonObject["final"]!!.jsonPrimitive.content)
    }

    @Test
    fun `sanitizes resolved config route port strings into sing-box numeric fields`() {
        val sanitized =
            parser.sanitizeResolvedConfig(
                """
                {
                  "inbounds": [
                    { "type": "tun", "tag": "tun-in" }
                  ],
                  "outbounds": [
                    { "type": "direct", "tag": "direct" }
                  ],
                  "route": {
                    "rules": [
                      {
                        "action": "route",
                        "outbound": "direct",
                        "port": "53"
                      },
                      {
                        "action": "route",
                        "outbound": "direct",
                        "port": "853"
                      }
                    ],
                    "final": "direct"
                  }
                }
                """.trimIndent(),
            )

        val routeRules = json.parseToJsonElement(sanitized).jsonObject["route"]!!.jsonObject["rules"]!!.jsonArray

        assertEquals(false, routeRules[0].jsonObject["port"]!!.jsonPrimitive.isString)
        assertEquals("53", routeRules[0].jsonObject["port"]!!.jsonPrimitive.content)
        assertEquals(false, routeRules[1].jsonObject["port"]!!.jsonPrimitive.isString)
        assertEquals("853", routeRules[1].jsonObject["port"]!!.jsonPrimitive.content)
    }

    @Test
    fun `rejects resolved config with local proxy inbound`() {
        expectIllegalArgument {
            parser.sanitizeResolvedConfig(
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
    fun `rejects resolved config with private outbound host by default`() {
        expectIllegalArgument {
            parser.sanitizeResolvedConfig(
                """
                {
                  "inbounds": [
                    { "type": "tun", "tag": "tun-in" }
                  ],
                  "outbounds": [
                    { "type": "vless", "tag": "proxy", "server": "127.0.0.1", "server_port": 443, "uuid": "11111111-1111-1111-1111-111111111111" }
                  ]
                }
                """.trimIndent(),
            )
        }
    }

    @Test
    fun `rejects resolved config with nested wireguard peer hostname that resolves private`() {
        expectIllegalArgument {
            parser.sanitizeResolvedConfig(
                """
                {
                  "inbounds": [
                    { "type": "tun", "tag": "tun-in" }
                  ],
                  "outbounds": [
                    {
                      "type": "wireguard",
                      "tag": "proxy",
                      "private_key": "private",
                      "peers": [
                        {
                          "server": "wg-internal.example.com",
                          "server_port": 51820,
                          "public_key": "public"
                        }
                      ]
                    }
                  ]
                }
                """.trimIndent(),
            )
        }
    }

    private fun expectIllegalArgument(block: () -> Unit) {
        try {
            block()
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }

    private fun buildSmartConfigPayload(): String =
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

    private fun buildOutlineAccessKey(
        host: String,
        port: Int,
        name: String,
    ): String {
        val inner = "ss://2022-blake3-aes-128-gcm:outline-password@$host:$port#${name.replace(" ", "%20").replace("+", "%2B")}"
        val encoded = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(inner.toByteArray())
        return "outline://$encoded"
    }
}
