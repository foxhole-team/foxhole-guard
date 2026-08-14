package com.foxhole.guard.ui

import com.foxhole.core.model.LOCAL_GUARD_PROFILE_ID
import com.foxhole.core.model.ProfileTrafficTotal
import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.Settings
import com.foxhole.core.model.TOR_ONLY_PROFILE_ID
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Сентинел-сессии (Tor-only `-20`, local guard `-10`) пишут трафик под служебными именами,
 * но панели VPN-профилей/протоколов обязаны их отсекать: Tor показан собственной панелью,
 * guard — фильтр, а не подключение.
 */
class StatisticsSentinelFilterTest {

    private fun state(totals: List<ProfileTrafficTotal>) = StatisticsRouteUiState(
        settings = Settings(profileTrafficTotals = totals),
    )

    @Test
    fun `tor-only and local-guard sentinel sessions never reach the vpn panels`() {
        val totals = listOf(
            ProfileTrafficTotal(
                profileId = TOR_ONLY_PROFILE_ID,
                profileName = "TOR",
                protocolHint = ProtocolHint.CUSTOM_CONFIG,
                rxTotalBytes = 10,
                updatedAt = 3,
            ),
            ProfileTrafficTotal(
                profileId = LOCAL_GUARD_PROFILE_ID,
                profileName = "Local firewall",
                protocolHint = ProtocolHint.CUSTOM_CONFIG,
                rxTotalBytes = 20,
                updatedAt = 2,
            ),
            ProfileTrafficTotal(
                profileId = 7L,
                profileName = "fox",
                protocolHint = ProtocolHint.VLESS,
                rxTotalBytes = 30,
                updatedAt = 1,
            ),
        )
        assertEquals(listOf(7L), protocolTrafficItems(state(totals)).map { it.profileId })
        assertEquals(listOf("fox"), profileTrafficItems(state(totals)).map { it.profileName })
    }

    @Test
    fun `custom config hint is not a displayable protocol row`() {
        val totals = listOf(
            ProfileTrafficTotal(
                profileId = 7L,
                profileName = "fox",
                protocolHint = ProtocolHint.VLESS,
                rxTotalBytes = 30,
                updatedAt = 1,
            ),
            ProfileTrafficTotal(
                profileId = 8L,
                profileName = "raw",
                protocolHint = ProtocolHint.CUSTOM_CONFIG,
                rxTotalBytes = 40,
                updatedAt = 2,
            ),
        )
        val state = state(totals)
        val protocols = protocolStatistics(state, protocolTrafficItems(state))
        assertEquals(listOf(ProtocolHint.VLESS), protocols.map { it.protocol })
    }
}
