package com.foxhole.core.runtime

import com.foxhole.core.model.AppTunnelLane
import com.foxhole.core.model.DnsSettings
import com.foxhole.core.model.ExpertSettings
import com.foxhole.core.model.LocalAuthSettings
import com.foxhole.core.model.LocalSurfaceSettings
import com.foxhole.core.model.PerAppRoutingMode
import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.ProxySurfaceMode
import com.foxhole.core.model.Settings
import com.foxhole.core.model.TrafficMode
import com.foxhole.core.model.TrafficSettings
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

internal class RuntimeConfigAssemblerInboundsTest : RuntimeConfigAssemblerTestSupport() {
    @Test
    fun `proxy mode routes runtime validation inbound through proxy outbound`() {
        val config =
            parse(
                assembler.assemble(
                    baseConfigJson = baseConfigWithRules("profile.example"),
                    settings = Settings(traffic = TrafficSettings(mode = TrafficMode.PROXY)),
                    activePreset = null,
                ),
            )
        val inbounds = config["inbounds"]!!.jsonArray.map { it.jsonObject }
        val rules = config["route"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }

        assertTrue(
            inbounds.any { inbound ->
                inbound["tag"]?.jsonPrimitive?.content == "foxhole-runtime-proxy-in" &&
                    inbound["type"]?.jsonPrimitive?.content == "http" &&
                    inbound["users"]
                        ?.jsonArray
                        ?.single()
                        ?.jsonObject
                        ?.get("password")
                        ?.jsonPrimitive
                        ?.content
                        ?.isNotBlank() == true
            },
        )
        assertRuntimeProxyRoute(rules.first())
        assertTrue(assembler.redactedRuntimeShape(config.toString()).contains("runtime_inbound=http"))
    }

    @Test
    fun `proxy mode routes reused local http inbound through proxy outbound`() {
        val config =
            parse(
                assembler.assemble(
                    baseConfigJson = baseConfigWithFinalOutbound("direct"),
                    settings =
                    Settings(
                        traffic = TrafficSettings(mode = TrafficMode.PROXY),
                        expert =
                        ExpertSettings(
                            localSurfaces =
                            LocalSurfaceSettings(
                                proxyMode = ProxySurfaceMode.HTTP,
                                auth = LocalAuthSettings(enabled = false),
                            ),
                        ),
                    ),
                    activePreset = null,
                ),
            )
        val inbounds = config["inbounds"]!!.jsonArray.map { it.jsonObject }
        val rules = config["route"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }

        assertEquals(1, inbounds.count { it["listen_port"]!!.jsonPrimitive.content == "10809" })
        assertTrue(inbounds.none { it["tag"]!!.jsonPrimitive.content == "foxhole-runtime-proxy-in" })
        assertTrue(inbounds.any { it["tag"]!!.jsonPrimitive.content == "http-in" })
        assertRuntimeProxyRoute(rules.first(), inbound = "http-in")
    }

