package com.foxhole.guard.ui.cli

import androidx.compose.ui.graphics.Color
import com.foxhole.core.model.PanelAppearance
import com.foxhole.core.model.ThemeMode
import com.foxhole.core.model.UiSettings
import com.foxhole.guard.ui.cli.components.cliPanelBackground
import com.foxhole.guard.ui.cli.map.cliMapLandFillColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CliPanelAppearanceTest {
    @Test
    fun `theme mode exposes system warm dark oled and light palettes`() {
        assertEquals(
            listOf(ThemeMode.SYSTEM, ThemeMode.DARK, ThemeMode.OLED, ThemeMode.LIGHT),
            ThemeMode.entries.toList(),
        )
        assertEquals(ThemeMode.DARK, UiSettings().themeMode)
        assertEquals(PanelAppearance.AUTO, UiSettings().panelAppearance)
        assertEquals(PanelAppearance.AUTO, cliPanelAppearanceFor(ThemeMode.SYSTEM))
        assertEquals(PanelAppearance.STANDARD, cliPanelAppearanceFor(ThemeMode.DARK))
        assertEquals(PanelAppearance.STANDARD, cliPanelAppearanceFor(ThemeMode.OLED))
        assertEquals(PanelAppearance.LIGHT, cliPanelAppearanceFor(ThemeMode.LIGHT))
    }

    @Test
    fun `dark appearance removes only implicit panel fill`() {
        val canonFill = Color(0xFF0D1117)
        val explicitFill = Color(0xFF010203)

        assertEquals(
            canonFill,
            cliPanelBackground(Color.Unspecified, canonFill, PanelAppearance.STANDARD),
        )
        assertEquals(
            Color.Transparent,
            cliPanelBackground(Color.Unspecified, canonFill, PanelAppearance.DARK),
        )
        assertEquals(
            explicitFill,
            cliPanelBackground(explicitFill, canonFill, PanelAppearance.DARK),
        )
    }

    @Test
    fun `dark appearance leaves map country outlines without a land fill`() {
        val standardFill = Color(0xFF101820)

        assertEquals(
            standardFill,
            cliMapLandFillColor(PanelAppearance.STANDARD, standardFill),
        )
        assertEquals(
            standardFill,
            cliMapLandFillColor(PanelAppearance.LIGHT, standardFill),
        )
        assertNull(cliMapLandFillColor(PanelAppearance.DARK, standardFill))

        val cache = source("main/kotlin/com/foxhole/guard/ui/cli/map/CliMapLandCache.kt")
        assertTrue(cache.contains("key.fillColor?.let"))
        assertTrue(cache.contains("style = AndroidPaint.Style.STROKE"))
    }

    @Test
    fun `help deck applies appearance to both card layers`() {
        val help = source("main/kotlin/com/foxhole/guard/ui/cli/settings/CliHelpSubScreen.kt")

        assertTrue(help.contains("val appearance = LocalCliPanelAppearance.current"))
        assertTrue(
            help.contains("cliPanelBackground(Color.Unspecified, colors.panel, appearance)"),
        )
        assertTrue(
            help.contains("cliPanelBackground(Color.Unspecified, colors.panelAlt, appearance)"),
        )
    }

    @Test
    fun `application settings expose colour options without a retired style selector`() {
        val settings = source("main/kotlin/com/foxhole/guard/ui/cli/settings/CliSettingsScreen.kt")
        val activity = source("main/kotlin/com/foxhole/guard/ui/cli/CliMainActivity.kt")
        val onboarding = source("main/kotlin/com/foxhole/guard/ui/cli/onboarding/CliOnboardingWizard.kt")
        val english = source("main/res/values/strings.xml")
        val russian = source("main/res/values-ru/strings.xml")

        assertTrue(settings.contains("ThemeMode.entries.map"))
        assertTrue(settings.contains("settings.ui.themeMode.name"))
        assertTrue(settings.contains("viewModel.onThemeSelected"))
        assertFalse(settings.contains("PanelAppearance.entries.map"))
        assertFalse(settings.contains("VisualStyle"))
        assertFalse(settings.contains("visualStyle"))
        assertFalse(settings.contains("onVisualStyleSelected"))
        assertTrue(settings.contains("AccentColor.entries.map"))
        assertTrue(settings.contains("settings.ui.accentColor.name"))
        assertTrue(settings.contains("viewModel.onAccentColorSelected"))
        assertTrue(activity.contains("homeViewModel.appearanceUiState.collectAsStateWithLifecycle()"))
        assertTrue(activity.contains("themeMode = appearance.themeMode"))
        assertFalse(activity.contains("visualStyle"))
        assertTrue(activity.contains("accentColor = appearance.accentColor"))
        assertTrue(activity.contains("cliResolvedThemeMode(appearance.themeMode)"))
        assertTrue(onboarding.contains("ThemeMode.entries.map"))
        assertTrue(onboarding.contains("R.string.cli_cfg_pixel_art"))
        assertTrue(onboarding.contains("viewModel::onPixelArtEnabledChanged"))
        assertFalse(onboarding.contains("PanelAppearance.entries.map"))
        assertTrue(english.contains(">Color palette</string>"))
        assertTrue(english.contains(">Automatic</string>"))
        assertTrue(english.contains(">Dark</string>"))
        assertTrue(english.contains(">Night</string>"))
        assertTrue(english.contains(">Light</string>"))
        assertFalse(english.contains(">Style</string>"))
        assertFalse(english.contains(">Retro</string>"))
        assertFalse(english.contains(">Modern</string>"))
        assertTrue(english.contains(">Accent color</string>"))
        assertTrue(russian.contains(">Цветовая палитра</string>"))
        assertTrue(russian.contains(">Автоматически</string>"))
        assertTrue(russian.contains(">Тёмная</string>"))
        assertTrue(russian.contains(">Ночь</string>"))
        assertTrue(russian.contains(">Светлая</string>"))
        assertFalse(russian.contains(">Выбор стиля</string>"))
        assertFalse(russian.contains(">Ретро</string>"))
        assertFalse(russian.contains(">Модерн</string>"))
        assertTrue(russian.contains(">Цветовой акцент</string>"))
        assertTrue(russian.contains(">Салатовый</string>"))
    }

    private fun source(relative: String): String =
        listOf(
            File("src/$relative"),
            File("app/src/$relative"),
            File("../app/src/$relative"),
        ).first(File::isFile).readText()
}
