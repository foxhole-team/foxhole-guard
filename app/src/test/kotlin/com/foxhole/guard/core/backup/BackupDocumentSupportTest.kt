package com.foxhole.guard.core.backup

import com.foxhole.core.model.CachedActiveProfile
import com.foxhole.core.model.ProfileSourceType
import com.foxhole.core.model.ProfileTrafficTotal
import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.Settings
import com.foxhole.core.model.SmartProfilePreference
import com.foxhole.core.model.retiredRawConfigSourceStorageToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupDocumentSupportTest {
    private fun sampleDocument(): BackupDocument =
        BackupDocument(
            createdAt = 1_780_000_000_000,
            appVersionName = "0.0.1-dev.40",
            appVersionCode = 42,
            settingsSchemaVersion = Settings().schemaVersion,
            settings = Settings(),
            profiles =
            listOf(
                BackupProfilePayload(
                    name = "Home VLESS",
                    sourceType = ProfileSourceType.RAW_CONFIG_JSON.name,
                    protocolHints = listOf(ProtocolHint.VLESS.name),
                    rawInput = "{\"outbounds\":[]}",
                ),
                BackupProfilePayload(
                    name = "Provider",
                    sourceType = "SUBSCRIPTION_URL",
                    protocolHints = listOf(ProtocolHint.VLESS.name, ProtocolHint.HYSTERIA2.name),
                    subscriptionUrl = "https://example.com/sub",
                    isActive = true,
                ),
            ),
        )

    @Test
    fun `encode decode round trip`() {
        val original = sampleDocument()
        val result = decodeBackupDocument(encodeBackupDocument(original))
        assertTrue(result is BackupParseResult.Success)
        val decoded = (result as BackupParseResult.Success).document
        assertEquals(original.profiles, decoded.profiles)
        assertEquals(original.appVersionName, decoded.appVersionName)
        assertEquals(original.settings, decoded.settings)
    }

    @Test
    fun `unknown fields are tolerated`() {
        val retiredSourceToken = retiredRawConfigSourceStorageToken()
        val payload =
            """
            {
              "format": "foxhole-guard-backup",
              "formatVersion": 1,
              "createdAt": 5,
              "appVersionName": "9.9.9",
              "appVersionCode": 99,
              "futureField": {"nested": true},
              "profiles": [
                {"name": "X", "sourceType": "$retiredSourceToken", "rawInput": "{}", "futureFlag": 3}
              ]
            }
            """.trimIndent()
        val result = decodeBackupDocument(payload)
        assertTrue(result is BackupParseResult.Success)
        val profile = (result as BackupParseResult.Success).document.profiles.single()
        assertEquals("X", profile.name)
        assertEquals(ProfileSourceType.RAW_CONFIG_JSON.name, profile.sourceType)
    }

    @Test
    fun `foreign json is rejected as not a backup`() {
        val result = decodeBackupDocument("""{"format": "other-app", "formatVersion": 1}""")
        assertEquals(BackupParseResult.Failure.NOT_A_BACKUP, result)
    }

    @Test
    fun `newer format version is rejected`() {
        val result = decodeBackupDocument("""{"format": "foxhole-guard-backup", "formatVersion": 99}""")
        assertEquals(BackupParseResult.Failure.UNSUPPORTED_FORMAT_VERSION, result)
    }

    @Test
    fun `garbage payload is malformed`() {
        assertEquals(BackupParseResult.Failure.MALFORMED, decodeBackupDocument("not json at all"))
    }

    @Test
    fun `sanitize strips device local runtime state`() {
        val settings =
            Settings(
                lastActiveProfile =
                CachedActiveProfile(
                    id = 7,
                    name = "x",
                    sourceType = ProfileSourceType.RAW_CONFIG_JSON,
                    protocolHint = ProtocolHint.VLESS,
                ),
                smartProfilePreferences = listOf(SmartProfilePreference(profileId = 7)),
                profileTrafficTotals =
                listOf(
                    ProfileTrafficTotal(profileId = 7, profileName = "x", protocolHint = ProtocolHint.VLESS),
                ),
            )
        val sanitized = settings.sanitizedForBackup()
        assertNull(sanitized.lastActiveProfile)
        assertTrue(sanitized.smartProfilePreferences.isEmpty())
        assertTrue(sanitized.profileTrafficTotals.isEmpty())
    }

    @Test
    fun `file name reflects scope and date`() {
        val now = 1_781_000_000_000
        assertTrue(backupFileName(true, true, now).startsWith("foxhole_guard_backup_all_"))
        assertTrue(backupFileName(true, false, now).startsWith("foxhole_guard_backup_profiles_"))
        assertTrue(backupFileName(false, true, now).startsWith("foxhole_guard_backup_settings_"))
        assertTrue(backupFileName(true, true, now).endsWith(".json"))
    }

    @Test
    fun `unknown protocol is flagged but profile stays importable`() {
        val payload =
            BackupProfilePayload(
                name = "Future",
                sourceType = ProfileSourceType.RAW_CONFIG_JSON.name,
                protocolHints = listOf("QUANTUM_TLS"),
                rawInput = "{}",
            )
        val compatibility = payload.compatibility()
        assertEquals(listOf("QUANTUM_TLS"), compatibility.unknownProtocolNames)
        assertTrue(compatibility.importable)
    }

    @Test
    fun `profile without any payload is not importable`() {
        val compatibility =
            BackupProfilePayload(name = "Empty", sourceType = ProfileSourceType.RAW_CONFIG_JSON.name).compatibility()
        assertFalse(compatibility.importable)
        assertNull(
            BackupProfilePayload(name = "Empty", sourceType = ProfileSourceType.RAW_CONFIG_JSON.name).restoreRawInput(),
        )
    }

    @Test
    fun `restore raw input prefers the stored resolved config over any network fetch`() {
        val payload =
            BackupProfilePayload(
                name = "P",
                sourceType = "SUBSCRIPTION_URL",
                subscriptionUrl = "https://example.com/sub",
                rawInput = "raw",
                resolvedConfigJson = "{}",
            )
        assertEquals("{}", payload.restoreRawInput())
    }

    @Test
    fun `restore raw input falls back to raw input and only then to the url`() {
        val withoutResolved =
            BackupProfilePayload(
                name = "P",
                sourceType = "SUBSCRIPTION_URL",
                subscriptionUrl = "https://example.com/sub",
                rawInput = "raw",
            )
        assertEquals("raw", withoutResolved.restoreRawInput())

        val urlOnly =
            BackupProfilePayload(
                name = "P",
                sourceType = "SUBSCRIPTION_URL",
                subscriptionUrl = "https://example.com/sub",
            )
        assertEquals("https://example.com/sub", urlOnly.restoreRawInput())
    }
}
