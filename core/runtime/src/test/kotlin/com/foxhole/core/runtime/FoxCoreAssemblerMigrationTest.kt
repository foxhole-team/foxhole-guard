package com.foxhole.core.runtime

import com.foxhole.core.model.AppTunnelLane
import com.foxhole.core.model.DnsSettings
import com.foxhole.core.model.ExpertSettings
import com.foxhole.core.model.FoxCoreDnsRuleSetBootstrap
import com.foxhole.core.model.I2pSettings
import com.foxhole.core.model.PerAppRoutingMode
import com.foxhole.core.model.PrivacyRouteMode
import com.foxhole.core.model.PrivacyRouteScope
import com.foxhole.core.model.PrivacyRouteSettings
import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.Settings
import com.foxhole.core.model.VpnSession
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
import org.junit.Assert.assertTrue
import org.junit.Test

internal class FoxCoreAssemblerMigrationTest : RuntimeConfigAssemblerTestSupport() {
    private val translator = FoxCoreConfigTranslator()

    @Test
    fun `assembled local guard becomes a narrow direct FoxCore session`() {
        val legacy =
            assembler.assembleLocalGuard(
                Settings(dns = DnsSettings(replaceSystemDns = true)),
                LocalGuardMode.DNS,
            )
        val translated =
            translator.translate(
                VpnSession(
                    profileId = -2L,
                    profileName = "Local guard",
                    protocolHint = ProtocolHint.LOCAL_GUARD,
                    configJson = legacy,
                    correlationId = "local-guard-contract",
                ),
            )
        val engine = json.parseToJsonElement(translated.engineConfigJson).jsonObject

        assertEquals("direct", engine.getValue("outbound").jsonObject.getValue("type").jsonPrimitive.content)
        assertTrue(
            engine
                .getValue("runtime")
                .jsonObject
                .getValue("local_guard")
                .jsonPrimitive
                .content
                .toBoolean(),
        )
        assertTrue(translated.tunPlan.routes.isNotEmpty())
        assertEquals(listOf("172.19.0.2"), translated.tunPlan.advertisedDnsServers)
        val policy = json.parseToJsonElement(translated.policyConfigJson).jsonObject
        assertEquals("real_ip", policy.getValue("dns").jsonObject.getValue("mode").jsonPrimitive.content)
    }

    @Test
    fun `full capture firewall uses fake ip while the dns only guard remains real ip`() {
        val legacy =
            assembler.assembleLocalGuard(
                Settings(dns = DnsSettings(interceptDnsRequests = true)),
                LocalGuardMode.FIREWALL,
            )
        val translated =
            translator.translate(
                VpnSession(
                    profileId = -10L,
                    profileName = "Local firewall",
                    protocolHint = ProtocolHint.LOCAL_GUARD,
                    configJson = legacy,
                    correlationId = "full-capture-firewall-contract",
                    forceFakeIpDns = true,
                ),
            )

        val policy = json.parseToJsonElement(translated.policyConfigJson).jsonObject
        assertEquals("fake_ip", policy.getValue("dns").jsonObject.getValue("mode").jsonPrimitive.content)
        assertTrue(localGuardUsesFullCapture(LocalGuardMode.FIREWALL, dnsGuardFullCapture = false))
        assertFalse(localGuardUsesFullCapture(LocalGuardMode.DNS, dnsGuardFullCapture = false))
        assertTrue(localGuardUsesFullCapture(LocalGuardMode.DNS, dnsGuardFullCapture = true))
    }

