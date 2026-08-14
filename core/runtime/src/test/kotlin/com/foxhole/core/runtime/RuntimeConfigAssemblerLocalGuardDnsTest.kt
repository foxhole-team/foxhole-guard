package com.foxhole.core.runtime

import com.foxhole.core.model.AppTunnelLane
import com.foxhole.core.model.DnsSettings
import com.foxhole.core.model.ExpertSettings
import com.foxhole.core.model.PrivacyRouteMode
import com.foxhole.core.model.PrivacyRouteScope
import com.foxhole.core.model.Settings
import com.foxhole.core.model.StatisticsSettings
import com.foxhole.core.model.TrafficSettings
import com.foxhole.core.model.UiSettings
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

internal class RuntimeConfigAssemblerLocalGuardDnsTest : RuntimeConfigAssemblerTestSupport() {
    @Test
    fun `local firewall guard does not enable unverified DNS rule set filtering`() {
        val settings =
            Settings(
                dns = DnsSettings(filteringEnabled = true, filtersUpdatedAt = 42L, interceptDnsRequests = true),
                expert = ExpertSettings(firewallEnabled = true),
            )

        val config = parse(assembler.assembleLocalGuard(settings, LocalGuardMode.FIREWALL))
        val dns = config["dns"]!!.jsonObject
        val route = config["route"]!!.jsonObject

        assertFalse(dns.containsKey("rules"))
        assertFalse(route.containsKey("rule_set"))
        // DNS capture (a firewall/journal feature gated by interceptDnsRequests) stays on: the
        // unverified rule set disables only the filtering rules, not the capture itself.
        assertTrue(
            route["rules"]!!.jsonArray.any { rule ->
                rule.jsonObject["action"]?.jsonPrimitive?.content == "hijack-dns"
            }
        )
    }

    @Test
    fun `local firewall guard suppresses bypass DNS rules until filtering is verified`() {
        val settings =
            Settings(
                dns =
                DnsSettings(
                    filteringEnabled = true,
                    blockAds = false,
                    blockTrackers = false,
                    blockAppTelemetry = false,
                    blockMaliciousDomains = false,
                    appBypassPackages = listOf("com.example.bank"),
                    domainBypassRules = listOf("login.example", "push.example"),
                ),
                expert = ExpertSettings(firewallEnabled = true),
            )

        val config = parse(assembler.assembleLocalGuard(settings, LocalGuardMode.FIREWALL))
        val dns = config["dns"]!!.jsonObject
        val route = config["route"]!!.jsonObject

        assertFalse(dns.containsKey("rules"))
        assertFalse(route.containsKey("rule_set"))
    }

