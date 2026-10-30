package com.foxhole.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InterfaceModeSerializationTest {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    @Test
    fun `older payloads retain the complete expert interface`() {
        val restored = json.decodeFromString(Settings.serializer(), """{"ui":{"locale":"RU"}}""")

        assertEquals("EXPERT", mode(restored.ui))
        assertEquals(AppLocale.RU, restored.ui.locale)
    }

    @Test
    fun `explicit user mode survives serialization without changing runtime settings`() {
        val restored = json.decodeFromString(
            Settings.serializer(),
            """{"ui":{"interfaceMode":"USER"},"connection":{"autoReconnect":false},"expert":{"killSwitchEnabled":true}}""",
        )
        val roundTrip = json.decodeFromString(
            Settings.serializer(),
            json.encodeToString(Settings.serializer(), restored)
        )

        assertEquals("USER", mode(roundTrip.ui))
        assertFalse(roundTrip.connection.autoReconnect)
        assertTrue(roundTrip.expert.killSwitchEnabled)
    }

    @Test
    fun `future interface value falls back locally and retains other choices`() {
        val restored = json.decodeFromString(
            Settings.serializer(),
            """{"ui":{"interfaceMode":"FUTURE","themeMode":"LIGHT","locale":"RU"},"connection":{"autoReconnect":false}}""",
        )

        assertEquals("EXPERT", mode(restored.ui))
        assertEquals(ThemeMode.LIGHT, restored.ui.themeMode)
        assertEquals(AppLocale.RU, restored.ui.locale)
        assertFalse(restored.connection.autoReconnect)
    }

    private fun mode(value: UiSettings): String? =
        json.encodeToJsonElement(UiSettings.serializer(), value).jsonObject["interfaceMode"]?.jsonPrimitive?.content
}
