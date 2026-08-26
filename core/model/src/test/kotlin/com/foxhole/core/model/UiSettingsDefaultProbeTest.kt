package com.foxhole.core.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiSettingsDefaultProbeTest {
    @Test
    fun `settings schema exposes only supported theme modes`() {
        assertEquals(20, SETTINGS_SCHEMA_VERSION)
        assertEquals(
            listOf(ThemeMode.SYSTEM, ThemeMode.DARK, ThemeMode.OLED, ThemeMode.LIGHT),
            ThemeMode.entries.toList(),
        )
    }

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
        assertEquals(ThemeMode.DARK, decoded.ui.themeMode)
        assertEquals(PanelAppearance.AUTO, decoded.ui.panelAppearance)
        assertFalse(decoded.ui.showHomeAdditionalInfo)
        assertEquals(HomeAdditionalInfoCategory.MAP, decoded.ui.homeAdditionalInfoCategory)
        assertFalse(decoded.ui.mapWidgetMapOnRight)

        assertTrue(decoded.ui.showQuickAccessPanel)
        // Widget reordering ships ON now; the settings row lets you lock the arrangement instead.
        assertTrue(decoded.ui.layoutEditingEnabled)
        assertFalse(decoded.ui.interfaceSettingsExpanded)
        assertFalse(decoded.ui.blurEffectsEnabled)
        assertFalse(decoded.ui.statisticsDockIconEnabled)

        assertTrue(decoded.ui.torEnabledAtMs == 0L)
        assertTrue(decoded.ui.i2pEnabledAtMs == 0L)
    }

    @Test
    fun `statistics dock placement is opt in and survives a round trip`() {
        val encoded = json.encodeToString(
            Settings.serializer(),
            Settings(ui = UiSettings(statisticsDockIconEnabled = true)),
        )

        assertTrue(
            json.decodeFromString(Settings.serializer(), encoded).ui.statisticsDockIconEnabled,
        )
    }

    @Test
    fun `expensive diagnostics are opt in`() {
        assertFalse(Settings().expert.networkActivityLogging)
    }

    @Test
    fun `dropped tor and i2p settings hidden keys are ignored on load`() {
        val decoded =
            json.decodeFromString(
                Settings.serializer(),
                """{"ui":{"torSettingsHidden":true,"i2pSettingsHidden":true}}""",
            )
        assertFalse(decoded.ui.trafficMapEnabled)
    }

    @Test
    fun `dropped assistant and status keys are ignored on load`() {
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
    fun `retired visualStyle is ignored without losing adjacent settings`() {
        val decoded = json.decodeFromString(
            Settings.serializer(),
            """{"ui":{"visualStyle":"PIXEL","accentColor":"CYAN","locale":"RU"}}""",
        )
        assertEquals(AccentColor.CYAN, decoded.ui.accentColor)
        assertEquals(AppLocale.RU, decoded.ui.locale)
        assertFalse(json.encodeToString(Settings.serializer(), decoded).contains("\"visualStyle\""))
    }

    @Test
    fun `missing accentColor decodes to the orange app default`() {
        val decoded = json.decodeFromString(Settings.serializer(), """{"ui":{}}""")
        assertEquals(AccentColor.ORANGE, decoded.ui.accentColor)
        assertEquals(AccentColor.ORANGE, UiSettings().accentColor)
        assertEquals(AccentColor.ORANGE, AppearanceUiState().accentColor)
        assertTrue(decoded.ui.pixelArtEnabled)
        assertTrue(UiSettings().pixelArtEnabled)
    }

    @Test
    fun `appearance state excludes the legacy panel field and applies atomically`() {
        val initial =
            UiSettings(
                themeMode = ThemeMode.DARK,
                panelAppearance = PanelAppearance.LIGHT,
                accentColor = AccentColor.CYAN,
                pixelArtEnabled = false,
            )

        assertEquals(
            AppearanceUiState(
                themeMode = ThemeMode.DARK,
                accentColor = AccentColor.CYAN,
                pixelArtEnabled = false,
            ),
            initial.appearanceUiState(),
        )
        assertEquals(
            initial.copy(panelAppearance = PanelAppearance.AUTO),
            initial.withAppearanceUiState(initial.appearanceUiState()),
        )
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
    fun `explicit automatic accent and system theme survive a round trip`() {
        val encoded = json.encodeToString(
            Settings.serializer(),
            Settings(
                ui = UiSettings(
                    themeMode = ThemeMode.SYSTEM,
                    accentColor = AccentColor.AUTO,
                ),
            ),
        )
        val decoded = json.decodeFromString(Settings.serializer(), encoded)

        assertEquals(ThemeMode.SYSTEM, decoded.ui.themeMode)
        assertEquals(AccentColor.AUTO, decoded.ui.accentColor)
    }

    @Test
    fun `legacy auto panel token survives decoding for migration`() {
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
    fun `legacy widget settings default new status widgets to simple`() {
        val decoded = json.decodeFromString(Settings.serializer(), """{"widgets":{}}""")

        assertEquals(StatusWidgetLayoutMode.SIMPLE, decoded.widgets.statusLayoutMode)
        assertEquals(StatusWidgetLayoutMode.SIMPLE, WidgetDefaultsSettings().statusLayoutMode)
        assertFalse(decoded.widgets.statusAppearance().outline)
        assertFalse(decoded.widgets.webAppsAppearance().outline)
    }

    @Test
    fun `explicit expanded status widget layout survives a round trip`() {
        val encoded = json.encodeToString(
            Settings.serializer(),
            Settings(
                widgets = WidgetDefaultsSettings(statusLayoutMode = StatusWidgetLayoutMode.EXPANDED),
            ),
        )

        assertEquals(
            StatusWidgetLayoutMode.EXPANDED,
            json.decodeFromString(Settings.serializer(), encoded).widgets.statusLayoutMode,
        )
    }

    @Test
    fun `home additional route category survives a round trip without enabling the preview`() {
        val encoded = json.encodeToString(
            Settings.serializer(),
            Settings(
                ui = UiSettings(
                    showHomeAdditionalInfo = false,
                    homeAdditionalInfoCategory = HomeAdditionalInfoCategory.ROUTE,
                ),
            ),
        )

        val decoded = json.decodeFromString(Settings.serializer(), encoded)
        assertFalse(decoded.ui.showHomeAdditionalInfo)
        assertEquals(HomeAdditionalInfoCategory.ROUTE, decoded.ui.homeAdditionalInfoCategory)
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
        val decoded =
            json.decodeFromString(
                Settings.serializer(),
                """{"ui":{"livingBackground":"AURORA","backgroundBreathEnabled":true}}""",
            )
        assertFalse(decoded.ui.trafficMapEnabled)
    }
}
