package com.foxhole.guard.ui.cli

import androidx.compose.ui.graphics.Color
import com.foxhole.core.model.AccentColor
import com.foxhole.core.model.Settings
import com.foxhole.core.model.ThemeMode
import com.foxhole.guard.core.settings.launcherMonochromeEnabled
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CliMonochromeTest {
    @Test
    fun `night uses the fox launcher without changing the remembered monochrome preference`() {
        val settings = Settings().let {
            it.copy(
                ui = it.ui.copy(themeMode = ThemeMode.OLED, monochromeEnabled = false),
            )
        }
        val decoded = Json.decodeFromString(
            Settings.serializer(),
            Json.encodeToString(Settings.serializer(), settings),
        )
        assertEquals(ThemeMode.OLED, decoded.ui.themeMode)
        assertFalse(decoded.ui.monochromeEnabled)
        assertTrue(launcherMonochromeEnabled(decoded.ui.themeMode, decoded.ui.monochromeEnabled))
        val light = decoded.ui.copy(themeMode = ThemeMode.LIGHT)
        assertFalse(launcherMonochromeEnabled(light.themeMode, light.monochromeEnabled))
    }

    @Test
    fun `explicit monochrome choice remains effective across other themes and survives restoring settings`() {
        ThemeMode.entries.forEach { theme ->
            val settings = Settings().let {
                it.copy(
                    ui = it.ui.copy(themeMode = theme, monochromeEnabled = true),
                )
            }
            val decoded = Json.decodeFromString(
                Settings.serializer(),
                Json.encodeToString(Settings.serializer(), settings),
            )
            assertTrue(decoded.ui.monochromeEnabled)
            assertTrue(launcherMonochromeEnabled(decoded.ui.themeMode, decoded.ui.monochromeEnabled))
            assertEquals(theme == ThemeMode.OLED, launcherMonochromeEnabled(theme, false))
        }
    }

    @Test
    fun `monochrome defaults off and survives encrypted settings serialization`() {
        val settings = Settings()
        assertFalse(settings.ui.monochromeEnabled)
        val changed = settings.copy(ui = settings.ui.copy(monochromeEnabled = true, accentColor = AccentColor.WHITE))
        val decoded = Json.decodeFromString(
            Settings.serializer(),
            Json.encodeToString(Settings.serializer(), changed)
        )
        assertTrue(decoded.ui.monochromeEnabled)
        assertEquals(AccentColor.WHITE, decoded.ui.accentColor)
    }

    @Test
    fun `router palette is independent of the remembered accent`() {
        for (accent in AccentColor.entries) {
            assertEquals(Color.Black, cliColorsFor(ThemeMode.DARK, accent, true).bg)
            assertEquals(Color.White, cliColorsFor(ThemeMode.DARK, accent, true).accent)
            assertEquals(Color(0xFFCBCACA), cliColorsFor(ThemeMode.LIGHT, accent, true).bg)
            assertEquals(cliColorsFor(ThemeMode.DARK, accent, true), cliColorsFor(ThemeMode.OLED, accent, true))
        }
        assertEquals(Color.White, cliColorsFor(ThemeMode.DARK, AccentColor.WHITE).accent)
        assertTrue(cliColorsFor(ThemeMode.LIGHT, AccentColor.WHITE).accent != Color.White)
    }
}