    @Test
    fun `assembled tor only becomes a single primary Arti outbound`() {
        val settings =
            Settings(
                privacyRoute =
                PrivacyRouteSettings(
                    permitted = true,
                    mode = PrivacyRouteMode.TOR_OVER_VPN,
                    scope = PrivacyRouteScope.ALL_APPS,
                ),
            )
        val legacy =
            assembler.assembleTorOnly(
                settings = settings,
                activePreset = null,
                torRuntimePaths =
                TorRuntimePaths(
                    dataDirectory = "/data/user/0/com.foxhole.guard/files/tor",
                    bridges =
                    listOf(
                        "obfs4 192.0.2.83:80 " +
                            "0bac39417268b96b9f514ef763fa6fba1a788956 cert=contract",
                    ),
                    pluggableTransports =
                    listOf(
                        TorPluggableTransport(
                            protocols = listOf("obfs4"),
                            executablePath = "/data/app/liblyrebird.so",
                            arguments = listOf("-enableLogging=false"),
                        ),
                    ),
                ),
            )
        val translated =
            translator.translate(
                VpnSession(
                    profileId = -3L,
                    profileName = "Tor",
                    protocolHint = ProtocolHint.TOR,
                    configJson = legacy,
                    correlationId = "tor-only-contract",
                    torActive = true,
                ),
            )
        val engine = json.parseToJsonElement(translated.engineConfigJson).jsonObject

        val outbound = engine.getValue("outbound").jsonObject
        assertEquals("tor", outbound.getValue("type").jsonPrimitive.content)
        assertEquals(1, outbound.getValue("bridges").jsonArray.size)
        assertEquals(1, outbound.getValue("transports").jsonArray.size)
        assertFalse(engine.containsKey("outbounds"))
        assertTrue(translated.tunPlan.disallowedApplications.contains(BuildConfig.APPLICATION_ID))
    }

    @Test
    fun `selected app tor only remains representable with a direct default`() {
        val torApp = "com.example.tor"
        val vpnApp = "com.example.vpn"
        val settings =
            Settings(
                privacyRoute =
                PrivacyRouteSettings(
                    permitted = true,
                    mode = PrivacyRouteMode.TOR_OVER_VPN,
                    scope = PrivacyRouteScope.SELECTED_APPS,
                ),
                expert =
                ExpertSettings(
                    perAppRoutingMode = PerAppRoutingMode.INCLUDE_SELECTED_APPS,
                    appAssignments =
                    mapOf(
                        torApp to AppTunnelLane.TOR,
                        vpnApp to AppTunnelLane.VPN,
                    ),
                ),
            )
        val assembled =
            assembler.assembleTorOnly(
                settings = settings,
                activePreset = null,
                torRuntimePaths = TorRuntimePaths(dataDirectory = "/tor-data"),
            )

        val translated =
            translator.translate(
                VpnSession(
                    profileId = -3L,
                    profileName = "Selected Tor",
                    protocolHint = ProtocolHint.TOR,
                    configJson = assembled,
                    correlationId = "selected-tor-only-contract",
                    torActive = true,
                ),
            )
        val policy = json.parseToJsonElement(translated.policyConfigJson).jsonObject
        val traffic = policy.getValue("traffic").jsonObject
        val routes = policy.getValue("routes").jsonArray.map { it.jsonObject }

        assertEquals("direct", traffic.getValue("default_action").jsonPrimitive.content)
        assertTrue(
            routes.any { route ->
                route["package"]?.jsonPrimitive?.content == torApp &&
                    route["transport"]?.jsonPrimitive?.content == "tcp" &&
                    route.getValue("action").jsonObject["type"]?.jsonPrimitive?.content == "outbound"
            },
        )
        assertTrue(
            routes.any { route ->
                route["package"]?.jsonPrimitive?.content == torApp &&
                    route["transport"]?.jsonPrimitive?.content == "udp" &&
                    route.getValue("action").jsonObject["type"]?.jsonPrimitive?.content == "block"
            },
        )
        assertFalse(translated.tunPlan.disallowedApplications.contains(vpnApp))
    }

    @Test
    fun `a dns bypass without a rule set never reaches the translator`() {
        val bypassApp = "app.dns.bypass"
        val settings =
            Settings(
                dns =
                DnsSettings(
                    filteringEnabled = true,
                    blockAds = false,
                    blockTrackers = false,
                    blockAppTelemetry = false,
                    blockMaliciousDomains = false,
                    appBypassPackages = listOf(bypassApp),
                    domainBypassRules = listOf("bypass.example"),
                ),
            )

        val assembled =
            assembler.assemble(
                baseConfigJson = vlessBaseConfig(),
                settings = settings,
                activePreset = null,
                vpnProtocolHint = ProtocolHint.VLESS,
            )
        val assembledRules = parse(assembled).getValue("dns").jsonObject["rules"]?.jsonArray.orEmpty()
        assertTrue("assembler emitted a bypass with no rule set again", assembledRules.isEmpty())

        translator.translate(
            VpnSession(
                profileId = 12L,
                profileName = "dns bypass without a rule set",
                protocolHint = ProtocolHint.VLESS,
                configJson = assembled,
                correlationId = "dns-bypass-contract",
            ),
        )
    }

