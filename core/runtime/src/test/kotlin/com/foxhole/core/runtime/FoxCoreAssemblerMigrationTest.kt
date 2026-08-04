package com.foxhole.core.runtime

import com.foxhole.core.model.AppTunnelLane
import com.foxhole.core.model.DnsSettings
import com.foxhole.core.model.ExpertSettings
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
    }

    @Test
    fun `assembled tor only becomes a single primary Arti outbound`() {
        val settings =
            Settings(
                privacyRoute =
                PrivacyRouteSettings(
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

    /**
     * A per-app split must survive the trip through the translator.
     *
     * The two halves of the split disagreed after the sing-box → FoxCore move: the route half was
     * ported (`translateInvertedApplicationRule`), the DNS half was not. The assembler kept emitting
     * the sing-box "these packages resolve on dns-direct" rule, which the translator refuses on
     * purpose — FoxCore has one resolver lane, and refusing beats pretending. The refusal is not the
     * bug; emitting the rule is. Because translation happens BEFORE the tun is built
     * (`FoxCoreRuntime.start`), the whole session was rejected and a profile with any split — or
     * merely one app on the EXCLUDE lane — could not connect at all. Measured on a Pixel: the
     * include-split leg of LiveApplicationSplitAndroidTest never reached CONNECTED.
     *
     * The include split had a second, independent way of being refused: blocked packages ride
     * inside the include set (their reject rule must be able to see them on a full-device tun), and
     * the inverted rule demanded that nothing had been classified yet — so "split + firewall" was
     * unrepresentable too. Both halves are covered below, because either one alone is enough to
     * make a split unable to connect.
     *
     * So this is not an assertion about JSON shape but about the contract between the two: what the
     * assembler produces for every split mode, the translator must accept.
     */
    /**
     * The same trap one screen over: a DNS bypass emitted with no rule set behind it.
     *
     * `buildDnsRules` used to write the bypass rules whenever filtering was on, while the signed
     * rule-set block needed at least one category enabled. Turn DNS filtering on, turn all four
     * categories off, add one bypass package or domain — a state the settings screen reaches — and
     * the assembler produced `{package_name, action: route, server: dns-remote}` with nothing to
     * be an exception to. FoxCore has no shape for that, so the translator refused the document,
     * and translation happens before the tun exists: the profile did not connect at all.
     *
     * A bypass of nothing means nothing, so it is no longer emitted.
     */
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

        // Throws if the document is not representable — which is how this failed one step before
        // establishTun.
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
                // Full tunnel plus one EXCLUDE-lane app is a split too — and the one a user reaches
                // without ever opening the per-app routing mode.
                "full tunnel with an excluded lane" to
                    Settings(
                        expert =
                        ExpertSettings(
                            perAppRoutingMode = PerAppRoutingMode.FULL_TUNNEL,
                            appAssignments = mapOf(excludedApp to AppTunnelLane.EXCLUDE),
                        ),
                    ),
                // The pair that has to hold together: the firewall's blocked apps ride inside the
                // include set so their reject rule can see them at all.
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
                // No per-app DNS rule may leave the assembler: it is the shape the translator
                // refuses, and the refusal kills the session before the tun exists.
                val assembledDns = parse(assembled).getValue("dns").jsonObject
                assertTrue(
                    "$name: assembler emitted a per-app dns rule again",
                    assembledDns["rules"]
                        ?.jsonArray
                        ?.none { rule -> rule.jsonObject.containsKey("package_name") } ?: true,
                )
                // Throws FoxCoreConfigTranslationException if the config is not representable —
                // which is exactly how this failed on the device, one step before establishTun.
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

        // The split itself survives, in the one place FoxCore keeps it: the traffic policy.
        val include = splitPolicies.getValue("include")
        assertEquals("direct", include.getValue("default_action").jsonPrimitive.content)
        assertEquals("vpn", include.applicationAction(splitApp))

        val exclude = splitPolicies.getValue("exclude")
        assertEquals("vpn", exclude.getValue("default_action").jsonPrimitive.content)
        assertEquals("direct", exclude.applicationAction(splitApp))

        val excludedLane = splitPolicies.getValue("full tunnel with an excluded lane")
        assertEquals("vpn", excludedLane.getValue("default_action").jsonPrimitive.content)
        assertEquals("direct", excludedLane.applicationAction(excludedApp))

        // The blocked app is in the include set and must still come out blocked, not tunnelled.
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

    /** A base profile whose primary outbound the translator can actually carry. */
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
