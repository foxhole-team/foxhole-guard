package com.foxhole.guard.core.settings

import com.foxhole.core.model.PanelAppearance
import com.foxhole.core.model.Settings
import com.foxhole.core.model.ThemeMode
import com.foxhole.core.model.UiSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class PanelAppearancePersistenceTest {
    @Test
    fun `normalization keeps panel appearance separate from theme mode`() {
        val normalized = Settings(
            ui = UiSettings(
                themeMode = ThemeMode.LIGHT,
                panelAppearance = PanelAppearance.DARK,
            ),
        ).normalized()

        assertEquals(ThemeMode.LIGHT, normalized.ui.themeMode)
        assertEquals(PanelAppearance.DARK, normalized.ui.panelAppearance)
    }
}