    @Test
    fun `an enabled verified dns filter stays representable for a vless tunnel`() {
        val bootstrap =
            FoxCoreDnsRuleSetBootstrap(
                name = "foxhole-adguard-dns-filter",
                artifactPath = "/data/user/0/com.foxhole.guard/files/dns-rule-sets/adguard-dns-filter.fhds",
                artifactSha256 = "a".repeat(64),
                publicKeyBase64 = "AQID",
                minimumSequence = 7L,
            )
        val paths =
            DnsFilterRuntimePaths(
                adGuardDnsFilterPath = bootstrap.artifactPath,
                foxCoreBootstrap = bootstrap,
                adGuardVpnCompatibilityDomains = listOf("connectivitycheck.example"),
            )
        val assembled =
            assembler.assemble(
                baseConfigJson = vlessBaseConfig(),
                settings = Settings(dns = DnsSettings(filteringEnabled = true)),
                activePreset = null,
                dnsFilterRuntimePaths = paths,
                vpnProtocolHint = ProtocolHint.VLESS,
            )
        val assembledRoute = parse(assembled).getValue("route").jsonObject
        assertFalse(assembledRoute.containsKey("rule_set"))
        assertTrue(
            assembledRoute
                .getValue("rules")
                .jsonArray
                .none { rule -> rule.jsonObject.containsKey("rule_set") },
        )

        val translated =
            translator.translate(
                VpnSession(
                    profileId = 13L,
                    profileName = "verified dns filter",
                    protocolHint = ProtocolHint.VLESS,
                    configJson = assembled,
                    correlationId = "verified-dns-filter-contract",
                ),
                dnsRuleSetBootstrap = bootstrap,
            )

        assertEquals(bootstrap, translated.dnsRuleSetBootstrap)
        val dns = json.parseToJsonElement(translated.policyConfigJson).jsonObject.getValue("dns").jsonObject
        assertTrue(dns.getValue("rule_sets").jsonArray.isNotEmpty())
    }

    @Test
    fun `standalone I2P local guard carries its verified dns bootstrap into FoxCore`() {
        val previousI2pEndpoint = I2pdSocksProxy.endpoint
        I2pdSocksProxy.endpoint =
            I2pdSocksProxyEndpoint(
                port = 44_444,
                username = "foxhole-local-guard-test",
                password = "one-time-local-guard-secret",
            )
        try {
            val bootstrap =
                FoxCoreDnsRuleSetBootstrap(
                    name = "foxhole-adguard-dns-filter",
                    artifactPath = "/data/user/0/com.foxhole.guard/files/dns-rule-sets/adguard-dns-filter.fhds",
                    artifactSha256 = "c".repeat(64),
                    publicKeyBase64 = "AQID",
                    minimumSequence = 9L,
                )
            val paths =
                DnsFilterRuntimePaths(
                    adGuardDnsFilterPath = bootstrap.artifactPath,
                    foxCoreBootstrap = bootstrap,
                )
            val assembled =
                assembler.assembleLocalGuard(
                    settings =
                    Settings(
                        dns = DnsSettings(filteringEnabled = true),
                        i2p = I2pSettings(enabled = true, engaged = true),
                    ),
                    mode = LocalGuardMode.FIREWALL,
                    dnsFilterRuntimePaths = paths,
                    i2pSocksPort = 44_444,
                )
            val translated =
                translator.translate(
                    session =
                    VpnSession(
                        profileId = -10L,
                        profileName = "I2P local guard",
                        protocolHint = ProtocolHint.LOCAL_GUARD,
                        configJson = assembled,
                        correlationId = "verified-dns-filter-local-guard-contract",
                    ),
                    dnsRuleSetBootstrap = bootstrap,
                )

            assertEquals(bootstrap, translated.dnsRuleSetBootstrap)
            val engine = json.parseToJsonElement(translated.engineConfigJson).jsonObject
            assertTrue(
                engine.getValue("outbounds").jsonArray.any { named ->
                    named.jsonObject.getValue("outbound").jsonObject.getValue("type").jsonPrimitive.content == "i2p"
                },
            )
            val dns = json.parseToJsonElement(translated.policyConfigJson).jsonObject.getValue("dns").jsonObject
            assertTrue(dns.getValue("rule_sets").jsonArray.isNotEmpty())
        } finally {
            I2pdSocksProxy.endpoint = previousI2pEndpoint
        }
    }

