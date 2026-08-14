package com.foxhole.core.runtime

import com.foxhole.core.model.AppTunnelLane
import com.foxhole.core.model.ExpertSettings
import com.foxhole.core.model.PerAppRoutingMode
import com.foxhole.core.model.PrivacyRouteMode
import com.foxhole.core.model.PrivacyRouteScope
import com.foxhole.core.model.PrivacyRouteSettings
import com.foxhole.core.model.Settings
import com.foxhole.core.model.WebAppsSettings
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

internal class RuntimeWebAppsTunIsolationTest : RuntimeConfigAssemblerTestSupport() {
    private val debugPackage = "com.foxhole.guard.debug"
    private val variantAssembler = RuntimeConfigAssembler(json, selfPackageName = debugPackage)

    @Test
    fun `local guard captures FoxHole uid while web apps are enabled`() {
        val config =
            parse(
                variantAssembler.assembleLocalGuard(
                    Settings(
                        expert = ExpertSettings(firewallEnabled = true),
                        webApps = WebAppsSettings(enabled = true),
                    ),
                    LocalGuardMode.FIREWALL,
                ),
            )

        val tun = config["inbounds"]!!.jsonArray.first().jsonObject
        assertFalse(tun.containsKey("exclude_package"))
    }

    @Test
    fun `tor only all apps captures FoxHole uid while web apps are enabled`() {
        val tun = torOnlyTun(PrivacyRouteScope.ALL_APPS, emptyMap())

        assertFalse(tun.containsKey("include_package"))
        assertFalse(tun.containsKey("exclude_package"))
    }

    @Test
    fun `tor only selected apps adds FoxHole uid to the selected capture`() {
        val tun =
            torOnlyTun(
                PrivacyRouteScope.SELECTED_APPS,
                mapOf("org.mozilla.firefox" to AppTunnelLane.TOR),
            )

        assertEquals(
            listOf(debugPackage, "org.mozilla.firefox"),
            tun["include_package"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertFalse(tun.containsKey("exclude_package"))
    }

    @Test
    fun `profile include split keeps FoxHole webviews inside the vpn`() {
        val settings =
            Settings(
                expert =
                ExpertSettings(
                    perAppRoutingMode = PerAppRoutingMode.INCLUDE_SELECTED_APPS,
                    appAssignments = mapOf("com.example.browser" to AppTunnelLane.VPN),
                ),
                webApps = WebAppsSettings(enabled = true),
            )
        val plan =
            buildSplitPlan(
                settings = settings,
                privacyRouteActive = false,
                selfPackageName = debugPackage,
            )

        assertEquals(
            listOf("com.example.browser", debugPackage),
            plan.vpnIncludedPackages,
        )
    }

    @Test
    fun `profile exclude split cannot exclude FoxHole while web apps are enabled`() {
        val settings =
            Settings(
                expert =
                ExpertSettings(
                    perAppRoutingMode = PerAppRoutingMode.EXCLUDE_SELECTED_APPS,
                    appAssignments =
                    mapOf(
                        debugPackage to AppTunnelLane.VPN,
                        "com.example.direct" to AppTunnelLane.VPN,
                    ),
                ),
                webApps = WebAppsSettings(enabled = true),
            )
        val plan =
            buildSplitPlan(
                settings = settings,
                privacyRouteActive = false,
                selfPackageName = debugPackage,
            )

        assertEquals(listOf("com.example.direct"), plan.vpnExcludedPackages)
    }

    @Test
    fun `web apps capture participates in the applied runtime fingerprint`() {
        val disabled = variantAssembler.runtimeFingerprint(Settings(), activePreset = null)
        val enabled =
            variantAssembler.runtimeFingerprint(
                Settings(webApps = WebAppsSettings(enabled = true)),
                activePreset = null,
            )

        assertNotEquals(disabled, enabled)
    }

    private fun torOnlyTun(
        scope: PrivacyRouteScope,
        assignments: Map<String, AppTunnelLane>,
    ): kotlinx.serialization.json.JsonObject {
        val settings =
            Settings(
                privacyRoute =
                PrivacyRouteSettings(
                    mode = PrivacyRouteMode.TOR_OVER_VPN,
                    scope = scope,
                ),
                expert = ExpertSettings(appAssignments = assignments),
                webApps = WebAppsSettings(enabled = true),
            )
        return parse(
            variantAssembler.assembleTorOnly(
                settings = settings,
                activePreset = null,
                torRuntimePaths = TorRuntimePaths(dataDirectory = "/tor-data"),
            ),
        )["inbounds"]!!.jsonArray.first().jsonObject
    }
}
