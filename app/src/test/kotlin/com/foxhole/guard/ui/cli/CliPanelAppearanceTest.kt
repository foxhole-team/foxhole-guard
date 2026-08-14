package com.foxhole.guard.ui.cli

import androidx.compose.ui.graphics.Color
import com.foxhole.core.model.PanelAppearance
import com.foxhole.core.model.ThemeMode
import com.foxhole.core.model.UiSettings
import com.foxhole.guard.ui.cli.components.cliPanelBackground
import com.foxhole.guard.ui.cli.map.cliMapLandFillColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CliPanelAppearanceTest {
    @Test
    fun `panel appearance stays separate from the system dark light theme mode`() {
        assertEquals(
            listOf(ThemeMode.SYSTEM, ThemeMode.DARK, ThemeMode.LIGHT),
            ThemeMode.entries.toList(),
        )
        assertEquals(PanelAppearance.STANDARD, UiSettings().panelAppearance)
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
    fun `application settings expose the two persisted appearance options in both locales`() {
        val settings = source("main/kotlin/com/foxhole/guard/ui/cli/settings/CliSettingsScreen.kt")
        val activity = source("main/kotlin/com/foxhole/guard/ui/cli/CliMainActivity.kt")
        val english = source("main/res/values/strings.xml")
        val russian = source("main/res/values-ru/strings.xml")

        assertTrue(settings.contains("PanelAppearance.entries.map"))
        assertTrue(settings.contains("settings.ui.panelAppearance.name"))
        assertTrue(settings.contains("viewModel.onPanelAppearanceSelected"))
        assertTrue(activity.contains("CliTheme(panelAppearance = panelAppearance)"))
        assertTrue(english.contains(">Appearance style</string>"))
        assertTrue(english.contains(">Standard</string>"))
        assertTrue(english.contains(">Dark</string>"))
        assertTrue(russian.contains(">Тема оформления</string>"))
        assertTrue(russian.contains(">Стандартная</string>"))
        assertTrue(russian.contains(">Тёмная</string>"))
    }

    private fun source(relative: String): String =
        listOf(
            File("src/$relative"),
            File("app/src/$relative"),
            File("../app/src/$relative"),
        ).first(File::isFile).readText()
}
