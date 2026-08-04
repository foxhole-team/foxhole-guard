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

/**
 * "Block without Tor": Tor-lane apps must lose the network entirely while the Tor route is not
 * engaged and the guard is armed — never leak to the plain VPN/direct path. When the route IS
 * engaged the apps route through Tor, so no reject rule is emitted.
 */
internal class TorFailClosedBlockRulesTest {
    private fun settings(
        torApps: List<String>,
        blockWithoutTor: Boolean,
    ): Settings =
        Settings(
            expert = ExpertSettings(appAssignments = torApps.associateWith { AppTunnelLane.TOR }),
            privacyRoute =
            PrivacyRouteSettings(
                scope = PrivacyRouteScope.SELECTED_APPS,
                blockAppsWhenTorUnavailable = blockWithoutTor,
            ),
        )

    @Test
    fun `armed guard rejects tor-lane apps while the route is off`() {
        val settings = settings(listOf("com.app.tor"), blockWithoutTor = true)
        val rules = buildTorFailClosedBlockRules(settings, privacyRouteActive = false)

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
        assertTrue(buildTorFailClosedBlockRules(settings, privacyRouteActive = true).isEmpty())
    }

    @Test
    fun `no reject rule when the guard is disarmed`() {
        val settings = settings(listOf("com.app.tor"), blockWithoutTor = false)
        assertTrue(buildTorFailClosedBlockRules(settings, privacyRouteActive = false).isEmpty())
    }

    @Test
    fun `no reject rule when no app is pinned to tor`() {
        val settings = settings(emptyList(), blockWithoutTor = true)
        assertTrue(buildTorFailClosedBlockRules(settings, privacyRouteActive = false).isEmpty())
    }
}
