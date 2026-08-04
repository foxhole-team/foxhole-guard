package com.foxhole.guard.runtime

import com.foxhole.core.model.PerAppRoutingMode
import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.VpnSession
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitVpnRecoveryPolicyTest {
    @Test
    fun `split modes preserve direct lane during VPN recovery`() {
        val session = vpnSession(profileId = 42L)

        assertTrue(splitVpnRecoveryRequired(session, PerAppRoutingMode.INCLUDE_SELECTED_APPS, listOf("vpn.app")))
        assertTrue(splitVpnRecoveryRequired(session, PerAppRoutingMode.EXCLUDE_SELECTED_APPS, listOf("direct.app")))
        assertFalse(splitVpnRecoveryRequired(session, PerAppRoutingMode.FULL_TUNNEL, listOf("stale.app")))
        assertFalse(splitVpnRecoveryRequired(session, PerAppRoutingMode.INCLUDE_SELECTED_APPS, emptyList()))
    }

    @Test
    fun `Tor-only and missing sessions do not enter split VPN recovery`() {
        assertFalse(splitVpnRecoveryRequired(null, PerAppRoutingMode.INCLUDE_SELECTED_APPS, listOf("vpn.app")))
        assertFalse(
            splitVpnRecoveryRequired(
                vpnSession(profileId = FoxholeVpnService.TOR_ONLY_PROFILE_ID),
                PerAppRoutingMode.INCLUDE_SELECTED_APPS,
                listOf("vpn.app"),
            ),
        )
    }

    private fun vpnSession(profileId: Long) = VpnSession(
        profileId = profileId,
        profileName = "test",
        protocolHint = ProtocolHint.WIREGUARD,
        configJson = "{}",
        correlationId = "test-session",
    )
}
