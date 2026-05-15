package com.foxhole.beta.ui

import com.foxhole.beta.core.model.Profile
import com.foxhole.beta.core.model.ProfileProtocolOption
import com.foxhole.beta.core.model.ProfileSourceType
import com.foxhole.beta.core.model.ProfileTrafficTotal
import com.foxhole.beta.core.model.ProtocolHint
import com.foxhole.beta.core.model.Settings
import com.foxhole.beta.core.model.StatisticsRetention
import com.foxhole.beta.core.model.TransportProtocol
import org.junit.Assert.assertEquals
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
                            rxTotalBytes = 100L,
                            txTotalBytes = 50L,
                            updatedAt = 2L,
                        ),
                        ProfileTrafficTotal(
                            profileId = 1L,
                            profileName = "Smart",
                            protocolHint = ProtocolHint.HYSTERIA2,
                            protocolOptionId = "hysteria",
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
}
