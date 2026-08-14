package com.foxhole.core.importer

import com.foxhole.core.model.ProfileSourceType
import com.foxhole.core.model.ProtocolHint
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

internal class ProfileImportUserConfigParserTest : ProfileImportParserTestSupport() {
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
                            "address": "203.0.113.59",
                            "port": 43000,
                            "users": [
                              {
                                "encryption": "none",
                                "flow": "xtls-rprx-vision",
                                "id": "00000000-0000-4000-8000-000000000011",
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
                          "publicKey": "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB",
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

        assertEquals(ProfileSourceType.RAW_CONFIG_JSON, parsed.sourceType)
        assertEquals(ProtocolHint.VLESS, parsed.protocolHint)
        assertEquals("💫 Игровой белый интернет", parsed.displayName)
        assertEquals("203.0.113.59", primary["server"]!!.jsonPrimitive.content)
        assertEquals("43000", primary["server_port"]!!.jsonPrimitive.content)
        assertEquals("api-maps.yandex.ru", tls["server_name"]!!.jsonPrimitive.content)
        assertEquals(
            "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB",
            tls["reality"]!!.jsonObject["public_key"]!!.jsonPrimitive.content
        )
        assertEquals("736acf61", tls["reality"]!!.jsonObject["short_id"]!!.jsonPrimitive.content)
        assertEquals(
            false,
            root["route"]!!.jsonObject["rules"]!!.jsonArray[0].jsonObject["port"]!!.jsonPrimitive.isString
        )
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
                            "address": "203.0.113.59",
                            "port": 43000,
                            "users": [
                              {
                                "id": "00000000-0000-4000-8000-000000000011"
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
    fun `rejects raw normalized config with clash api`() {
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
    fun `rejects raw normalized config with v2ray api`() {
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
    fun `retired engine json stays rejected when insecure tls override is enabled`() {
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
                        "allowInsecure": true
                      }
                    }
                  ]
                }
                """.trimIndent(),
                allowInsecureTls = true,
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
    fun `sanitizes resolved config route port strings into numeric fields`() {
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
}
