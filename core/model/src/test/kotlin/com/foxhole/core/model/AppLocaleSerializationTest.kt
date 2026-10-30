package com.foxhole.core.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

internal class AppLocaleSerializationTest {
    @Test
    fun `supported locales retain unique canonical tags and stored identities`() {
        assertEquals(listOf("", "ru", "en", "ja", "de", "fr", "es", "zh-CN"), AppLocale.entries.map { it.appLanguageTags() })
        AppLocale.entries.forEach { locale ->
            assertEquals(locale, Json.decodeFromString(AppLocale.serializer(), Json.encodeToString(AppLocale.serializer(), locale)))
        }
    }

    @Test
    fun `new locales preserve other settings while old payloads keep automatic locale`() {
        assertEquals(AppLocale.SYSTEM, Json.decodeFromString(Settings.serializer(), "{}").ui.locale)
        AppLocale.entries.forEach { locale ->
            val restored = Json.decodeFromString(
                Settings.serializer(),
                """{"ui":{"locale":"${locale.name}"},"connection":{"autoReconnect":false}}""",
            )
            assertEquals(locale, restored.ui.locale)
            assertEquals(false, restored.connection.autoReconnect)
        }
    }
}
