package com.foxhole.guard.ui

import com.foxhole.core.model.ConnectionSnapshot
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.PrivacyRouteMode
import com.foxhole.core.model.PrivacyRouteSettings
import com.foxhole.core.model.Profile
import com.foxhole.core.model.ProfileProtocolOption
import com.foxhole.core.model.ProfileSourceType
import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.Settings
import com.foxhole.core.model.TorRoutePlacement
import com.foxhole.guard.runtime.FoxholeVpnService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TorTransitionPolicyTest {
    @Test
    fun `selecting UDP while TOR is active requires disable prompt`() {
        assertTrue(shouldPromptDisableTorForUdpProtocol(torEnabledOrRuntimeActive = true, switchingToUdp = true))
        assertFalse(shouldPromptDisableTorForUdpProtocol(torEnabledOrRuntimeActive = true, switchingToUdp = false))
        assertFalse(shouldPromptDisableTorForUdpProtocol(torEnabledOrRuntimeActive = false, switchingToUdp = true))
    }

    @Test
    fun `starting TCP VPN while TOR-only is active requires placement prompt`() {
        assertTrue(
            shouldPromptStartTcpVpnWhileTorOnlyActive(
                torRoutePlacement = TorRoutePlacement.TOR_ONLY_DEVICE,
                targetProtocolIsUdp = false,
            ),
        )
        assertFalse(
            shouldPromptStartTcpVpnWhileTorOnlyActive(
                torRoutePlacement = TorRoutePlacement.TOR_ONLY_DEVICE,
                targetProtocolIsUdp = true,
            ),
        )
        assertFalse(
            shouldPromptStartTcpVpnWhileTorOnlyActive(
                torRoutePlacement = TorRoutePlacement.TOR_OVER_VPN,
                targetProtocolIsUdp = false,
            ),
        )
    }

    @Test
    fun `resolves TOR route placement from settings and connection`() {
        assertEquals(
            TorRoutePlacement.OFF,
            resolveTorRoutePlacement(
                settings = Settings(privacyRoute = PrivacyRouteSettings(mode = PrivacyRouteMode.OFF)),
                connection = ConnectionSnapshot(state = ConnectionState.IDLE),
            ),
        )
        assertEquals(
            TorRoutePlacement.TOR_ONLY_DEVICE,
            resolveTorRoutePlacement(
                settings = Settings(privacyRoute = PrivacyRouteSettings(mode = PrivacyRouteMode.TOR_OVER_VPN)),
                connection = ConnectionSnapshot(
                    state = ConnectionState.CONNECTED,
                    profileId = FoxholeVpnService.TOR_ONLY_PROFILE_ID,
                ),
            ),
        )
        assertEquals(
            TorRoutePlacement.TOR_OVER_VPN,
            resolveTorRoutePlacement(
                settings = Settings(privacyRoute = PrivacyRouteSettings(mode = PrivacyRouteMode.TOR_OVER_VPN)),
                connection = ConnectionSnapshot(
                    state = ConnectionState.CONNECTED,
                    profileId = 42L,
                ),
            ),
        )
        assertEquals(
            TorRoutePlacement.TOR_ON_DEVICE_WITH_VPN,
            resolveTorRoutePlacement(
                settings = Settings(
                    privacyRoute = PrivacyRouteSettings(
                        mode = PrivacyRouteMode.TOR_OVER_VPN,
                        bypassVpnTunnel = true,
                    ),
                ),
                connection = ConnectionSnapshot(
                    state = ConnectionState.CONNECTED,
                    profileId = 42L,
                ),
            ),
        )
    }

    @Test
    fun `profile protocol lookup prefers requested option then selected option`() {
        val profile =
            Profile(
                id = 42L,
                name = "Smart",
                sourceType = ProfileSourceType.RAW_CONFIG_JSON,
                secretRef = "secret",
                protocolHint = ProtocolHint.VLESS,
                lastUpdatedAt = null,
                lastEtag = null,
                protocolOptions = listOf(
                    ProfileProtocolOption("tcp", "TCP", ProtocolHint.VLESS),
                    ProfileProtocolOption("udp", "UDP", ProtocolHint.WIREGUARD),
                ),
                selectedProtocolOptionId = "tcp",
                isActive = true,
            )

        assertEquals("udp", profile.protocolOptionOrDefault("udp")?.id)
        assertEquals("tcp", profile.protocolOptionOrDefault(null)?.id)
        assertEquals("tcp", profile.protocolOptionOrDefault("missing")?.id)
    }
}
