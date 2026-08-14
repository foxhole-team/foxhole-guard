package com.foxhole.core.runtime

import com.foxhole.core.model.AppTunnelLane
import com.foxhole.core.model.ExpertSettings
import com.foxhole.core.model.PerAppRoutingMode
import com.foxhole.core.model.PrivacyRouteMode
import com.foxhole.core.model.PrivacyRouteScope
import com.foxhole.core.model.PrivacyRouteSettings
import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.Settings
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// A2: one app per lane, rolled through every split mode. The tun inbound stays full-device
// (Ф-ГА2); lane semantics live entirely in route/dns rules: TOR rides the tor outbound, VPN rides
// the tunnel, BLOCK is denied, EXCLUDE goes direct (outside the tunnel path and DNS filtering).
internal class RuntimeConfigAssemblerLanesTest : RuntimeConfigAssemblerTestSupport() {
    private val torApp = "app.lane.tor"
    private val vpnApp = "app.lane.vpn"
    private val blockedApp = "app.lane.blocked"
    private val excludedApp = "app.lane.excluded"

    private fun settings(mode: PerAppRoutingMode): Settings =
        Settings(
            privacyRoute =
            PrivacyRouteSettings(
                mode = PrivacyRouteMode.TOR_OVER_VPN,
                scope = PrivacyRouteScope.SELECTED_APPS,
            ),
            expert =
            ExpertSettings(
                perAppRoutingMode = mode,
                blockedPackagesEnabled = true,
                appAssignments =
                mapOf(
                    torApp to AppTunnelLane.TOR,
                    vpnApp to AppTunnelLane.VPN,
                    blockedApp to AppTunnelLane.BLOCK,
                    excludedApp to AppTunnelLane.EXCLUDE,
                ),
            ),
        )

    private fun assembleConfig(mode: PerAppRoutingMode): JsonObject =
        parse(
            assembler.assemble(
                baseConfigJson = baseConfigWithRules("profile.example"),
                settings = settings(mode),
                activePreset = null,
                torRuntimePaths =
                TorRuntimePaths(
                    dataDirectory = "/tor-data",
                ),
                vpnProtocolHint = ProtocolHint.VLESS,
            ),
        )

    private fun packageRules(config: JsonObject): List<JsonObject> =
        config["route"]!!.jsonObject["rules"]!!.jsonArray
            .map { it.jsonObject }
            .filter { it.containsKey("package_name") }

    private fun rulesFor(config: JsonObject, packageName: String): List<JsonObject> =
        packageRules(config).filter { rule ->
            rule["package_name"]!!.jsonArray.any { it.jsonPrimitive.content == packageName }
        }

    private fun outbounds(rules: List<JsonObject>): Set<String> =
        rules.mapNotNull { it["outbound"]?.jsonPrimitive?.content }.toSet()

    @Test
    fun `full tunnel - lanes map to block, tor and direct rules with a full-device tun`() {
        val config = assembleConfig(PerAppRoutingMode.FULL_TUNNEL)
        val tunInbound = config["inbounds"]!!.jsonArray.first().jsonObject

        assertFalse(tunInbound.containsKey("include_package"))
        assertFalse(tunInbound.containsKey("exclude_package"))

        assertTrue(outbounds(rulesFor(config, blockedApp)).contains("block"))
        assertTrue(outbounds(rulesFor(config, torApp)).contains(TOR_OVER_VPN_OUTBOUND_TAG))
        assertTrue(outbounds(rulesFor(config, excludedApp)).contains("direct"))
        // The VPN lane is the ordinary tunnelled app: no dedicated rule, it rides the final route.
        assertTrue(rulesFor(config, vpnApp).isEmpty())

        // The block rule is ordered first so a blocked app cannot leak through hijack-dns.
        val firstPackageRule = packageRules(config).first()
        assertTrue(
            firstPackageRule["package_name"]!!.jsonArray.any { it.jsonPrimitive.content == blockedApp },
        )
    }

    // The excluded lane used to carry its own "resolve on dns-direct" rule. FoxCore has ONE
    // interceptor with ONE upstream lane, so per-app DNS is not something the engine can be told —
    // `FoxCorePolicyTranslator` refuses the shape, and while the assembler still wrote it EVERY
    // config with an excluded (or included) app was rejected before the tun was built, so the
    // profile could not connect at all. The lane still leaves the tunnel; only its DNS does not.
    @Test
    fun `full tunnel - excluded lane carries no per-app dns rule`() {
        val config = assembleConfig(PerAppRoutingMode.FULL_TUNNEL)
        val dnsRules =
            config["dns"]!!.jsonObject["rules"]?.jsonArray?.map { it.jsonObject }.orEmpty()

        assertTrue(dnsRules.none { rule -> rule.containsKey("package_name") })
        // ...and the lane is still outside the tunnel, in the half FoxCore does understand.
        assertTrue(outbounds(rulesFor(config, excludedApp)).contains("direct"))
    }

    @Test
    fun `exclude split - tunnel selection leaves the tunnel but the tor lane keeps riding tor`() {
        val config = assembleConfig(PerAppRoutingMode.EXCLUDE_SELECTED_APPS)

        assertTrue(outbounds(rulesFor(config, vpnApp)).contains("direct"))
        assertTrue(outbounds(rulesFor(config, excludedApp)).contains("direct"))
        assertTrue(outbounds(rulesFor(config, torApp)).contains(TOR_OVER_VPN_OUTBOUND_TAG))
        assertFalse(outbounds(rulesFor(config, torApp)).contains("direct"))
        assertTrue(outbounds(rulesFor(config, blockedApp)).contains("block"))
    }

    @Test
    fun `include split - tor and vpn lanes ride the tunnel, block stays denied`() {
        val config = assembleConfig(PerAppRoutingMode.INCLUDE_SELECTED_APPS)

        assertTrue(outbounds(rulesFor(config, torApp)).contains(TOR_OVER_VPN_OUTBOUND_TAG))
        assertTrue(outbounds(rulesFor(config, blockedApp)).contains("block"))
        // The include split is expressed as an inverted direct rule over the included set: the VPN
        // lane must be part of that set (and thus NOT direct-routed itself).
        val invertedIncludeRules =
            packageRules(config).filter { rule ->
                rule["invert"]?.jsonPrimitive?.content == "true" &&
                    rule["outbound"]?.jsonPrimitive?.content == "direct"
            }
        assertEquals(1, invertedIncludeRules.size)
        assertTrue(
            invertedIncludeRules.single()["package_name"]!!.jsonArray.any {
                it.jsonPrimitive.content == vpnApp
            },
        )
        // The excluded lane is simply not part of the include set.
        assertFalse(
            invertedIncludeRules.single()["package_name"]!!.jsonArray.any {
                it.jsonPrimitive.content == excludedApp
            },
        )
    }
}
