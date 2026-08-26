package com.foxhole.guard.core.settings

import com.foxhole.core.model.ConnectionSettings
import com.foxhole.core.model.PrivacyRouteMode
import com.foxhole.core.model.PrivacyRouteSettings
import com.foxhole.core.model.Settings
import com.foxhole.core.model.TrafficMode
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyRoutePermissionInvariantTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun `revoking permission atomically disarms the desired TOR route`() {
        val engaged =
            Settings(
                connection = ConnectionSettings(safeModeEnabled = false),
                privacyRoute =
                PrivacyRouteSettings(
                    permitted = true,
                    mode = PrivacyRouteMode.TOR_OVER_VPN,
                ),
                ui = Settings().ui.copy(torEnabledAtMs = 41L),
            )

        val revoked = updatePrivacyRoutePermissionIn(engaged, permitted = false, enabledAtMs = 99L)

        assertFalse(revoked.privacyRoute.permitted)
        assertEquals(PrivacyRouteMode.OFF, revoked.privacyRoute.mode)
        assertEquals(0L, revoked.ui.torEnabledAtMs)
    }

    @Test
    fun `granting permission alone never restores the previous desired route`() {
        val revoked =
            updatePrivacyRoutePermissionIn(
                Settings(
                    connection = ConnectionSettings(safeModeEnabled = false),
                    privacyRoute =
                    PrivacyRouteSettings(
                        permitted = true,
                        mode = PrivacyRouteMode.TOR_OVER_VPN,
                    ),
                ),
                permitted = false,
            )

        val granted = updatePrivacyRoutePermissionIn(revoked, permitted = true, enabledAtMs = 99L)

        assertTrue(granted.privacyRoute.permitted)
        assertEquals(PrivacyRouteMode.OFF, granted.privacyRoute.mode)
        assertFalse(granted.privacyRoute.enabled)
        assertEquals(99L, granted.ui.torEnabledAtMs)
    }

    @Test
    fun `legacy forbidden TOR mode is repaired during hydration`() {
        val invalid =
            Settings(
                connection = ConnectionSettings(safeModeEnabled = false),
                privacyRoute =
                PrivacyRouteSettings(
                    permitted = false,
                    mode = PrivacyRouteMode.TOR_OVER_VPN,
                ),
            )

        val hydrated = json.decodeFromString<Settings>(json.encodeToString(invalid)).normalized()

        assertFalse(hydrated.privacyRoute.permitted)
        assertEquals(PrivacyRouteMode.OFF, hydrated.privacyRoute.mode)
    }

    @Test
    fun `TOR placement changes mode and bypass in one settings transform`() {
        val updated =
            updatePrivacyRouteModeAndBypassVpnTunnelIn(
                current =
                Settings(
                    privacyRoute = PrivacyRouteSettings(permitted = true),
                ),
                mode = PrivacyRouteMode.TOR_OVER_VPN,
                bypassVpnTunnel = true,
            )

        assertEquals(PrivacyRouteMode.TOR_OVER_VPN, updated.privacyRoute.mode)
        assertTrue(updated.privacyRoute.bypassVpnTunnel)
        assertEquals(TrafficMode.TUNNEL, updated.traffic.mode)
    }
}
