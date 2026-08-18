package com.foxhole.core.importer

import com.foxhole.core.model.ProfileSourceType
import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.SubscriptionEntryStatus
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

internal class ProfileImportParserTest : ProfileImportParserTestSupport() {
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
        assertEquals(ProtocolHint.NAIVE, parsed.protocolHint)
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
    fun `parses vless uri into normalized config`() {
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
                "hysteria2://cGFzc3dvcmQtcGxhY2Vob2xkZXItZm9yLWRvY3M9@edge.example.net:8443?sni=edge.example.net",
            )

        val root = json.parseToJsonElement(parsed.normalizedConfigJson!!).jsonObject
        val outbound = root["outbounds"]!!.jsonArray.first().jsonObject
        val tls = outbound["tls"]!!.jsonObject

        assertEquals(ProfileSourceType.SHARE_URI, parsed.sourceType)
        assertEquals(ProtocolHint.HYSTERIA2, parsed.protocolHint)
        assertEquals("edge.example.net", outbound["server"]!!.jsonPrimitive.content)
        assertEquals("8443", outbound["server_port"]!!.jsonPrimitive.content)
        assertEquals("edge.example.net", tls["server_name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `parses real single vless reality uri`() {
        val parsed =
            parser.parseUserInput(
                "vless://22222222222222222222222222222222@edge.example.net:8447?type=tcp&encryption=none&security=reality&sni=www.microsoft.com&pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA&sid=0000000000000000&fp=chrome&spx=/#example_secure_core_direct_62afe4",
            )

        val root = json.parseToJsonElement(parsed.normalizedConfigJson!!).jsonObject
        val outbound = root["outbounds"]!!.jsonArray.first().jsonObject
        val tls = outbound["tls"]!!.jsonObject

        assertEquals(ProfileSourceType.SHARE_URI, parsed.sourceType)
        assertEquals(ProtocolHint.VLESS, parsed.protocolHint)
        assertEquals("edge.example.net", outbound["server"]!!.jsonPrimitive.content)
        assertEquals("8447", outbound["server_port"]!!.jsonPrimitive.content)
        assertEquals("www.microsoft.com", tls["server_name"]!!.jsonPrimitive.content)
        assertEquals(
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
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
                vless://11111111-1111-1111-1111-111111111111@edge.example.net:8443?type=tcp&security=reality&encryption=none&flow=xtls-rprx-vision&sni=www.cloudflare.com&fp=chrome&pbk=public-key&sid=3025ae2cbf30b735&spx=%2F#asax-private-proxy-vless-20260525

                Client fields:
                address=edge.example.net
                port=8443
                """.trimIndent(),
            )

        val root = json.parseToJsonElement(parsed.normalizedConfigJson!!).jsonObject
        val outbound = root["outbounds"]!!.jsonArray.first().jsonObject
        val tls = outbound["tls"]!!.jsonObject

        assertEquals(ProfileSourceType.SHARE_URI, parsed.sourceType)
        assertEquals(ProtocolHint.VLESS, parsed.protocolHint)
        assertEquals("asax-private-proxy-vless-20260525", parsed.displayName)
        assertEquals("edge.example.net", outbound["server"]!!.jsonPrimitive.content)
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
                server: edge.example.net:443
                auth: client-secret

                tls:
                  sni: edge.example.net
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
        assertEquals("edge.example.net", parsed.displayName)
        assertEquals("hysteria2", outbound["type"]!!.jsonPrimitive.content)
        assertEquals("edge.example.net", outbound["server"]!!.jsonPrimitive.content)
        assertEquals("443", outbound["server_port"]!!.jsonPrimitive.content)
        assertEquals("client-secret", outbound["password"]!!.jsonPrimitive.content)
        assertEquals("udp", outbound["network"]!!.jsonPrimitive.content)
        assertEquals("edge.example.net", tls["server_name"]!!.jsonPrimitive.content)
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
                                "address": "203.0.113.59",
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
                "vless://00000000-0000-4000-8000-000000000010@203.0.113.253:39537?security=none&type=tcp&seed=4a47333ab40b2ac1#💫 Игровой белый интернет",
            )

        val root = json.parseToJsonElement(parsed.normalizedConfigJson!!).jsonObject
        val outbound = root["outbounds"]!!.jsonArray.first().jsonObject

        assertEquals(ProfileSourceType.SHARE_URI, parsed.sourceType)
        assertEquals(ProtocolHint.VLESS, parsed.protocolHint)
        assertEquals("💫 Игровой белый интернет", parsed.displayName)
        assertEquals("vless", outbound["type"]!!.jsonPrimitive.content)
        assertEquals("203.0.113.253", outbound["server"]!!.jsonPrimitive.content)
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
            parser.parseSubscriptionContentWithStrategy(
                """
                trojan://secret@example.org:443?security=tls#trojan
                vless://11111111-1111-1111-1111-111111111111@example.com:443?security=tls&type=ws&path=%2Fws&host=cdn.example.com#edge
                """.trimIndent(),
                "remote",
            ).parsed
        val root = json.parseToJsonElement(parsed.normalizedConfigJson!!).jsonObject
        val outbounds = root["outbounds"]!!.jsonArray

        assertEquals(ProfileSourceType.SUBSCRIPTION_URL, parsed.sourceType)
        assertEquals(2, parsed.nodesCount)
        assertEquals(5, outbounds.size)
    }

    @Test
    fun `ignores unsupported subscription protocols while keeping supported nodes`() {
        val parsed =
            parser.parseSubscriptionProfiles(
                """
                trojan://secret@example.org:443?security=tls#trojan
                ssr://secret@example.org:443#unsupported
                """.trimIndent(),
                fallbackName = "remote",
            )

        assertEquals(1, parsed.profiles.size)
        assertEquals(2, parsed.entryReports.size)
        assertEquals(
            listOf(
                com.foxhole.core.model.SubscriptionEntryStatus.ACCEPTED,
                com.foxhole.core.model.SubscriptionEntryStatus.IGNORED_UNSUPPORTED,
            ),
            parsed.entryReports.map { report -> report.status },
        )
        assertEquals(listOf("TROJAN", "SSR"), parsed.entryReports.map { report -> report.protocolLabel })
    }

    @Test
    fun `rejects malformed entry for a supported subscription protocol`() {
        expectIllegalArgument {
            parser.parseSubscriptionProfiles(
                """
                trojan://secret@example.org:443?security=tls#trojan
                vless://missing-host
                """.trimIndent(),
                fallbackName = "remote",
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

        val parsed = parser.parseSubscriptionContentWithStrategy(payload, "remote").parsed

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
    fun `groups compatible same host fetched subscription protocols into one smart profile`() {
        val parsed =
            parser.parseSubscriptionProfiles(
                """
                hy2://secret@bundle.example.com:443?insecure=0#hy2
                vless://11111111-1111-1111-1111-111111111111@bundle.example.com:443?encryption=none&security=tls&type=tcp#vless
                """.trimIndent(),
                "vpn.example.com",
                groupCompatibleSingleServerMultiProtocol = true,
            )

        val profile = parsed.profiles.single()

        assertEquals("vpn.example.com", parsed.displayName)
        assertEquals("vpn.example.com", profile.displayName)
        assertEquals(ProtocolHint.HYSTERIA2, profile.protocolHint)
        assertEquals("hysteria2", profile.selectedProtocolOptionId)
        assertEquals(
            listOf(ProtocolHint.HYSTERIA2, ProtocolHint.VLESS),
            profile.protocolOptions.map { option -> option.protocolHint },
        )
        assertEquals(listOf("hy2", "vless"), profile.protocolOptions.map { option -> option.displayName })
    }

    @Test
    fun `subscription report accepts supported protocols and describes ignored protocols`() {
        val parsed =
            parser.parseSubscriptionProfiles(
                """
                hy2://secret@bundle.example.com:443?insecure=0#hy2
                vless://11111111-1111-1111-1111-111111111111@bundle.example.com:443?encryption=none&security=none&type=tcp#vless
                ssr://secret@bundle.example.com:443#unsupported
                """.trimIndent(),
                "remote",
                allowInsecureTls = true,
                groupCompatibleSingleServerMultiProtocol = true,
            )

        assertEquals(2, parsed.entryReports.count { report -> report.status == SubscriptionEntryStatus.ACCEPTED })
        assertEquals(2, parsed.profiles.single().protocolOptions.size)
        assertEquals(
            listOf(
                "HYSTERIA2" to SubscriptionEntryStatus.ACCEPTED,
                "VLESS" to SubscriptionEntryStatus.ACCEPTED,
                "SSR" to SubscriptionEntryStatus.IGNORED_UNSUPPORTED,
            ),
            parsed.entryReports.map { report -> report.protocolLabel to report.status },
        )
        assertEquals("unsupported protocol", parsed.entryReports.last().reason)
    }

    @Test
    fun `an implemented utls fingerprint survives import under its own name`() {
        val requestedNames =
            listOf(
                "qq",
                "firefox",
                "firefox_153",
                "firefox_148",
                "edge",
                "safari",
                "ios",
                "chrome_151",
                "chrome_131",
                "randomized",
            )
        for (requested in requestedNames) {
            val parsed =
                parser.parseUserInput(
                    "vless://11111111-1111-1111-1111-111111111111@example.com:443" +
                        "?encryption=none&security=reality&type=tcp&fp=$requested&sni=edge.example.com" +
                        "&pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA01&sid=0000000000000001#vless",
                )

            val root = json.parseToJsonElement(parsed.normalizedConfigJson!!).jsonObject
            val tls = root["outbounds"]!!.jsonArray.first().jsonObject["tls"]!!.jsonObject
            assertEquals(
                requested,
                tls["utls"]!!.jsonObject["fingerprint"]!!.jsonPrimitive.content,
            )
        }
    }

    @Test
    fun `a parrot with no table is substituted and named`() {
        val parsed =
            parser.parseUserInput(
                "vless://11111111-1111-1111-1111-111111111111@example.com:443" +
                    "?encryption=none&security=tls&type=tcp&fp=hellogolang&sni=edge.example.com#vless",
            )

        val root = json.parseToJsonElement(parsed.normalizedConfigJson!!).jsonObject
        val tls = root["outbounds"]!!.jsonArray.first().jsonObject["tls"]!!.jsonObject
        assertEquals("chrome", tls["utls"]!!.jsonObject["fingerprint"]!!.jsonPrimitive.content)
        assertEquals("hellogolang", unsupportedUtlsFingerprint("hellogolang"))
    }

    @Test
    fun `reality refuses a parrot that cannot carry it`() {
        val impossible =
            mapOf(
                "360" to "no key_share",
                "android" to "no key_share",
            )
        for ((requested, reason) in impossible) {
            val failure =
                assertThrows(IllegalArgumentException::class.java) {
                    parser.parseUserInput(
                        "vless://11111111-1111-1111-1111-111111111111@example.com:443" +
                            "?encryption=none&security=reality&type=tcp&fp=$requested&sni=edge.example.com" +
                            "&pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA01&sid=0000000000000001#vless",
                    )
                }
            assertTrue(failure.message.orEmpty(), failure.message.orEmpty().contains("fp=$requested"))
            assertTrue(failure.message.orEmpty(), failure.message.orEmpty().contains(reason))
        }
    }

    @Test
    fun `only an unimplemented fingerprint is reported as swapped`() {
        assertEquals("hellogolang", unsupportedUtlsFingerprint("hellogolang"))
        assertEquals("360", unsupportedUtlsFingerprint("360"))
        assertNull(unsupportedUtlsFingerprint("qq"))
        assertNull(unsupportedUtlsFingerprint("Firefox"))
        assertNull(unsupportedUtlsFingerprint("chrome"))
        assertNull(unsupportedUtlsFingerprint("CHROME"))
        assertNull(unsupportedUtlsFingerprint("auto"))
        assertNull(unsupportedUtlsFingerprint(""))
        assertNull(unsupportedUtlsFingerprint(null))
    }

    @Test
    fun `one insecure entry refuses the whole subscription until the caller opts in`() {
        val body =
            """
            vless://11111111-1111-1111-1111-111111111111@bundle.example.com:443?encryption=none&security=none&type=tcp#vless
            trojan://secret@bundle.example.com:443?security=tls&sni=bundle.example.com&allowInsecure=1#trojan
            hy2://secret@bundle.example.com:443?insecure=0#hy2
            """.trimIndent()

        val refused =
            runCatching {
                parser.parseSubscriptionProfiles(
                    body,
                    "remote",
                    allowInsecureTls = false,
                    groupCompatibleSingleServerMultiProtocol = true,
                )
            }
        assertTrue("insecure entry must refuse the import", refused.isFailure)

        val allowed =
            parser.parseSubscriptionProfiles(
                body,
                "remote",
                allowInsecureTls = true,
                groupCompatibleSingleServerMultiProtocol = true,
            )
        assertEquals(
            3,
            allowed.entryReports.count { report -> report.status == SubscriptionEntryStatus.ACCEPTED },
        )
    }

    @Test
    fun `full share subscription accepts core protocols and ignores only mtproto`() {
        val parsed =
            parser.parseSubscriptionProfiles(
                """
                vless://11111111-1111-1111-1111-111111111111@vless.example.com:8443?security=tls#vless
                hysteria2://secret@hy2.example.com:8444/?sni=hy2.example.com#hy2
                trojan://secret@trojan.example.com:8445?security=tls&sni=trojan.example.com#trojan
                naive+https://user:password@naive.example.com:8449#naive
                ss://aes-256-gcm:plain-password@ss.example.com:8446#shadowsocks
                ss://aes-256-gcm:outline-password@outline-one.example.com:8447/?outline=1#outline-one
                ss://aes-256-gcm:outline-password@outline-two.example.com:8448/?outline=1#outline-two
                tg://proxy?server=mtproto.example.com&port=443&secret=unsupported
                wireguard://wireguard-private-key%3D@wg.example.com:51820?publickey=wireguard-public-key%3D&address=10.80.0.2%2F32&allowed_ips=0.0.0.0%2F0&keepalive=25&dns=1.1.1.1%2C8.8.8.8&mtu=1280#wireguard
                awg://amnezia-private-key%3D@awg.example.com:51821?publickey=amnezia-public-key%3D&address=10.81.0.2%2F32&allowed_ips=0.0.0.0%2F0&keepalive=25&dns=1.1.1.1&mtu=1280&jc=4&jmin=40&jmax=70&s1=15&s2=20&h1=10-19&h2=20-29&h3=30-39&h4=40-49#amnezia
                """.trimIndent(),
                "remote",
            )

        assertEquals(9, parsed.profiles.size)
        assertEquals(
            listOf(
                "VLESS" to SubscriptionEntryStatus.ACCEPTED,
                "HYSTERIA2" to SubscriptionEntryStatus.ACCEPTED,
                "TROJAN" to SubscriptionEntryStatus.ACCEPTED,
                "NAIVE" to SubscriptionEntryStatus.ACCEPTED,
                "SHADOWSOCKS" to SubscriptionEntryStatus.ACCEPTED,
                "OUTLINE" to SubscriptionEntryStatus.ACCEPTED,
                "OUTLINE" to SubscriptionEntryStatus.ACCEPTED,
                "MTPROTO" to SubscriptionEntryStatus.IGNORED_UNSUPPORTED,
                "WIREGUARD" to SubscriptionEntryStatus.ACCEPTED,
                "AMNEZIAWG" to SubscriptionEntryStatus.ACCEPTED,
            ),
            parsed.entryReports.map { report -> report.protocolLabel to report.status },
        )
        assertEquals(
            ProtocolHint.WIREGUARD,
            parsed.profiles.single { profile -> profile.displayName == "wireguard" }.protocolHint,
        )
        assertEquals(
            ProtocolHint.WIREGUARD,
            parsed.profiles.single { profile -> profile.displayName == "amnezia" }.protocolHint,
        )
    }

    @Test
    fun `groups different host fetched subscription protocols into one smart profile`() {
        val parsed =
            parser.parseSubscriptionProfiles(
                """
                hy2://secret@hy2.example.com:443?insecure=0#hy2
                vless://11111111-1111-1111-1111-111111111111@vless.example.com:443?encryption=none&security=tls&type=tcp#vless
                """.trimIndent(),
                "remote",
                groupCompatibleSingleServerMultiProtocol = true,
            )

        val profile = parsed.profiles.single()
        assertEquals("remote", profile.displayName)
        assertEquals(
            listOf(ProtocolHint.HYSTERIA2, ProtocolHint.VLESS),
            profile.protocolOptions.map { option -> option.protocolHint },
        )
    }

    @Test
    fun `keeps ordinary same protocol subscription servers as separate profiles`() {
        val parsed =
            parser.parseSubscriptionProfiles(
                """
                vless://11111111-1111-1111-1111-111111111111@alpha.example.com:443?encryption=none&security=tls&type=tcp#alpha
                vless://22222222-2222-2222-2222-222222222222@beta.example.com:443?encryption=none&security=tls&type=tcp#beta
                """.trimIndent(),
                "remote",
                groupCompatibleSingleServerMultiProtocol = true,
            )

        assertEquals(2, parsed.profiles.size)
        assertEquals(listOf("alpha", "beta"), parsed.profiles.map { profile -> profile.displayName })
        assertEquals(listOf(0, 0), parsed.profiles.map { profile -> profile.protocolOptions.size })
    }

    @Test
    fun `groups duplicate nodes when subscription still contains multiple protocols`() {
        val parsed =
            parser.parseSubscriptionProfiles(
                """
                vless://11111111-1111-1111-1111-111111111111@alpha.example.com:443?encryption=none&security=tls&type=tcp#alpha
                vless://22222222-2222-2222-2222-222222222222@beta.example.com:443?encryption=none&security=tls&type=tcp#beta
                hysteria2://secret@hy2.example.com:8444/?sni=hy2.example.com#hy2
                """.trimIndent(),
                "remote",
                groupCompatibleSingleServerMultiProtocol = true,
            )

        assertEquals(
            listOf(ProtocolHint.VLESS, ProtocolHint.VLESS, ProtocolHint.HYSTERIA2),
            parsed.profiles.single().protocolOptions.map { option -> option.protocolHint },
        )
    }

    @Test
    fun `parses stealthsurf style subscription payload`() {
        val parsed =
            parser.parseSubscriptionContentWithStrategy(
                "vless://00000000-0000-4000-8000-000000000010@203.0.113.253:39537?security=none&type=tcp&seed=4a47333ab40b2ac1#\ud83d\udcab \u0418\u0433\u0440\u043e\u0432\u043e\u0439 \u0431\u0435\u043b\u044b\u0439 \u0438\u043d\u0442\u0435\u0440\u043d\u0435\u0442",
                "connect.stealthsurf.app",
            ).parsed

        assertEquals(ProfileSourceType.SUBSCRIPTION_URL, parsed.sourceType)
        assertEquals(ProtocolHint.VLESS, parsed.protocolHint)
        assertEquals(
            "\ud83d\udcab \u0418\u0433\u0440\u043e\u0432\u043e\u0439 \u0431\u0435\u043b\u044b\u0439 \u0438\u043d\u0442\u0435\u0440\u043d\u0435\u0442",
            parsed.displayName
        )
        assertEquals(1, parsed.nodesCount)
    }
}
