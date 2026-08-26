package com.foxhole.guard.core.backup

import com.foxhole.core.model.AccentColor
import com.foxhole.core.model.AppLocale
import com.foxhole.core.model.AppLockMode
import com.foxhole.core.model.AppLockSettings
import com.foxhole.core.model.AppTunnelLane
import com.foxhole.core.model.CachedActiveProfile
import com.foxhole.core.model.ExpertSettings
import com.foxhole.core.model.KnownApplicationIdentity
import com.foxhole.core.model.LocalAuthSettings
import com.foxhole.core.model.LocalSurfaceSettings
import com.foxhole.core.model.PanelAppearance
import com.foxhole.core.model.PendingQuarantineAppDetails
import com.foxhole.core.model.ProfileSourceType
import com.foxhole.core.model.ProfileTrafficTotal
import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.Settings
import com.foxhole.core.model.SmartProfilePreference
import com.foxhole.core.model.ThemeMode
import com.foxhole.core.model.UiSettings
import com.foxhole.core.model.UpdateSourceSettings
import com.foxhole.core.model.blockedLanePackages
import com.foxhole.core.model.retiredRawConfigSourceStorageToken
import com.foxhole.guard.core.settings.normalized
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
        assertEquals(original.settings?.sanitizedForBackup(), decoded.settings)
    }

    @Test
    fun `legacy backup appearance overrides its encoded default theme once`() {
        val legacy =
            sampleDocument().copy(
                settingsSchemaVersion = 19,
                settings =
                Settings(
                    schemaVersion = 19,
                    ui =
                    UiSettings(
                        themeMode = ThemeMode.SYSTEM,
                        panelAppearance = PanelAppearance.DARK,
                    ),
                ),
            )

        val migrated = decodeBackupDocument(encodeBackupDocument(legacy)) as BackupParseResult.Success
        assertEquals(ThemeMode.OLED, migrated.document.settings?.ui?.themeMode)
        assertEquals(PanelAppearance.AUTO, migrated.document.settings?.ui?.panelAppearance)

        val repeated =
            decodeBackupDocument(encodeBackupDocument(migrated.document)) as BackupParseResult.Success
        assertEquals(migrated.document.settings, repeated.document.settings)
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
              "settingsSchemaVersion": 20,
              "futureField": {"nested": true},
              "settings": {
                "schemaVersion": 20,
                "ui": {"visualStyle": "PIXEL", "accentColor": "CYAN", "locale": "RU"}
              },
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
        val ui = result.document.settings?.ui
        assertEquals(AccentColor.CYAN, ui?.accentColor)
        assertEquals(AppLocale.RU, ui?.locale)
        assertFalse(encodeBackupDocument(result.document).contains("\"visualStyle\""))
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
    fun `newer settings schema is rejected before unknown fields can be dropped`() {
        val payload =
            """
            {
              "format": "foxhole-guard-backup",
              "formatVersion": 1,
              "settingsSchemaVersion": 999,
              "settings": {"schemaVersion": 999}
            }
            """.trimIndent()

        assertEquals(
            BackupParseResult.Failure.UNSUPPORTED_SETTINGS_SCHEMA,
            decodeBackupDocument(payload),
        )
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
    fun `plain backup never exports a private release token`() {
        val secret = "github_pat_secret_value"
        val document =
            sampleDocument().copy(
                settings =
                Settings(
                    updateSources =
                    UpdateSourceSettings(
                        appReleasesUrl = "https://api.github.com/repos/example/private/releases/latest",
                        appReleasesToken = secret,
                    ),
                ),
            )

        val payload = encodeBackupDocument(document)
        assertFalse(payload.contains(secret))
        val decoded = (decodeBackupDocument(payload) as BackupParseResult.Success).document
        assertEquals("", decoded.settings?.updateSources?.appReleasesToken)
        assertEquals(
            document.settings?.updateSources?.appReleasesUrl,
            decoded.settings?.updateSources?.appReleasesUrl,
        )
    }

    @Test
    fun `legacy backup token is discarded on import`() {
        val unsafeDocument =
            sampleDocument().copy(
                settings =
                Settings(
                    updateSources =
                    UpdateSourceSettings(
                        appReleasesUrl = "https://api.github.com/repos/example/private/releases/latest",
                        appReleasesToken = "legacy_plaintext_pat",
                    ),
                ),
            )
        val legacyPayload = backupJson.encodeToString(BackupDocument.serializer(), unsafeDocument)

        val decoded = (decodeBackupDocument(legacyPayload) as BackupParseResult.Success).document
        assertEquals("", decoded.settings?.updateSources?.appReleasesToken)
    }

    @Test
    fun `backup strips device credentials and restore preserves destination credentials`() {
        val destination =
            Settings(
                appLock = AppLockSettings(mode = AppLockMode.PASSWORD, passwordSetAt = 123L),
                updateSources = UpdateSourceSettings(appReleasesToken = "destination_pat"),
                expert =
                ExpertSettings(
                    localSurfaces =
                    LocalSurfaceSettings(
                        auth = LocalAuthSettings(password = "local_proxy", apiSecret = "local_api"),
                        lanAuth = LocalAuthSettings(password = "lan_proxy", apiSecret = "lan_api"),
                    ),
                ),
                appTrafficUsageAccessConsent = true,
                usageTrackingStartedAt = 77L,
            )
        val backup =
            Settings(
                updateSources = UpdateSourceSettings(appReleasesUrl = "https://example.test/releases"),
                appTrafficStatsEnabled = true,
            ).sanitizedForBackup()
        val encoded = backupJson.encodeToString(Settings.serializer(), backup)

        assertFalse(encoded.contains("destination_pat"))
        assertFalse(encoded.contains("local_proxy"))
        assertFalse(encoded.contains("lan_proxy"))
        val merged = mergeRestoredSettings(backup, destination)
        assertEquals(destination.appLock, merged.appLock)
        assertEquals("destination_pat", merged.updateSources.appReleasesToken)
        assertEquals(destination.expert.localSurfaces.auth, merged.expert.localSurfaces.auth)
        assertEquals(destination.expert.localSurfaces.lanAuth, merged.expert.localSurfaces.lanAuth)
        assertTrue(merged.appTrafficUsageAccessConsent)
        assertTrue(merged.appTrafficStatsEnabled)
        assertEquals(77L, merged.usageTrackingStartedAt)
    }

    @Test
    fun `backup cannot move quarantine and restore cannot release destination pending block`() {
        val packageName = "com.example.pending"
        val details =
            PendingQuarantineAppDetails(
                packageName = packageName,
                label = "Pending",
                firstInstallTime = 100L,
                detectedAt = 200L,
                installerPackageName = "com.store",
            )
        val destination =
            Settings(
                expert =
                ExpertSettings(
                    firewallEnabled = true,
                    appAssignments = mapOf(packageName to AppTunnelLane.BLOCK),
                    pendingQuarantinePackages = listOf(packageName),
                    pendingQuarantineAppDetails = listOf(details),
                    blockedPackagesEnabled = true,
                    blockAppsAlways = true,
                ),
            )
        val exported = destination.sanitizedForBackup()
        assertTrue(exported.expert.pendingQuarantinePackages.isEmpty())
        assertTrue(exported.expert.pendingQuarantineAppDetails.isEmpty())
        assertFalse(packageName in exported.expert.appAssignments)

        val hostileBackup =
            Settings(
                expert =
                ExpertSettings(
                    firewallEnabled = false,
                    blockedPackagesEnabled = false,
                    blockAppsAlways = false,
                ),
            )
        val merged = mergeRestoredSettings(hostileBackup, destination).normalized()
        assertEquals(listOf(packageName), merged.expert.pendingQuarantinePackages)
        assertEquals(listOf(details), merged.expert.pendingQuarantineAppDetails)
        assertEquals(listOf(packageName), merged.expert.blockedLanePackages())
        assertTrue(merged.expert.firewallEnabled)
        assertTrue(merged.expert.blockedPackagesEnabled)
        assertTrue(merged.expert.blockAppsAlways)
    }

    @Test
    fun `enabling quarantine on restore installs the captured destination baseline atomically`() {
        val baseline =
            listOf(
                KnownApplicationIdentity(
                    packageName = "com.already.installed",
                    signingCertificateSha256 = "ab".repeat(32),
                    firstSeenAtMs = 100L,
                ),
            )
        val backup = Settings(expert = ExpertSettings(firewallEnabled = true, newAppQuarantineEnabled = true))

        val merged =
            mergeRestoredSettings(
                backup = backup,
                current = Settings(),
                quarantineKnownApplicationsOverride = baseline,
            ).normalized()

        assertTrue(merged.expert.newAppQuarantineEnabled)
        assertEquals(baseline, merged.expert.quarantineKnownApplications)
    }

    @Test
    fun `file name reflects scope and date`() {
        val now = 1_781_000_000_000
        assertTrue(backupFileName(true, true, now).startsWith("foxhole_guard_backup_all_"))
        assertTrue(backupFileName(true, false, now).startsWith("foxhole_guard_backup_profiles_"))
        assertTrue(backupFileName(false, true, now).startsWith("foxhole_guard_backup_settings_"))
        assertTrue(backupFileName(true, true, now).endsWith(".foxhole-backup"))
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
