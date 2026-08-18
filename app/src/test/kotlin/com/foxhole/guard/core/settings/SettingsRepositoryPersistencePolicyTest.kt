package com.foxhole.guard.core.settings

import com.foxhole.core.model.AutoConnectReasonCode
import com.foxhole.core.model.DiagnosticsRetention
import com.foxhole.core.model.ExpertSettings
import com.foxhole.core.model.NETWORK_FINGERPRINT_SCHEMA_CURRENT
import com.foxhole.core.model.Settings
import com.foxhole.core.model.SmartProfileNetworkMemory
import com.foxhole.core.model.SmartProfilePreference
import com.foxhole.core.model.SmartProfileProtocolMemory
import com.foxhole.core.model.UiSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal class SettingsRepositoryPersistencePolicyTest : SettingsRepositoryTestSupport() {
    @Test
    fun `hiding expert settings keeps unlocked marker and closes section`() {
        val hidden =
            Settings(
                ui = UiSettings(showExpertSettings = true),
                expert = ExpertSettings(unlockedAt = 1234L),
            ).withExpertSettingsVisibility(visible = false)

        assertFalse(hidden.ui.showExpertSettings)
        assertEquals(1234L, hidden.expert.unlockedAt)
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
        assertEquals(1234L, hidden.expert.unlockedAt)
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
    fun `remembered smart start latency reads latest global memory only and drops stale values after three weeks`() {
        val now = 30L * 24L * 60L * 60L * 1000L
        val preference =
            SmartProfilePreference(
                profileId = 42L,
                protocolMemories =
                listOf(
                    SmartProfileProtocolMemory(
                        optionId = "wireguard",
                        lastSuccessAt = now - (22L * 24L * 60L * 60L * 1000L),
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
            mapOf("trojan" to 170L),
            preference.rememberedSmartStartLatencyByOptionId(now = now),
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
            settings.rememberedSmartStartLatencyByProfileId(now = now),
        )
    }

    @Test
    fun `settings expose remembered latency unavailable without turning it into no data`() {
        val now = 9_500_000L
        val preference =
            SmartProfilePreference(
                profileId = 7L,
                protocolMemories =
                listOf(
                    SmartProfileProtocolMemory(
                        optionId = "vless",
                        lastSuccessAt = now - 1_000L,
                        lastLatencyMs = null,
                        lastReasonCode = AutoConnectReasonCode.LATENCY_ENDPOINT_BLOCKED,
                    ),
                    SmartProfileProtocolMemory(
                        optionId = "trojan",
                        lastSuccessAt = now - 2_000L,
                        lastLatencyMs = 170L,
                        lastReasonCode = null,
                    ),
                ),
            )
        val settings = Settings(smartProfilePreferences = listOf(preference))

        assertEquals(
            setOf("vless"),
            preference.rememberedSmartProfileLatencyUnavailableOptionIds(now = now),
        )
        assertEquals(
            mapOf("trojan" to 170L),
            preference.rememberedSmartStartLatencyByOptionId(now = now),
        )
        assertEquals(
            mapOf(7L to setOf("vless")),
            settings.rememberedSmartProfileLatencyUnavailableByProfileId(now = now),
        )
    }

    @Test
    fun `settings expose remembered down protocols by profile`() {
        val now = 12_000_000L
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
                                lastSuccessAt = now - 8_000L,
                                lastFailureAt = now - 1_000L,
                                failureStreak = 2,
                                cooldownUntilAt = now + 60_000L,
                            ),
                            SmartProfileProtocolMemory(
                                optionId = "trojan",
                                lastSuccessAt = now - 1_000L,
                                lastFailureAt = now - 8_000L,
                                failureStreak = 0,
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
                                        optionId = "shadowsocks",
                                        lastFailureAt = now - 500L,
                                        failureStreak = 1,
                                        cooldownUntilAt = now + 90_000L,
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            )

        assertEquals(
            mapOf(7L to setOf("wireguard")),
            settings.rememberedSmartProfileDownOptionIdsByProfileId(now = now),
        )
    }
}
