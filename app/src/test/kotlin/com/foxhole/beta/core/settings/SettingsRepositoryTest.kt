package com.foxhole.beta.core.settings

import com.foxhole.beta.BuildConfig
import com.foxhole.beta.core.model.AppLocale
import com.foxhole.beta.core.model.AutoConnectReasonCode
import com.foxhole.beta.core.model.ConnectionSettings
import com.foxhole.beta.core.model.DiagnosticsRetention
import com.foxhole.beta.core.model.ExpertSettings
import com.foxhole.beta.core.model.LatencyProbeMethod
import com.foxhole.beta.core.model.NETWORK_FINGERPRINT_SCHEMA_CURRENT
import com.foxhole.beta.core.model.SMART_START_PROTOCOL_TIMEOUT_DEFAULT_SECONDS
import com.foxhole.beta.core.model.SMART_START_REFRESH_TIMEOUT_DEFAULT_SECONDS
import com.foxhole.beta.core.model.SMART_START_TIMEOUT_MAX_SECONDS
import com.foxhole.beta.core.model.Settings
import com.foxhole.beta.core.model.SmartProfileNetworkMemory
import com.foxhole.beta.core.model.SmartProfilePreference
import com.foxhole.beta.core.model.SmartProfileProtocolMemory
import com.foxhole.beta.core.model.SmartStartTransportPriority
import com.foxhole.beta.core.model.SubscriptionRefreshInterval
import com.foxhole.beta.core.model.ThemeMode
import com.foxhole.beta.core.model.UiSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsRepositoryTest {
    @Test
    fun `system locale uses empty appcompat tag and explicit locales keep their tags`() {
        assertEquals("", AppLocale.SYSTEM.appLanguageTags())
        assertEquals("en", AppLocale.EN.appLanguageTags())
        assertEquals("ru", AppLocale.RU.appLanguageTags())
    }

    @Test
    fun `migrates dead api ip sb endpoint to current default`() {
        assertEquals(BuildConfig.DEFAULT_IP_INFO_ENDPOINT, normalizeIpInfoEndpoint("https://api.ip.sb/geoip"))
        assertEquals(BuildConfig.DEFAULT_IP_INFO_ENDPOINT, normalizeIpInfoEndpoint("https://api.ip.sb/geoip/"))
    }

    @Test
    fun `migrates legacy api ipify endpoint to current default`() {
        assertEquals(
            BuildConfig.DEFAULT_IP_INFO_ENDPOINT,
            normalizeIpInfoEndpoint("https://api.ipify.org?format=json"),
        )
        assertEquals(
            BuildConfig.DEFAULT_IP_INFO_ENDPOINT,
            normalizeIpInfoEndpoint("https://api.ipify.org/?format=json"),
        )
    }

    @Test
    fun `keeps custom non legacy endpoint`() {
        assertEquals("https://ifconfig.co/json", normalizeIpInfoEndpoint(" https://ifconfig.co/json "))
    }

    @Test
    fun `uses default when endpoint blank`() {
        assertEquals(BuildConfig.DEFAULT_IP_INFO_ENDPOINT, normalizeIpInfoEndpoint("  "))
    }

    @Test
    fun `stored theme parser falls back to system for unsupported values`() {
        assertEquals(ThemeMode.SYSTEM, parseStoredThemeMode("BROKEN_THEME"))
    }

    @Test
    fun `optional fast theme parser keeps blank and invalid values unset`() {
        assertNull(parseOptionalStoredThemeMode(null))
        assertNull(parseOptionalStoredThemeMode(" "))
        assertNull(parseOptionalStoredThemeMode("BROKEN_THEME"))
        assertEquals(ThemeMode.DARK, parseOptionalStoredThemeMode("DARK"))
    }

    @Test
    fun `support bot handle must match telegram bot pattern`() {
        assertEquals("@foxhole_support_bot", normalizeSupportBotHandle("  @foxhole_support_bot "))
        assertNull(normalizeSupportBotHandle("foxhole_support_bot"))
        assertNull(normalizeSupportBotHandle("@foxhole_support"))
        assertNull(normalizeSupportBotHandle("@bot"))
    }

    @Test
    fun `stored support bot override drops invalid and bundled default values`() {
        assertNull(storedSupportBotHandleOverride(null))
        assertNull(storedSupportBotHandleOverride("broken"))
        assertNull(storedSupportBotHandleOverride(BuildConfig.DEFAULT_SUPPORT_BOT_HANDLE))
        assertNull(storedSupportBotHandleOverride("@foxhole_app_support_bot"))
        assertEquals("@custom_support_bot", storedSupportBotHandleOverride("@custom_support_bot"))
    }

    @Test
    fun `effective support bot handle falls back to bundled default`() {
        assertEquals(BuildConfig.DEFAULT_SUPPORT_BOT_HANDLE, effectiveSupportBotHandle(null))
        assertEquals(BuildConfig.DEFAULT_SUPPORT_BOT_HANDLE, effectiveSupportBotHandle("broken"))
        assertEquals("@custom_support_bot", effectiveSupportBotHandle("@custom_support_bot"))
    }

    @Test
    fun `sanitizes unsupported theme mode in stored payload`() {
        assertEquals(
            """{"ui":{"themeMode":"SYSTEM"}}""",
            sanitizeStoredThemeModePayload("""{"ui":{"themeMode":"BROKEN_THEME"}}"""),
        )
    }

    @Test
    fun `keeps supported stored theme mode unchanged in payload`() {
        assertEquals(
            """{"ui":{"themeMode":"LIGHT"}}""",
            sanitizeStoredThemeModePayload("""{"ui":{"themeMode":"LIGHT"}}"""),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects private ip info endpoint`() {
        normalizeIpInfoEndpoint("https://127.0.0.1/geo")
    }

    @Test
    fun `ui defaults to system theme and hidden expert settings`() {
        assertEquals(ThemeMode.SYSTEM, Settings().ui.themeMode)
        assertFalse(Settings().ui.showExpertSettings)
    }

    @Test
    fun `expert insecure tls override defaults off`() {
        assertFalse(ExpertSettings().allowInsecureTls)
    }

    @Test
    fun `subscription auto refresh defaults off`() {
        assertFalse(Settings().connection.autoRefreshSubscriptions)
        assertEquals(SubscriptionRefreshInterval.HOURS_6, Settings().connection.subscriptionRefreshInterval)
    }

    @Test
    fun `latency probe method defaults to http`() {
        assertEquals(LatencyProbeMethod.HTTP, Settings().connection.latencyProbeMethod)
    }

    @Test
    fun `smart start connection settings use requested defaults`() {
        val connection = Settings().connection

        assertEquals(SMART_START_PROTOCOL_TIMEOUT_DEFAULT_SECONDS, connection.smartStartProtocolSelectionTimeoutSeconds)
        assertEquals(SMART_START_REFRESH_TIMEOUT_DEFAULT_SECONDS, connection.smartStartRefreshSelectionTimeoutSeconds)
        assertEquals(SmartStartTransportPriority.ALL, connection.smartStartTransportPriority)
    }

    @Test
    fun `smart start timeout normalization clamps and snaps to five second steps`() {
        assertEquals(10, normalizeSmartStartTimeoutSeconds(value = 3, minSeconds = 10))
        assertEquals(15, normalizeSmartStartTimeoutSeconds(value = 17, minSeconds = 15))
        assertEquals(55, normalizeSmartStartTimeoutSeconds(value = 59, minSeconds = 10))
        assertEquals(SMART_START_TIMEOUT_MAX_SECONDS, normalizeSmartStartTimeoutSeconds(value = 99, minSeconds = 15))
    }

    @Test
    fun `smart start replay logging defaults off`() {
        assertFalse(ExpertSettings().smartStartReplayLogging)
    }

    @Test
    fun `reset expert safe defaults disables insecure tls import exceptions`() {
        val reset =
            Settings(
                expert =
                    ExpertSettings(
                        unlockedAt = 1234L,
                        warningAcknowledgedAt = 5678L,
                        allowInsecureTls = true,
                    ),
            ).resetExpertSettingsToSafeDefaults()

        assertFalse(reset.expert.allowInsecureTls)
        assertNull(reset.expert.warningAcknowledgedAt)
        assertEquals(1234L, reset.expert.unlockedAt)
        assertTrue(reset.connection.stealthModeEnabled)
    }

    @Test
    fun `experimental reset only clears experimental settings`() {
        val original =
            Settings(
                ui = UiSettings(themeMode = ThemeMode.DARK, locale = AppLocale.RU, showExpertSettings = true),
                connection = ConnectionSettings(autoReconnect = false),
                expert =
                    ExpertSettings(
                        unlockedAt = 1234L,
                        warningAcknowledgedAt = 5678L,
                        blockScreenshots = true,
                        networkActivityLogging = true,
                        diagnosticsRetention = DiagnosticsRetention.DAYS_7,
                        smartStartReplayLogging = true,
                        allowInsecureTls = true,
                        sniff = true,
                        bypassLan = true,
                    ),
            )

        assertTrue(original.hasCustomExperimentalSettings())
        val reset = original.resetExperimentalSettingsToDefaults()

        assertEquals(original.ui, reset.ui)
        assertEquals(original.connection, reset.connection)
        assertEquals(1234L, reset.expert.unlockedAt)
        assertTrue(reset.expert.blockScreenshots)
        assertFalse(reset.expert.networkActivityLogging)
        assertFalse(reset.expert.allowInsecureTls)
        assertTrue(reset.expert.sniff)
        assertFalse(reset.expert.bypassLan)
        assertFalse(reset.hasCustomExperimentalSettings())
    }

    @Test
    fun `application reset restores settings defaults without deleting profile-scoped data`() {
        val smartPreference =
            SmartProfilePreference(
                profileId = 42L,
                lastKnownGoodOptionId = "tcp",
                protocolMemories = listOf(SmartProfileProtocolMemory(optionId = "tcp", lastSuccessAt = 1_000L)),
            )
        val original =
            Settings(
                ui = UiSettings(themeMode = ThemeMode.DARK, locale = AppLocale.RU, showExpertSettings = true),
                connection =
                    ConnectionSettings(
                        autoReconnect = false,
                        autoStartOnBoot = true,
                        ipInfoEndpoint = "https://ifconfig.co/json",
                    ),
                expert =
                    ExpertSettings(
                        unlockedAt = 1234L,
                        blockScreenshots = true,
                        networkActivityLogging = true,
                    ),
                smartProfilePreferences = listOf(smartPreference),
            )

        val reset = original.resetApplicationSettingsToDefaults()

        assertEquals(UiSettings(), reset.ui)
        assertEquals(ConnectionSettings(ipInfoEndpoint = BuildConfig.DEFAULT_IP_INFO_ENDPOINT), reset.connection)
        assertEquals(ExpertSettings(), reset.expert)
        assertEquals(listOf(smartPreference), reset.smartProfilePreferences)
    }

    @Test
    fun `hiding expert settings clears unlocked marker and keeps section closed`() {
        val hidden =
            Settings(
                ui = UiSettings(showExpertSettings = true),
                expert = ExpertSettings(unlockedAt = 1234L),
            ).withExpertSettingsVisibility(visible = false)

        assertFalse(hidden.ui.showExpertSettings)
        assertNull(hidden.expert.unlockedAt)
    }

    @Test
    fun `expert settings keep network activity logging flag`() {
        val visible =
            Settings(
                ui = UiSettings(showExpertSettings = true),
                expert = ExpertSettings(unlockedAt = 1234L, networkActivityLogging = true),
            ).withExpertSettingsVisibility(visible = true)

        assertTrue(visible.expert.networkActivityLogging)
    }

    @Test
    fun `expert settings keep diagnostics retention when visibility changes`() {
        val hidden =
            Settings(
                ui = UiSettings(showExpertSettings = true),
                expert = ExpertSettings(unlockedAt = 1234L, diagnosticsRetention = DiagnosticsRetention.DAYS_7),
            ).withExpertSettingsVisibility(visible = false)

        assertEquals(DiagnosticsRetention.DAYS_7, hidden.expert.diagnosticsRetention)
        assertNull(hidden.expert.unlockedAt)
    }

    @Test
    fun `smart profile normalization keeps memory even when exclusions are empty`() {
        val normalized =
            normalizeSmartProfilePreferences(
                listOf(
                    SmartProfilePreference(
                        profileId = 7L,
                        lastKnownGoodOptionId = "wireguard",
                        lastKnownGoodLatencyMs = 180L,
                        protocolMemories =
                            listOf(
                                SmartProfileProtocolMemory(
                                    optionId = "wireguard",
                                    lastSuccessAt = 1_000L,
                                    lastLatencyMs = 180L,
                                    lastReasonCode = AutoConnectReasonCode.RESTORED_LAST_GOOD,
                                ),
                            ),
                        networkMemories =
                            listOf(
                                SmartProfileNetworkMemory(
                                    networkFingerprint = "wifi-home",
                                    networkFingerprintSchema = NETWORK_FINGERPRINT_SCHEMA_CURRENT,
                                    lastKnownGoodOptionId = "wireguard",
                                    protocolMemories =
                                        listOf(
                                            SmartProfileProtocolMemory(
                                                optionId = "wireguard",
                                                lastSuccessAt = 1_100L,
                                                lastLatencyMs = 170L,
                                            ),
                                        ),
                                ),
                            ),
                    ),
                ),
            )

        assertEquals(1, normalized.size)
        assertEquals("wireguard", normalized.first().lastKnownGoodOptionId)
        assertEquals(1, normalized.first().protocolMemories.size)
        assertEquals(1, normalized.first().networkMemories.size)
    }

    @Test
    fun `smart profile normalization removes empty protocol memories and trims option ids`() {
        val normalized =
            normalizeSmartProfilePreferences(
                listOf(
                    SmartProfilePreference(
                        profileId = 9L,
                        protocolMemories =
                            listOf(
                                SmartProfileProtocolMemory(optionId = "  "),
                                SmartProfileProtocolMemory(
                                    optionId = " wireguard ",
                                    lastFailureAt = 2_000L,
                                    lastReasonCode = AutoConnectReasonCode.DNS_FAILURE,
                                    failureStreak = -5,
                                ),
                            ),
                    ),
                ),
            )

        assertEquals(1, normalized.size)
        assertEquals("wireguard", normalized.first().protocolMemories.single().optionId)
        assertEquals(0, normalized.first().protocolMemories.single().failureStreak)
    }

    @Test
    fun `clearing smart start runtime data keeps manual protocol exclusions`() {
        val cleared =
            SmartProfilePreference(
                profileId = 9L,
                excludedProtocolOptionIds = listOf("wireguard"),
                lastKnownGoodOptionId = "wireguard",
                lastKnownGoodLatencyMs = 120L,
                lastKnownGoodAt = 1_000L,
                lastFullSmartRefreshAt = 2_000L,
                smartStartBaselineReady = true,
                recommendedProtocolIds = listOf("wireguard"),
                protocolMemories =
                    listOf(
                        SmartProfileProtocolMemory(
                            optionId = "wireguard",
                            lastSuccessAt = 1_000L,
                            lastLatencyMs = 120L,
                        ),
                    ),
                networkMemories =
                    listOf(
                        SmartProfileNetworkMemory(
                            networkFingerprint = "wifi-home",
                            networkFingerprintSchema = NETWORK_FINGERPRINT_SCHEMA_CURRENT,
                            lastKnownGoodOptionId = "wireguard",
                        ),
                    ),
            ).clearedSmartStartRuntimeData()

        assertEquals(9L, cleared.profileId)
        assertEquals(listOf("wireguard"), cleared.excludedProtocolOptionIds)
        assertNull(cleared.lastKnownGoodOptionId)
        assertNull(cleared.lastKnownGoodLatencyMs)
        assertNull(cleared.lastKnownGoodAt)
        assertNull(cleared.lastFullSmartRefreshAt)
        assertFalse(cleared.smartStartBaselineReady)
        assertTrue(cleared.recommendedProtocolIds.isEmpty())
        assertTrue(cleared.protocolMemories.isEmpty())
        assertTrue(cleared.networkMemories.isEmpty())
    }

    @Test
    fun `preferred last known good uses scoped network memory before profile fallback`() {
        val preference =
            SmartProfilePreference(
                profileId = 11L,
                lastKnownGoodOptionId = "wireguard",
                networkMemories =
                    listOf(
                        SmartProfileNetworkMemory(
                            networkFingerprint = "wifi-home",
                            networkFingerprintSchema = NETWORK_FINGERPRINT_SCHEMA_CURRENT,
                            lastKnownGoodOptionId = "trojan",
                        ),
                    ),
            )

        assertEquals("trojan", preference.preferredLastKnownGoodOptionId("wifi-home"))
        assertEquals("wireguard", preference.preferredLastKnownGoodOptionId("cellular"))
    }

    @Test
    fun `successful probe without measured latency keeps previous remembered latency`() {
        val update =
            recordProbeResultIntoMemory(
                lastKnownGoodOptionId = "wireguard",
                lastKnownGoodLatencyMs = 210L,
                lastKnownGoodAt = 1_000L,
                protocolMemories =
                    listOf(
                        SmartProfileProtocolMemory(
                            optionId = "wireguard",
                            lastSuccessAt = 900L,
                            lastLatencyMs = 210L,
                        ),
                    ),
                optionId = "wireguard",
                latencyMs = null,
                success = true,
                reasonCode = AutoConnectReasonCode.LATENCY_ENDPOINT_BLOCKED,
                markAsLastKnownGood = true,
                recordedAt = 2_000L,
            )

        assertEquals(210L, update.lastKnownGoodLatencyMs)
        assertEquals(210L, update.protocolMemories.single().lastLatencyMs)
        assertEquals(AutoConnectReasonCode.LATENCY_ENDPOINT_BLOCKED, update.protocolMemories.single().lastReasonCode)
    }

    @Test
    fun `successful probe records adaptive outcome metadata and clears cooldown`() {
        val update =
            recordProbeResultIntoMemory(
                lastKnownGoodOptionId = "wireguard",
                lastKnownGoodLatencyMs = 240L,
                lastKnownGoodAt = 1_000L,
                protocolMemories =
                    listOf(
                        SmartProfileProtocolMemory(
                            optionId = "wireguard",
                            lastSuccessAt = 1_000L,
                            lastLatencyMs = 240L,
                            successCount = 2,
                            failureCount = 1,
                            lastConnectDurationMs = 1_900L,
                            cooldownUntilAt = 5_000L,
                        ),
                    ),
                optionId = "wireguard",
                latencyMs = 180L,
                success = true,
                reasonCode = AutoConnectReasonCode.RESTORED_LAST_GOOD,
                markAsLastKnownGood = true,
                recordedAt = 6_000L,
                connectDurationMs = 1_250L,
                validatedAt = 6_100L,
                trafficAt = 6_200L,
            )

        val memory = update.protocolMemories.single()
        assertEquals(3, memory.successCount)
        assertEquals(1, memory.failureCount)
        assertEquals(1_250L, memory.lastConnectDurationMs)
        assertEquals(6_100L, memory.lastValidatedAt)
        assertEquals(6_200L, memory.lastTrafficAt)
        assertNull(memory.cooldownUntilAt)
        assertEquals(180L, update.lastKnownGoodLatencyMs)
        assertEquals(6_000L, update.lastKnownGoodAt)
    }

    @Test
    fun `failed probe increments failure history and applies cooldown`() {
        val recordedAt = 10_000L
        val update =
            recordProbeResultIntoMemory(
                lastKnownGoodOptionId = "wireguard",
                lastKnownGoodLatencyMs = 240L,
                lastKnownGoodAt = 1_000L,
                protocolMemories =
                    listOf(
                        SmartProfileProtocolMemory(
                            optionId = "wireguard",
                            lastSuccessAt = 1_000L,
                            lastLatencyMs = 240L,
                            failureStreak = 1,
                            failureCount = 1,
                        ),
                    ),
                optionId = "wireguard",
                latencyMs = null,
                success = false,
                reasonCode = AutoConnectReasonCode.DNS_FAILURE,
                markAsLastKnownGood = false,
                recordedAt = recordedAt,
                connectDurationMs = 4_000L,
            )

        val memory = update.protocolMemories.single()
        assertEquals(2, memory.failureStreak)
        assertEquals(1, memory.validationFailureCount)
        assertEquals(2, memory.failureCount)
        assertEquals(4_000L, memory.lastConnectDurationMs)
        assertEquals(recordedAt + (3L * 60L * 1000L * 2L), memory.cooldownUntilAt)
        assertEquals(240L, update.lastKnownGoodLatencyMs)
        assertEquals(1_000L, update.lastKnownGoodAt)
    }

    @Test
    fun `manual metrics failure can skip ranking memory side effects`() {
        val previous =
            SmartProfileProtocolMemory(
                optionId = "wireguard",
                lastSuccessAt = 1_000L,
                lastLatencyMs = 240L,
                failureStreak = 1,
                failureCount = 1,
                lastFailureAt = 2_000L,
                cooldownUntilAt = 5_000L,
                lastReasonCode = AutoConnectReasonCode.DNS_FAILURE,
            )

        val update =
            recordProbeResultIntoMemory(
                lastKnownGoodOptionId = "wireguard",
                lastKnownGoodLatencyMs = 240L,
                lastKnownGoodAt = 1_000L,
                protocolMemories = listOf(previous),
                optionId = "wireguard",
                latencyMs = null,
                success = false,
                reasonCode = AutoConnectReasonCode.CONNECT_ERROR,
                markAsLastKnownGood = false,
                recordedAt = 10_000L,
                connectDurationMs = 8_000L,
                countTowardOutcomeHistory = false,
                affectsFailureRankingMemory = false,
            )

        assertEquals(previous, update.protocolMemories.single())
        assertEquals("wireguard", update.lastKnownGoodOptionId)
        assertEquals(240L, update.lastKnownGoodLatencyMs)
        assertEquals(1_000L, update.lastKnownGoodAt)
    }

    @Test
    fun `remembered smart start latency prefers fresh network scoped memory and drops stale values after 72 hours`() {
        val now = 10L * 24L * 60L * 60L * 1000L
        val preference =
            SmartProfilePreference(
                profileId = 42L,
                protocolMemories =
                    listOf(
                        SmartProfileProtocolMemory(
                            optionId = "wireguard",
                            lastSuccessAt = now - (80L * 60L * 60L * 1000L),
                            lastLatencyMs = 220L,
                        ),
                        SmartProfileProtocolMemory(
                            optionId = "trojan",
                            lastSuccessAt = now - (6L * 60L * 60L * 1000L),
                            lastLatencyMs = 170L,
                        ),
                    ),
                networkMemories =
                    listOf(
                        SmartProfileNetworkMemory(
                            networkFingerprint = "wifi-home",
                            networkFingerprintSchema = NETWORK_FINGERPRINT_SCHEMA_CURRENT,
                            protocolMemories =
                                listOf(
                                    SmartProfileProtocolMemory(
                                        optionId = "wireguard",
                                        lastSuccessAt = now - (2L * 60L * 60L * 1000L),
                                        lastLatencyMs = 145L,
                                    ),
                                ),
                        ),
                    ),
            )

        assertEquals(
            mapOf(
                "trojan" to 170L,
                "wireguard" to 145L,
            ),
            preference.rememberedSmartStartLatencyByOptionId(
                networkFingerprint = "wifi-home",
                now = now,
            ),
        )
        assertEquals(
            mapOf("trojan" to 170L),
            preference.rememberedSmartStartLatencyByOptionId(
                networkFingerprint = "cellular",
                now = now,
            ),
        )
    }

    @Test
    fun `settings expose remembered smart start latency maps by profile`() {
        val now = 9_000_000L
        val settings =
            Settings(
                smartProfilePreferences =
                    listOf(
                        SmartProfilePreference(
                            profileId = 7L,
                            protocolMemories =
                                listOf(
                                    SmartProfileProtocolMemory(
                                        optionId = "wireguard",
                                        lastSuccessAt = now - 1_000L,
                                        lastLatencyMs = 160L,
                                    ),
                                ),
                        ),
                        SmartProfilePreference(
                            profileId = 9L,
                            protocolMemories =
                                listOf(
                                    SmartProfileProtocolMemory(
                                        optionId = "trojan",
                                        lastSuccessAt = now - (SMART_START_REMEMBERED_LATENCY_RETENTION_MS + 1L),
                                        lastLatencyMs = 190L,
                                    ),
                                ),
                        ),
                    ),
            )

        assertEquals(
            mapOf(7L to mapOf("wireguard" to 160L)),
            settings.rememberedSmartStartLatencyByProfileId(
                networkFingerprint = null,
                now = now,
            ),
        )
    }

}
