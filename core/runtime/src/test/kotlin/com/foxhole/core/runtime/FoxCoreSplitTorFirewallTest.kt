package com.foxhole.core.runtime

import com.foxhole.core.model.AppTunnelLane
import com.foxhole.core.model.ExpertSettings
import com.foxhole.core.model.PerAppRoutingMode
import com.foxhole.core.model.PrivacyRouteMode
import com.foxhole.core.model.PrivacyRouteScope
import com.foxhole.core.model.PrivacyRouteSettings
import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.Settings
import com.foxhole.core.model.VpnSession
import com.foxhole.core.model.withLane
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Split, the Tor lane and the firewall enabled together: reported from the device as a config the
 * core refuses. With the fail-closed Tor lane armed, the Tor packages are rejected outright, so no
 * split rule may name them — in EXCLUDE mode the selected apps ARE the excluded set, which put one
 * package under both a reject and a direct-route rule (policy_unrepresentable).
 */
internal class FoxCoreSplitTorFirewallTest : RuntimeConfigAssemblerTestSupport() {
    private val translator = FoxCoreConfigTranslator()

    private fun settingsWithAllThree(
        routingMode: PerAppRoutingMode,
        torEngaged: Boolean,
        failClosed: Boolean,
    ): Settings =
        Settings(
            expert =
            ExpertSettings(
                perAppRoutingMode = routingMode,
                blockedPackagesEnabled = true,
            )
                .withLane(AppTunnelLane.TOR, listOf("com.example.tor"))
                .withLane(AppTunnelLane.VPN, listOf("com.example.vpn"))
                .withLane(AppTunnelLane.BLOCK, listOf("com.example.blocked"))
                .withLane(AppTunnelLane.EXCLUDE, listOf("com.example.excluded")),
            privacyRoute =
            PrivacyRouteSettings(
                mode = if (torEngaged) PrivacyRouteMode.TOR_OVER_VPN else PrivacyRouteMode.OFF,
                scope = PrivacyRouteScope.SELECTED_APPS,
                blockAppsWhenTorUnavailable = failClosed,
            ),
        )

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

    private fun translate(settings: Settings) {
        val legacy = assembler.assemble(baseConfigJson = vlessBaseConfig(), settings = settings, activePreset = null)
        translator.translate(
            VpnSession(
                profileId = 1L,
                profileName = "split+tor+firewall",
                protocolHint = ProtocolHint.VLESS,
                configJson = legacy,
                correlationId = "split-tor-firewall",
            ),
        )
    }

    /** Tor disengaged with fail-closed on: the Tor lane is rejected while the split still names apps. */
    @Test
    fun `fail-closed tor lane beside include split translates`() {
        translate(settingsWithAllThree(PerAppRoutingMode.INCLUDE_SELECTED_APPS, torEngaged = false, failClosed = true))
    }

    @Test
    fun `fail-closed tor lane beside exclude split translates`() {
        translate(settingsWithAllThree(PerAppRoutingMode.EXCLUDE_SELECTED_APPS, torEngaged = false, failClosed = true))
    }

    /**
     * Both reaches on «selected apps» at once — the pairing the beta notice listed as broken.
     * The Tor app must reach Tor while the VPN split still carries its own selection, and no
     * package may be named by two SPECIFIC rules (that config the core refuses outright).
     */
    @Test
    fun `include split beside a selected-apps tor lane routes each lane to its own outbound`() {
        val settings =
            settingsWithAllThree(PerAppRoutingMode.INCLUDE_SELECTED_APPS, torEngaged = true, failClosed = false)
        val rules = assembledRouteRules(settings)

        assertEquals(
            listOf(TOR_OVER_VPN_OUTBOUND_TAG),
            rules.filter { it.packages() == listOf("com.example.tor") && it.string("network") == "tcp" }
                .map { it.string("outbound") },
        )
        // The VPN split is the inverted catch-all, so the Tor package keeps the verdict the tor
        // rule above already gave it instead of colliding with a second specific rule.
        val vpnSplitRule = rules.single { it.string("outbound") == "direct" && it["invert"] != null }
        assertTrue("com.example.tor" in vpnSplitRule.packages())
        assertTrue("com.example.vpn" in vpnSplitRule.packages())
        assertEquals(emptyList<String>(), duplicatePackagesAcrossSpecificRules(rules))
    }

    @Test
    fun `translated include split does not overwrite the tor lane with a vpn application verdict`() {
        val settings =
            settingsWithAllThree(PerAppRoutingMode.INCLUDE_SELECTED_APPS, torEngaged = true, failClosed = false)
        val assembled =
            parse(
                assembler.assemble(
                    baseConfigJson = vlessBaseConfig(),
                    settings = settings,
                    activePreset = null,
                    vpnProtocolHint = ProtocolHint.VLESS,
                ),
            )
        val policy =
            FoxCorePolicyTranslator.translate(
                root = assembled,
                primaryIsPacketTunnel = false,
                overlays = setOf(FoxCoreOverlay.TOR),
                expectedRevision = null,
            ).document
        val applications =
            policy["traffic"]!!
                .jsonObject["applications"]!!
                .jsonArray
                .associate { entry ->
                    entry.jsonObject["package"]!!.jsonPrimitive.content to
                        entry.jsonObject["action"]!!.jsonPrimitive.content
                }
        val torRoutes =
            policy["routes"]!!
                .jsonArray
                .map { it.jsonObject }
                .filter { route -> route["package"]?.jsonPrimitive?.content == "com.example.tor" }

        assertEquals("vpn", applications["com.example.vpn"])
        assertFalse(applications.containsKey("com.example.tor"))
        assertEquals(setOf("tcp", "udp"), torRoutes.map { it["transport"]!!.jsonPrimitive.content }.toSet())
    }

    @Test
    fun `exclude split beside a selected-apps tor lane keeps the tor app inside the tunnel`() {
        val settings =
            settingsWithAllThree(PerAppRoutingMode.EXCLUDE_SELECTED_APPS, torEngaged = true, failClosed = false)
        val rules = assembledRouteRules(settings)

        // EXCLUDE means «selected apps leave the tunnel», but a Tor app has to stay in it to reach
        // Tor at all, so buildSplitPlan subtracts it from the excluded set.
        val excludeRule =
            rules.single { rule ->
                rule.string("outbound") == "direct" && rule["invert"] == null && rule.packages().isNotEmpty()
            }
        assertTrue("com.example.tor" !in excludeRule.packages())
        assertTrue("com.example.vpn" in excludeRule.packages())
        assertEquals(emptyList<String>(), duplicatePackagesAcrossSpecificRules(rules))
    }

    private fun assembledRouteRules(settings: Settings): List<JsonObject> =
        json
            .parseToJsonElement(
                assembler.assemble(
                    baseConfigJson = vlessBaseConfig(),
                    settings = settings,
                    activePreset = null,
                    vpnProtocolHint = ProtocolHint.VLESS,
                ),
            )
            .jsonObject["route"]
            ?.jsonObject
            ?.get("rules")
            ?.jsonArray
            ?.map { it.jsonObject }
            .orEmpty()

    /** Packages named by more than one non-inverted rule — what makes a config unrepresentable. */
    private fun duplicatePackagesAcrossSpecificRules(rules: List<JsonObject>): List<String> =
        rules
            .filter { it["invert"] == null }
            .flatMap { rule -> rule.packages().map { packageName -> packageName to rule.string("network") } }
            .groupBy { (packageName, network) -> packageName to network }
            .filterValues { it.size > 1 }
            .keys
            .map { (packageName, _) -> packageName }

    private fun JsonObject.packages(): List<String> =
        this["package_name"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
}