    @Test
    fun `local firewall guard attaches verified DNS rule set when prepared`() {
        val filterPath = "/data/user/0/com.foxhole.guard/files/dns-rule-sets/adguard-dns-filter.verified.srs"
        val config =
            parse(
                assembler.assembleLocalGuard(
                    settings =
                    Settings(
                        dns = DnsSettings(filteringEnabled = true),
                        expert = ExpertSettings(firewallEnabled = true),
                    ),
                    mode = LocalGuardMode.FIREWALL,
                    dnsFilterRuntimePaths =
                    DnsFilterRuntimePaths(
                        adGuardDnsFilterPath = filterPath,
                        adGuardVpnCompatibilityDomains = listOf("adguard-vpn.com"),
                    ),
                ),
            )
        val dnsRules = config["dns"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }
        val adGuardRule =
            dnsRules.single { rule -> rule.stringArray("rule_set").contains("foxhole-adguard-dns-filter") }
        val compatibilityRule =
            dnsRules.single { rule -> rule.stringArray("domain_suffix").contains("adguard-vpn.com") }
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
    fun `local firewall guard leaves DNS uncaptured when intercept is disabled`() {
        val filterPath = "/data/user/0/com.foxhole.guard/files/dns-rule-sets/adguard-dns-filter.verified.srs"
        val settings =
            Settings(
                dns = DnsSettings(interceptDnsRequests = false, filteringEnabled = true),
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
        val route = config["route"]!!.jsonObject
        val rules = route["rules"]!!.jsonArray.map { it.jsonObject }

        assertEquals("dns-remote", config["dns"]!!.jsonObject["final"]!!.jsonPrimitive.content)
        assertEquals("dns-remote", route["default_domain_resolver"]!!.jsonPrimitive.content)
        assertTrue(rules.none { rule -> rule["action"]?.jsonPrimitive?.content == "hijack-dns" })
    }

    @Test
    fun `local firewall guard blocks selected apps without Android package allowlist`() {
        val settings =
            Settings(
                expert =
                ExpertSettings(
                    blockedPackagesEnabled = true,
                    appAssignments = mapOf("org.mozilla.firefox" to AppTunnelLane.BLOCK),
                    blockAppsAlways = true,
                ),
            )

        val config = parse(assembler.assembleLocalGuard(settings, LocalGuardMode.FIREWALL))
        val tunInbound = config["inbounds"]!!.jsonArray.single().jsonObject
        val route = config["route"]!!.jsonObject

        assertFalse(tunInbound.containsKey("include_package"))
        assertLocalGuardExcludesFoxHole(tunInbound)
        assertEquals(false, tunInbound["strict_route"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("direct", route["final"]!!.jsonPrimitive.content)
        assertTrue(
            route["rules"]!!.jsonArray.map { it.jsonObject }.any { rule ->
                rule["package_name"]?.jsonArray?.single()?.jsonPrimitive?.content == "org.mozilla.firefox" &&
                    rule["outbound"]?.jsonPrimitive?.content == "block"
            },
        )
    }

    @Test
    fun `transparent i2p firewall does not hijack dns or block apps`() {
        // i2p raised the firewall (no manual firewall): it must be a pass-through — no DNS hijack
        // even with intercept on, no block rules — only the .i2p diversion carries anything.
        val settings =
            Settings(
                dns = DnsSettings(interceptDnsRequests = true),
                i2p = com.foxhole.core.model.I2pSettings(enabled = true, allowOutsideTunnel = true),
            )
        assertTrue(settings.i2pRaisesLocalGuard())
        val config = parse(assembler.assembleLocalGuard(settings, LocalGuardMode.FIREWALL))
        val rules = config["route"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }
        assertTrue("no dns hijack", rules.none { it["action"]?.jsonPrimitive?.content == "hijack-dns" })
        assertTrue("no app block", rules.none { it["outbound"]?.jsonPrimitive?.content == "block" })
    }

    /**
     * И то же самое, когда блокировки РЕАЛЬНО взведены.
     *
     * Прошлый тест проходил по случайности: он строил настройки с `blockAppsAlways = false`, то
     * есть проверял отсутствие правил там, где их не было бы в любом случае. Достижимое состояние
     * другое и получается само: закрепление приложения в лейне BLOCK взводит `blockAppsAlways`,
     * выключение фаервола снимает только свой флаг, нормализация блокировки не снимает — и тогда
     * включение I2P поднимает прозрачный страж поверх взведённых блокировок.
     */
    @Test
    fun `transparent i2p firewall blocks nothing even with app blocking armed`() {
        val settings =
            Settings(
                dns = DnsSettings(interceptDnsRequests = true),
                i2p = com.foxhole.core.model.I2pSettings(enabled = true, allowOutsideTunnel = true),
                expert = ExpertSettings(
                    appAssignments = mapOf("org.mozilla.firefox" to AppTunnelLane.BLOCK),
                    blockedPackagesEnabled = true,
                    blockAppsAlways = true,
                    firewallEnabled = false,
                ),
            )
        assertTrue(settings.i2pRaisesLocalGuard())
        assertTrue("этот тест бессмыслен без взведённых блокировок", settings.expert.blockAppsAlways)

        val config = parse(assembler.assembleLocalGuard(settings, LocalGuardMode.FIREWALL))
        val rules = config["route"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }

        assertTrue(
            "прозрачный страж обещает не трогать трафик — значит и правила блокировки не выпускает",
            rules.none { it["outbound"]?.jsonPrimitive?.content == "block" },
        )
        assertTrue("no dns hijack", rules.none { it["action"]?.jsonPrimitive?.content == "hijack-dns" })
    }

    @Test
    fun `engaged i2p raises the firewall guard when no manual firewall or vpn`() {
        // I2P always needs a routing surface, so it raises the transparent firewall itself.
        val i2pOutside =
            Settings(i2p = com.foxhole.core.model.I2pSettings(enabled = true, allowOutsideTunnel = true))
        assertEquals(LocalGuardMode.FIREWALL, i2pOutside.localGuardModeOrNull())
        assertTrue(i2pOutside.i2pRaisesLocalGuard())

        // Disabling i2p drops the guard again (nothing else keeps it up).
        assertEquals(null, i2pOutside.copy(i2p = i2pOutside.i2p.copy(enabled = false)).localGuardModeOrNull())

        // The legacy allowOutsideTunnel field no longer changes the carrier contract.
        assertEquals(
            LocalGuardMode.FIREWALL,
            Settings(
                i2p = com.foxhole.core.model.I2pSettings(enabled = true, allowOutsideTunnel = false)
            ).localGuardModeOrNull(),
        )

        // A manually enabled firewall owns the guard; i2p is not the thing raising it then.
        val manualFirewall =
            Settings(
                expert = ExpertSettings(firewallEnabled = true),
                i2p = com.foxhole.core.model.I2pSettings(enabled = true, allowOutsideTunnel = true),
            )
        assertEquals(LocalGuardMode.FIREWALL, manualFirewall.localGuardModeOrNull())
        assertFalse(manualFirewall.i2pRaisesLocalGuard())
    }

    @Test
    fun `i2p beside an enabled firewall keeps dns interception and app blocking`() {
        val settings =
            Settings(
                dns = DnsSettings(interceptDnsRequests = true),
                i2p = com.foxhole.core.model.I2pSettings(enabled = true, engaged = true),
                expert =
                ExpertSettings(
                    firewallEnabled = true,
                    blockedPackagesEnabled = true,
                    appAssignments = mapOf("org.mozilla.firefox" to AppTunnelLane.BLOCK),
                    blockAppsAlways = true,
                ),
            )
        assertFalse(settings.i2pRaisesLocalGuard())

        val config = parse(assembler.assembleLocalGuard(settings, LocalGuardMode.FIREWALL))
        val rules = config.getValue("route").jsonObject.getValue("rules").jsonArray.map { it.jsonObject }

        assertTrue(rules.any { it["action"]?.jsonPrimitive?.content == "hijack-dns" })
        assertTrue(
            rules.any { rule ->
                rule["package_name"]?.jsonArray?.single()?.jsonPrimitive?.content == "org.mozilla.firefox" &&
                    rule["outbound"]?.jsonPrimitive?.content == "block"
            },
        )
    }

    @Test
    fun `plain expert defaults raise no local guard while the firewall does`() {
        assertEquals(
            null,
            Settings(expert = ExpertSettings()).localGuardModeOrNull(),
        )
        assertEquals(
            LocalGuardMode.FIREWALL,
            Settings(
                expert =
                ExpertSettings(
                    firewallEnabled = true,
                ),
            ).localGuardModeOrNull(),
        )
        assertEquals(
            LocalGuardMode.FIREWALL,
            Settings(
                expert =
                ExpertSettings(
                    firewallEnabled = true,
                ),
            ).localGuardModeOrNull(),
        )
        assertEquals(
            LocalGuardMode.FIREWALL,
            Settings(
                expert =
                ExpertSettings(
                    firewallEnabled = true,
                    blockedPackagesEnabled = true,
                    appAssignments = mapOf("org.mozilla.firefox" to AppTunnelLane.BLOCK),
                    blockAppsAlways = true,
                ),
            ).localGuardModeOrNull(),
        )
    }

    @Test
    fun `firewall local guard starts whenever firewall is enabled`() {
        val blockedApps =
            ExpertSettings(
                blockedPackagesEnabled = true,
                appAssignments = mapOf("org.mozilla.firefox" to AppTunnelLane.BLOCK),
                blockAppsAlways = true,
            )

        assertEquals(
            null,
            Settings(expert = blockedApps).localGuardModeOrNull(),
        )
        assertEquals(
            LocalGuardMode.FIREWALL,
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
    fun `firewall alone starts full-device local guard`() {
        val firewall = ExpertSettings(firewallEnabled = true)

        val settings = Settings(expert = firewall)
        assertEquals(LocalGuardMode.FIREWALL, settings.localGuardModeOrNull())
    }

    @Test
    fun `firewall logging features keep firewall local guard active`() {
        val firewall = ExpertSettings(firewallEnabled = true)

        assertEquals(
            LocalGuardMode.FIREWALL,
            Settings(
                expert = firewall.copy(),
            ).localGuardModeOrNull(),
        )
        assertEquals(
            LocalGuardMode.FIREWALL,
            Settings(
                ui = UiSettings(trafficMapEnabled = true),
                expert = firewall,
            ).localGuardModeOrNull(),
        )
        assertEquals(
            LocalGuardMode.FIREWALL,
            Settings(
                expert = firewall,
                statistics = StatisticsSettings(enabled = true, countryTrafficEnabled = true),
            ).localGuardModeOrNull(),
        )
        assertEquals(
            LocalGuardMode.FIREWALL,
            Settings(
                expert = firewall.copy(
                    systemDnsProtectionEnabled = true,
                ),
            ).localGuardModeOrNull(),
        )
    }

    @Test
    fun `system dns replacement uses lightweight dns local guard`() {
        val settings =
            Settings(
                dns = DnsSettings(replaceSystemDns = true),
            )

        assertEquals(LocalGuardMode.DNS, settings.localGuardModeOrNull())

        val config = parse(assembler.assembleLocalGuard(settings, LocalGuardMode.DNS))
        val dns = config["dns"]!!.jsonObject
        val route = config["route"]!!.jsonObject
        val rules = route["rules"]!!.jsonArray.map { it.jsonObject }
        val dnsServers = dns["servers"]!!.jsonArray.map { it.jsonObject }
        val tunInbound = config["inbounds"]!!.jsonArray.single().jsonObject

        assertEquals("dns-remote", dns["final"]!!.jsonPrimitive.content)
        assertEquals("dns-remote", route["default_domain_resolver"]!!.jsonPrimitive.content)
        assertEquals("direct", route["final"]!!.jsonPrimitive.content)
        assertTrue(rules.any { rule -> rule["action"]?.jsonPrimitive?.content == "hijack-dns" })
        assertFalse(tunInbound.containsKey("include_package"))
        assertLocalGuardExcludesFoxHole(tunInbound)
        assertEquals(
            listOf("172.19.0.2/32", "::/0"),
            tunInbound["route_address"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(
            listOf("172.19.0.1/30", "fdfe:dcba:9876::1/126"),
            tunInbound["address"]!!.jsonArray.map { it.jsonPrimitive.content },
        )

        // The guard resolves with the user's configured provider (Cloudflare plain by default),
        // not a hard-coded filter DNS.
        val providerServer =
            dnsServers.single { server -> server["tag"]!!.jsonPrimitive.content == "dns-remote" }
        assertEquals("1.1.1.1", providerServer["server"]!!.jsonPrimitive.content)
        assertEquals("udp", providerServer["type"]!!.jsonPrimitive.content)
        assertFalse(providerServer.containsKey("detour"))
    }

    @Test
    fun `permanent firewall app blocking avoids Android package allowlist when system dns is enabled`() {
        val settings =
            Settings(
                dns = DnsSettings(replaceSystemDns = true),
                expert =
                ExpertSettings(
                    firewallEnabled = true,
                    blockedPackagesEnabled = true,
                    appAssignments = mapOf("org.mozilla.firefox" to AppTunnelLane.BLOCK),
                    blockAppsAlways = true,
                ),
            )

        assertEquals(LocalGuardMode.FIREWALL, settings.localGuardModeOrNull())

        val config = parse(assembler.assembleLocalGuard(settings, LocalGuardMode.FIREWALL))
        val dns = config["dns"]!!.jsonObject
        val route = config["route"]!!.jsonObject
        val rules = route["rules"]!!.jsonArray.map { it.jsonObject }
        val tunInbound = config["inbounds"]!!.jsonArray.single().jsonObject

        assertEquals("dns-remote", dns["final"]!!.jsonPrimitive.content)
        assertEquals("dns-remote", route["default_domain_resolver"]!!.jsonPrimitive.content)
        assertEquals("direct", route["final"]!!.jsonPrimitive.content)
        assertFalse(tunInbound.containsKey("include_package"))
        assertLocalGuardExcludesFoxHole(tunInbound)
        assertTrue(rules.any { rule -> rule["action"]?.jsonPrimitive?.content == "hijack-dns" })
        assertTrue(
            rules.any { rule ->
                rule["package_name"]?.jsonArray?.single()?.jsonPrimitive?.content == "org.mozilla.firefox"
            },
        )
    }

    // Network-activity logging is a facet of the firewall guard, never a runtime of its own (the
    // JOURNAL mode this once asserted was unreachable and is gone).
    @Test
    fun `firewall guard with activity logging captures DNS while app block rules stay active`() {
        val settings =
            Settings(
                dns = DnsSettings(interceptDnsRequests = true),
                expert =
                ExpertSettings(
                    networkActivityLogging = true,
                    blockedPackagesEnabled = true,
                    appAssignments = mapOf("org.mozilla.firefox" to AppTunnelLane.BLOCK),
                    blockAppsAlways = true,
                ),
            )

        val config = parse(assembler.assembleLocalGuard(settings, LocalGuardMode.FIREWALL))
        val dns = config["dns"]!!.jsonObject
        val route = config["route"]!!.jsonObject
        val rules = route["rules"]!!.jsonArray.map { it.jsonObject }
        val dnsServers = dns["servers"]!!.jsonArray.map { it.jsonObject }
        val tunInbound = config["inbounds"]!!.jsonArray.single().jsonObject

        assertEquals("dns-remote", dns["final"]!!.jsonPrimitive.content)
        assertEquals("dns-remote", route["default_domain_resolver"]!!.jsonPrimitive.content)
        assertEquals("direct", route["final"]!!.jsonPrimitive.content)
        assertEquals(false, tunInbound["strict_route"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(dnsServers.any { server -> server["tag"]!!.jsonPrimitive.content == "dns-remote" })
        assertFalse(dnsServers.any { server -> server["detour"]?.jsonPrimitive?.content == "proxy" })
        assertFalse(tunInbound.containsKey("include_package"))
        assertLocalGuardExcludesFoxHole(tunInbound)
        assertTrue(
            rules.any { rule -> rule["package_name"]?.jsonArray?.single()?.jsonPrimitive?.content == "org.mozilla.firefox" }
        )
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
                    Settings(traffic = com.foxhole.core.model.TrafficSettings(mtu = 1280)),
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
    fun `local guard tun excludes the package the app actually runs as`() {
        // BuildConfig.APPLICATION_ID is the release id, so a suffixed variant (debug/internal) used
        // to exclude a package that is not even installed and routed its own control plane through
        // the guard it was running.
        val variantAssembler = RuntimeConfigAssembler(json, selfPackageName = "com.foxhole.guard.debug")
        val settings = Settings(expert = ExpertSettings(firewallEnabled = true))

        val config = parse(variantAssembler.assembleLocalGuard(settings, LocalGuardMode.FIREWALL))
        val tunInbound = config["inbounds"]!!.jsonArray.first().jsonObject
        val excluded = tunInbound["exclude_package"]!!.jsonArray.map { it.jsonPrimitive.content }

        assertEquals(listOf("com.foxhole.guard.debug"), excluded)
    }

    @Test
    fun `tor-only tun excludes the package the app actually runs as`() {
        val variantAssembler = RuntimeConfigAssembler(json, selfPackageName = "com.foxhole.guard.debug")
        val settings =
            Settings(
                privacyRoute =
                com.foxhole.core.model.PrivacyRouteSettings(
                    mode = PrivacyRouteMode.TOR_OVER_VPN,
                    scope = PrivacyRouteScope.ALL_APPS,
                ),
            )

        val config =
            parse(
                variantAssembler.assembleTorOnly(
                    settings = settings,
                    activePreset = null,
                    torRuntimePaths = TorRuntimePaths(
                        dataDirectory = "/tor-data",
                    ),
                ),
            )
        val tunInbound = config["inbounds"]!!.jsonArray.first().jsonObject
        val excluded = tunInbound["exclude_package"]!!.jsonArray.map { it.jsonPrimitive.content }

        assertEquals(listOf("com.foxhole.guard.debug"), excluded)
    }
}
