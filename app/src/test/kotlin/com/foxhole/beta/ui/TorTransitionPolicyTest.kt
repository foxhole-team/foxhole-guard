package com.foxhole.beta.ui

import com.foxhole.beta.core.model.ConnectionSnapshot
import com.foxhole.beta.core.model.ConnectionState
import com.foxhole.beta.core.model.PrivacyRouteMode
import com.foxhole.beta.core.model.PrivacyRouteSettings
import com.foxhole.beta.core.model.Profile
import com.foxhole.beta.core.model.ProfileProtocolOption
import com.foxhole.beta.core.model.ProfileSourceType
import com.foxhole.beta.core.model.ProtocolHint
import com.foxhole.beta.core.model.Settings
import com.foxhole.beta.core.model.TorRoutePlacement
import com.foxhole.beta.vpn.FoxholeVpnService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TorTransitionPolicyTest {
    @Test
    fun `selecting UDP while Tor is active requires disable prompt`() {
        assertTrue(shouldPromptDisableTorForUdpProtocol(torEnabledOrRuntimeActive = true, switchingToUdp = true))
        assertFalse(shouldPromptDisableTorForUdpProtocol(torEnabledOrRuntimeActive = true, switchingToUdp = false))
        assertFalse(shouldPromptDisableTorForUdpProtocol(torEnabledOrRuntimeActive = false, switchingToUdp = true))
    }

    @Test
    fun `starting TCP VPN while Tor-only is active requires placement prompt`() {
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
    fun `resolves Tor route placement from settings and connection`() {
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
                sourceType = ProfileSourceType.RAW_SINGBOX_JSON,
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
