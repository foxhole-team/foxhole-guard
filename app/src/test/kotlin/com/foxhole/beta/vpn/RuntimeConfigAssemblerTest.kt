package com.foxhole.beta.vpn

import com.foxhole.beta.core.model.AppLocale
import com.foxhole.beta.core.model.ClashApiSettings
import com.foxhole.beta.core.model.DiagnosticsRetention
import com.foxhole.beta.core.model.DnsSettings
import com.foxhole.beta.core.model.ExpertSettings
import com.foxhole.beta.core.model.LocalAuthSettings
import com.foxhole.beta.core.model.LocalSurfaceSettings
import com.foxhole.beta.core.model.PerAppRoutingMode
import com.foxhole.beta.core.model.PrivacyRouteMode
import com.foxhole.beta.core.model.PrivacyRouteScope
import com.foxhole.beta.core.model.ProfileTrafficTotal
import com.foxhole.beta.core.model.ProtocolHint
import com.foxhole.beta.core.model.ProxyInboundSettings
import com.foxhole.beta.core.model.ProxySurfaceMode
import com.foxhole.beta.core.model.RoutingPreset
import com.foxhole.beta.core.model.RoutingPresetOverrideMode
import com.foxhole.beta.core.model.RoutingPresetSource
import com.foxhole.beta.core.model.RoutingRule
import com.foxhole.beta.core.model.RoutingRuleAction
import com.foxhole.beta.core.model.SecureDnsMode
import com.foxhole.beta.core.model.Settings
import com.foxhole.beta.core.model.TrafficSettings
import com.foxhole.beta.core.model.TrafficMode
import com.foxhole.beta.core.model.TunStack
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeConfigAssemblerTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
    private val assembler = RuntimeConfigAssembler(json)

    private class FakeLanProxyAddressProvider(
        var address: String? = null,
    ) : LanProxyAddressProvider {
        override fun currentWifiIpv4Address(): String? = address
    }

    @Test
    fun `respect profile keeps existing route rules before local preset`() {
        val config =
            parse(
                assembler.assemble(
                    baseConfigJson = baseConfigWithRules("profile.example"),
                    settings = Settings(),
                    activePreset = preset(RoutingPresetOverrideMode.RESPECT_PROFILE),
                ),
        )

        val rules = config["route"]!!.jsonObject["rules"]!!.jsonArray
        assertEquals(5, rules.size)
        assertSniffRule(rules[0].jsonObject)
        assertPortDnsHijack(rules[1].jsonObject)
        assertProtocolDnsHijack(rules[2].jsonObject)
        assertEquals("profile.example", rules[3].jsonObject["domain"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("local.example", rules[4].jsonObject["domain"]!!.jsonArray[0].jsonPrimitive.content)
    }

    @Test
    fun `force local preset replaces profile route rules`() {
        val config =
            parse(
                assembler.assemble(
                    baseConfigJson = baseConfigWithRules("profile.example"),
                    settings = Settings(),
                    activePreset = preset(RoutingPresetOverrideMode.FORCE_LOCAL),
                ),
            )

        val rules = config["route"]!!.jsonObject["rules"]!!.jsonArray
        assertEquals(4, rules.size)
        assertSniffRule(rules[0].jsonObject)
        assertPortDnsHijack(rules[1].jsonObject)
        assertProtocolDnsHijack(rules[2].jsonObject)
        assertEquals("local.example", rules[3].jsonObject["domain"]!!.jsonArray[0].jsonPrimitive.content)
    }

    @Test
    fun `system tun stack falls back to gvisor for non wireguard tunnel protocols`() {
        val config =
            parse(
                assembler.assemble(
                    baseConfigJson = baseConfigWithRules("profile.example"),
                    settings = Settings(traffic = TrafficSettings(tunStack = TunStack.SYSTEM)),
                    activePreset = null,
                    vpnProtocolHint = ProtocolHint.VLESS,
                ),
            )

        val tun = config["inbounds"]!!.jsonArray.first().jsonObject

        assertEquals("gvisor", tun["stack"]!!.jsonPrimitive.content)
    }

    @Test
    fun `system tun stack is preserved for wireguard tunnel protocols`() {
        val config =
            parse(
                assembler.assemble(
                    baseConfigJson = baseConfigWithRules("profile.example"),
                    settings = Settings(traffic = TrafficSettings(tunStack = TunStack.SYSTEM)),
                    activePreset = null,
                    vpnProtocolHint = ProtocolHint.WIREGUARD,
                ),
            )

        val tun = config["inbounds"]!!.jsonArray.first().jsonObject

        assertEquals("system", tun["stack"]!!.jsonPrimitive.content)
    }

    @Test
    fun `tor privacy route adds tor outbound detoured through proxy and blocks udp for all apps`() {
        val config =
            parse(
                assembler.assemble(
                    baseConfigJson = baseConfigWithRules("profile.example"),
                    settings =
                        Settings(
                            privacyRoute =
                                com.foxhole.beta.core.model.PrivacyRouteSettings(
                                    mode = PrivacyRouteMode.TOR_OVER_VPN,
                                    scope = PrivacyRouteScope.ALL_APPS,
                                ),
                        ),
                    activePreset = null,
                    torRuntimePaths =
                        TorRuntimePaths(
                            executablePath = "/data/app/com.foxhole.beta/lib/arm64/libTor.so",
                            dataDirectory = "/data/user/0/com.foxhole.beta/files/tor-data/arm64-v8a",
                            torrcDefaultsFilePath = "/data/user/0/com.foxhole.beta/files/tor-data/arm64-v8a/torrc-defaults",
                        ),
                    vpnProtocolHint = ProtocolHint.VLESS,
                ),
            )

        val outbounds = config["outbounds"]!!.jsonArray.map { it.jsonObject }
        val tor = outbounds.single { it["tag"]!!.jsonPrimitive.content == "tor-over-vpn" }
        assertEquals("tor", tor["type"]!!.jsonPrimitive.content)
        assertEquals("proxy", tor["detour"]!!.jsonPrimitive.content)
        assertEquals("/data/app/com.foxhole.beta/lib/arm64/libTor.so", tor["executable_path"]!!.jsonPrimitive.content)
        assertEquals(
            listOf("--defaults-torrc", "/data/user/0/com.foxhole.beta/files/tor-data/arm64-v8a/torrc-defaults"),
            tor["extra_args"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals("/data/user/0/com.foxhole.beta/files/tor-data/arm64-v8a", tor["data_directory"]!!.jsonPrimitive.content)
        val torrc = tor["torrc"]!!.jsonObject
        assertEquals("1", torrc["ClientOnly"]!!.jsonPrimitive.content)
        assertTrue(torrc["ClientOnly"]!!.jsonPrimitive.isString)
        assertEquals("1", torrc["AvoidDiskWrites"]!!.jsonPrimitive.content)
        assertTrue(torrc["AvoidDiskWrites"]!!.jsonPrimitive.isString)

        val dnsRemote = config["dns"]!!.jsonObject["servers"]!!.jsonArray.map { it.jsonObject }
            .single { it["tag"]!!.jsonPrimitive.content == "dns-remote" }
        assertEquals("tor-over-vpn", dnsRemote["detour"]!!.jsonPrimitive.content)

        val route = config["route"]!!.jsonObject
        assertEquals("tor-over-vpn", route["final"]!!.jsonPrimitive.content)
        val udpBlock = route["rules"]!!.jsonArray.map { it.jsonObject }
            .single { it["network"]?.jsonPrimitive?.content == "udp" }
        assertEquals("block", udpBlock["outbound"]!!.jsonPrimitive.content)
    }

    @Test
    fun `tor privacy route selected apps routes only selected tcp packages and blocks their udp`() {
        val config =
            parse(
                assembler.assemble(
                    baseConfigJson = baseConfigWithRules("profile.example"),
                    settings =
                        Settings(
                            privacyRoute =
                                com.foxhole.beta.core.model.PrivacyRouteSettings(
                                    mode = PrivacyRouteMode.TOR_OVER_VPN,
                                    scope = PrivacyRouteScope.SELECTED_APPS,
                                    selectedPackages = listOf("org.mozilla.firefox"),
                                ),
                        ),
                    activePreset = null,
                    torRuntimePaths = TorRuntimePaths(executablePath = "/tor", dataDirectory = "/tor-data"),
                    vpnProtocolHint = ProtocolHint.VLESS,
                ),
            )

        val route = config["route"]!!.jsonObject
        assertEquals("proxy", route["final"]!!.jsonPrimitive.content)
        val packageRules = route["rules"]!!.jsonArray.map { it.jsonObject }
            .filter { it["package_name"] != null && it["network"] != null }
        assertEquals(2, packageRules.size)
        assertTrue(packageRules.any { it["network"]!!.jsonPrimitive.content == "tcp" && it["outbound"]!!.jsonPrimitive.content == "tor-over-vpn" })
        assertTrue(packageRules.any { it["network"]!!.jsonPrimitive.content == "udp" && it["outbound"]!!.jsonPrimitive.content == "block" })
    }

    @Test
    fun `tor privacy route selected apps without packages leaves ordinary proxy route untouched`() {
        val config =
            parse(
                assembler.assemble(
                    baseConfigJson = baseConfigWithRules("profile.example"),
                    settings =
                        Settings(
                            privacyRoute =
                                com.foxhole.beta.core.model.PrivacyRouteSettings(
                                    mode = PrivacyRouteMode.TOR_OVER_VPN,
                                    scope = PrivacyRouteScope.SELECTED_APPS,
                                    selectedPackages = emptyList(),
                                ),
                        ),
                    activePreset = null,
                    torRuntimePaths = TorRuntimePaths(executablePath = "/tor", dataDirectory = "/tor-data"),
                    vpnProtocolHint = ProtocolHint.VLESS,
                ),
            )

        val outboundTags = config["outbounds"]!!.jsonArray.map { it.jsonObject["tag"]!!.jsonPrimitive.content }

        assertFalse(outboundTags.contains("tor-over-vpn"))
        assertEquals("proxy", config["route"]!!.jsonObject["final"]!!.jsonPrimitive.content)
    }

    @Test
    fun `tor privacy route is ignored for udp vpn protocols`() {
        val config =
            parse(
                assembler.assemble(
                    baseConfigJson = baseConfigWithRules("profile.example"),
                    settings =
                        Settings(
                            privacyRoute =
                                com.foxhole.beta.core.model.PrivacyRouteSettings(
                                    mode = PrivacyRouteMode.TOR_OVER_VPN,
                                    scope = PrivacyRouteScope.ALL_APPS,
                                ),
                        ),
                    activePreset = null,
                    torRuntimePaths = TorRuntimePaths(executablePath = "/tor", dataDirectory = "/tor-data"),
                    vpnProtocolHint = ProtocolHint.HYSTERIA2,
                ),
            )

        val outboundTags = config["outbounds"]!!.jsonArray.map { it.jsonObject["tag"]!!.jsonPrimitive.content }
        assertFalse(outboundTags.contains("tor-over-vpn"))
        assertEquals("proxy", config["route"]!!.jsonObject["final"]!!.jsonPrimitive.content)
    }

    @Test
    fun `default settings expose loopback proxy for runtime owned refresh only`() {
        val config = parse(assembler.assemble(baseConfigWithRules("profile.example"), Settings(), null))
        val inbounds = config["inbounds"]!!.jsonArray.map { it.jsonObject }

        assertEquals(2, inbounds.size)
        assertEquals(FOXHOLE_RUNTIME_LOG_LEVEL, config["log"]!!.jsonObject["level"]!!.jsonPrimitive.content)
        assertFalse(inbounds[0].containsKey("sniff"))
        assertEquals("tun", inbounds[0]["type"]!!.jsonPrimitive.content)
        assertEquals("mixed", inbounds[1]["type"]!!.jsonPrimitive.content)
        assertEquals("foxhole-runtime-proxy-in", inbounds[1]["tag"]!!.jsonPrimitive.content)
        assertEquals("127.0.0.1", inbounds[1]["listen"]!!.jsonPrimitive.content)
        assertFalse(inbounds[1].containsKey("users"))
        assertSniffRule(config["route"]!!.jsonObject["rules"]!!.jsonArray[0].jsonObject)
        assertFalse(config.containsKey("experimental"))
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
    fun `include split tunnel writes selected packages to tun inbound`() {
        val settings =
            Settings(
                expert =
                    ExpertSettings(
                        perAppRoutingMode = PerAppRoutingMode.INCLUDE_SELECTED_APPS,
                        selectedPackages = listOf("com.example.app"),
                    ),
            )

        val config = parse(assembler.assemble(baseConfigWithRules("profile.example"), settings, null))
        val tunInbound = config["inbounds"]!!.jsonArray.first().jsonObject
        val rules = config["route"]!!.jsonObject["rules"]!!.jsonArray

        assertEquals(2, config["inbounds"]!!.jsonArray.size)
        assertEquals("com.example.app", tunInbound["include_package"]!!.jsonArray[0].jsonPrimitive.content)
        assertFalse(tunInbound.containsKey("exclude_package"))
        assertTrue(rules.none { rule -> rule.jsonObject.containsKey("package_name") })
        assertEquals("proxy", config["route"]!!.jsonObject["final"]!!.jsonPrimitive.content)
    }

    @Test
    fun `local firewall guard does not depend on proxy DNS detour`() {
        val settings =
            Settings(
                expert =
                    ExpertSettings(
                        blockedPackagesEnabled = true,
                        blockedPackages = listOf("org.mozilla.firefox"),
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
        assertEquals("dns-direct", dns["final"]!!.jsonPrimitive.content)
        assertEquals("dns-direct", route["default_domain_resolver"]!!.jsonPrimitive.content)
        assertEquals("block", route["final"]!!.jsonPrimitive.content)
        assertEquals(false, tunInbound["strict_route"]!!.jsonPrimitive.content.toBoolean())
        assertFalse(dnsServers.any { server -> server["tag"]!!.jsonPrimitive.content == "dns-remote" })
        assertFalse(dnsServers.any { server -> server["detour"]?.jsonPrimitive?.content == "proxy" })
        assertEquals("org.mozilla.firefox", tunInbound["include_package"]!!.jsonArray.single().jsonPrimitive.content)
    }

    @Test
    fun `local firewall guard blocks explicitly selected apps only`() {
        val settings =
            Settings(
                expert =
                    ExpertSettings(
                        killSwitchEnabled = true,
                        blockedPackagesEnabled = true,
                        blockedPackages = listOf("org.mozilla.firefox"),
                        blockAppsAlways = true,
                    ),
            )

        val config = parse(assembler.assembleLocalGuard(settings, LocalGuardMode.FIREWALL))
        val tunInbound = config["inbounds"]!!.jsonArray.single().jsonObject
        val route = config["route"]!!.jsonObject

        assertEquals(
            listOf("org.mozilla.firefox"),
            tunInbound["include_package"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertFalse(tunInbound.containsKey("exclude_package"))
        assertEquals(false, tunInbound["strict_route"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("block", route["final"]!!.jsonPrimitive.content)
    }

    @Test
    fun `kill switch preference does not create a local guard mode`() {
        assertEquals(
            null,
            Settings(expert = ExpertSettings(killSwitchEnabled = true)).localGuardModeOrNull(),
        )
        assertEquals(
            LocalGuardMode.JOURNAL,
            Settings(
                expert =
                    ExpertSettings(
                        killSwitchEnabled = true,
                        firewallEnabled = true,
                    ),
            ).localGuardModeOrNull(),
        )
        assertEquals(
            LocalGuardMode.FIREWALL,
            Settings(
                expert =
                    ExpertSettings(
                        killSwitchEnabled = true,
                        firewallEnabled = true,
                        blockedPackagesEnabled = true,
                        blockedPackages = listOf("org.mozilla.firefox"),
                        blockAppsAlways = true,
                    ),
            ).localGuardModeOrNull(),
        )
    }

    @Test
    fun `firewall toggle owns local guard mode without leaking blocked app state`() {
        val blockedApps =
            ExpertSettings(
                blockedPackagesEnabled = true,
                blockedPackages = listOf("org.mozilla.firefox"),
                blockAppsAlways = true,
            )

        assertEquals(
            null,
            Settings(expert = blockedApps).localGuardModeOrNull(),
        )
        assertEquals(
            LocalGuardMode.JOURNAL,
            Settings(
                expert =
                    blockedApps.copy(
                        firewallEnabled = true,
                        blockAppsAlways = false,
                    ),
            ).localGuardModeOrNull(),
        )
        assertEquals(
            LocalGuardMode.FIREWALL,
            Settings(
                expert =
                    blockedApps.copy(
                        firewallEnabled = true,
                    ),
            ).localGuardModeOrNull(),
        )
        assertEquals(
            null,
            Settings(
                expert =
                    blockedApps.copy(
                        firewallEnabled = false,
                    ),
            ).localGuardModeOrNull(),
        )
    }

    @Test
    fun `local journal guard keeps DNS direct while app block rules stay active`() {
        val settings =
            Settings(
                expert =
                    ExpertSettings(
                        networkActivityLogging = true,
                        networkActivityPersistentLogging = true,
                        blockedPackagesEnabled = true,
                        blockedPackages = listOf("org.mozilla.firefox"),
                        blockAppsAlways = true,
                    ),
            )

        val config = parse(assembler.assembleLocalGuard(settings, LocalGuardMode.JOURNAL))
        val dns = config["dns"]!!.jsonObject
        val route = config["route"]!!.jsonObject
        val rules = route["rules"]!!.jsonArray.map { it.jsonObject }
        val dnsServers = dns["servers"]!!.jsonArray.map { it.jsonObject }
        val tunInbound = config["inbounds"]!!.jsonArray.single().jsonObject

        assertEquals("dns-direct", dns["final"]!!.jsonPrimitive.content)
        assertEquals("dns-direct", route["default_domain_resolver"]!!.jsonPrimitive.content)
        assertEquals("direct", route["final"]!!.jsonPrimitive.content)
        assertEquals(false, tunInbound["strict_route"]!!.jsonPrimitive.content.toBoolean())
        assertFalse(dnsServers.any { server -> server["tag"]!!.jsonPrimitive.content == "dns-remote" })
        assertFalse(dnsServers.any { server -> server["detour"]?.jsonPrimitive?.content == "proxy" })
        assertFalse(tunInbound.containsKey("include_package"))
        assertTrue(rules.any { rule -> rule["package_name"]?.jsonArray?.single()?.jsonPrimitive?.content == "org.mozilla.firefox" })
        assertTrue(rules.any { rule -> rule["action"]?.jsonPrimitive?.content == "hijack-dns" })
    }

    @Test
    fun `wireguard endpoint mtu caps android tun mtu`() {
        val base = parse(baseConfigWithRules("profile.example"))
        val config =
            parse(
                assembler.assemble(
                    buildJsonObject {
                        base.forEach { (key, value) -> put(key, value) }
                        put(
                            "endpoints",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("type", "wireguard")
                                        put("tag", "wireguard-direct")
                                        put("mtu", 1280)
                                    },
                                )
                            },
                        )
                    }.toString(),
                    Settings(),
                    null,
                ),
            )

        val tunInbound = config["inbounds"]!!.jsonArray.first().jsonObject
        assertEquals(1280, tunInbound["mtu"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `wireguard endpoint mtu does not raise lower configured tun mtu`() {
        val base = parse(baseConfigWithRules("profile.example"))
        val config =
            parse(
                assembler.assemble(
                    buildJsonObject {
                        base.forEach { (key, value) -> put(key, value) }
                        put(
                            "endpoints",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("type", "wireguard")
                                        put("tag", "wireguard-direct")
                                        put("mtu", 1420)
                                    },
                                )
                            },
                        )
                    }.toString(),
                    Settings(traffic = com.foxhole.beta.core.model.TrafficSettings(mtu = 1280)),
                    null,
                ),
            )

        val tunInbound = config["inbounds"]!!.jsonArray.first().jsonObject
        assertEquals(1280, tunInbound["mtu"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `wireguard endpoint mtu is ignored for non wireguard endpoints`() {
        val base = parse(baseConfigWithRules("profile.example"))
        val config =
            parse(
                assembler.assemble(
                    buildJsonObject {
                        base.forEach { (key, value) -> put(key, value) }
                        put(
                            "endpoints",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("type", "other")
                                        put("tag", "other-direct")
                                        put("mtu", 1280)
                                    },
                                )
                            },
                        )
                    }.toString(),
                    Settings(),
                    null,
                ),
            )

        val tunInbound = config["inbounds"]!!.jsonArray.first().jsonObject
        assertEquals(1500, tunInbound["mtu"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `wireguard endpoint mtu chooses the lowest endpoint mtu`() {
        val base = parse(baseConfigWithRules("profile.example"))
        val config =
            parse(
                assembler.assemble(
                    buildJsonObject {
                        base.forEach { (key, value) -> put(key, value) }
                        put(
                            "endpoints",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("type", "wireguard")
                                        put("tag", "wireguard-direct-a")
                                        put("mtu", 1420)
                                    },
                                )
                                add(
                                    buildJsonObject {
                                        put("type", "wireguard")
                                        put("tag", "wireguard-direct-b")
                                        put("mtu", 1280)
                                    },
                                )
                            },
                        )
                    }.toString(),
                    Settings(),
                    null,
                ),
            )

        val tunInbound = config["inbounds"]!!.jsonArray.first().jsonObject
        assertEquals(1280, tunInbound["mtu"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `wireguard endpoint mtu ignores unparsable values`() {
        val base = parse(baseConfigWithRules("profile.example"))
        val config =
            parse(
                assembler.assemble(
                    buildJsonObject {
                        base.forEach { (key, value) -> put(key, value) }
                        put(
                            "endpoints",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("type", "wireguard")
                                        put("tag", "wireguard-direct")
                                        put("mtu", "bad")
                                    },
                                )
                            },
                        )
                    }.toString(),
                    Settings(),
                    null,
                ),
            )

        val tunInbound = config["inbounds"]!!.jsonArray.first().jsonObject
        assertEquals(1500, tunInbound["mtu"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `wireguard endpoint mtu accepts numeric string values`() {
        val base = parse(baseConfigWithRules("profile.example"))
        val config =
            parse(
                assembler.assemble(
                    buildJsonObject {
                        base.forEach { (key, value) -> put(key, value) }
                        put(
                            "endpoints",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("type", "wireguard")
                                        put("tag", "wireguard-direct")
                                        put("mtu", "1280")
                                    },
                                )
                            },
                        )
                    }.toString(),
                    Settings(),
                    null,
                ),
            )

        val tunInbound = config["inbounds"]!!.jsonArray.first().jsonObject
        assertEquals(1280, tunInbound["mtu"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `wireguard endpoint mtu keeps configured tun mtu when missing`() {
        val base = parse(baseConfigWithRules("profile.example"))
        val config =
            parse(
                assembler.assemble(
                    buildJsonObject {
                        base.forEach { (key, value) -> put(key, value) }
                        put(
                            "endpoints",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("type", "wireguard")
                                        put("tag", "wireguard-direct")
                                    },
                                )
                            },
                        )
                    }.toString(),
                    Settings(),
                    null,
                ),
            )

        val tunInbound = config["inbounds"]!!.jsonArray.first().jsonObject
        assertEquals(1500, tunInbound["mtu"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `wireguard endpoint mtu handles uppercase type`() {
        val base = parse(baseConfigWithRules("profile.example"))
        val config =
            parse(
                assembler.assemble(
                    buildJsonObject {
                        base.forEach { (key, value) -> put(key, value) }
                        put(
                            "endpoints",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("type", "WireGuard")
                                        put("tag", "wireguard-direct")
                                        put("mtu", 1280)
                                    },
                                )
                            },
                        )
                    }.toString(),
                    Settings(),
                    null,
                ),
            )

        val tunInbound = config["inbounds"]!!.jsonArray.first().jsonObject
        assertEquals(1280, tunInbound["mtu"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `wireguard endpoint mtu keeps configured tun mtu without endpoints`() {
        val config = parse(assembler.assemble(baseConfigWithRules("profile.example"), Settings(), null))

        val tunInbound = config["inbounds"]!!.jsonArray.first().jsonObject
        assertEquals(1500, tunInbound["mtu"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `wireguard selected uses local resolver while preserving imported wireguard dns`() {
        val config =
            parse(
                assembler.assemble(
                    baseConfigWithWireGuardDns(selectedDefault = "wireguard-direct"),
                    Settings(),
                    null,
                ),
            )
        val dns = config["dns"]!!.jsonObject
        val wireGuardDns =
            dns["servers"]!!
                .jsonArray
                .first { server -> server.jsonObject["tag"]!!.jsonPrimitive.content == "dns-wireguard" }
                .jsonObject

        assertEquals("dns-direct", dns["final"]!!.jsonPrimitive.content)
        assertEquals("udp", wireGuardDns["type"]!!.jsonPrimitive.content)
        assertEquals("1.1.1.1", wireGuardDns["server"]!!.jsonPrimitive.content)
        assertEquals("53", wireGuardDns["server_port"]!!.jsonPrimitive.content)
        assertEquals("proxy", wireGuardDns["detour"]!!.jsonPrimitive.content)
    }

    @Test
    fun `non wireguard selected keeps proxied doh final with imported wireguard dns present`() {
        val config =
            parse(
                assembler.assemble(
                    baseConfigWithWireGuardDns(selectedDefault = "vless-direct"),
                    Settings(),
                    null,
                ),
            )
        val dns = config["dns"]!!.jsonObject
        val servers = dns["servers"]!!.jsonArray.map { it.jsonObject["tag"]!!.jsonPrimitive.content }

        assertEquals("dns-remote", dns["final"]!!.jsonPrimitive.content)
        assertTrue(servers.contains("dns-wireguard"))
    }


    @Test
    fun `tunnel mode preserves platform http proxy settings`() {
        val config = parse(assembler.assemble(baseConfigWithPlatformHttpProxy(), Settings(), null))
        val tunInbound = config["inbounds"]!!.jsonArray.first().jsonObject
        val httpProxy = tunInbound["platform"]!!.jsonObject["http_proxy"]!!.jsonObject

        assertEquals("true", httpProxy["enabled"]!!.jsonPrimitive.content)
        assertEquals("127.0.0.1", httpProxy["server"]!!.jsonPrimitive.content)
        assertEquals("10809", httpProxy["server_port"]!!.jsonPrimitive.content)
        assertFalse(httpProxy["server_port"]!!.jsonPrimitive.isString)
    }

    @Test
    fun `local proxy surfaces require auth and clash api uses shared secret`() {
        val settings =
            Settings(
                traffic = com.foxhole.beta.core.model.TrafficSettings(mode = TrafficMode.PROXY),
                expert =
                    ExpertSettings(
                        localSurfaces =
                            LocalSurfaceSettings(
                                proxyMode = ProxySurfaceMode.SOCKS5,
                                socks = ProxyInboundSettings(enabled = true, host = "127.0.0.1", port = 10808),
                                clashApi = ClashApiSettings(enabled = true, host = "127.0.0.1", port = 9090),
                                auth =
                                    LocalAuthSettings(
                                        username = "foxhole-user",
                                        password = "foxhole-pass",
                                        apiSecret = "foxhole-secret",
                                    ),
                            ),
                    ),
            )

        val config = parse(assembler.assemble(baseConfigWithRules("profile.example"), settings, null))
        val socksInbound = config["inbounds"]!!.jsonArray[0].jsonObject
        val socksUser = socksInbound["users"]!!.jsonArray.first().jsonObject
        val clashApi = config["experimental"]!!.jsonObject["clash_api"]!!.jsonObject

        assertEquals("foxhole-user", socksUser["username"]!!.jsonPrimitive.content)
        assertEquals("foxhole-pass", socksUser["password"]!!.jsonPrimitive.content)
        assertEquals("127.0.0.1:9090", clashApi["external_controller"]!!.jsonPrimitive.content)
        assertEquals("foxhole-secret", clashApi["secret"]!!.jsonPrimitive.content)
    }

    @Test
    fun `proxy mode omits tun inbound and keeps local proxy surfaces only`() {
        val settings =
            Settings(
                traffic = com.foxhole.beta.core.model.TrafficSettings(mode = TrafficMode.PROXY),
                expert =
                    ExpertSettings(
                        localSurfaces =
                            LocalSurfaceSettings(
                                proxyMode = ProxySurfaceMode.HTTP,
                                http = ProxyInboundSettings(enabled = true, host = "127.0.0.1", port = 10809),
                                auth =
                                    LocalAuthSettings(
                                        username = "foxhole-user",
                                        password = "foxhole-pass",
                                        apiSecret = "foxhole-secret",
                                    ),
                            ),
                    ),
            )

        val config = parse(assembler.assemble(baseConfigWithRules("profile.example"), settings, null))
        val inbounds = config["inbounds"]!!.jsonArray

        assertEquals(1, inbounds.size)
        assertEquals("http", inbounds.first().jsonObject["type"]!!.jsonPrimitive.content)
        assertFalse(inbounds.first().jsonObject.containsKey("stack"))
    }

    @Test
    fun `proxy mode does not inject hijack dns rule`() {
        val settings =
            Settings(
                traffic = com.foxhole.beta.core.model.TrafficSettings(mode = TrafficMode.PROXY),
                expert =
                    ExpertSettings(
                        localSurfaces =
                            LocalSurfaceSettings(
                                proxyMode = ProxySurfaceMode.HTTP,
                                http = ProxyInboundSettings(enabled = true, host = "127.0.0.1", port = 10809),
                            ),
                    ),
            )

        val config = parse(assembler.assemble(baseConfigWithRules("profile.example"), settings, null))
        val rules = config["route"]!!.jsonObject["rules"]!!.jsonArray

        assertTrue(rules.none { it.jsonObject["action"]?.jsonPrimitive?.content == "hijack-dns" })
    }

    @Test
    fun `proxy auth toggle off omits inbound users`() {
        val settings =
            Settings(
                traffic = com.foxhole.beta.core.model.TrafficSettings(mode = TrafficMode.PROXY),
                expert =
                    ExpertSettings(
                        localSurfaces =
                            LocalSurfaceSettings(
                                proxyMode = ProxySurfaceMode.HTTP,
                                http = ProxyInboundSettings(enabled = true, host = "127.0.0.1", port = 10809),
                                auth =
                                    LocalAuthSettings(
                                        enabled = false,
                                        username = "foxhole-user",
                                        password = "foxhole-pass",
                                        apiSecret = "foxhole-secret",
                                    ),
                            ),
                    ),
            )

        val config = parse(assembler.assemble(baseConfigWithRules("profile.example"), settings, null))
        val httpInbound = config["inbounds"]!!.jsonArray.first().jsonObject

        assertFalse(httpInbound.containsKey("users"))
    }

    @Test
    fun `proxy mode binds lan proxy to wifi only when lan access is enabled`() {
        val lanAddressProvider = FakeLanProxyAddressProvider(address = "192.168.1.23")
        val lanAwareAssembler = RuntimeConfigAssembler(json, lanAddressProvider)
        val settings =
            Settings(
                traffic = com.foxhole.beta.core.model.TrafficSettings(mode = TrafficMode.PROXY),
                expert =
                    ExpertSettings(
                        localSurfaces =
                            LocalSurfaceSettings(
                                proxyMode = ProxySurfaceMode.HTTP,
                                allowLanAccess = true,
                                lanProxyMode = ProxySurfaceMode.HTTP,
                                http = ProxyInboundSettings(enabled = true, host = "127.0.0.1", port = 10809),
                            ),
                    ),
            )

        val config = parse(lanAwareAssembler.assemble(baseConfigWithRules("profile.example"), settings, null))
        val inbounds = config["inbounds"]!!.jsonArray
        val listens = inbounds.map { inbound -> inbound.jsonObject["listen"]!!.jsonPrimitive.content }

        assertEquals(2, inbounds.size)
        assertTrue("192.168.1.23" in listens)
    }

    @Test
    fun `lan proxy uses independent auth credentials`() {
        val lanAddressProvider = FakeLanProxyAddressProvider(address = "192.168.1.23")
        val lanAwareAssembler = RuntimeConfigAssembler(json, lanAddressProvider)
        val settings =
            Settings(
                traffic = com.foxhole.beta.core.model.TrafficSettings(mode = TrafficMode.PROXY),
                expert =
                    ExpertSettings(
                        localSurfaces =
                            LocalSurfaceSettings(
                                proxyMode = ProxySurfaceMode.HTTP,
                                lanProxyMode = ProxySurfaceMode.HTTP,
                                allowLanAccess = true,
                                http = ProxyInboundSettings(enabled = true, host = "127.0.0.1", port = 10809),
                                auth =
                                    LocalAuthSettings(
                                        username = "local-user",
                                        password = "local-pass",
                                    ),
                                lanAuth =
                                    LocalAuthSettings(
                                        username = "lan-user",
                                        password = "lan-pass",
                                    ),
                            ),
                    ),
            )

        val config = parse(lanAwareAssembler.assemble(baseConfigWithRules("profile.example"), settings, null))
        val inbounds = config["inbounds"]!!.jsonArray.map { it.jsonObject }.associateBy { it["tag"]!!.jsonPrimitive.content }
        val localUser = inbounds.getValue("http-in")["users"]!!.jsonArray.first().jsonObject
        val lanUser = inbounds.getValue("http-in-lan")["users"]!!.jsonArray.first().jsonObject

        assertEquals("local-user", localUser["username"]!!.jsonPrimitive.content)
        assertEquals("local-pass", localUser["password"]!!.jsonPrimitive.content)
        assertEquals("lan-user", lanUser["username"]!!.jsonPrimitive.content)
        assertEquals("lan-pass", lanUser["password"]!!.jsonPrimitive.content)
    }

    @Test
    fun `lan proxy omits users when lan auth is off`() {
        val lanAddressProvider = FakeLanProxyAddressProvider(address = "192.168.1.23")
        val lanAwareAssembler = RuntimeConfigAssembler(json, lanAddressProvider)
        val settings =
            Settings(
                traffic = com.foxhole.beta.core.model.TrafficSettings(mode = TrafficMode.PROXY),
                expert =
                    ExpertSettings(
                        localSurfaces =
                            LocalSurfaceSettings(
                                proxyMode = ProxySurfaceMode.HTTP,
                                lanProxyMode = ProxySurfaceMode.HTTP,
                                allowLanAccess = true,
                                http = ProxyInboundSettings(enabled = true, host = "127.0.0.1", port = 10809),
                                auth =
                                    LocalAuthSettings(
                                        username = "local-user",
                                        password = "local-pass",
                                    ),
                                lanAuth =
                                    LocalAuthSettings(
                                        enabled = false,
                                        username = "lan-user",
                                        password = "lan-pass",
                                    ),
                            ),
                    ),
            )

        val config = parse(lanAwareAssembler.assemble(baseConfigWithRules("profile.example"), settings, null))
        val inbounds = config["inbounds"]!!.jsonArray.map { it.jsonObject }.associateBy { it["tag"]!!.jsonPrimitive.content }

        assertTrue(inbounds.getValue("http-in").containsKey("users"))
        assertFalse(inbounds.getValue("http-in-lan").containsKey("users"))
    }

    @Test
    fun `all proxy surface mode emits mixed inbound`() {
        val settings =
            Settings(
                traffic = com.foxhole.beta.core.model.TrafficSettings(mode = TrafficMode.PROXY),
                expert =
                    ExpertSettings(
                        localSurfaces =
                            LocalSurfaceSettings(
                                proxyMode = ProxySurfaceMode.ALL,
                                mixed = ProxyInboundSettings(enabled = true, host = "127.0.0.1", port = 10810),
                            ),
                    ),
            )

        val config = parse(assembler.assemble(baseConfigWithRules("profile.example"), settings, null))
        val inbound = config["inbounds"]!!.jsonArray.single().jsonObject

        assertEquals("mixed", inbound["type"]!!.jsonPrimitive.content)
        assertEquals("mixed-in", inbound["tag"]!!.jsonPrimitive.content)
        assertEquals("10810", inbound["listen_port"]!!.jsonPrimitive.content)
        assertFalse(inbound["listen_port"]!!.jsonPrimitive.isString)
    }

    @Test
    fun `runtime fingerprint changes when wifi lan address changes`() {
        val lanAddressProvider = FakeLanProxyAddressProvider(address = "192.168.1.23")
        val lanAwareAssembler = RuntimeConfigAssembler(json, lanAddressProvider)
        val settings =
            Settings(
                expert =
                    ExpertSettings(
                        localSurfaces = LocalSurfaceSettings(allowLanAccess = true),
                    ),
            )

        val firstFingerprint = lanAwareAssembler.runtimeFingerprint(settings, null)
        lanAddressProvider.address = "192.168.1.44"
        val secondFingerprint = lanAwareAssembler.runtimeFingerprint(settings, null)

        assertNotEquals(firstFingerprint, secondFingerprint)
    }

    @Test
    fun `runtime fingerprint ignores non runtime settings fields`() {
        val runtimeSettings =
            Settings(
                traffic = com.foxhole.beta.core.model.TrafficSettings(mode = TrafficMode.PROXY),
                expert =
                    ExpertSettings(
                        localSurfaces =
                            LocalSurfaceSettings(
                                proxyMode = ProxySurfaceMode.HTTP,
                                http = ProxyInboundSettings(enabled = true, host = "127.0.0.1", port = 10809),
                                auth =
                                    LocalAuthSettings(
                                        username = "foxhole-user",
                                        password = "foxhole-pass",
                                        apiSecret = "foxhole-secret",
                                    ),
                            ),
                    ),
            )
        val uiOnlyChanges =
            runtimeSettings.copy(
                ui = runtimeSettings.ui.copy(locale = AppLocale.RU, showExpertSettings = false),
                connection =
                    runtimeSettings.connection.copy(
                        autoReconnect = false,
                        autoStartOnBoot = true,
                        ipInfoEndpoint = "https://ifconfig.co/json",
                    ),
                profileTrafficTotals =
                    listOf(
                        ProfileTrafficTotal(
                            profileId = 7L,
                            profileName = "Gaming",
                            protocolHint = ProtocolHint.VLESS,
                            rxTotalBytes = 1024L,
                            txTotalBytes = 2048L,
                            updatedAt = 12345L,
                        ),
                    ),
                usageTrackingStartedAt = 12345L,
            )

        assertEquals(
            assembler.runtimeFingerprint(runtimeSettings, null),
            assembler.runtimeFingerprint(uiOnlyChanges, null),
        )
    }

    @Test
    fun `runtime fingerprint ignores non runtime expert metadata`() {
        val runtimeSettings =
            Settings(
                expert =
                    ExpertSettings(
                        unlockedAt = 1L,
                        warningAcknowledgedAt = 2L,
                        blockScreenshots = true,
                        networkActivityLogging = false,
                        diagnosticsRetention = DiagnosticsRetention.HOURS_6,
                        sniff = true,
                    ),
            )
        val metadataOnlyChanges =
            runtimeSettings.copy(
                expert =
                    runtimeSettings.expert.copy(
                        unlockedAt = 99L,
                        warningAcknowledgedAt = 100L,
                        blockScreenshots = false,
                        networkActivityLogging = true,
                        diagnosticsRetention = DiagnosticsRetention.DAYS_14,
                    ),
            )

        assertEquals(
            assembler.runtimeFingerprint(runtimeSettings, null),
            assembler.runtimeFingerprint(metadataOnlyChanges, null),
        )
    }

    @Test
    fun `runtime fingerprint ignores selected packages when split tunnel is off`() {
        val runtimeSettings =
            Settings(
                expert =
                    ExpertSettings(
                        perAppRoutingMode = PerAppRoutingMode.FULL_TUNNEL,
                        selectedPackages = emptyList(),
                    ),
            )
        val selectedAppsPrepared =
            runtimeSettings.copy(
                expert =
                    runtimeSettings.expert.copy(
                        selectedPackages = listOf("com.example.browser"),
                    ),
            )

        assertEquals(
            assembler.runtimeFingerprint(runtimeSettings, null),
            assembler.runtimeFingerprint(selectedAppsPrepared, null),
        )
    }

    @Test
    fun `runtime fingerprint changes for selected packages when split tunnel is on`() {
        val runtimeSettings =
            Settings(
                expert =
                    ExpertSettings(
                        perAppRoutingMode = PerAppRoutingMode.INCLUDE_SELECTED_APPS,
                        selectedPackages = listOf("com.example.browser"),
                    ),
            )
        val selectedAppsChanged =
            runtimeSettings.copy(
                expert =
                    runtimeSettings.expert.copy(
                        selectedPackages = listOf("com.example.browser", "com.example.chat"),
                    ),
            )

        assertNotEquals(
            assembler.runtimeFingerprint(runtimeSettings, null),
            assembler.runtimeFingerprint(selectedAppsChanged, null),
        )
    }

    @Test
    fun `runtime fingerprint ignores blocked packages when blocking is off`() {
        val runtimeSettings =
            Settings(
                expert =
                    ExpertSettings(
                        blockedPackages = emptyList(),
                        blockedPackagesEnabled = false,
                    ),
            )
        val blockedAppsPrepared =
            runtimeSettings.copy(
                expert =
                    runtimeSettings.expert.copy(
                        blockedPackages = listOf("com.example.chat"),
                        blockedPackagesEnabled = false,
                    ),
            )

        assertEquals(
            assembler.runtimeFingerprint(runtimeSettings, null),
            assembler.runtimeFingerprint(blockedAppsPrepared, null),
        )
    }

    @Test
    fun `runtime fingerprint changes when proxy auth changes`() {
        val runtimeSettings =
            Settings(
                traffic = com.foxhole.beta.core.model.TrafficSettings(mode = TrafficMode.PROXY),
                expert =
                    ExpertSettings(
                        localSurfaces =
                            LocalSurfaceSettings(
                                proxyMode = ProxySurfaceMode.HTTP,
                                http = ProxyInboundSettings(enabled = true, host = "127.0.0.1", port = 10809),
                                auth =
                                    LocalAuthSettings(
                                        username = "foxhole-user",
                                        password = "foxhole-pass",
                                        apiSecret = "foxhole-secret",
                                    ),
                            ),
                    ),
            )
        val changedAuth =
            runtimeSettings.copy(
                expert =
                    runtimeSettings.expert.copy(
                        localSurfaces =
                            runtimeSettings.expert.localSurfaces.copy(
                                auth =
                                    runtimeSettings.expert.localSurfaces.auth.copy(
                                        username = "foxhole-user-2",
                                        password = "foxhole-pass-2",
                                    ),
                            ),
                    ),
            )

        assertNotEquals(
            assembler.runtimeFingerprint(runtimeSettings, null),
            assembler.runtimeFingerprint(changedAuth, null),
        )
    }

    @Test
    fun `exclude split tunnel writes selected packages to tun inbound`() {
        val settings =
            Settings(
                expert =
                    ExpertSettings(
                        perAppRoutingMode = PerAppRoutingMode.EXCLUDE_SELECTED_APPS,
                        selectedPackages = listOf("com.bank.app"),
                    ),
            )

        val config = parse(assembler.assemble(baseConfigWithRules("profile.example"), settings, null))
        val tunInbound = config["inbounds"]!!.jsonArray.first().jsonObject
        val rules = config["route"]!!.jsonObject["rules"]!!.jsonArray

        assertFalse(tunInbound.containsKey("include_package"))
        assertEquals("com.bank.app", tunInbound["exclude_package"]!!.jsonArray[0].jsonPrimitive.content)
        assertTrue(rules.none { rule -> rule.jsonObject.containsKey("package_name") })
    }

    @Test
    fun `blocked package rules are ordered before selected app rules and honor block toggle`() {
        val settings =
            Settings(
                expert =
                    ExpertSettings(
                        perAppRoutingMode = PerAppRoutingMode.INCLUDE_SELECTED_APPS,
                        selectedPackages = listOf("com.example.selected"),
                        blockedPackages = listOf("com.example.blocked"),
                        blockedPackagesEnabled = true,
                    ),
            )

        val config = parse(assembler.assemble(baseConfigWithRules("profile.example"), settings, null))
        val tunInbound = config["inbounds"]!!.jsonArray.first().jsonObject
        val rules = config["route"]!!.jsonObject["rules"]!!.jsonArray
        val blockRule = rules[0].jsonObject

        assertEquals(
            listOf("com.example.blocked", "com.example.selected"),
            tunInbound["include_package"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals("com.example.blocked", blockRule["package_name"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("block", blockRule["outbound"]!!.jsonPrimitive.content)

        val disabled =
            parse(
                assembler.assemble(
                    baseConfigWithRules("profile.example"),
                    settings.copy(expert = settings.expert.copy(blockedPackagesEnabled = false)),
                    null,
                ),
            )
        val disabledRules = disabled["route"]!!.jsonObject["rules"]!!.jsonArray
        assertTrue(disabledRules.none { rule -> rule.jsonObject["outbound"]?.jsonPrimitive?.content == "block" })
    }

    @Test
    fun `site rules map chips to sing-box fields and selected managed sites follow global mode`() {
        val preset =
            RoutingPreset(
                id = 11,
                name = "sites",
                source = RoutingPresetSource.LOCAL,
                overrideMode = RoutingPresetOverrideMode.RESPECT_PROFILE,
                enabled = true,
                updatedAt = 1,
                rules =
                    listOf(
                        RoutingRule(
                            id = 1,
                            presetId = 11,
                            name = "Foxhole selected site: example.com",
                            enabled = true,
                            order = 0,
                            action = RoutingRuleAction.PROXY,
                            matchDomains = listOf("example.com", "*.example.org", "kw:video", "re:^stun\\..+"),
                            matchIpCidrs = listOf("1.2.3.0/24"),
                            matchPorts = emptyList(),
                            matchProtocols = emptyList(),
                            matchNetworks = emptyList(),
                        ),
                        RoutingRule(
                            id = 2,
                            presetId = 11,
                            name = "Foxhole blocked site: kw:ads",
                            enabled = true,
                            order = 1,
                            action = RoutingRuleAction.BLOCK,
                            matchDomains = listOf("kw:ads"),
                            matchIpCidrs = emptyList(),
                            matchPorts = emptyList(),
                            matchProtocols = emptyList(),
                            matchNetworks = emptyList(),
                        ),
                        RoutingRule(
                            id = 3,
                            presetId = 11,
                            name = "custom site",
                            enabled = true,
                            order = 2,
                            action = RoutingRuleAction.PROXY,
                            matchDomains = listOf("custom.example"),
                            matchIpCidrs = emptyList(),
                            matchPorts = emptyList(),
                            matchProtocols = emptyList(),
                            matchNetworks = emptyList(),
                        ),
                    ),
            )
        val settings =
            Settings(
                expert = ExpertSettings(siteRoutingAction = RoutingRuleAction.DIRECT),
            )

        val config = parse(assembler.assemble(baseConfigWithRules("profile.example"), settings, preset))
        val rules = config["route"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }
        val selectedRule = rules.first { it.stringArray("domain").contains("example.com") }
        val blockedRule = rules.first { it.stringArray("domain_keyword").contains("ads") }
        val customRule = rules.first { it.stringArray("domain").contains("custom.example") }

        assertEquals(listOf("example.com"), selectedRule.stringArray("domain"))
        assertEquals(listOf("example.org"), selectedRule.stringArray("domain_suffix"))
        assertEquals(listOf("video"), selectedRule.stringArray("domain_keyword"))
        assertEquals(listOf("^stun\\..+"), selectedRule.stringArray("domain_regex"))
        assertEquals(listOf("1.2.3.0/24"), selectedRule.stringArray("ip_cidr"))
        assertEquals("direct", selectedRule["outbound"]!!.jsonPrimitive.content)
        assertEquals("block", blockedRule["outbound"]!!.jsonPrimitive.content)
        assertEquals("proxy", customRule["outbound"]!!.jsonPrimitive.content)
    }

    @Test
    fun `foxhole dns defaults use local bootstrap and proxied plain final resolver`() {
        val config = parse(assembler.assemble(baseConfigWithLegacyFoxholeDns(), Settings(), null))
        val dns = config["dns"]!!.jsonObject
        val route = config["route"]!!.jsonObject
        val servers = dns["servers"]!!.jsonArray

        assertEquals("dns-remote", dns["final"]!!.jsonPrimitive.content)
        assertEquals("dns-local", servers[0].jsonObject["tag"]!!.jsonPrimitive.content)
        assertEquals("local", servers[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertFalse(servers[0].jsonObject.containsKey("detour"))
        assertEquals("dns-direct", servers[1].jsonObject["tag"]!!.jsonPrimitive.content)
        assertEquals("local", servers[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertFalse(servers[1].jsonObject.containsKey("server"))
        assertFalse(servers[1].jsonObject.containsKey("server_port"))
        assertFalse(servers[1].jsonObject.containsKey("path"))
        assertFalse(servers[1].jsonObject.containsKey("detour"))
        assertEquals("dns-remote", servers[2].jsonObject["tag"]!!.jsonPrimitive.content)
        assertEquals("udp", servers[2].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("1.1.1.1", servers[2].jsonObject["server"]!!.jsonPrimitive.content)
        assertEquals("53", servers[2].jsonObject["server_port"]!!.jsonPrimitive.content)
        assertFalse(servers[2].jsonObject.containsKey("path"))
        assertEquals("proxy", servers[2].jsonObject["detour"]!!.jsonPrimitive.content)
        assertEquals("true", route["auto_detect_interface"]!!.jsonPrimitive.content)
    }

    @Test
    fun `foxhole dns keeps proxied plain final resolver when private dns is off`() {
        val config =
            parse(
                assembler.assemble(
                    baseConfigWithLegacyFoxholeDns(),
                    Settings(),
                    null,
                    privateDnsMode = PrivateDnsMode.OFF,
                ),
            )
        val dns = config["dns"]!!.jsonObject
        val servers = dns["servers"]!!.jsonArray

        assertEquals("dns-remote", dns["final"]!!.jsonPrimitive.content)
        assertEquals("dns-remote", servers[2].jsonObject["tag"]!!.jsonPrimitive.content)
        assertEquals("udp", servers[2].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("1.1.1.1", servers[2].jsonObject["server"]!!.jsonPrimitive.content)
        assertEquals("53", servers[2].jsonObject["server_port"]!!.jsonPrimitive.content)
        assertFalse(servers[2].jsonObject.containsKey("path"))
        assertEquals("proxy", servers[2].jsonObject["detour"]!!.jsonPrimitive.content)
    }

    @Test
    fun `foxhole dns keeps local bootstrap resolver when private dns is strict`() {
        val config =
            parse(
                assembler.assemble(
                    baseConfigWithLegacyFoxholeDns(),
                    Settings(),
                    null,
                    privateDnsMode = PrivateDnsMode.STRICT,
                ),
            )
        val dns = config["dns"]!!.jsonObject
        val route = config["route"]!!.jsonObject
        val servers = dns["servers"]!!.jsonArray

        assertEquals("dns-remote", dns["final"]!!.jsonPrimitive.content)
        assertEquals("dns-direct", route["default_domain_resolver"]!!.jsonPrimitive.content)
        assertEquals("dns-direct", servers[1].jsonObject["tag"]!!.jsonPrimitive.content)
        assertEquals("local", servers[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertFalse(servers[1].jsonObject.containsKey("server"))
        assertFalse(servers[1].jsonObject.containsKey("server_port"))
        assertFalse(servers[1].jsonObject.containsKey("path"))
        assertFalse(servers[1].jsonObject.containsKey("detour"))
        assertEquals("dns-remote", servers[2].jsonObject["tag"]!!.jsonPrimitive.content)
        assertEquals("udp", servers[2].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("proxy", servers[2].jsonObject["detour"]!!.jsonPrimitive.content)
    }

    @Test
    fun `legacy udp bootstrap dns is rewritten to local bootstrap`() {
        val config = parse(assembler.assemble(baseConfigWithLegacyUdpBootstrapDns(), Settings(), null))
        val servers = config["dns"]!!.jsonObject["servers"]!!.jsonArray

        assertEquals("dns-direct", servers[1].jsonObject["tag"]!!.jsonPrimitive.content)
        assertEquals("local", servers[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertFalse(servers[1].jsonObject.containsKey("server"))
        assertFalse(servers[1].jsonObject.containsKey("server_port"))
        assertFalse(servers[1].jsonObject.containsKey("path"))
        assertFalse(servers[1].jsonObject.containsKey("detour"))
        assertEquals("dns-remote", servers[2].jsonObject["tag"]!!.jsonPrimitive.content)
        assertEquals("proxy", servers[2].jsonObject["detour"]!!.jsonPrimitive.content)
    }

    @Test
    fun `dns leak controls update tunnel strict routing and DNS hijack rules`() {
        val settings =
            Settings(
                expert = ExpertSettings(strictRoute = false),
                dns =
                    DnsSettings(
                        blockOutsideTunnel = false,
                        interceptDnsRequests = false,
                    ),
            )

        val config = parse(assembler.assemble(baseConfigWithRules("profile.example"), settings, null))
        val tunInbound = config["inbounds"]!!.jsonArray.first().jsonObject
        val rules = config["route"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }

        assertEquals(false, tunInbound["strict_route"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(rules.none { rule -> rule["action"]?.jsonPrimitive?.content == "hijack-dns" })
        assertTrue(rules.any { rule -> rule.stringArray("domain").contains("profile.example") })

        val forced =
            parse(
                assembler.assemble(
                    baseConfigWithRules("profile.example"),
                    settings.copy(dns = settings.dns.copy(blockOutsideTunnel = true)),
                    null,
                ),
            )
        assertEquals(true, forced["inbounds"]!!.jsonArray.first().jsonObject["strict_route"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `dns through vpn and secure mode select remote resolver fields`() {
        val settings =
            Settings(
                dns =
                    DnsSettings(
                        dnsThroughVpn = false,
                        server = "dns.example",
                        secureMode = SecureDnsMode.DOT,
                    ),
            )

        val config = parse(assembler.assemble(baseConfigWithRules("profile.example"), settings, null))
        val remote = config["dns"]!!.jsonObject["servers"]!!.jsonArray[2].jsonObject

        assertEquals("dns-remote", remote["tag"]!!.jsonPrimitive.content)
        assertEquals("tls", remote["type"]!!.jsonPrimitive.content)
        assertEquals("dns.example", remote["server"]!!.jsonPrimitive.content)
        assertEquals("853", remote["server_port"]!!.jsonPrimitive.content)
        assertEquals("dns-direct", remote["domain_resolver"]!!.jsonPrimitive.content)
        assertFalse(remote.containsKey("path"))
        assertFalse(remote.containsKey("detour"))
    }

    @Test
    fun `plain dns mode emits udp resolver without doh path`() {
        val settings =
            Settings(
                dns =
                    DnsSettings(
                        server = "9.9.9.9",
                        secureMode = SecureDnsMode.PLAIN,
                    ),
            )

        val config = parse(assembler.assemble(baseConfigWithRules("profile.example"), settings, null))
        val remote = config["dns"]!!.jsonObject["servers"]!!.jsonArray[2].jsonObject

        assertEquals("udp", remote["type"]!!.jsonPrimitive.content)
        assertEquals("9.9.9.9", remote["server"]!!.jsonPrimitive.content)
        assertEquals("53", remote["server_port"]!!.jsonPrimitive.content)
        assertFalse(remote.containsKey("path"))
        assertFalse(remote.containsKey("domain_resolver"))
        assertEquals("proxy", remote["detour"]!!.jsonPrimitive.content)
    }

    @Test
    fun `dns filtering bypass rules use remote resolver and disappear when filtering is disabled`() {
        val settings =
            Settings(
                dns =
                    DnsSettings(
                        filteringEnabled = true,
                        appBypassPackages = listOf("com.example.bank"),
                        domainBypassRules = listOf("login.example", "push.example"),
                    ),
            )

        val config = parse(assembler.assemble(baseConfigWithRules("profile.example"), settings, null))
        val dnsRules = config["dns"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }

        assertEquals(2, dnsRules.size)
        assertEquals(listOf("com.example.bank"), dnsRules[0].stringArray("package_name"))
        assertEquals("dns-remote", dnsRules[0]["server"]!!.jsonPrimitive.content)
        assertEquals(listOf("login.example", "push.example"), dnsRules[1].stringArray("domain_suffix"))
        assertEquals("dns-remote", dnsRules[1]["server"]!!.jsonPrimitive.content)

        val disabled =
            parse(
                assembler.assemble(
                    baseConfigWithRules("profile.example"),
                    settings.copy(dns = settings.dns.copy(filteringEnabled = false)),
                    null,
                ),
        )
        assertFalse(disabled["dns"]!!.jsonObject.containsKey("rules"))
    }

    @Test
    fun `dns filtering attaches bundled adguard rule set when prepared`() {
        val filterPath = "/data/user/0/com.foxhole.beta/files/dns-rule-sets/adguard-dns-filter.srs"
        val config =
            parse(
                assembler.assemble(
                    baseConfigWithRules("profile.example"),
                    Settings(dns = DnsSettings(filteringEnabled = true)),
                    null,
                    dnsFilterRuntimePaths =
                        DnsFilterRuntimePaths(
                            adGuardDnsFilterPath = filterPath,
                            adGuardVpnCompatibilityDomains = listOf("adguard-vpn.com"),
                        ),
                ),
            )
        val dnsRules = config["dns"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }
        val adGuardRule = dnsRules.single { rule -> rule.stringArray("rule_set").contains("foxhole-adguard-dns-filter") }
        val compatibilityRule = dnsRules.single { rule -> rule.stringArray("domain_suffix").contains("adguard-vpn.com") }
        val ruleSet = config["route"]!!.jsonObject["rule_set"]!!.jsonArray.single().jsonObject

        assertTrue(dnsRules.indexOf(compatibilityRule) < dnsRules.indexOf(adGuardRule))
        assertEquals("dns-remote", compatibilityRule["server"]!!.jsonPrimitive.content)
        assertEquals("predefined", adGuardRule["action"]!!.jsonPrimitive.content)
        assertEquals("NXDOMAIN", adGuardRule["rcode"]!!.jsonPrimitive.content)
        assertEquals("local", ruleSet["type"]!!.jsonPrimitive.content)
        assertEquals("foxhole-adguard-dns-filter", ruleSet["tag"]!!.jsonPrimitive.content)
        assertEquals("binary", ruleSet["format"]!!.jsonPrimitive.content)
        assertEquals(filterPath, ruleSet["path"]!!.jsonPrimitive.content)
    }

    @Test
    fun `route defaults inject hijack dns and direct bootstrap resolver`() {
        val config = parse(assembler.assemble(baseConfigWithRules("profile.example"), Settings(), null))
        val route = config["route"]!!.jsonObject
        val rules = route["rules"]!!.jsonArray

        assertSniffRule(rules[0].jsonObject)
        assertPortDnsHijack(rules[1].jsonObject)
        assertProtocolDnsHijack(rules[2].jsonObject)
        assertEquals("dns-direct", route["default_domain_resolver"]!!.jsonPrimitive.content)
        assertEquals("true", route["auto_detect_interface"]!!.jsonPrimitive.content)
    }

    @Test
    fun `foxhole managed routes keep final and bootstrap resolver`() {
        val config = parse(assembler.assemble(baseConfigWithFoxholeManagedRoute(), Settings(), null))
        val route = config["route"]!!.jsonObject
        val rules = route["rules"]!!.jsonArray

        assertSniffRule(rules[0].jsonObject)
        assertPortDnsHijack(rules[1].jsonObject)
        assertProtocolDnsHijack(rules[2].jsonObject)
        assertEquals("proxy", route["final"]!!.jsonPrimitive.content)
        assertEquals("dns-direct", route["default_domain_resolver"]!!.jsonPrimitive.content)
        assertEquals("true", route["auto_detect_interface"]!!.jsonPrimitive.content)
    }

    @Test
    fun `preset route ports are emitted as numeric ports and sing-box port ranges`() {
        val preset =
            RoutingPreset(
                id = 7,
                name = "ports",
                source = RoutingPresetSource.LOCAL,
                overrideMode = RoutingPresetOverrideMode.RESPECT_PROFILE,
                enabled = true,
                updatedAt = 1,
                rules =
                    listOf(
                        RoutingRule(
                            id = 9,
                            presetId = 7,
                            name = "ports",
                            enabled = true,
                            order = 0,
                            action = RoutingRuleAction.PROXY,
                            matchDomains = emptyList(),
                            matchIpCidrs = emptyList(),
                            matchPorts = listOf("53", "443-445"),
                            matchProtocols = emptyList(),
                            matchNetworks = emptyList(),
                        ),
                    ),
            )

        val config = parse(assembler.assemble(baseConfigWithRules("profile.example"), Settings(), preset))
        val rule = config["route"]!!.jsonObject["rules"]!!.jsonArray[4].jsonObject

        assertEquals("53", rule["port"]!!.jsonPrimitive.content)
        assertFalse(rule["port"]!!.jsonPrimitive.isString)
        assertEquals("443-445", rule["port_range"]!!.jsonPrimitive.content)
    }

    @Test
    fun `sniff and route only are emitted only when enabled`() {
        val settings =
            Settings(
                expert =
                    ExpertSettings(
                        sniff = true,
                        routeOnly = true,
                    ),
            )

        val config = parse(assembler.assemble(baseConfigWithRules("profile.example"), settings, null))
        val tunInbound = config["inbounds"]!!.jsonArray.first().jsonObject
        val rules = config["route"]!!.jsonObject["rules"]!!.jsonArray

        assertFalse(tunInbound.containsKey("sniff"))
        assertFalse(tunInbound.containsKey("sniff_override_destination"))
        assertEquals("sniff", rules[0].jsonObject["action"]!!.jsonPrimitive.content)
        assertPortDnsHijack(rules[1].jsonObject)
        assertProtocolDnsHijack(rules[2].jsonObject)
    }

    @Test
    fun `custom dns servers are preserved`() {
        val config = parse(assembler.assemble(baseConfigWithCustomDns(), Settings(), null))
        val dns = config["dns"]!!.jsonObject
        val route = config["route"]!!.jsonObject

        assertEquals("https://dns.example/dns-query", dns["servers"]!!.jsonArray[0].jsonObject["address"]!!.jsonPrimitive.content)
        assertEquals("prefer_ipv4", dns["strategy"]!!.jsonPrimitive.content)
        assertEquals("dns-custom", route["default_domain_resolver"]!!.jsonPrimitive.content)
        assertEquals("true", route["auto_detect_interface"]!!.jsonPrimitive.content)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects conflicting local ports`() {
        assembler.assemble(
            baseConfigJson = baseConfigWithRules("profile.example"),
            settings =
                Settings(
                    expert =
                        ExpertSettings(
                            localSurfaces =
                                LocalSurfaceSettings(
                                    socks = ProxyInboundSettings(enabled = true, port = 10808),
                                    clashApi = ClashApiSettings(enabled = true, port = 10808),
                                ),
                        ),
                ),
            activePreset = null,
        )
    }

    private fun preset(mode: RoutingPresetOverrideMode): RoutingPreset =
        RoutingPreset(
            id = 1,
            name = "local",
            source = RoutingPresetSource.LOCAL,
            overrideMode = mode,
            enabled = true,
            updatedAt = 1,
            rules =
                listOf(
                    RoutingRule(
                        id = 1,
                        presetId = 1,
                        name = "local",
                        enabled = true,
                        order = 0,
                        action = RoutingRuleAction.PROXY,
                        matchDomains = listOf("local.example"),
                        matchIpCidrs = emptyList(),
                        matchPorts = emptyList(),
                        matchProtocols = emptyList(),
                        matchNetworks = emptyList(),
                    ),
                ),
        )

    private fun baseConfigWithRules(domain: String): String =
        buildJsonObject {
            put("inbounds", buildJsonArray {
                add(
                    buildJsonObject {
                        put("type", "tun")
                        put("tag", "tun-in")
                        put("interface_name", "foxhole")
                        put("mtu", 1500)
                        put("auto_route", true)
                        put("strict_route", true)
                        put("sniff", false)
                        put("stack", "system")
                        put("address", buildJsonArray {
                            add(JsonPrimitive("172.19.0.1/30"))
                            add(JsonPrimitive("fdfe:dcba:9876::1/126"))
                        })
                    },
                )
            })
            put("outbounds", buildJsonArray {
                add(buildJsonObject { put("type", "selector"); put("tag", "proxy") })
                add(buildJsonObject { put("type", "direct"); put("tag", "direct") })
                add(buildJsonObject { put("type", "block"); put("tag", "block") })
            })
            put("dns", buildJsonObject { put("strategy", "prefer_ipv4") })
            put("route", buildJsonObject {
                put("rules", buildJsonArray {
                    add(
                        buildJsonObject {
                            put("domain", buildJsonArray { add(JsonPrimitive(domain)) })
                            put("action", "route")
                            put("outbound", "direct")
                        },
                    )
                })
                put("final", "proxy")
            })
        }.toString()

    private fun baseConfigWithPlatformHttpProxy(): String {
        val base = parse(baseConfigWithRules("profile.example"))
        val sourceTun = base["inbounds"]!!.jsonArray.first().jsonObject
        val patchedTun =
            buildJsonObject {
                sourceTun.forEach { (key, value) -> put(key, value) }
                put(
                    "platform",
                    buildJsonObject {
                        put(
                            "http_proxy",
                            buildJsonObject {
                                put("enabled", true)
                                put("server", "127.0.0.1")
                                put("server_port", 10809)
                            },
                        )
                    },
                )
            }
        return buildJsonObject {
            base.forEach { (key, value) ->
                if (key == "inbounds") {
                    put("inbounds", buildJsonArray { add(patchedTun) })
                } else {
                    put(key, value)
                }
            }
        }.toString()
    }

    private fun baseConfigWithWireGuardDns(selectedDefault: String): String {
        val base = parse(baseConfigWithRules("profile.example"))
        return buildJsonObject {
            put("inbounds", base["inbounds"]!!)
            put(
                "endpoints",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("type", "wireguard")
                            put("tag", "wireguard-direct")
                            put("mtu", 1280)
                        },
                    )
                },
            )
            put(
                "outbounds",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("type", "vless")
                            put("tag", "vless-direct")
                            put("server", "edge.example")
                            put("server_port", 443)
                            put("uuid", "11111111-1111-1111-1111-111111111111")
                        },
                    )
                    add(
                        buildJsonObject {
                            put("type", "selector")
                            put("tag", "proxy")
                            put("default", selectedDefault)
                            put(
                                "outbounds",
                                buildJsonArray {
                                    add(JsonPrimitive("vless-direct"))
                                    add(JsonPrimitive("wireguard-direct"))
                                },
                            )
                        },
                    )
                    add(buildJsonObject { put("type", "direct"); put("tag", "direct") })
                    add(buildJsonObject { put("type", "block"); put("tag", "block") })
                },
            )
            put(
                "dns",
                buildJsonObject {
                    put(
                        "servers",
                        buildJsonArray {
                            add(buildJsonObject { put("tag", "dns-local"); put("type", "local") })
                            add(buildJsonObject { put("tag", "dns-direct"); put("type", "local") })
                            add(
                                buildJsonObject {
                                    put("tag", "dns-remote")
                                    put("type", "https")
                                    put("server", "1.1.1.1")
                                    put("server_port", 443)
                                    put("path", "/dns-query")
                                    put("detour", "proxy")
                                },
                            )
                            add(
                                buildJsonObject {
                                    put("tag", "dns-wireguard")
                                    put("type", "udp")
                                    put("server", "1.1.1.1")
                                    put("server_port", 53)
                                    put("detour", "proxy")
                                },
                            )
                        },
                    )
                    put("strategy", "prefer_ipv4")
                    put("final", "dns-remote")
                },
            )
            put("route", base["route"]!!)
        }.toString()
    }

    private fun baseConfigWithOutbounds(outbounds: JsonArray): String =
        buildJsonObject {
            val base = parse(baseConfigWithRules("profile.example"))
            put("inbounds", base["inbounds"]!!)
            put("outbounds", outbounds)
            put("dns", base["dns"]!!)
            put("route", base["route"]!!)
        }.toString()

    private fun baseConfigWithLegacyFoxholeDns(): String =
        buildJsonObject {
            put("inbounds", parse(baseConfigWithRules("profile.example"))["inbounds"]!!)
            put("outbounds", parse(baseConfigWithRules("profile.example"))["outbounds"]!!)
            put(
                "dns",
                buildJsonObject {
                    put(
                        "servers",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("tag", "dns-remote")
                                    put("address", "https://1.1.1.1/dns-query")
                                    put("detour", "proxy")
                                },
                            )
                            add(
                                buildJsonObject {
                                    put("tag", "dns-local")
                                    put("address", "local")
                                },
                            )
                        },
                    )
                    put("strategy", "prefer_ipv4")
                },
            )
            put("route", buildJsonObject { put("final", "proxy"); put("default_domain_resolver", "dns-remote") })
        }.toString()

    private fun baseConfigWithLegacyUdpBootstrapDns(): String =
        buildJsonObject {
            put("inbounds", parse(baseConfigWithRules("profile.example"))["inbounds"]!!)
            put("outbounds", parse(baseConfigWithRules("profile.example"))["outbounds"]!!)
            put(
                "dns",
                buildJsonObject {
                    put(
                        "servers",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("tag", "dns-local")
                                    put("type", "local")
                                },
                            )
                            add(
                                buildJsonObject {
                                    put("tag", "dns-direct")
                                    put("type", "udp")
                                    put("server", "1.1.1.1")
                                    put("server_port", 53)
                                },
                            )
                            add(
                                buildJsonObject {
                                    put("tag", "dns-remote")
                                    put("type", "https")
                                    put("server", "1.1.1.1")
                                    put("server_port", 443)
                                    put("path", "/dns-query")
                                    put("detour", "proxy")
                                },
                            )
                        },
                    )
                    put("strategy", "prefer_ipv4")
                    put("final", "dns-remote")
                },
            )
            put("route", buildJsonObject { put("final", "proxy"); put("default_domain_resolver", "dns-direct") })
        }.toString()

    private fun baseConfigWithCustomDns(): String =
        buildJsonObject {
            put("inbounds", parse(baseConfigWithRules("profile.example"))["inbounds"]!!)
            put("outbounds", parse(baseConfigWithRules("profile.example"))["outbounds"]!!)
            put(
                "dns",
                buildJsonObject {
                    put(
                        "servers",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("tag", "dns-custom")
                                    put("address", "https://dns.example/dns-query")
                                    put("detour", "proxy")
                                },
                            )
                        },
                    )
                    put("strategy", "prefer_ipv4")
                },
            )
            put("route", parse(baseConfigWithRules("profile.example"))["route"]!!)
        }.toString()

    private fun baseConfigWithFoxholeManagedRoute(): String =
        buildJsonObject {
            put("inbounds", parse(baseConfigWithRules("profile.example"))["inbounds"]!!)
            put("outbounds", parse(baseConfigWithRules("profile.example"))["outbounds"]!!)
            put("dns", parse(baseConfigWithRules("profile.example"))["dns"]!!)
            put(
                "route",
                buildJsonObject {
                    put(
                        "rules",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("protocol", "dns")
                                    put("port", 53)
                                    put("action", "hijack-dns")
                                },
                            )
                        },
                    )
                    put("final", "proxy")
                    put("default_domain_resolver", "dns-direct")
                    put("auto_detect_interface", true)
                },
            )
        }.toString()

    private fun parse(raw: String) = json.parseToJsonElement(raw).jsonObject

    private fun assertPortDnsHijack(rule: JsonObject) {
        assertEquals("hijack-dns", rule["action"]!!.jsonPrimitive.content)
        assertEquals("53", rule["port"]!!.jsonPrimitive.content)
        assertFalse(rule["port"]!!.jsonPrimitive.isString)
        assertFalse(rule.containsKey("protocol"))
    }

    private fun assertSniffRule(rule: JsonObject) {
        assertEquals("sniff", rule["action"]!!.jsonPrimitive.content)
    }

    private fun assertProtocolDnsHijack(rule: JsonObject) {
        assertEquals("hijack-dns", rule["action"]!!.jsonPrimitive.content)
        assertEquals("dns", rule["protocol"]!!.jsonPrimitive.content)
        assertFalse(rule.containsKey("port"))
    }

    private fun JsonObject.stringArray(key: String): List<String> =
        this[key]
            ?.jsonArray
            ?.map { value -> value.jsonPrimitive.content }
            .orEmpty()
}
