package com.foxhole.guard.ui.cli

import androidx.compose.ui.graphics.Color
import com.foxhole.core.model.AccentColor
import com.foxhole.core.model.Settings
import com.foxhole.core.model.ThemeMode
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CliMonochromeTest {
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
