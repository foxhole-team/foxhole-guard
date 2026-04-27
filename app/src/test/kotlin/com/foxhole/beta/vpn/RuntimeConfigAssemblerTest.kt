package com.foxhole.beta.vpn

import com.foxhole.beta.core.model.AppLocale
import com.foxhole.beta.core.model.ClashApiSettings
import com.foxhole.beta.core.model.DiagnosticsRetention
import com.foxhole.beta.core.model.ExpertSettings
import com.foxhole.beta.core.model.LocalAuthSettings
import com.foxhole.beta.core.model.LocalSurfaceSettings
import com.foxhole.beta.core.model.PerAppRoutingMode
import com.foxhole.beta.core.model.ProfileTrafficTotal
import com.foxhole.beta.core.model.ProtocolHint
import com.foxhole.beta.core.model.ProxyInboundSettings
import com.foxhole.beta.core.model.RoutingPreset
import com.foxhole.beta.core.model.RoutingPresetOverrideMode
import com.foxhole.beta.core.model.RoutingPresetSource
import com.foxhole.beta.core.model.RoutingRule
import com.foxhole.beta.core.model.RoutingRuleAction
import com.foxhole.beta.core.model.Settings
import com.foxhole.beta.core.model.TrafficMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
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
        assertEquals(4, rules.size)
        assertPortDnsHijack(rules[0].jsonObject)
        assertProtocolDnsHijack(rules[1].jsonObject)
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
        assertPortDnsHijack(rules[0].jsonObject)
        assertProtocolDnsHijack(rules[1].jsonObject)
        assertEquals("local.example", rules[2].jsonObject["domain"]!!.jsonArray[0].jsonPrimitive.content)
    }

    @Test
    fun `default settings keep local surfaces disabled`() {
        val config = parse(assembler.assemble(baseConfigWithRules("profile.example"), Settings(), null))

        assertEquals(1, config["inbounds"]!!.jsonArray.size)
        assertEquals(FOXHOLE_RUNTIME_LOG_LEVEL, config["log"]!!.jsonObject["level"]!!.jsonPrimitive.content)
        assertFalse(config["inbounds"]!!.jsonArray[0].jsonObject.containsKey("sniff"))
        assertFalse(config.containsKey("experimental"))
    }

    @Test
    fun `expert settings add local surfaces and selected packages`() {
        val settings =
            Settings(
                expert =
                    ExpertSettings(
                        perAppRoutingMode = PerAppRoutingMode.INCLUDE_SELECTED_APPS,
                        selectedPackages = listOf("com.example.app"),
                        localSurfaces =
                            LocalSurfaceSettings(
                                socks = ProxyInboundSettings(enabled = true, host = "127.0.0.1", port = 10808),
                            ),
                    ),
            )

        val config = parse(assembler.assemble(baseConfigWithRules("profile.example"), settings, null))
        val tunInbound = config["inbounds"]!!.jsonArray.first().jsonObject

        assertEquals(2, config["inbounds"]!!.jsonArray.size)
        assertEquals("com.example.app", tunInbound["include_package"]!!.jsonArray[0].jsonPrimitive.content)
    }

    @Test
    fun `local proxy surfaces require auth and clash api uses shared secret`() {
        val settings =
            Settings(
                expert =
                    ExpertSettings(
                        localSurfaces =
                            LocalSurfaceSettings(
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
        val socksInbound = config["inbounds"]!!.jsonArray[1].jsonObject
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
                                allowLanAccess = true,
                                http = ProxyInboundSettings(enabled = true, host = "127.0.0.1", port = 10809),
                            ),
                    ),
            )

        val config = parse(lanAwareAssembler.assemble(baseConfigWithRules("profile.example"), settings, null))
        val inbounds = config["inbounds"]!!.jsonArray
        val listens = inbounds.map { inbound -> inbound.jsonObject["listen"]!!.jsonPrimitive.content }

        assertEquals(1, inbounds.size)
        assertTrue("192.168.1.23" in listens)
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
                        allowHttpConfigImports = false,
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
                        allowHttpConfigImports = true,
                    ),
            )

        assertEquals(
            assembler.runtimeFingerprint(runtimeSettings, null),
            assembler.runtimeFingerprint(metadataOnlyChanges, null),
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
    fun `exclude mode writes exclude package list into tun inbound`() {
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

        assertEquals("com.bank.app", tunInbound["exclude_package"]!!.jsonArray[0].jsonPrimitive.content)
        assertFalse(tunInbound.containsKey("include_package"))
    }

    @Test
    fun `foxhole dns defaults are upgraded to split resolver config`() {
        val config = parse(assembler.assemble(baseConfigWithLegacyFoxholeDns(), Settings(), null))
        val dns = config["dns"]!!.jsonObject
        val route = config["route"]!!.jsonObject
        val servers = dns["servers"]!!.jsonArray

        assertEquals("dns-remote", dns["final"]!!.jsonPrimitive.content)
        assertEquals("dns-local", servers[0].jsonObject["tag"]!!.jsonPrimitive.content)
        assertEquals("local", servers[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertFalse(servers[0].jsonObject.containsKey("detour"))
        assertEquals("dns-direct", servers[1].jsonObject["tag"]!!.jsonPrimitive.content)
        assertEquals("udp", servers[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("1.1.1.1", servers[1].jsonObject["server"]!!.jsonPrimitive.content)
        assertEquals("53", servers[1].jsonObject["server_port"]!!.jsonPrimitive.content)
        assertEquals("direct", servers[1].jsonObject["detour"]!!.jsonPrimitive.content)
        assertEquals("dns-remote", servers[2].jsonObject["tag"]!!.jsonPrimitive.content)
        assertEquals("https", servers[2].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("1.1.1.1", servers[2].jsonObject["server"]!!.jsonPrimitive.content)
        assertEquals("443", servers[2].jsonObject["server_port"]!!.jsonPrimitive.content)
        assertEquals("/dns-query", servers[2].jsonObject["path"]!!.jsonPrimitive.content)
        assertEquals("proxy", servers[2].jsonObject["detour"]!!.jsonPrimitive.content)
        assertEquals("true", route["auto_detect_interface"]!!.jsonPrimitive.content)
    }

    @Test
    fun `route defaults inject hijack dns and local bootstrap resolver`() {
        val config = parse(assembler.assemble(baseConfigWithRules("profile.example"), Settings(), null))
        val route = config["route"]!!.jsonObject
        val rules = route["rules"]!!.jsonArray

        assertPortDnsHijack(rules[0].jsonObject)
        assertProtocolDnsHijack(rules[1].jsonObject)
        assertEquals("dns-direct", route["default_domain_resolver"]!!.jsonPrimitive.content)
        assertEquals("true", route["auto_detect_interface"]!!.jsonPrimitive.content)
    }

    @Test
    fun `foxhole managed routes keep final and bootstrap resolver`() {
        val config = parse(assembler.assemble(baseConfigWithFoxholeManagedRoute(), Settings(), null))
        val route = config["route"]!!.jsonObject
        val rules = route["rules"]!!.jsonArray

        assertPortDnsHijack(rules[0].jsonObject)
        assertProtocolDnsHijack(rules[1].jsonObject)
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
        val rule = config["route"]!!.jsonObject["rules"]!!.jsonArray[3].jsonObject

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

        assertEquals("true", tunInbound["sniff"]!!.jsonPrimitive.content)
        assertEquals("false", tunInbound["sniff_override_destination"]!!.jsonPrimitive.content)
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
                                    http = ProxyInboundSettings(enabled = true, port = 10808),
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

    private fun assertProtocolDnsHijack(rule: JsonObject) {
        assertEquals("hijack-dns", rule["action"]!!.jsonPrimitive.content)
        assertEquals("dns", rule["protocol"]!!.jsonPrimitive.content)
        assertFalse(rule.containsKey("port"))
    }
}