    @Test
    fun `vless tcp outbound enables xudp udp relay for browser quic`() {
        val config =
            parse(
                assembler.assemble(
                    baseConfigJson =
                    baseConfigWithOutbounds(
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("type", "vless")
                                    put("tag", "proxy")
                                    put("server", "edge.example")
                                    put("server_port", 443)
                                    put("uuid", "11111111-1111-1111-1111-111111111111")
                                    put("network", "tcp")
                                },
                            )
                        },
                    ),
                    settings = Settings(),
                    activePreset = null,
                    vpnProtocolHint = ProtocolHint.VLESS,
                ),
            )

        val outbounds = config["outbounds"]!!.jsonArray.map { it.jsonObject }
        val vless = outbounds.single { it["type"]?.jsonPrimitive?.content == "vless" }
        val rules = config["route"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }

        assertFalse(vless.containsKey("network"))
        assertEquals("xudp", vless["packet_encoding"]!!.jsonPrimitive.content)
        assertFalse(
            rules.any {
                it["network"]?.jsonPrimitive?.content == "udp" &&
                    it["action"]?.jsonPrimitive?.content == "reject"
            },
        )
        assertTrue(assembler.redactedRuntimeShape(config.toString()).contains("route_udp_reject=false"))
    }

    @Test
    fun `vless vision tcp outbound preserves explicit tcp network`() {
        val config =
            parse(
                assembler.assemble(
                    baseConfigJson =
                    baseConfigWithOutbounds(
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("type", "vless")
                                    put("tag", "proxy")
                                    put("server", "edge.example")
                                    put("server_port", 443)
                                    put("uuid", "11111111-1111-1111-1111-111111111111")
                                    put("flow", "xtls-rprx-vision")
                                    put("network", "tcp")
                                    putJsonObject("tls") {
                                        put("enabled", true)
                                        putJsonObject("reality") {
                                            put("enabled", true)
                                            put("public_key", "pubkey")
                                            put("short_id", "abcd")
                                        }
                                    }
                                },
                            )
                        },
                    ),
                    settings = Settings(),
                    activePreset = null,
                    vpnProtocolHint = ProtocolHint.VLESS,
                ),
            )

        val outbounds = config["outbounds"]!!.jsonArray.map { it.jsonObject }
        val vless = outbounds.single { it["type"]?.jsonPrimitive?.content == "vless" }
        val rules = config["route"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }

        assertEquals("tcp", vless["network"]!!.jsonPrimitive.content)
        assertFalse(vless.containsKey("packet_encoding"))
        assertTrue(
            rules.any {
                it["network"]?.jsonPrimitive?.content == "udp" &&
                    it["action"]?.jsonPrimitive?.content == "reject"
            },
        )
        assertTrue(assembler.redactedRuntimeShape(config.toString()).contains("network=tcp"))
        assertTrue(assembler.redactedRuntimeShape(config.toString()).contains("route_udp_reject=true"))
    }

    @Test
    fun `tcp only non-vless outbound rejects browser udp before sniff`() {
        val config =
            parse(
                assembler.assemble(
                    baseConfigJson =
                    baseConfigWithOutbounds(
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("type", "trojan")
                                    put("tag", "proxy")
                                    put("server", "edge.example")
                                    put("server_port", 443)
                                    put("password", "secret")
                                    put("network", "tcp")
                                },
                            )
                        },
                    ),
                    settings = Settings(),
                    activePreset = null,
                    vpnProtocolHint = ProtocolHint.TROJAN,
                ),
            )

        val rules = config["route"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }
        val udpRejectIndex =
            rules.indexOfFirst {
                it["network"]?.jsonPrimitive?.content == "udp" &&
                    it["action"]?.jsonPrimitive?.content == "reject"
            }
        val sniffIndex =
            rules.indexOfFirst { it["action"]?.jsonPrimitive?.content == "sniff" }

        assertTrue(udpRejectIndex >= 0)
        assertTrue(sniffIndex >= 0)
        assertTrue(udpRejectIndex < sniffIndex)
        val udpRejects =
            rules.filter {
                it["network"]?.jsonPrimitive?.content == "udp" &&
                    it["action"]?.jsonPrimitive?.content == "reject"
            }
        assertEquals(listOf("1-52", "54-65535"), udpRejects.map { it["port_range"]!!.jsonPrimitive.content })
        assertTrue(
            "the managed DNS interceptor must receive UDP/53 before the TCP-only transport reject",
            udpRejects.none { reject ->
                reject["port"]?.jsonPrimitive?.intOrNull == 53 ||
                    reject["port_range"]?.jsonPrimitive?.content == "53-53"
            },
        )
        udpRejects.forEach { udpReject ->
            assertEquals("default", udpReject["method"]!!.jsonPrimitive.content)
            assertEquals("true", udpReject["no_drop"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `selector default tcp only non-vless outbound rejects browser udp`() {
        val config =
            parse(
                assembler.assemble(
                    baseConfigJson =
                    baseConfigWithOutbounds(
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("type", "vless")
                                    put("tag", "vless-direct")
                                    put("server", "vless.example")
                                    put("server_port", 443)
                                    put("uuid", "11111111-1111-1111-1111-111111111111")
                                    put("network", "tcp")
                                },
                            )
                            add(
                                buildJsonObject {
                                    put("type", "trojan")
                                    put("tag", "trojan-direct")
                                    put("server", "trojan.example")
                                    put("server_port", 443)
                                    put("password", "secret")
                                    put("network", "tcp")
                                },
                            )
                            add(
                                buildJsonObject {
                                    put("type", "selector")
                                    put("tag", "proxy")
                                    put("default", "trojan-direct")
                                    putJsonArray("outbounds") {
                                        add(JsonPrimitive("vless-direct"))
                                        add(JsonPrimitive("trojan-direct"))
                                    }
                                },
                            )
                        },
                    ),
                    settings = Settings(),
                    activePreset = null,
                    vpnProtocolHint = ProtocolHint.TROJAN,
                ),
            )

        val rules = config["route"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }
        val udpRejectIndex =
            rules.indexOfFirst {
                it["network"]?.jsonPrimitive?.content == "udp" &&
                    it["action"]?.jsonPrimitive?.content == "reject"
            }
        val sniffIndex =
            rules.indexOfFirst { it["action"]?.jsonPrimitive?.content == "sniff" }
        val dnsHijackIndex =
            rules.indexOfFirst {
                it["port"]?.jsonPrimitive?.intOrNull == 53 &&
                    it["action"]?.jsonPrimitive?.content == "hijack-dns"
            }
        val shape = assembler.redactedRuntimeShape(config.toString())

        assertTrue(udpRejectIndex >= 0)
        assertTrue(dnsHijackIndex >= 0)
        assertTrue(dnsHijackIndex < udpRejectIndex)
        assertTrue(sniffIndex >= 0)
        assertTrue(udpRejectIndex < sniffIndex)
        assertTrue(shape, shape.contains("type=trojan"))
        assertTrue(shape, shape.contains("selector=true"))
        assertTrue(shape, shape.contains("route_udp_reject=true"))
    }

    @Test
    fun `full-device tunnel keeps app package eligible for vpn-bound validation`() {
        val config = parse(assembler.assemble(baseConfigWithRules("profile.example"), Settings(), null))
        val tunInbound = config["inbounds"]!!.jsonArray.first().jsonObject

        assertFalse(tunInbound.containsKey("exclude_package"))
        assertFalse(tunInbound.containsKey("include_package"))
    }

    @Test
    fun `redacted runtime shape reports proxy tls flags without secrets`() {
        val config =
            assembler.assemble(
                baseConfigJson =
                baseConfigWithOutbounds(
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("type", "vless")
                                put("tag", "vless-direct")
                                put("server", "edge.example")
                                put("server_port", 443)
                                put("uuid", "11111111-1111-1111-1111-111111111111")
                                put("flow", "xtls-rprx-vision")
                                put("packet_encoding", "xudp")
                                putJsonObject("tls") {
                                    put("enabled", true)
                                    put("server_name", "cdn.example")
                                    putJsonObject("utls") {
                                        put("enabled", true)
                                        put("fingerprint", "chrome")
                                    }
                                }
                            },
                        )
                        add(
                            buildJsonObject {
                                put("type", "selector")
                                put("tag", "proxy")
                                put("default", "vless-direct")
                                putJsonArray("outbounds") { add(JsonPrimitive("vless-direct")) }
                            },
                        )
                    },
                ),
                settings = Settings(),
                activePreset = null,
            )

        val shape = assembler.redactedRuntimeShape(config)

        assertTrue(shape, shape.contains("type=vless"))
        assertTrue(shape, shape.contains("selector=true"))
        assertTrue(shape, shape.contains("tls=true"))
        assertTrue(shape, shape.contains("utls=true"))
        assertTrue(shape, shape.contains("fp=chrome"))
        assertTrue(shape, shape.contains("flow=true"))
        assertTrue(shape, shape.contains("packet=xudp"))
        assertFalse(shape, shape.contains("edge.example"))
        assertFalse(shape, shape.contains("11111111"))
    }

    @Test
    fun `redacted runtime shape reports lan inbound count without address`() {
        val config =
            buildJsonObject {
                putJsonArray("inbounds") {
                    add(
                        buildJsonObject {
                            put("type", "http")
                            put("tag", "http-in-lan")
                            put("listen", "192.168.13.20")
                            put("listen_port", 10809)
                        },
                    )
                }
                putJsonArray("outbounds") {
                    add(
                        buildJsonObject {
                            put("type", "direct")
                            put("tag", "direct")
                        },
                    )
                }
                putJsonObject("dns") {
                    putJsonArray("servers") {}
                }
                putJsonObject("route") {
                    put("final", "direct")
                }
            }.toString()

        val shape = assembler.redactedRuntimeShape(config)

        assertTrue(shape, shape.contains("lan_inbounds=1"))
        assertFalse(shape, shape.contains("192.168.13.20"))
    }

    @Test
    fun `tcp capable outbounds get mobile keepalive without network strategy override`() {
        val config =
            parse(
                assembler.assemble(
                    baseConfigJson =
                    baseConfigWithOutbounds(
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("type", "vless")
                                    put("tag", "proxy")
                                    put("server", "edge.example")
                                    put("server_port", 443)
                                    put("uuid", "11111111-1111-1111-1111-111111111111")
                                },
                            )
                            add(
                                buildJsonObject {
                                    put("type", "trojan")
                                    put("tag", "trojan-ws")
                                    put("server", "edge.example")
                                    put("server_port", 443)
                                    put("password", "secret")
                                    put("transport", buildJsonObject { put("type", "ws") })
                                },
                            )
                            add(
                                buildJsonObject {
                                    put("type", "hysteria2")
                                    put("tag", "hy2")
                                    put("server", "edge.example")
                                    put("server_port", 443)
                                },
                            )
                        },
                    ),
                    settings = Settings(),
                    activePreset = null,
                ),
            )

        val outbounds = config["outbounds"]!!.jsonArray.map { it.jsonObject }
        val vless = outbounds[0]
        val trojan = outbounds[1]
        val hysteria2 = outbounds[2]
        assertEquals("outbounds=$outbounds", "30s", vless["tcp_keep_alive"]?.jsonPrimitive?.content)
        assertEquals("15s", vless["tcp_keep_alive_interval"]?.jsonPrimitive?.content)
        assertFalse(vless.containsKey("network_strategy"))
        assertEquals("30s", trojan["tcp_keep_alive"]!!.jsonPrimitive.content)
        assertFalse(trojan.containsKey("network_strategy"))
        assertFalse(hysteria2.containsKey("tcp_keep_alive"))
        assertFalse(hysteria2.containsKey("network_strategy"))
    }

    @Test
    fun `tcp reliability patch respects explicit dial fields`() {
        val config =
            parse(
                assembler.assemble(
                    baseConfigJson =
                    baseConfigWithOutbounds(
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("type", "vless")
                                    put("tag", "proxy")
                                    put("server", "edge.example")
                                    put("server_port", 443)
                                    put("uuid", "11111111-1111-1111-1111-111111111111")
                                    put("tcp_keep_alive", "2m")
                                    put("tcp_keep_alive_interval", "30s")
                                    put("network_strategy", "default")
                                },
                            )
                            add(
                                buildJsonObject {
                                    put("type", "trojan")
                                    put("tag", "direct-detour")
                                    put("server", "edge.example")
                                    put("server_port", 443)
                                    put("password", "secret")
                                    put("detour", "direct")
                                },
                            )
                        },
                    ),
                    settings = Settings(),
                    activePreset = null,
                ),
            )

        val outbounds = config["outbounds"]!!.jsonArray.map { it.jsonObject }
        assertEquals("2m", outbounds[0]["tcp_keep_alive"]!!.jsonPrimitive.content)
        assertEquals("30s", outbounds[0]["tcp_keep_alive_interval"]!!.jsonPrimitive.content)
        assertEquals("default", outbounds[0]["network_strategy"]!!.jsonPrimitive.content)
        assertFalse(outbounds[1].containsKey("tcp_keep_alive"))
        assertFalse(outbounds[1].containsKey("network_strategy"))
    }

    @Test
    fun `include split lives in route rules only - tun inbound stays full-device`() {
        val settings =
            Settings(
                expert =
                ExpertSettings(
                    perAppRoutingMode = PerAppRoutingMode.INCLUDE_SELECTED_APPS,
                    appAssignments = mapOf("com.example.app" to AppTunnelLane.VPN),
                ),
            )

        val config = parse(assembler.assemble(baseConfigWithRules("profile.example"), settings, null))
        val tunInbound = config["inbounds"]!!.jsonArray.first().jsonObject
        val rules = config["route"]!!.jsonObject["rules"]!!.jsonArray

        assertEquals(2, config["inbounds"]!!.jsonArray.size)
        // Ф-ГА2: membership must never change the kernel interface — no package keys on the tun.
        assertFalse(tunInbound.containsKey("include_package"))
        assertFalse(tunInbound.containsKey("exclude_package"))
        val splitRule =
            rules.map { it.jsonObject }.single { rule -> rule.containsKey("package_name") }
        assertEquals("com.example.app", splitRule["package_name"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals(true, splitRule["invert"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("direct", splitRule["outbound"]!!.jsonPrimitive.content)

        val dnsRules = config["dns"]!!.jsonObject["rules"]?.jsonArray.orEmpty()
        assertTrue(dnsRules.none { rule -> rule.jsonObject.containsKey("package_name") })
        assertTrue(
            config["outbounds"]!!.jsonArray.any { outbound ->
                outbound.jsonObject["tag"]?.jsonPrimitive?.content == "direct"
            },
        )
        assertEquals("proxy", config["route"]!!.jsonObject["final"]!!.jsonPrimitive.content)
    }

    @Test
    fun `local firewall guard does not depend on proxy DNS detour`() {
        val settings =
            Settings(
                dns = DnsSettings(interceptDnsRequests = true),
                expert =
                ExpertSettings(
                    blockedPackagesEnabled = true,
                    appAssignments = mapOf("org.mozilla.firefox" to AppTunnelLane.BLOCK),
                    blockAppsAlways = true,
                ),
            )

        val config = parse(assembler.assembleLocalGuard(settings, LocalGuardMode.FIREWALL))
        val dns = config["dns"]!!.jsonObject
        val route = config["route"]!!.jsonObject
        val outbounds = config["outbounds"]!!.jsonArray.map { it.jsonObject["tag"]!!.jsonPrimitive.content }
        val dnsServers = dns["servers"]!!.jsonArray.map { it.jsonObject }
        val tunInbound = config["inbounds"]!!.jsonArray.single().jsonObject

        assertEquals(listOf("direct", "block"), outbounds)
        assertEquals("dns-remote", dns["final"]!!.jsonPrimitive.content)
        assertEquals("dns-remote", route["default_domain_resolver"]!!.jsonPrimitive.content)
        assertEquals("direct", route["final"]!!.jsonPrimitive.content)
        assertEquals(false, route["override_android_vpn"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(false, tunInbound["strict_route"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(
            route["rules"]!!.jsonArray.map { it.jsonObject }.any { rule ->
                rule["action"]?.jsonPrimitive?.content == "hijack-dns"
            }
        )
        assertTrue(dnsServers.any { server -> server["tag"]!!.jsonPrimitive.content == "dns-remote" })
        assertFalse(dnsServers.any { server -> server["detour"] != null })
        assertFalse(tunInbound.containsKey("include_package"))
        assertLocalGuardExcludesFoxHole(tunInbound)
        assertTrue(
            route["rules"]!!.jsonArray.map { it.jsonObject }.any { rule ->
                rule["package_name"]?.jsonArray?.single()?.jsonPrimitive?.content == "org.mozilla.firefox" &&
                    rule["outbound"]?.jsonPrimitive?.content == "block"
            },
        )
    }

    @Test
    fun `local firewall guard blocks a blocked app before hijacking its DNS`() {
        val settings =
            Settings(
                dns = DnsSettings(interceptDnsRequests = true),
                expert =
                ExpertSettings(
                    blockedPackagesEnabled = true,
                    appAssignments = mapOf("org.mozilla.firefox" to AppTunnelLane.BLOCK),
                    blockAppsAlways = true,
                ),
            )

        val config = parse(assembler.assembleLocalGuard(settings, LocalGuardMode.FIREWALL))
        val rules = config["route"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }
        val blockIndex =
            rules.indexOfFirst { rule ->
                rule["package_name"]?.jsonArray?.singleOrNull()?.jsonPrimitive?.content == "org.mozilla.firefox" &&
                    rule["outbound"]?.jsonPrimitive?.content == "block"
            }
        val firstHijackIndex = rules.indexOfFirst { rule -> rule["action"]?.jsonPrimitive?.content == "hijack-dns" }

        assertTrue(blockIndex >= 0)
        assertTrue(firstHijackIndex >= 0)
        assertTrue(blockIndex < firstHijackIndex)
    }

    @Test
    fun `local firewall guard captures DNS with public DoH when intercept is enabled`() {
        val filterPath = "/data/user/0/com.foxhole.guard/files/dns-rule-sets/adguard-dns-filter.verified.srs"
        val settings =
            Settings(
                dns = DnsSettings(filteringEnabled = true, interceptDnsRequests = true),
                expert = ExpertSettings(firewallEnabled = true),
            )

        val config =
            parse(
                assembler.assembleLocalGuard(
                    settings,
                    LocalGuardMode.FIREWALL,
                    dnsFilterRuntimePaths = DnsFilterRuntimePaths(adGuardDnsFilterPath = filterPath),
                ),
            )
        val dns = config["dns"]!!.jsonObject
        val route = config["route"]!!.jsonObject
        val rules = route["rules"]!!.jsonArray.map { it.jsonObject }
        val dnsServers = dns["servers"]!!.jsonArray.map { it.jsonObject }

        assertEquals("dns-remote", dns["final"]!!.jsonPrimitive.content)
        assertEquals("dns-remote", route["default_domain_resolver"]!!.jsonPrimitive.content)
        val remoteServer = dnsServers.single { server -> server["tag"]!!.jsonPrimitive.content == "dns-remote" }
        assertEquals("https", remoteServer["type"]!!.jsonPrimitive.content)
        assertEquals("1.1.1.1", remoteServer["server"]!!.jsonPrimitive.content)
        assertEquals("443", remoteServer["server_port"]!!.jsonPrimitive.content)
        assertEquals("/dns-query", remoteServer["path"]!!.jsonPrimitive.content)
        assertFalse(remoteServer.containsKey("detour"))
        assertTrue(
            rules.any { rule ->
                rule["network"]?.jsonPrimitive?.content == "tcp" &&
                    rule["port"]?.jsonPrimitive?.content == "853" &&
                    rule["outbound"]?.jsonPrimitive?.content == "block"
            }
        )
        assertTrue(rules.any { rule -> rule["action"]?.jsonPrimitive?.content == "hijack-dns" })
    }

    @Test
    fun `runtime log level stays bounded when a verified dns filter is active`() {
        val filterPath = "/data/user/0/com.foxhole.guard/files/dns-rule-sets/adguard-dns-filter.verified.srs"
        val settings = Settings(dns = DnsSettings(filteringEnabled = true))

        val filtered =
            parse(
                assembler.assembleLocalGuard(
                    settings,
                    LocalGuardMode.FIREWALL,
                    dnsFilterRuntimePaths = DnsFilterRuntimePaths(adGuardDnsFilterPath = filterPath),
                ),
            )
        val unfiltered =
            parse(assembler.assembleLocalGuard(settings, LocalGuardMode.FIREWALL, dnsFilterRuntimePaths = null))

        assertEquals(FOXHOLE_RUNTIME_LOG_LEVEL, filtered["log"]!!.jsonObject["level"]!!.jsonPrimitive.content)
        assertEquals(
            FOXHOLE_RUNTIME_LOG_LEVEL,
            unfiltered["log"]!!.jsonObject["level"]!!.jsonPrimitive.content,
        )
    }
}
