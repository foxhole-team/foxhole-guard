package com.foxhole.guard.runtime

import com.foxhole.core.model.PrivacyRouteMode
import com.foxhole.core.model.PrivacyRouteSettings
import com.foxhole.core.model.Profile
import com.foxhole.core.model.ProfileProtocolOption
import com.foxhole.core.model.ProfileSourceType
import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VpnTorStartPreflightTest {
    @Test
    fun `ordinary VPN does not require a profile through the TOR preflight`() {
        assertNull(vpnTorStartBlockReason(Settings(), null, null, torOnlyConnect = false))
    }

    @Test
    fun `direct TOR remains the supported profile-less route`() {
        assertNull(
            vpnTorStartBlockReason(
                settings = vpnTorSettings(bypassVpnTunnel = true),
                profile = null,
                protocolOptionId = null,
                torOnlyConnect = true,
            ),
        )
    }

    @Test
    fun `VPN plus TOR refuses to fall back to TOR-only without a profile`() {
        assertEquals(
            VpnTorStartBlockReason.PROFILE_REQUIRED,
            vpnTorStartBlockReason(
                settings = vpnTorSettings(),
                profile = null,
                protocolOptionId = null,
                torOnlyConnect = true,
            ),
        )
    }

    @Test
    fun `VPN plus TOR blocks a selected UDP protocol`() {
        val profile = profileWithOptions(selectedId = "wg")
        assertEquals(
            VpnTorStartBlockReason.INCOMPATIBLE_VPN_PROTOCOL,
            vpnTorStartBlockReason(vpnTorSettings(), profile, null, torOnlyConnect = false),
        )
    }

    @Test
    fun `VPN plus TOR allows a selected TCP protocol`() {
        val profile = profileWithOptions(selectedId = "vless")
        assertNull(vpnTorStartBlockReason(vpnTorSettings(), profile, null, torOnlyConnect = false))
    }

    @Test
    fun `explicit UDP override is checked instead of persisted TCP selection`() {
        val profile = profileWithOptions(selectedId = "vless")
        assertEquals(
            VpnTorStartBlockReason.INCOMPATIBLE_VPN_PROTOCOL,
            vpnTorStartBlockReason(vpnTorSettings(), profile, "wg", torOnlyConnect = false),
        )
    }

    @Test
    fun `every shipped UDP transport is incompatible with VPN plus TOR`() {
        listOf(ProtocolHint.HYSTERIA2, ProtocolHint.TUIC, ProtocolHint.WIREGUARD).forEach { hint ->
            val profile = profileWithHint(hint)
            assertEquals(
                hint.name,
                VpnTorStartBlockReason.INCOMPATIBLE_VPN_PROTOCOL,
                vpnTorStartBlockReason(vpnTorSettings(), profile, null, torOnlyConnect = false),
            )
        }
    }

    private fun vpnTorSettings(bypassVpnTunnel: Boolean = false): Settings =
        Settings(
            privacyRoute =
            PrivacyRouteSettings(
                mode = PrivacyRouteMode.TOR_OVER_VPN,
                bypassVpnTunnel = bypassVpnTunnel,
            ),
        )

    private fun profileWithOptions(selectedId: String): Profile =
        Profile(
            id = 1L,
            name = "test",
            sourceType = ProfileSourceType.RAW_CONFIG_JSON,
            secretRef = "secret",
            protocolHint = ProtocolHint.VLESS,
            lastUpdatedAt = null,
            lastEtag = null,
            protocolOptions =
            listOf(
                ProfileProtocolOption("vless", "VLESS", ProtocolHint.VLESS),
                ProfileProtocolOption("wg", "WireGuard", ProtocolHint.WIREGUARD),
            ),
            selectedProtocolOptionId = selectedId,
            isActive = true,
        )

    private fun profileWithHint(hint: ProtocolHint): Profile =
        profileWithOptions(selectedId = "vless").copy(
            protocolHint = hint,
            protocolOptions = emptyList(),
            selectedProtocolOptionId = null,
        )
}
