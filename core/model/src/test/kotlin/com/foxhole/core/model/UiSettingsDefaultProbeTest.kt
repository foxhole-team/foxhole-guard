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
        assertEquals(PanelAppearance.AUTO, decoded.ui.panelAppearance)
        assertFalse(decoded.ui.mapWidgetMapOnRight)
        // Quick access ships ON: the status card lays out with the panel from the first frame.
        assertTrue(decoded.ui.showQuickAccessPanel)
        // Widget reordering ships ON now; the settings row lets you lock the arrangement instead.
        assertTrue(decoded.ui.layoutEditingEnabled)
        assertFalse(decoded.ui.interfaceSettingsExpanded)
        assertFalse(decoded.ui.blurEffectsEnabled)
        // Enable-order stamps start unstamped (0 = off / pre-stamp).
        assertTrue(decoded.ui.torEnabledAtMs == 0L)
        assertTrue(decoded.ui.i2pEnabledAtMs == 0L)
    }

    @Test
    fun `expensive diagnostics are opt in`() {
        assertFalse(Settings().expert.networkActivityLogging)
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
    fun `missing visualStyle decodes to the modern default`() {
        val decoded = json.decodeFromString(Settings.serializer(), """{"ui":{}}""")
        assertEquals(VisualStyle.PLAIN, decoded.ui.visualStyle)
        assertEquals(VisualStyle.PLAIN, UiSettings().visualStyle)
    }

    @Test
    fun `missing accentColor decodes to the dynamic system accent`() {
        val decoded = json.decodeFromString(Settings.serializer(), """{"ui":{}}""")
        assertEquals(AccentColor.AUTO, decoded.ui.accentColor)
        assertEquals(AccentColor.AUTO, UiSettings().accentColor)
    }

    @Test
    fun `chosen accent survives a round trip`() {
        val encoded = json.encodeToString(
            Settings.serializer(),
            Settings(ui = UiSettings(accentColor = AccentColor.LIME)),
        )
        assertEquals(
            AccentColor.LIME,
            json.decodeFromString(Settings.serializer(), encoded).ui.accentColor,
        )
    }

    @Test
    fun `auto palette survives a round trip and stays first in the picker order`() {
        val encoded = json.encodeToString(
            Settings.serializer(),
            Settings(ui = UiSettings(panelAppearance = PanelAppearance.AUTO)),
        )
        assertEquals(
            PanelAppearance.AUTO,
            json.decodeFromString(Settings.serializer(), encoded).ui.panelAppearance,
        )
        assertEquals(PanelAppearance.AUTO, PanelAppearance.entries.first())
    }

    @Test
    fun `plain visualStyle survives a round trip`() {
        val encoded = json.encodeToString(
            Settings.serializer(),
            Settings(ui = UiSettings(visualStyle = VisualStyle.PLAIN)),
        )
        val decoded = json.decodeFromString(Settings.serializer(), encoded)
        assertEquals(VisualStyle.PLAIN, decoded.ui.visualStyle)
    }

    @Test
    fun `light palette decodes and survives a round trip`() {
        val decoded = json.decodeFromString(
            Settings.serializer(),
            """{"ui":{"panelAppearance":"LIGHT"}}""",
        )
        assertEquals(PanelAppearance.LIGHT, decoded.ui.panelAppearance)
        val encoded = json.encodeToString(Settings.serializer(), decoded)
        assertEquals(
            PanelAppearance.LIGHT,
            json.decodeFromString(Settings.serializer(), encoded).ui.panelAppearance,
        )
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
