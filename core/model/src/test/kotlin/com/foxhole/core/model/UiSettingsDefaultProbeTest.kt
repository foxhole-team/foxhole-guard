package com.foxhole.core.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiSettingsDefaultProbeTest {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Test
    fun `missing showTorDashboardControls decodes to true`() {
        val decoded = json.decodeFromString(Settings.serializer(), """{"ui":{}}""")
        assertTrue(decoded.ui.showTorDashboardControls)
    }

    @Test
    fun `constructor default is true`() {
        assertTrue(UiSettings().showTorDashboardControls)
    }

    @Test
    fun `missing interface fields decode to the dashboard defaults`() {
        val decoded = json.decodeFromString(Settings.serializer(), """{"ui":{}}""")
        assertEquals(PanelAppearance.STANDARD, decoded.ui.panelAppearance)
        assertFalse(decoded.ui.mapWidgetMapOnRight)
        // Quick access ships ON: the status card lays out with the panel from the first frame.
        assertTrue(decoded.ui.showQuickAccessPanel)
        // Widget reordering ships ON now; the settings row lets you lock the arrangement instead.
        assertTrue(decoded.ui.layoutEditingEnabled)
        assertFalse(decoded.ui.interfaceSettingsExpanded)
        // Enable-order stamps start unstamped (0 = off / pre-stamp).
        assertTrue(decoded.ui.torEnabledAtMs == 0L)
        assertTrue(decoded.ui.i2pEnabledAtMs == 0L)
    }

    @Test
    fun `dropped tor and i2p settings hidden keys are ignored on load`() {
        // The "hide TOR/I2P settings" cosmetic toggles are retired; stored JSON still loads.
        val decoded =
            json.decodeFromString(
                Settings.serializer(),
                """{"ui":{"torSettingsHidden":true,"i2pSettingsHidden":true}}""",
            )
        assertFalse(decoded.ui.trafficMapEnabled)
    }

    @Test
    fun `dropped assistant and status keys are ignored on load`() {
        // The fox assistant and the merged status/map mode are retired; old JSON with their keys
        // (including the historical mapFoxEnabled name) still loads.
        val decoded =
            json.decodeFromString(
                Settings.serializer(),
                """{"ui":{"mapFoxEnabled":false,"statusCardSeparate":true}}""",
            )
        assertFalse(decoded.ui.trafficMapEnabled)
    }

    @Test
    fun `dropped mapCountriesHidden key is ignored on load`() {
        val decoded =
            json.decodeFromString(Settings.serializer(), """{"ui":{"mapCountriesHidden":true}}""")
        assertFalse(decoded.ui.trafficMapEnabled)
    }

    @Test
    fun `dropped living background keys are ignored on load`() {
        // Installs upgrading from builds that shipped the living background / connection breath
        // still carry these keys in the stored JSON.
        val decoded =
            json.decodeFromString(
                Settings.serializer(),
                """{"ui":{"livingBackground":"AURORA","backgroundBreathEnabled":true}}""",
            )
        assertFalse(decoded.ui.trafficMapEnabled)
    }
}
