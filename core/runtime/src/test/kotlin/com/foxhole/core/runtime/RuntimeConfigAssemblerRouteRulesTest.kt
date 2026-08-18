package com.foxhole.core.runtime

import com.foxhole.core.model.AppTunnelLane
import com.foxhole.core.model.ExpertSettings
import com.foxhole.core.model.PerAppRoutingMode
import com.foxhole.core.model.PrivacyRouteMode
import com.foxhole.core.model.PrivacyRouteScope
import com.foxhole.core.model.PrivacyRouteUdpPolicy
import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.RoutingPresetOverrideMode
import com.foxhole.core.model.Settings
import com.foxhole.core.model.TrafficSettings
import com.foxhole.core.model.TunStack
import com.foxhole.core.model.VpnSession
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.io.path.createTempDirectory

internal class RuntimeConfigAssemblerRouteRulesTest : RuntimeConfigAssemblerTestSupport() {
    @Test
    fun `protocol test freezes device traffic while keeping app probes in vpn`() {
        val appPackage = "com.foxhole.guard.test"
        val testAssembler = RuntimeConfigAssembler(json, selfPackageName = appPackage)
        val config =
            parse(
                testAssembler.assemble(
                    baseConfigJson = baseConfigWithRules("profile.example"),
                    settings = Settings(),
                    activePreset = null,
                    protocolTestTrafficFreeze = true,
                ),
            )

        val route = config["route"]!!.jsonObject
        assertEquals("block", route["final"]!!.jsonPrimitive.content)
        val appRule = route["rules"]!!.jsonArray.first().jsonObject
        assertEquals(appPackage, appRule["package_name"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals("proxy", appRule["outbound"]!!.jsonPrimitive.content)
    }

    @Test
    fun `protocol test freeze remains translatable with the selected VPN outbound`() {
        val assembled =
            parse(
                RuntimeConfigAssembler(json, selfPackageName = "com.foxhole.guard.test").assemble(
                    baseConfigJson = translatableVlessConfig(),
                    settings = Settings(),
                    activePreset = null,
                    protocolTestTrafficFreeze = true,
                ),
            )

        FoxCoreConfigTranslator().translate(
            session = VpnSession(
                profileId = 1L,
                profileName = "protocol test fixture",
                protocolHint = ProtocolHint.VLESS,
                configJson = assembled.toString(),
                correlationId = "protocol-test-freeze",
            ),
        )
    }

    private fun translatableVlessConfig(): String =
        baseConfigWithOutbounds(
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("type", "vless")
                        put("tag", "node")
                        put("server", "203.0.113.10")
                        put("server_port", 443)
                        put("uuid", "11111111-1111-1111-1111-111111111111")
                    },
                )
                add(
                    buildJsonObject {
                        put("type", "selector")
                        put("tag", "proxy")
                        put("default", "node")
                        put("outbounds", buildJsonArray { add(JsonPrimitive("node")) })
                    },
                )
                add(
                    buildJsonObject {
                        put("type", "direct")
                        put("tag", "direct")
                    },
                )
                add(
                    buildJsonObject {
                        put("type", "block")
                        put("tag", "block")
                    },
                )
            },
        )

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
        assertEquals(4, rules.size)
        assertRuntimeProxyRoute(rules[0].jsonObject)
        assertSniffRule(rules[1].jsonObject)
        assertEquals("profile.example", rules[2].jsonObject["domain"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("local.example", rules[3].jsonObject["domain"]!!.jsonArray[0].jsonPrimitive.content)
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
        assertEquals(3, rules.size)
        assertRuntimeProxyRoute(rules[0].jsonObject)
        assertSniffRule(rules[1].jsonObject)
        assertEquals("local.example", rules[2].jsonObject["domain"]!!.jsonArray[0].jsonPrimitive.content)
    }

    @Test
    fun `system tun stack is coerced to gvisor for tcp-stable tunnel protocols`() {
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
    fun `system tun stack is coerced to gvisor for wireguard tunnel protocols`() {
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

        assertEquals("gvisor", tun["stack"]!!.jsonPrimitive.content)
    }

    @Test
    fun `explicit gvisor tun stack is preserved for wireguard tunnel protocols`() {
        val config =
            parse(
                assembler.assemble(
                    baseConfigJson = baseConfigWithRules("profile.example"),
                    settings = Settings(traffic = TrafficSettings(tunStack = TunStack.GVISOR)),
                    activePreset = null,
                    vpnProtocolHint = ProtocolHint.WIREGUARD,
                ),
            )

        val tun = config["inbounds"]!!.jsonArray.first().jsonObject

        assertEquals("gvisor", tun["stack"]!!.jsonPrimitive.content)
    }

    @Test
    fun `system tun stack is coerced to gvisor for local firewall guard`() {
        val config =
            parse(
                assembler.assembleLocalGuard(
                    settings = Settings(traffic = TrafficSettings(tunStack = TunStack.SYSTEM)),
                    mode = LocalGuardMode.FIREWALL,
                ),
            )

        val tun = config["inbounds"]!!.jsonArray.first().jsonObject

        assertEquals("gvisor", tun["stack"]!!.jsonPrimitive.content)
    }

    @Test
    fun `tor privacy route routes tcp through tor and udp through proxy by default`() {
        val config =
            parse(
                assembler.assemble(
                    baseConfigJson = baseConfigWithRules("profile.example"),
                    settings =
                    Settings(
                        privacyRoute =
                        com.foxhole.core.model.PrivacyRouteSettings(
                            mode = PrivacyRouteMode.TOR_OVER_VPN,
                            scope = PrivacyRouteScope.ALL_APPS,
                        ),
                    ),
                    activePreset = null,
                    torRuntimePaths =
                    TorRuntimePaths(
                        dataDirectory = "/data/user/0/com.foxhole.guard/files/tor-data/arm64-v8a",
                    ),
                    vpnProtocolHint = ProtocolHint.VLESS,
                ),
            )

        val outbounds = config["outbounds"]!!.jsonArray.map { it.jsonObject }
        val tor = outbounds.single { it["tag"]!!.jsonPrimitive.content == "tor-over-vpn" }
        assertEquals("tor", tor["type"]!!.jsonPrimitive.content)
        assertEquals("proxy", tor["detour"]!!.jsonPrimitive.content)
        // Over the VPN tunnel Arti uses the already-established stream proxy and therefore does
        // not start bridge transports on a second, ambiguous network path.
        assertTrue(tor["extra_args"] == null)
        assertEquals(
            "/data/user/0/com.foxhole.guard/files/tor-data/arm64-v8a",
            tor["data_directory"]!!.jsonPrimitive.content
        )
        assertFalse(tor.containsKey("executable_path"))
        assertFalse(tor.containsKey("torrc"))

        val dnsRemote = config["dns"]!!.jsonObject["servers"]!!.jsonArray.map { it.jsonObject }
            .single { it["tag"]!!.jsonPrimitive.content == "dns-remote" }
        assertEquals("tor-over-vpn", dnsRemote["detour"]!!.jsonPrimitive.content)
        assertEquals("https", dnsRemote["type"]!!.jsonPrimitive.content)
        assertEquals(443, dnsRemote["server_port"]!!.jsonPrimitive.int)
        assertEquals("/dns-query", dnsRemote["path"]!!.jsonPrimitive.content)

        val route = config["route"]!!.jsonObject
        assertEquals("tor-over-vpn", route["final"]!!.jsonPrimitive.content)
        val udpBlock = route["rules"]!!.jsonArray.map { it.jsonObject }
            .single { it["network"]?.jsonPrimitive?.content == "udp" }
        assertEquals("proxy", udpBlock["outbound"]!!.jsonPrimitive.content)
    }

    @Test
    fun `tor privacy route blocks udp only when udp policy is block`() {
        val config =
            parse(
                assembler.assemble(
                    baseConfigJson = baseConfigWithRules("profile.example"),
                    settings =
                    Settings(
                        privacyRoute =
                        com.foxhole.core.model.PrivacyRouteSettings(
                            mode = PrivacyRouteMode.TOR_OVER_VPN,
                            scope = PrivacyRouteScope.ALL_APPS,
                            udpPolicy = PrivacyRouteUdpPolicy.BLOCK,
                        ),
                    ),
                    activePreset = null,
                    torRuntimePaths = TorRuntimePaths(
                        dataDirectory = "/tor-data",
                    ),
                    vpnProtocolHint = ProtocolHint.VLESS,
                ),
            )

        val udpBlocks = config["route"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }
            .filter {
                it["network"]?.jsonPrimitive?.content == "udp" &&
                    it["outbound"]?.jsonPrimitive?.content == "block"
            }

        assertEquals(listOf("1-52", "54-65535"), udpBlocks.map { it["port_range"]!!.jsonPrimitive.content })
        assertTrue(
            "Tor's UDP block must leave port 53 to the managed DNS interceptor",
            udpBlocks.none { it["port"]?.jsonPrimitive?.content == "53" },
        )
    }

    @Test
    fun `tor privacy route can use rotated identity data directory`() {
        val root = createTempDirectory("foxhole-tor-identity").toFile()
        try {
            val paths =
                TorRuntimePaths(
                    dataDirectory = root.absolutePath,
                ).withIdentityVersion(42L)

            val config =
                parse(
                    assembler.assemble(
                        baseConfigJson = baseConfigWithRules("profile.example"),
                        settings =
                        Settings(
                            privacyRoute =
                            com.foxhole.core.model.PrivacyRouteSettings(
                                mode = PrivacyRouteMode.TOR_OVER_VPN,
                                scope = PrivacyRouteScope.ALL_APPS,
                            ),
                        ),
                        activePreset = null,
                        torRuntimePaths = paths,
                        vpnProtocolHint = ProtocolHint.VLESS,
                    ),
                )

            val tor =
                config["outbounds"]!!
                    .jsonArray
                    .map { outbound -> outbound.jsonObject }
                    .single { outbound -> outbound["tag"]!!.jsonPrimitive.content == "tor-over-vpn" }

            assertEquals("${root.absolutePath}/identity-42", paths.dataDirectory)
            assertTrue(root.resolve("identity-42").isDirectory)
            assertEquals(paths.dataDirectory, tor["data_directory"]!!.jsonPrimitive.content)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `tor privacy route selected apps routes selected tcp through tor and udp through proxy`() {
        val config =
            parse(
                assembler.assemble(
                    baseConfigJson = baseConfigWithRules("profile.example"),
                    settings =
                    Settings(
                        privacyRoute =
                        com.foxhole.core.model.PrivacyRouteSettings(
                            mode = PrivacyRouteMode.TOR_OVER_VPN,
                            scope = PrivacyRouteScope.SELECTED_APPS,
                        ),
                        expert =
                        ExpertSettings(
                            appAssignments = mapOf("org.mozilla.firefox" to AppTunnelLane.TOR),
                        ),
                    ),
                    activePreset = null,
                    torRuntimePaths = TorRuntimePaths(
                        dataDirectory = "/tor-data",
                    ),
                    vpnProtocolHint = ProtocolHint.VLESS,
                ),
            )

        val route = config["route"]!!.jsonObject
        val tunInbound = config["inbounds"]!!.jsonArray.first().jsonObject
        assertEquals("proxy", route["final"]!!.jsonPrimitive.content)
        assertFalse(tunInbound.containsKey("exclude_package"))
        assertFalse(tunInbound.containsKey("include_package"))
        val packageRules = route["rules"]!!.jsonArray.map { it.jsonObject }
            .filter { it["package_name"] != null && it["network"] != null }
        assertEquals(2, packageRules.size)
        assertTrue(
            packageRules.any {
                it["network"]!!.jsonPrimitive.content == "tcp" &&
                    it["outbound"]!!.jsonPrimitive.content == "tor-over-vpn"
            },
        )
        assertTrue(
            packageRules.any {
                it["network"]!!.jsonPrimitive.content == "udp" &&
                    it["outbound"]!!.jsonPrimitive.content == "proxy"
            },
        )
        // Selected-apps Tor: the app's own loopback-proxy probes must observe the plain VPN
        // egress (dashboard VPN identity), never ride Tor like the chosen packages do.
        val runtimeProxyRule = route["rules"]!!.jsonArray.map { it.jsonObject }
            .single { rule ->
                rule["inbound"]?.jsonArray?.any { it.jsonPrimitive.content == "foxhole-runtime-proxy-in" } == true
            }
        assertEquals("proxy", runtimeProxyRule["outbound"]!!.jsonPrimitive.content)
        assertEquals("tcp", runtimeProxyRule["network"]!!.jsonPrimitive.content)
    }

    @Test
    fun `tor over vpn selected apps keeps full-device tun capture with blocked packages`() {
        val config =
            parse(
                assembler.assemble(
                    baseConfigJson = baseConfigWithRules("profile.example"),
                    settings =
                    Settings(
                        privacyRoute =
                        com.foxhole.core.model.PrivacyRouteSettings(
                            mode = PrivacyRouteMode.TOR_OVER_VPN,
                            scope = PrivacyRouteScope.SELECTED_APPS,
                        ),
                        expert =
                        ExpertSettings(
                            blockedPackagesEnabled = true,
                            appAssignments =
                            mapOf(
                                "org.mozilla.firefox" to AppTunnelLane.TOR,
                                "com.bank.app" to AppTunnelLane.BLOCK,
                            ),
                        ),
                    ),
                    activePreset = null,
                    torRuntimePaths = TorRuntimePaths(
                        dataDirectory = "/tor-data",
                    ),
                    vpnProtocolHint = ProtocolHint.VLESS,
                ),
            )

        val route = config["route"]!!.jsonObject
        val tunInbound = config["inbounds"]!!.jsonArray.first().jsonObject
        val packageRules =
            route["rules"]!!
                .jsonArray
                .map { it.jsonObject }
                .filter { it["package_name"] != null }

        assertFalse(tunInbound.containsKey("include_package"))
        assertFalse(tunInbound.containsKey("exclude_package"))
        assertTrue(
            packageRules.any { rule ->
                rule["package_name"]!!.jsonArray.single().jsonPrimitive.content == "org.mozilla.firefox" &&
                    rule["network"]!!.jsonPrimitive.content == "tcp" &&
                    rule["outbound"]!!.jsonPrimitive.content == "tor-over-vpn"
            },
        )
        assertTrue(
            packageRules.any { rule ->
                rule["package_name"]!!.jsonArray.single().jsonPrimitive.content == "com.bank.app" &&
                    rule["outbound"]!!.jsonPrimitive.content == "block"
            },
        )
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
                        com.foxhole.core.model.PrivacyRouteSettings(
                            mode = PrivacyRouteMode.TOR_OVER_VPN,
                            scope = PrivacyRouteScope.SELECTED_APPS,
                        ),
                    ),
                    activePreset = null,
                    torRuntimePaths = TorRuntimePaths(
                        dataDirectory = "/tor-data",
                    ),
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
                        com.foxhole.core.model.PrivacyRouteSettings(
                            mode = PrivacyRouteMode.TOR_OVER_VPN,
                            scope = PrivacyRouteScope.ALL_APPS,
                        ),
                    ),
                    activePreset = null,
                    torRuntimePaths = TorRuntimePaths(
                        dataDirectory = "/tor-data",
                    ),
                    vpnProtocolHint = ProtocolHint.HYSTERIA2,
                ),
            )

        val outboundTags = config["outbounds"]!!.jsonArray.map { it.jsonObject["tag"]!!.jsonPrimitive.content }
        assertFalse(outboundTags.contains("tor-over-vpn"))
        assertEquals("proxy", config["route"]!!.jsonObject["final"]!!.jsonPrimitive.content)
    }

    @Test
    fun `tor privacy route can bypass vpn tunnel for udp vpn protocols`() {
        val config =
            parse(
                assembler.assemble(
                    baseConfigJson = baseConfigWithRules("profile.example"),
                    settings =
                    Settings(
                        privacyRoute =
                        com.foxhole.core.model.PrivacyRouteSettings(
                            mode = PrivacyRouteMode.TOR_OVER_VPN,
                            scope = PrivacyRouteScope.ALL_APPS,
                            bypassVpnTunnel = true,
                        ),
                    ),
                    activePreset = null,
                    torRuntimePaths = TorRuntimePaths(
                        dataDirectory = "/tor-data",
                    ),
                    vpnProtocolHint = ProtocolHint.HYSTERIA2,
                ),
            )

        val tor = config["outbounds"]!!.jsonArray.map { it.jsonObject }
            .single { it["tag"]!!.jsonPrimitive.content == "tor-over-vpn" }

        assertEquals("tor", tor["type"]!!.jsonPrimitive.content)
        assertFalse(tor.containsKey("detour"))
        assertEquals("tor-over-vpn", config["route"]!!.jsonObject["final"]!!.jsonPrimitive.content)
    }

    @Test
    fun `tor only session denies the vpn lane it cannot carry`() {
        val config =
            parse(
                assembler.assembleTorOnly(
                    settings =
                    Settings(
                        expert =
                        ExpertSettings(
                            appAssignments =
                            mapOf(
                                "com.app.tor" to AppTunnelLane.TOR,
                                "com.app.vpn" to AppTunnelLane.VPN,
                            ),
                        ),
                        privacyRoute =
                        com.foxhole.core.model.PrivacyRouteSettings(
                            mode = PrivacyRouteMode.TOR_OVER_VPN,
                            scope = PrivacyRouteScope.SELECTED_APPS,
                            blockAppsWhenTorUnavailable = true,
                        ),
                    ),
                    activePreset = null,
                    torRuntimePaths = TorRuntimePaths(dataDirectory = "/tor-data"),
                ),
            )

        val tunInbound = config["inbounds"]!!.jsonArray.first().jsonObject
        assertEquals(
            listOf("com.app.tor", "com.app.vpn"),
            tunInbound["include_package"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        val rejectRule =
            config["route"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }
                .single { it["action"]?.jsonPrimitive?.content == "reject" }
        assertEquals(
            listOf("com.app.vpn"),
            rejectRule["package_name"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `tor only session leaves the vpn lane alone while the guard is disarmed`() {
        val config =
            parse(
                assembler.assembleTorOnly(
                    settings =
                    Settings(
                        expert =
                        ExpertSettings(
                            appAssignments =
                            mapOf(
                                "com.app.tor" to AppTunnelLane.TOR,
                                "com.app.vpn" to AppTunnelLane.VPN,
                            ),
                        ),
                        privacyRoute =
                        com.foxhole.core.model.PrivacyRouteSettings(
                            mode = PrivacyRouteMode.TOR_OVER_VPN,
                            scope = PrivacyRouteScope.SELECTED_APPS,
                        ),
                    ),
                    activePreset = null,
                    torRuntimePaths = TorRuntimePaths(dataDirectory = "/tor-data"),
                ),
            )

        val tunInbound = config["inbounds"]!!.jsonArray.first().jsonObject
        assertEquals(
            listOf("com.app.tor"),
            tunInbound["include_package"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertTrue(
            config["route"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }
                .none { it["action"]?.jsonPrimitive?.content == "reject" },
        )
    }

    @Test
    fun `tor only config exposes embedded FoxCore tor as proxy outbound without vpn profile`() {
        val config =
            parse(
                assembler.assembleTorOnly(
                    settings =
                    Settings(
                        privacyRoute =
                        com.foxhole.core.model.PrivacyRouteSettings(
                            mode = PrivacyRouteMode.TOR_OVER_VPN,
                            scope = PrivacyRouteScope.ALL_APPS,
                        ),
                    ),
                    activePreset = null,
                    torRuntimePaths = TorRuntimePaths(
                        dataDirectory = "/tor-data",
                    ),
                ),
            )

        val tor = config["outbounds"]!!.jsonArray.map { it.jsonObject }
            .single { it["tag"]!!.jsonPrimitive.content == "proxy" }
        val route = config["route"]!!.jsonObject
        val routeOutbounds =
            route["rules"]!!.jsonArray
                .map { it.jsonObject }
                .mapNotNull { it["outbound"]?.jsonPrimitive?.content }

        assertEquals("tor", tor["type"]!!.jsonPrimitive.content)
        assertEquals("/tor-data", tor["data_directory"]!!.jsonPrimitive.content)
        assertFalse(tor.containsKey("extra_args"))
        assertFalse(tor.containsKey("detour"))
        assertEquals("proxy", route["final"]!!.jsonPrimitive.content)
        assertFalse(routeOutbounds.contains("tor-over-vpn"))
        assertTrue(routeOutbounds.contains("proxy"))
        val tunInbound = config["inbounds"]!!.jsonArray.first().jsonObject
        assertEquals("tun", tunInbound["type"]!!.jsonPrimitive.content)
        assertEquals("gvisor", tunInbound["stack"]!!.jsonPrimitive.content)
        assertEquals(
            listOf(BuildConfig.APPLICATION_ID),
            tunInbound["exclude_package"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertTrue(
            route["rules"]!!.jsonArray.map { it.jsonObject }
                .any { it["outbound"]?.jsonPrimitive?.content == "block" },
        )
    }

    @Test
    fun `tor only config uses embedded FoxCore tor outbound`() {
        val config =
            parse(
                assembler.assembleTorOnly(
                    settings =
                    Settings(
                        privacyRoute =
                        com.foxhole.core.model.PrivacyRouteSettings(
                            mode = PrivacyRouteMode.TOR_OVER_VPN,
                            scope = PrivacyRouteScope.ALL_APPS,
                        ),
                    ),
                    activePreset = null,
                    torRuntimePaths = TorRuntimePaths(
                        dataDirectory = "/tor-data",
                    ),
                ),
            )

        val proxy = config["outbounds"]!!.jsonArray.map { it.jsonObject }
            .single { it["tag"]!!.jsonPrimitive.content == "proxy" }

        assertEquals("tor", proxy["type"]!!.jsonPrimitive.content)
        assertEquals("/tor-data", proxy["data_directory"]!!.jsonPrimitive.content)
        assertFalse(proxy.containsKey("extra_args"))
        assertFalse(proxy.containsKey("unix_path"))
        val tunInbound = config["inbounds"]!!.jsonArray.first().jsonObject
        assertEquals(
            listOf(BuildConfig.APPLICATION_ID),
            tunInbound["exclude_package"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `tor only all apps ignores stale include split tunnel packages`() {
        val config =
            parse(
                assembler.assembleTorOnly(
                    settings =
                    Settings(
                        privacyRoute =
                        com.foxhole.core.model.PrivacyRouteSettings(
                            mode = PrivacyRouteMode.TOR_OVER_VPN,
                            scope = PrivacyRouteScope.ALL_APPS,
                        ),
                        expert =
                        ExpertSettings(
                            perAppRoutingMode = PerAppRoutingMode.INCLUDE_SELECTED_APPS,
                            appAssignments = mapOf("com.example.stale" to AppTunnelLane.VPN),
                        ),
                    ),
                    activePreset = null,
                    torRuntimePaths = TorRuntimePaths(
                        dataDirectory = "/tor-data",
                    ),
                ),
            )

        val tunInbound = config["inbounds"]!!.jsonArray.first().jsonObject

        assertFalse(tunInbound.containsKey("include_package"))
        assertEquals(
            listOf(BuildConfig.APPLICATION_ID),
            tunInbound["exclude_package"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `tor only all apps ignores stale exclude split tunnel packages`() {
        val config =
            parse(
                assembler.assembleTorOnly(
                    settings =
                    Settings(
                        privacyRoute =
                        com.foxhole.core.model.PrivacyRouteSettings(
                            mode = PrivacyRouteMode.TOR_OVER_VPN,
                            scope = PrivacyRouteScope.ALL_APPS,
                        ),
                        expert =
                        ExpertSettings(
                            perAppRoutingMode = PerAppRoutingMode.EXCLUDE_SELECTED_APPS,
                            appAssignments = mapOf("com.bank.app" to AppTunnelLane.VPN),
                        ),
                    ),
                    activePreset = null,
                    torRuntimePaths = TorRuntimePaths(
                        dataDirectory = "/tor-data",
                    ),
                ),
            )

        val tunInbound = config["inbounds"]!!.jsonArray.first().jsonObject

        assertFalse(tunInbound.containsKey("include_package"))
        assertEquals(
            listOf(BuildConfig.APPLICATION_ID),
            tunInbound["exclude_package"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `tor only selected apps uses privacy route packages instead of stale split tunnel packages`() {
        val config =
            parse(
                assembler.assembleTorOnly(
                    settings =
                    Settings(
                        privacyRoute =
                        com.foxhole.core.model.PrivacyRouteSettings(
                            mode = PrivacyRouteMode.TOR_OVER_VPN,
                            scope = PrivacyRouteScope.SELECTED_APPS,
                        ),
                        expert =
                        ExpertSettings(
                            perAppRoutingMode = PerAppRoutingMode.INCLUDE_SELECTED_APPS,
                            appAssignments =
                            mapOf(
                                "org.mozilla.firefox" to AppTunnelLane.TOR,
                                "com.example.stale" to AppTunnelLane.VPN,
                            ),
                        ),
                    ),
                    activePreset = null,
                    torRuntimePaths = TorRuntimePaths(
                        dataDirectory = "/tor-data",
                    ),
                ),
            )

        val tunInbound = config["inbounds"]!!.jsonArray.first().jsonObject

        assertEquals(
            listOf("org.mozilla.firefox"),
            tunInbound["include_package"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertFalse(tunInbound.containsKey("exclude_package"))
    }

    @Test
    fun `default settings expose loopback proxy for runtime owned refresh only`() {
        val config = parse(assembler.assemble(baseConfigWithRules("profile.example"), Settings(), null))
        val inbounds = config["inbounds"]!!.jsonArray.map { it.jsonObject }

        assertEquals(2, inbounds.size)
        assertEquals(FOXHOLE_RUNTIME_LOG_LEVEL, config["log"]!!.jsonObject["level"]!!.jsonPrimitive.content)
        assertFalse(inbounds[0].containsKey("sniff"))
        assertEquals("tun", inbounds[0]["type"]!!.jsonPrimitive.content)
        assertEquals("http", inbounds[1]["type"]!!.jsonPrimitive.content)
        assertEquals("foxhole-runtime-proxy-in", inbounds[1]["tag"]!!.jsonPrimitive.content)
        assertEquals("127.0.0.1", inbounds[1]["listen"]!!.jsonPrimitive.content)
        assertEquals("10809", inbounds[1]["listen_port"]!!.jsonPrimitive.content)
        val runtimeProxyUser = inbounds[1]["users"]!!.jsonArray.single().jsonObject
        assertEquals(runtimeLoopbackProxyAuth().username, runtimeProxyUser["username"]!!.jsonPrimitive.content)
        assertEquals(runtimeLoopbackProxyAuth().password, runtimeProxyUser["password"]!!.jsonPrimitive.content)
        val rules = config["route"]!!.jsonObject["rules"]!!.jsonArray
        assertRuntimeProxyRoute(rules[0].jsonObject)
        assertSniffRule(rules[1].jsonObject)
        assertFalse(config.containsKey("experimental"))
    }
}
