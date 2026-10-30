package com.foxhole.guard.core.settings

import com.foxhole.core.model.AppLocale
import com.foxhole.core.model.CoreTuningSettings
import com.foxhole.core.model.SETTINGS_SCHEMA_VERSION
import com.foxhole.core.model.Settings
import com.foxhole.core.model.ThemeMode
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class SettingsPayloadDecoderTest {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    @Test
    fun `modern malformed tuning must never fall back to legacy security defaults`() {
        assertThrows(SerializationException::class.java) { decode(MALFORMED_MODERN) }
    }

    @Test
    fun `modern decode failure preserves the encrypted file and its forensic copy`() {
        val directory = Files.createTempDirectory("foxhole-settings-decoder").toFile()
        val source = directory.resolve("settings.json").apply { writeText("encrypted-original") }
        val copy = directory.resolve("settings.json.corrupt-copy")
        var rewritten = false
        try {
            val result = readEncryptedSettingsResult(
                settingsFile = source,
                readPayload = { MALFORMED_MODERN },
                decodePayload = ::decode,
                sanitizePayload = ::sanitizeStoredThemeModePayload,
                encodeCanonical = { json.encodeToString(Settings.serializer(), it) },
                rewriteCanonical = { rewritten = true },
                preserveCorruptFile = { source.copyTo(copy) },
            )
            assertTrue("malformed modern settings must fail closed", result is EncryptedSettingsLoadResult.Corrupt)
            assertFalse(rewritten)
            assertEquals("encrypted-original", source.readText())
            assertEquals("encrypted-original", copy.readText())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `recognizable flat legacy settings preserve their locale theme and connection flags`() {
        val settings = decode("""{"themeMode":"LIGHT","locale":"RU","autoReconnect":false,"autoStartOnBoot":true}""")
        assertEquals(ThemeMode.LIGHT, settings.ui.themeMode)
        assertEquals(AppLocale.RU, settings.ui.locale)
        assertFalse(settings.connection.autoReconnect)
        assertTrue(settings.connection.autoStartOnBoot)
    }

    @Test
    fun `modern nested settings preserve security flags and omitted defaults`() {
        val settings = decode(
            """{"expert":{"killSwitchEnabled":true,"core":{"workerThreads":2}},"connection":{"autoReconnect":false}}"""
        )
        assertTrue(settings.expert.killSwitchEnabled)
        assertEquals(CoreTuningSettings(workerThreads = 2), settings.expert.core)
        assertFalse(settings.connection.autoReconnect)
    }

    @Test
    fun `unknown only payloads cannot become a default replacement for user settings`() {
        assertThrows(SerializationException::class.java) { decode("""{"unrecognizedSettings":{"blocked":true}}""") }
        assertThrows(SerializationException::class.java) {
            decode("""{"autoReconnect":false,"unrecognizedRouting":{"blocked":true}}""")
        }
    }

    @Test
    fun `mixed legacy and modern shapes are refused instead of dropping one set of flags`() {
        assertThrows(SerializationException::class.java) { decode("""{"ui":{"locale":"RU"},"autoReconnect":false}""") }
        assertThrows(SerializationException::class.java) { decode("""{"schemaVersion":1,"autoReconnect":false}""") }
    }

    @Test
    fun `invalid or future schema versions are preserved instead of being rewritten as current defaults`() {
        listOf("\"invalid\"", "0", (SETTINGS_SCHEMA_VERSION + 1).toString()).forEach { version ->
            assertThrows(SerializationException::class.java) {
                decode("""{"schemaVersion":$version,"expert":{"killSwitchEnabled":true}}""")
            }
        }
    }

    @Test
    fun `an empty default object and supported modern version remain valid`() {
        assertEquals(CoreTuningSettings(), decode("{}").expert.core)
        assertEquals(SETTINGS_SCHEMA_VERSION, decode("""{"schemaVersion":$SETTINGS_SCHEMA_VERSION}""").schemaVersion)
    }

    private fun decode(payload: String) = decodeStoredSettingsPayload(payload, json)

    private companion object {
        const val MALFORMED_MODERN = """{"expert":{"killSwitchEnabled":true,"core":{"workerThreads":"invalid"}}}"""
    }
}
