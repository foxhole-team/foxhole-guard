package com.foxhole.guard.core.settings

import com.foxhole.core.model.PanelAppearance
import com.foxhole.core.model.Settings
import com.foxhole.core.model.ThemeMode
import com.foxhole.core.model.UiSettings
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class PanelAppearancePersistenceTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `legacy panel appearances migrate when theme mode was absent`() {
        val expected =
            mapOf(
                PanelAppearance.AUTO to ThemeMode.SYSTEM,
                PanelAppearance.STANDARD to ThemeMode.DARK,
                PanelAppearance.DARK to ThemeMode.OLED,
                PanelAppearance.LIGHT to ThemeMode.LIGHT,
            )

        expected.forEach { (legacyAppearance, expectedThemeMode) ->
            val payload = """{"ui":{"panelAppearance":"${legacyAppearance.name}"}}"""
            val migratedPayload = migrateStoredAppearancePayload(payload)
            val migrated =
                json.decodeFromString(
                    Settings.serializer(),
                    migratedPayload,
                ).normalized()

            assertEquals(legacyAppearance.name, expectedThemeMode, migrated.ui.themeMode)
            assertEquals(legacyAppearance.name, PanelAppearance.AUTO, migrated.ui.panelAppearance)
            assertEquals(migratedPayload, migrateStoredAppearancePayload(migratedPayload))
        }
    }

    @Test
    fun `explicit stored theme mode wins over every legacy appearance`() {
        ThemeMode.entries.forEach { storedThemeMode ->
            PanelAppearance.entries.forEach { legacyAppearance ->
                val payload =
                    """{"ui":{"themeMode":"${storedThemeMode.name}","panelAppearance":"${legacyAppearance.name}"}}"""
                val migrated =
                    json.decodeFromString(
                        Settings.serializer(),
                        migrateStoredAppearancePayload(payload),
                    ).normalized()

                assertEquals("$storedThemeMode/$legacyAppearance", storedThemeMode, migrated.ui.themeMode)
                assertEquals("$storedThemeMode/$legacyAppearance", PanelAppearance.AUTO, migrated.ui.panelAppearance)
            }
        }
    }

    @Test
    fun `appearance normalization is idempotent`() {
        val settings =
            Settings(
                ui =
                UiSettings(
                    themeMode = ThemeMode.LIGHT,
                    panelAppearance = PanelAppearance.DARK,
                ),
            )
        val normalized = settings.normalized()

        assertEquals(ThemeMode.LIGHT, normalized.ui.themeMode)
        assertEquals(PanelAppearance.AUTO, normalized.ui.panelAppearance)
        assertEquals(normalized, normalized.normalized())
    }
}
