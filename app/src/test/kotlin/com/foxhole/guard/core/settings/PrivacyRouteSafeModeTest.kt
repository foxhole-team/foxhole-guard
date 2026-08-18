package com.foxhole.guard.core.settings

import com.foxhole.core.model.PrivacyRouteMode
import com.foxhole.core.model.PrivacyRouteScope
import com.foxhole.core.model.Settings
import com.foxhole.core.model.TorBridgeTransport
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

internal class PrivacyRouteSafeModeTest : SettingsRepositoryTestSupport() {
    private val pristine = Settings().normalized()

    @Test
    fun `fresh install runs in safe mode with the tor route disarmed`() {
        assertTrue(pristine.connection.safeModeEnabled)
        assertEquals(PrivacyRouteMode.OFF, pristine.privacyRoute.mode)
        assertFalse(pristine.privacyRoute.permitted)
    }

    @Test
    fun `disabling bridges survives normalization and a reread on a fresh install`() {
        val stored = updatePrivacyRouteBridgesEnabledIn(pristine, false).normalized()

        assertNotEquals(pristine, stored)
        assertFalse(stored.privacyRoute.bridgesEnabled)
        assertFalse(reread(stored).privacyRoute.bridgesEnabled)
        assertTrue(stored.connection.safeModeEnabled)
    }

    @Test
    fun `bridge transport survives normalization and a reread on a fresh install`() {
        val stored = updatePrivacyRouteBridgeTransportIn(pristine, TorBridgeTransport.OBFS4).normalized()

        assertNotEquals(pristine, stored)
        assertEquals(TorBridgeTransport.OBFS4, stored.privacyRoute.bridgeTransport)
        assertEquals(TorBridgeTransport.OBFS4, reread(stored).privacyRoute.bridgeTransport)
        assertTrue(stored.connection.safeModeEnabled)
    }

    @Test
    fun `bridge auto update survives normalization and a reread on a fresh install`() {
        val stored = updatePrivacyRouteBridgesAutoUpdateIn(pristine, true).normalized()

        assertTrue(stored.privacyRoute.bridgesAutoUpdate)
        assertTrue(reread(stored).privacyRoute.bridgesAutoUpdate)
        assertTrue(stored.connection.safeModeEnabled)
    }

    @Test
    fun `bridge source survives normalization and a reread on a fresh install`() {
        val stored = updatePrivacyRouteBridgesUseFoxholeSourceIn(pristine, true).normalized()

        assertTrue(stored.privacyRoute.bridgesUseFoxholeSource)
        assertTrue(reread(stored).privacyRoute.bridgesUseFoxholeSource)
        assertTrue(stored.connection.safeModeEnabled)
    }

    @Test
    fun `exit rotation and its interval survive normalization and a reread on a fresh install`() {
        val stored =
            updatePrivacyRouteAutoRotateIntervalIn(
                updatePrivacyRouteAutoRotateExitIn(pristine, true),
                30,
            ).normalized()

        assertTrue(stored.privacyRoute.autoRotateExit)
        assertEquals(30, stored.privacyRoute.autoRotateIntervalMinutes)
        val restored = reread(stored)
        assertTrue(restored.privacyRoute.autoRotateExit)
        assertEquals(30, restored.privacyRoute.autoRotateIntervalMinutes)
        assertTrue(stored.connection.safeModeEnabled)
    }

    @Test
    fun `block apps without tor survives an empty tor lane`() {
        val stored = updatePrivacyRouteBlockAppsWhenTorUnavailableIn(pristine, true).normalized()

        assertTrue(stored.privacyRoute.blockAppsWhenTorUnavailable)
        assertTrue(reread(stored).privacyRoute.blockAppsWhenTorUnavailable)
    }

    @Test
    fun `safe mode still disarms everything that engages the tor lane`() {
        val engaged =
            pristine.copy(
                privacyRoute =
                pristine.privacyRoute.copy(
                    mode = PrivacyRouteMode.TOR_OVER_VPN,
                    permitted = true,
                    bypassVpnTunnel = true,
                    scope = PrivacyRouteScope.SELECTED_APPS,
                ),
            ).normalized()

        assertEquals(PrivacyRouteMode.OFF, engaged.privacyRoute.mode)
        assertFalse(engaged.privacyRoute.permitted)
        assertFalse(engaged.privacyRoute.bypassVpnTunnel)
        assertEquals(PrivacyRouteScope.ALL_APPS, engaged.privacyRoute.scope)
    }

    @Test
    fun `disarming the route does not take the tuning fields with it`() {
        val stored =
            updatePrivacyRouteBridgesEnabledIn(pristine, false)
                .let { settings -> updatePrivacyRouteAutoRotateIntervalIn(settings, 45) }
                .let { settings ->
                    settings.copy(privacyRoute = settings.privacyRoute.copy(mode = PrivacyRouteMode.TOR_OVER_VPN))
                }
                .normalized()

        assertEquals(PrivacyRouteMode.OFF, stored.privacyRoute.mode)
        assertFalse(stored.privacyRoute.bridgesEnabled)
        assertEquals(45, stored.privacyRoute.autoRotateIntervalMinutes)
    }

    @Test
    fun `leaving safe mode keeps the tuning fields untouched`() {
        val stored = updatePrivacyRouteAutoRotateIntervalIn(pristine, 60).normalized()
        val unlocked = stored.copy(connection = stored.connection.copy(safeModeEnabled = false)).normalized()

        assertEquals(60, unlocked.privacyRoute.autoRotateIntervalMinutes)
        assertEquals(60, reread(unlocked).privacyRoute.autoRotateIntervalMinutes)
    }

    private fun reread(value: Settings): Settings =
        json.decodeFromString<Settings>(json.encodeToString(value)).normalized()
}