    @Test
    fun `verified dns filter stays representable with tor and i2p routes`() {
        val previousI2pEndpoint = I2pdSocksProxy.endpoint
        I2pdSocksProxy.endpoint =
            I2pdSocksProxyEndpoint(
                port = 44_444,
                username = "foxhole-test",
                password = "one-time-test-secret",
            )
        try {
            verifyDnsFilterWithTorAndI2p()
        } finally {
            I2pdSocksProxy.endpoint = previousI2pEndpoint
        }
    }

    private fun verifyDnsFilterWithTorAndI2p() {
        val bootstrap =
            FoxCoreDnsRuleSetBootstrap(
                name = "foxhole-adguard-dns-filter",
                artifactPath = "/data/user/0/com.foxhole.guard/files/dns-rule-sets/adguard-dns-filter.fhds",
                artifactSha256 = "b".repeat(64),
                publicKeyBase64 = "AQID",
                minimumSequence = 8L,
            )
        val settings =
            Settings(
                dns = DnsSettings(filteringEnabled = true),
                privacyRoute = PrivacyRouteSettings(
                    permitted = true,
                    mode = PrivacyRouteMode.TOR_OVER_VPN,
                    scope = PrivacyRouteScope.ALL_APPS,
                ),
                i2p = I2pSettings(enabled = true, engaged = true),
            )
        val dnsFilterPaths =
            DnsFilterRuntimePaths(
                adGuardDnsFilterPath = bootstrap.artifactPath,
                foxCoreBootstrap = bootstrap,
            )
        val assembled =
            assembler.assemble(
                baseConfigJson = vlessBaseConfig(),
                settings = settings,
                activePreset = null,
                privateDnsState = PrivateDnsState(PrivateDnsMode.OPPORTUNISTIC),
                torRuntimePaths = TorRuntimePaths(dataDirectory = "/data/user/0/com.foxhole.guard/files/tor"),
                dnsFilterRuntimePaths = dnsFilterPaths,
                vpnProtocolHint = ProtocolHint.VLESS,
                i2pSocksPort = 44_444,
            )

        val translated =
            translator.translate(
                VpnSession(
                    profileId = 14L,
                    profileName = "verified dns filter with tor and i2p",
                    protocolHint = ProtocolHint.VLESS,
                    configJson = assembled,
                    correlationId = "verified-dns-filter-tor-i2p-contract",
                    torActive = true,
                ),
                dnsRuleSetBootstrap = bootstrap,
            )

        assertEquals(bootstrap, translated.dnsRuleSetBootstrap)
        val engine = json.parseToJsonElement(translated.engineConfigJson).jsonObject
        assertEquals("selector", engine.getValue("outbound").jsonObject.getValue("type").jsonPrimitive.content)
        assertTrue(
            engine.getValue("outbounds").jsonArray.any { named ->
                named.jsonObject.getValue("outbound").jsonObject.getValue("type").jsonPrimitive.content == "tor"
            },
        )
        val dns = json.parseToJsonElement(translated.policyConfigJson).jsonObject.getValue("dns").jsonObject
        assertTrue(dns.getValue("rule_sets").jsonArray.isNotEmpty())
    }

