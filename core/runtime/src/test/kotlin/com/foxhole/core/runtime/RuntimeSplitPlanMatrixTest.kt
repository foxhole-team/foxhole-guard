package com.foxhole.core.runtime

import com.foxhole.core.model.AppTunnelLane
import com.foxhole.core.model.ExpertSettings
import com.foxhole.core.model.PerAppRoutingMode
import com.foxhole.core.model.PrivacyRouteScope
import com.foxhole.core.model.PrivacyRouteSettings
import com.foxhole.core.model.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

internal class RuntimeSplitPlanMatrixTest {
    private fun settings(
        mode: PerAppRoutingMode = PerAppRoutingMode.FULL_TUNNEL,
        assignments: Map<String, AppTunnelLane> = emptyMap(),
        blockedEnabled: Boolean = false,
        torScope: PrivacyRouteScope = PrivacyRouteScope.ALL_APPS,
    ): Settings =
        Settings(
            expert =
            ExpertSettings(
                perAppRoutingMode = mode,
                appAssignments = assignments,
                blockedPackagesEnabled = blockedEnabled,
            ),
            privacyRoute = PrivacyRouteSettings(scope = torScope),
        )

    @Test
    fun `include split carries this package even with web apps off`() {
        val plan =
            buildSplitPlan(
                settings(
                    mode = PerAppRoutingMode.INCLUDE_SELECTED_APPS,
                    assignments = mapOf("com.other.app" to AppTunnelLane.VPN),
                ),
                privacyRouteActive = false,
                selfPackageName = "com.foxhole.guard",
            )

        assertEquals(listOf("com.foxhole.guard", "com.other.app"), plan.vpnIncludedPackages)
    }

    @Test
    fun `an empty include split stays empty rather than tunnelling this package alone`() {
        val plan =
            buildSplitPlan(
                settings(mode = PerAppRoutingMode.INCLUDE_SELECTED_APPS),
                privacyRouteActive = false,
                selfPackageName = "com.foxhole.guard",
            )

        assertTrue(plan.vpnIncludedPackages.isEmpty())
    }

    @Test
    fun `full tunnel without tor is the whole-device plan`() {
        val plan = buildSplitPlan(settings(), privacyRouteActive = false)

        assertEquals(VpnAppSelectionMode.FULL_DEVICE, plan.vpnMode)
        assertTrue(plan.vpnIncludedPackages.isEmpty())
        assertTrue(plan.vpnExcludedPackages.isEmpty())
        assertFalse(plan.torAllApps)
        assertTrue(plan.torTcpPackages.isEmpty())
        assertTrue(plan.warnings.isEmpty())
    }

    @Test
    fun `include split routes only the selected apps`() {
        val plan =
            buildSplitPlan(
                settings(
                    mode = PerAppRoutingMode.INCLUDE_SELECTED_APPS,
                    assignments = mapOf("com.app.a" to AppTunnelLane.VPN),
                ),
                privacyRouteActive = false,
            )

        assertEquals(VpnAppSelectionMode.INCLUDE_ONLY, plan.vpnMode)
        assertEquals(listOf("com.app.a"), plan.vpnIncludedPackages)
        assertTrue(plan.vpnExcludedPackages.isEmpty())
    }

    @Test
    fun `exclude split keeps the selected apps out of the tunnel`() {
        val plan =
            buildSplitPlan(
                settings(
                    mode = PerAppRoutingMode.EXCLUDE_SELECTED_APPS,
                    assignments = mapOf("com.app.a" to AppTunnelLane.VPN),
                ),
                privacyRouteActive = false,
            )

        assertEquals(VpnAppSelectionMode.EXCLUDE_SELECTED, plan.vpnMode)
        assertEquals(listOf("com.app.a"), plan.vpnExcludedPackages)
        assertTrue(plan.vpnIncludedPackages.isEmpty())
    }

    @Test
    fun `whole-device tor rides the full tunnel`() {
        val plan =
            buildSplitPlan(
                settings(torScope = PrivacyRouteScope.ALL_APPS),
                privacyRouteActive = true,
            )

        assertTrue(plan.torAllApps)
        assertTrue(plan.torTcpPackages.isEmpty())
        assertEquals(VpnAppSelectionMode.FULL_DEVICE, plan.vpnMode)
    }

    @Test
    fun `selected-apps tor joins the include split and forces the tun to carry it`() {
        val plan =
            buildSplitPlan(
                settings(
                    mode = PerAppRoutingMode.INCLUDE_SELECTED_APPS,
                    assignments =
                    mapOf(
                        "com.app.vpn" to AppTunnelLane.VPN,
                        "com.app.tor" to AppTunnelLane.TOR,
                    ),
                    torScope = PrivacyRouteScope.SELECTED_APPS,
                ),
                privacyRouteActive = true,
            )

        assertFalse(plan.torAllApps)
        assertEquals(listOf("com.app.tor"), plan.torTcpPackages)
        assertEquals(listOf("com.app.tor", "com.app.vpn"), plan.vpnIncludedPackages)
    }

    @Test
    fun `selected-apps tor is pulled out of the exclude set so its traffic stays in the tun`() {
        val plan =
            buildSplitPlan(
                settings(
                    mode = PerAppRoutingMode.EXCLUDE_SELECTED_APPS,
                    assignments = mapOf("com.app.tor" to AppTunnelLane.TOR),
                    torScope = PrivacyRouteScope.SELECTED_APPS,
                ),
                privacyRouteActive = true,
            )

        assertEquals(listOf("com.app.tor"), plan.torTcpPackages)
        assertFalse("com.app.tor" in plan.vpnExcludedPackages)
        assertTrue(plan.warnings.contains(SplitWarning.TOR_SELECTED_APPS_FORCE_TUN_INCLUDE))
    }

    @Test
    fun `blocked lane feeds the firewall only while block enforcement is armed`() {
        val armed =
            buildSplitPlan(
                settings(
                    assignments = mapOf("com.app.bad" to AppTunnelLane.BLOCK),
                    blockedEnabled = true,
                ),
                privacyRouteActive = false,
            )
        val disarmed =
            buildSplitPlan(
                settings(
                    assignments = mapOf("com.app.bad" to AppTunnelLane.BLOCK),
                    blockedEnabled = false,
                ),
                privacyRouteActive = false,
            )

        assertEquals(listOf("com.app.bad"), armed.blockedPackages)
        assertTrue(disarmed.blockedPackages.isEmpty())
    }

    @Test
    fun `tor stays inert while the privacy route is off regardless of scope and lanes`() {
        val plan =
            buildSplitPlan(
                settings(
                    assignments = mapOf("com.app.tor" to AppTunnelLane.TOR),
                    torScope = PrivacyRouteScope.SELECTED_APPS,
                ),
                privacyRouteActive = false,
            )

        assertFalse(plan.torAllApps)
        assertTrue(plan.torTcpPackages.isEmpty())
    }
}
