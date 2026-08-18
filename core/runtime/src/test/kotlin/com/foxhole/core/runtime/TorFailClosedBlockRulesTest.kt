package com.foxhole.core.runtime

import com.foxhole.core.model.AppTunnelLane
import com.foxhole.core.model.ExpertSettings
import com.foxhole.core.model.PrivacyRouteScope
import com.foxhole.core.model.PrivacyRouteSettings
import com.foxhole.core.model.Settings
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

internal class TorFailClosedBlockRulesTest {
    private fun settings(
        torApps: List<String>,
        blockWithoutTor: Boolean,
    ): Settings = laneSettings(torApps.associateWith { AppTunnelLane.TOR }, blockWithoutTor)

    private fun laneSettings(
        assignments: Map<String, AppTunnelLane>,
        blockWithoutTor: Boolean,
    ): Settings =
        Settings(
            expert = ExpertSettings(appAssignments = assignments),
            privacyRoute =
            PrivacyRouteSettings(
                scope = PrivacyRouteScope.SELECTED_APPS,
                blockAppsWhenTorUnavailable = blockWithoutTor,
            ),
        )

    private fun rejectedPackages(
        settings: Settings,
        torLaneCarried: Boolean,
        vpnLaneCarried: Boolean,
    ): List<String> =
        buildFailClosedBlockRules(settings, torLaneCarried, vpnLaneCarried)
            .singleOrNull()
            ?.jsonObject
            ?.get("package_name")
            ?.jsonArray
            ?.map { it.jsonPrimitive.content }
            .orEmpty()

    @Test
    fun `armed guard rejects tor-lane apps while the route is off`() {
        val settings = settings(listOf("com.app.tor"), blockWithoutTor = true)
        val rules = buildFailClosedBlockRules(settings, torLaneCarried = false, vpnLaneCarried = true)

        val rule = rules.single().jsonObject
        assertEquals("reject", rule["action"]!!.jsonPrimitive.content)
        assertEquals(
            listOf("com.app.tor"),
            rule["package_name"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `no reject rule while the tor route is engaged`() {
        val settings = settings(listOf("com.app.tor"), blockWithoutTor = true)
        assertTrue(
            buildFailClosedBlockRules(settings, torLaneCarried = true, vpnLaneCarried = true).isEmpty(),
        )
    }

    @Test
    fun `no reject rule when the guard is disarmed`() {
        val settings = settings(listOf("com.app.tor"), blockWithoutTor = false)
        assertTrue(
            buildFailClosedBlockRules(settings, torLaneCarried = false, vpnLaneCarried = false).isEmpty(),
        )
    }

    @Test
    fun `no reject rule when no app is pinned to a tunnel lane`() {
        val settings = settings(emptyList(), blockWithoutTor = true)
        assertTrue(
            buildFailClosedBlockRules(settings, torLaneCarried = false, vpnLaneCarried = false).isEmpty(),
        )
    }

    @Test
    fun `an uncarried vpn lane is rejected whether or not tor is engaged`() {
        val settings =
            laneSettings(
                mapOf(
                    "com.app.tor" to AppTunnelLane.TOR,
                    "com.app.vpn" to AppTunnelLane.VPN,
                    "com.app.excluded" to AppTunnelLane.EXCLUDE,
                    "com.app.blocked" to AppTunnelLane.BLOCK,
                ),
                blockWithoutTor = true,
            )

        assertEquals(
            listOf("com.app.vpn"),
            rejectedPackages(settings, torLaneCarried = true, vpnLaneCarried = false),
        )
        assertEquals(
            listOf("com.app.tor", "com.app.vpn"),
            rejectedPackages(settings, torLaneCarried = false, vpnLaneCarried = false),
        )
    }

    @Test
    fun `a carried vpn lane survives the tor lane being blocked`() {
        val settings =
            laneSettings(
                mapOf(
                    "com.app.tor" to AppTunnelLane.TOR,
                    "com.app.vpn" to AppTunnelLane.VPN,
                    "com.app.excluded" to AppTunnelLane.EXCLUDE,
                    "com.app.blocked" to AppTunnelLane.BLOCK,
                ),
                blockWithoutTor = true,
            )

        assertEquals(
            listOf("com.app.tor"),
            rejectedPackages(settings, torLaneCarried = false, vpnLaneCarried = true),
        )
    }

    @Test
    fun `armed guard rejects vpn-selected apps when no app is pinned to tor`() {
        val settings =
            laneSettings(mapOf("com.app.vpn" to AppTunnelLane.VPN), blockWithoutTor = true)

        assertEquals(
            listOf("com.app.vpn"),
            rejectedPackages(settings, torLaneCarried = false, vpnLaneCarried = false),
        )
        assertTrue(
            buildFailClosedBlockRules(settings, torLaneCarried = false, vpnLaneCarried = true).isEmpty(),
        )
    }
}