    @Test
    fun `assembled per-app split stays a config FoxCore accepts`() {
        val splitApp = "app.split.selected"
        val excludedApp = "app.split.excluded"
        val blockedApp = "app.split.blocked"
        val cases =
            mapOf(
                "include" to
                    Settings(
                        expert =
                        ExpertSettings(
                            perAppRoutingMode = PerAppRoutingMode.INCLUDE_SELECTED_APPS,
                            appAssignments = mapOf(splitApp to AppTunnelLane.VPN),
                        ),
                    ),
                "exclude" to
                    Settings(
                        expert =
                        ExpertSettings(
                            perAppRoutingMode = PerAppRoutingMode.EXCLUDE_SELECTED_APPS,
                            appAssignments = mapOf(splitApp to AppTunnelLane.VPN),
                        ),
                    ),

                "full tunnel with an excluded lane" to
                    Settings(
                        expert =
                        ExpertSettings(
                            perAppRoutingMode = PerAppRoutingMode.FULL_TUNNEL,
                            appAssignments = mapOf(excludedApp to AppTunnelLane.EXCLUDE),
                        ),
                    ),

                "include with the firewall on" to
                    Settings(
                        expert =
                        ExpertSettings(
                            perAppRoutingMode = PerAppRoutingMode.INCLUDE_SELECTED_APPS,
                            blockedPackagesEnabled = true,
                            appAssignments =
                            mapOf(
                                splitApp to AppTunnelLane.VPN,
                                blockedApp to AppTunnelLane.BLOCK,
                            ),
                        ),
                    ),
            )

        val splitPolicies =
            cases.mapValues { (name, settings) ->
                val assembled =
                    assembler.assemble(
                        baseConfigJson = vlessBaseConfig(),
                        settings = settings,
                        activePreset = null,
                        vpnProtocolHint = ProtocolHint.VLESS,
                    )

                val assembledDns = parse(assembled).getValue("dns").jsonObject
                assertTrue(
                    "$name: assembler emitted a per-app dns rule again",
                    assembledDns["rules"]
                        ?.jsonArray
                        ?.none { rule -> rule.jsonObject.containsKey("package_name") } ?: true,
                )

                translator
                    .translate(
                        VpnSession(
                            profileId = 11L,
                            profileName = "split $name",
                            protocolHint = ProtocolHint.VLESS,
                            configJson = assembled,
                            correlationId = "split-contract",
                        ),
                    ).let { translated ->
                        json
                            .parseToJsonElement(translated.policyConfigJson)
                            .jsonObject
                            .getValue("traffic")
                            .jsonObject
                    }
            }

        val include = splitPolicies.getValue("include")
        assertEquals("direct", include.getValue("default_action").jsonPrimitive.content)
        assertEquals("vpn", include.applicationAction(splitApp))

        val exclude = splitPolicies.getValue("exclude")
        assertEquals("vpn", exclude.getValue("default_action").jsonPrimitive.content)
        assertEquals("direct", exclude.applicationAction(splitApp))

        val excludedLane = splitPolicies.getValue("full tunnel with an excluded lane")
        assertEquals("vpn", excludedLane.getValue("default_action").jsonPrimitive.content)
        assertEquals("direct", excludedLane.applicationAction(excludedApp))

        val firewalled = splitPolicies.getValue("include with the firewall on")
        assertEquals("direct", firewalled.getValue("default_action").jsonPrimitive.content)
        assertEquals("vpn", firewalled.applicationAction(splitApp))
        assertEquals("block", firewalled.applicationAction(blockedApp))
    }

    private fun JsonObject.applicationAction(packageName: String): String? =
        this["applications"]
            ?.jsonArray
            ?.map { it.jsonObject }
            ?.firstOrNull { it["package"]?.jsonPrimitive?.content == packageName }
            ?.get("action")
            ?.jsonPrimitive
            ?.content

    private fun vlessBaseConfig(): String =
        baseConfigWithOutbounds(
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("type", "vless")
                        put("tag", "node")
                        put("server", "203.0.113.10")
                        put("server_port", 443)
                        put("uuid", "11111111-1111-1111-1111-111111111111")
                        put(
                            "tls",
                            buildJsonObject {
                                put("enabled", true)
                                put("server_name", "edge.example")
                            },
                        )
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
}
