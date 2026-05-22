package com.foxhole.beta.ui

import com.foxhole.beta.core.model.Profile
import com.foxhole.beta.core.model.ProfileProtocolOption
import com.foxhole.beta.core.model.ProfileSourceType
import com.foxhole.beta.core.model.ProfileTrafficTotal
import com.foxhole.beta.core.model.ProtocolHint
import com.foxhole.beta.core.model.ProtocolQuality
import com.foxhole.beta.core.model.Settings
import com.foxhole.beta.core.model.StatisticsRetention
import com.foxhole.beta.core.model.StatisticsSettings
import com.foxhole.beta.core.model.TrafficSnapshot
import com.foxhole.beta.core.model.TransportProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsStatisticsScreenTest {
    @Test
    fun `security related settings default to off`() {
        val settings = Settings()

        assertFalse(settings.expert.firewallEnabled)
        assertFalse(settings.expert.systemDnsProtectionEnabled)
        assertFalse(settings.expert.newAppQuarantineEnabled)
        assertFalse(settings.statistics.appChangesEnabled)
        assertFalse(settings.anomaly.enabled)
        assertFalse(settings.anomaly.notifyUnusualTraffic)
        assertFalse(settings.anomaly.analyzeBackgroundTraffic)
        assertFalse(settings.anomaly.analyzeDestinationCountries)
    }

    @Test
    fun `smart profile statistics keep tcp protocol traffic separate before profile summary`() {
        val profile =
            Profile(
                id = 1L,
                name = "Smart",
                sourceType = ProfileSourceType.SUBSCRIPTION_URL,
                secretRef = "secret",
                protocolHint = ProtocolHint.VLESS,
                lastUpdatedAt = null,
                lastEtag = null,
                protocolOptions = listOf(
                    ProfileProtocolOption(
                        id = "vless",
                        displayName = "VLESS",
                        protocolHint = ProtocolHint.VLESS,
                        isSelected = true,
                    ),
                    ProfileProtocolOption(
                        id = "hysteria",
                        displayName = "Hysteria2",
                        protocolHint = ProtocolHint.HYSTERIA2,
                    ),
                ),
                selectedProtocolOptionId = "vless",
                isActive = true,
            )
        val state =
            SettingsRouteUiState(
                profiles = listOf(profile),
                activeProfile = profile,
                settings = Settings(
                    profileTrafficTotals = listOf(
                        ProfileTrafficTotal(
                            profileId = 1L,
                            profileName = "Smart",
                            protocolHint = ProtocolHint.VLESS,
                            protocolOptionId = "vless",
                            transport = TransportProtocol.TCP,
                            rxTotalBytes = 100L,
                            txTotalBytes = 50L,
                            updatedAt = 2L,
                        ),
                        ProfileTrafficTotal(
                            profileId = 1L,
                            profileName = "Smart",
                            protocolHint = ProtocolHint.HYSTERIA2,
                            protocolOptionId = "hysteria",
                            transport = TransportProtocol.UDP,
                            rxTotalBytes = 300L,
                            txTotalBytes = 30L,
                            updatedAt = 3L,
                        ),
                    ),
                ),
            )

        val statistics = statisticsUiState(state = state, retention = StatisticsRetention.FOREVER)

        assertEquals(480L, statistics.profileTraffic.single().totalBytes)
        assertEquals(
            150L,
            statistics.vpnProtocols.single { item -> item.protocol == ProtocolHint.VLESS }.totalBytes,
        )
        assertEquals(
            330L,
            statistics.vpnProtocols.single { item -> item.protocol == ProtocolHint.HYSTERIA2 }.totalBytes,
        )
        assertEquals(
            150L,
            statistics.transports.single { item -> item.transport == TransportProtocol.TCP }.totalBytes,
        )
        assertEquals(
            330L,
            statistics.transports.single { item -> item.transport == TransportProtocol.UDP }.totalBytes,
        )

        val detail = profileStatisticsDetail(state = state, item = statistics.profileTraffic.single())
        assertEquals(480L, detail.totalBytes)
        assertEquals(150L, detail.protocols.single { item -> item.label == "VLESS" }.totalBytes)
        assertEquals(330L, detail.protocols.single { item -> item.label == "Hysteria2" }.totalBytes)
    }

    @Test
    fun `profile detail protocol switcher is hidden for single protocol profiles`() {
        val singleProfile =
            Profile(
                id = 7L,
                name = "Single",
                sourceType = ProfileSourceType.RAW_SINGBOX_JSON,
                secretRef = "secret",
                protocolHint = ProtocolHint.VLESS,
                lastUpdatedAt = null,
                lastEtag = null,
                isActive = true,
            )
        val smartProfile =
            singleProfile.copy(
                protocolOptions = listOf(
                    ProfileProtocolOption(
                        id = "vless",
                        displayName = "VLESS",
                        protocolHint = ProtocolHint.VLESS,
                        isSelected = true,
                    ),
                    ProfileProtocolOption(
                        id = "hysteria",
                        displayName = "Hysteria2",
                        protocolHint = ProtocolHint.HYSTERIA2,
                    ),
                ),
                selectedProtocolOptionId = "vless",
            )

        assertFalse(shouldShowProfileProtocolSwitcher(singleProfile, connectedProtocolCount = 1))
        assertTrue(shouldShowProfileProtocolSwitcher(smartProfile, connectedProtocolCount = 1))
        assertFalse(shouldShowProfileProtocolSwitcher(smartProfile, connectedProtocolCount = 0))
    }

    @Test
    fun `dashboard statistics range excludes all`() {
        assertEquals(
            listOf(
                StatisticsDisplayRange.HOURS_24,
                StatisticsDisplayRange.WEEK,
                StatisticsDisplayRange.MONTH,
            ),
            dashboardStatisticsDisplayRanges(),
        )
    }

    @Test
    fun `traffic only protocol statistics do not imply successful attempts`() {
        val profile =
            Profile(
                id = 7L,
                name = "Traffic only",
                sourceType = ProfileSourceType.SHARE_URI,
                secretRef = "secret",
                protocolHint = ProtocolHint.VLESS,
                lastUpdatedAt = null,
                lastEtag = null,
                isActive = true,
            )
        val state =
            SettingsRouteUiState(
                profiles = listOf(profile),
                activeProfile = profile,
                settings = Settings(
                    profileTrafficTotals = listOf(
                        ProfileTrafficTotal(
                            profileId = 7L,
                            profileName = "Traffic only",
                            protocolHint = ProtocolHint.VLESS,
                            protocolOptionId = null,
                            transport = TransportProtocol.TCP,
                            rxTotalBytes = 2048L,
                            txTotalBytes = 1024L,
                            updatedAt = 10L,
                        ),
                    ),
                ),
            )

        val statistics = statisticsUiState(state = state, retention = StatisticsRetention.FOREVER)
        val item = statistics.vpnProtocols.single()

        assertEquals(ProtocolQuality.TRAFFIC_ONLY, item.quality)
        assertEquals(0, item.successCount)
        assertEquals(0, item.failureCount)
        assertNull(item.successRateOrNull)
        assertNull(item.errorRateOrNull)
    }

    @Test
    fun `app traffic runtime allowed requires settings and usage access`() {
        val enabled =
            Settings(
                statistics = StatisticsSettings(enabled = true, appTrafficEnabled = true),
                appTrafficStatsEnabled = true,
            )

        assertTrue(appTrafficStatsRuntimeAllowed(enabled, usageAccessGranted = true))
        assertFalse(appTrafficStatsRuntimeAllowed(enabled, usageAccessGranted = false))
        assertFalse(
            appTrafficStatsRuntimeAllowed(
                enabled.copy(appTrafficStatsEnabled = false),
                usageAccessGranted = true,
            ),
        )
        assertFalse(
            appTrafficStatsRuntimeAllowed(
                enabled.copy(statistics = enabled.statistics.copy(appTrafficEnabled = false)),
                usageAccessGranted = true,
            ),
        )
        assertFalse(
            appTrafficStatsRuntimeAllowed(
                enabled.copy(statistics = enabled.statistics.copy(enabled = false)),
                usageAccessGranted = true,
            ),
        )
    }

    @Test
    fun `extended statistics mode follows usage access availability`() {
        val state =
            SettingsRouteUiState(
                settings = Settings(
                    statistics = StatisticsSettings(enabled = true, appTrafficEnabled = true),
                    appTrafficStatsEnabled = true,
                ),
            )

        assertTrue(
            statisticsUiState(
                state = state,
                retention = StatisticsRetention.FOREVER,
                usageAccessGranted = true,
            ).extendedMode,
        )
        assertFalse(
            statisticsUiState(
                state = state,
                retention = StatisticsRetention.FOREVER,
                usageAccessGranted = false,
            ).extendedMode,
        )
    }

    @Test
    fun `active live traffic is added only when persisted totals are older than live sample`() {
        val profile =
            Profile(
                id = 11L,
                name = "Active",
                sourceType = ProfileSourceType.SHARE_URI,
                secretRef = "secret",
                protocolHint = ProtocolHint.VLESS,
                lastUpdatedAt = null,
                lastEtag = null,
                isActive = true,
            )
        val baseState =
            SettingsRouteUiState(
                profiles = listOf(profile),
                activeProfile = profile,
                settings = Settings(
                    profileTrafficTotals = listOf(
                        ProfileTrafficTotal(
                            profileId = 11L,
                            profileName = "Active",
                            protocolHint = ProtocolHint.VLESS,
                            rxTotalBytes = 100L,
                            txTotalBytes = 50L,
                            updatedAt = 100L,
                        ),
                    ),
                ),
            )

        val withOlderPersisted =
            baseState.copy(
                traffic = TrafficSnapshot(
                    available = true,
                    rxTotalBytes = 10L,
                    txTotalBytes = 5L,
                    sampledAt = 200L,
                ),
            )
        assertEquals(165L, profileTrafficItems(withOlderPersisted).single().totalBytes)

        val updatedTotals =
            withOlderPersisted.settings.profileTrafficTotals.map { total ->
                total.copy(updatedAt = 250L)
            }
        val persistedAlreadyUpdated =
            withOlderPersisted.copy(
                settings = withOlderPersisted.settings.copy(profileTrafficTotals = updatedTotals),
            )
        assertEquals(150L, profileTrafficItems(persistedAlreadyUpdated).single().totalBytes)
    }
}
