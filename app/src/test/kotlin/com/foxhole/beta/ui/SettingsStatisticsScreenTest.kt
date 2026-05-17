package com.foxhole.beta.ui

import com.foxhole.beta.core.model.Profile
import com.foxhole.beta.core.model.ProfileProtocolOption
import com.foxhole.beta.core.model.ProfileSourceType
import com.foxhole.beta.core.model.ProfileTrafficTotal
import com.foxhole.beta.core.model.ProtocolHint
import com.foxhole.beta.core.model.ProtocolQuality
import com.foxhole.beta.core.model.Settings
import com.foxhole.beta.core.model.StatisticsRetention
import com.foxhole.beta.core.model.TransportProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsStatisticsScreenTest {
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
}
