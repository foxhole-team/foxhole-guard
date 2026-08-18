package com.foxhole.guard.core.settings

import com.foxhole.core.model.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

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
                encodeCanonical = { error("should not encode missing file") },
                rewriteCanonical = { error("should not rewrite missing file") },
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
                encodeCanonical = { """{"ui":{"themeMode":"SYSTEM"}}""" },
                rewriteCanonical = {
                    rewritten = true
                },
                preserveCorruptFile = { error("sanitized payload should not be preserved") },
            )

        assertTrue(result is EncryptedSettingsLoadResult.Loaded)
        assertTrue(rewritten)
    }

    @Test
    fun `does not rewrite when the stored payload is already canonical`() {
        val directory = Files.createTempDirectory("foxhole-settings-canonical").toFile()
        val canonicalPayload = """{"ui":{"themeMode":"SYSTEM"}}"""
        val settingsFile = directory.resolve("settings.json").apply { writeText("ciphertext-placeholder") }

        val result =
            readEncryptedSettingsResult(
                settingsFile = settingsFile,
                readPayload = { canonicalPayload },
                decodePayload = { Settings() },
                sanitizePayload = { it },
                encodeCanonical = { canonicalPayload },
                rewriteCanonical = { error("canonical payload must not be rewritten") },
                preserveCorruptFile = { error("canonical payload is not corrupt") },
            )

        assertTrue(result is EncryptedSettingsLoadResult.Loaded)
    }

    @Test
    fun `rewrites when decoded settings normalize to a different payload`() {
        val directory = Files.createTempDirectory("foxhole-settings-drift").toFile()
        val settingsFile = directory.resolve("settings.json").apply { writeText("ciphertext-placeholder") }
        var rewritten = false

        val result =
            readEncryptedSettingsResult(
                settingsFile = settingsFile,
                readPayload = { """{"ui":{},"legacyField":true}""" },
                decodePayload = { Settings() },
                sanitizePayload = { it },
                encodeCanonical = { """{"ui":{"themeMode":"SYSTEM"}}""" },
                rewriteCanonical = { rewritten = true },
                preserveCorruptFile = { error("normalization drift is not corruption") },
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
                encodeCanonical = { error("should not encode unreadable file") },
                rewriteCanonical = { },
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
