package com.foxhole.beta.core.settings

import com.foxhole.beta.core.model.Settings
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsStorageSupportTest {
    @Test
    fun `returns missing when encrypted settings file does not exist`() {
        val missingFile = Files.createTempDirectory("foxhole-settings-missing").resolve("settings.json").toFile()

        val result =
            readEncryptedSettingsResult(
                settingsFile = missingFile,
                readPayload = { error("should not read missing file") },
                decodePayload = { error("should not decode missing file") },
                sanitizePayload = { it },
                rewriteSanitized = { error("should not rewrite missing file") },
                preserveCorruptFile = { error("should not preserve missing file") },
            )

        assertTrue(result is EncryptedSettingsLoadResult.Missing)
    }

    @Test
    fun `rewrites sanitized payload instead of treating it as corruption`() {
        val directory = Files.createTempDirectory("foxhole-settings-sanitized").toFile()
        val settingsFile = directory.resolve("settings.json").apply { writeText("ciphertext-placeholder") }
        var rewritten = false

        val result =
            readEncryptedSettingsResult(
                settingsFile = settingsFile,
                readPayload = { """{"ui":{"themeMode":"BROKEN_THEME"}}""" },
                decodePayload = { payload ->
                    assertEquals("""{"ui":{"themeMode":"SYSTEM"}}""", payload)
                    Settings()
                },
                sanitizePayload = { payload ->
                    payload.replace("BROKEN_THEME", "SYSTEM")
                },
                rewriteSanitized = {
                    rewritten = true
                },
                preserveCorruptFile = { error("sanitized payload should not be preserved") },
            )

        assertTrue(result is EncryptedSettingsLoadResult.Loaded)
        assertTrue(rewritten)
    }

    @Test
    fun `preserves forensic copy when encrypted settings cannot be read`() {
        val directory = Files.createTempDirectory("foxhole-settings-corrupt").toFile()
        val settingsFile = directory.resolve("settings.json").apply { writeText("corrupted-bytes") }
        val preservedFile = directory.resolve("settings.json.corrupt-copy")

        val result =
            readEncryptedSettingsResult(
                settingsFile = settingsFile,
                readPayload = { throw IllegalStateException("decrypt failed") },
                decodePayload = { Settings() },
                sanitizePayload = { it },
                rewriteSanitized = { },
                preserveCorruptFile = {
                    settingsFile.copyTo(preservedFile, overwrite = false)
                },
            )

        assertTrue(result is EncryptedSettingsLoadResult.Corrupt)
        result as EncryptedSettingsLoadResult.Corrupt
        assertEquals("decrypt failed", result.cause.message)
        assertEquals(preservedFile.absolutePath, result.preservedCopy?.absolutePath)
        assertTrue(settingsFile.exists())
        assertTrue(preservedFile.exists())
    }
}
