package com.foxhole.guard.ui

import com.foxhole.core.model.RoutingModePreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

internal class LiveModeSwitchKindTest {
    @Test
    fun `vpn to vpn+tor attaches tor`() {
        assertEquals(
            LiveModeSwitchKind.ATTACH_TOR,
            liveModeSwitchKind(RoutingModePreset.VPN, RoutingModePreset.VPN_TOR),
        )
    }

    @Test
    fun `split to vpn+tor attaches tor`() {
        assertEquals(
            LiveModeSwitchKind.ATTACH_TOR,
            liveModeSwitchKind(RoutingModePreset.SPLIT_INCLUDE, RoutingModePreset.VPN_TOR),
        )
    }

    @Test
    fun `vpn+tor to vpn detaches tor`() {
        assertEquals(
            LiveModeSwitchKind.DETACH_TOR,
            liveModeSwitchKind(RoutingModePreset.VPN_TOR, RoutingModePreset.VPN),
        )
    }

    @Test
    fun `vpn+tor to split detaches tor`() {
        assertEquals(
            LiveModeSwitchKind.DETACH_TOR,
            liveModeSwitchKind(RoutingModePreset.VPN_TOR, RoutingModePreset.SPLIT_EXCLUDE),
        )
    }

    @Test
    fun `vpn to pure tor stops vpn`() {
        assertEquals(
            LiveModeSwitchKind.TOR_STOPS_VPN,
            liveModeSwitchKind(RoutingModePreset.VPN, RoutingModePreset.TOR),
        )
    }

    @Test
    fun `vpn+tor to pure tor stops vpn`() {
        assertEquals(
            LiveModeSwitchKind.TOR_STOPS_VPN,
            liveModeSwitchKind(RoutingModePreset.VPN_TOR, RoutingModePreset.TOR),
        )
    }

    @Test
    fun `split to split needs no modal`() {
        assertNull(liveModeSwitchKind(RoutingModePreset.SPLIT_INCLUDE, RoutingModePreset.SPLIT_EXCLUDE))
    }

    @Test
    fun `same mode needs no modal`() {
        assertNull(liveModeSwitchKind(RoutingModePreset.VPN, RoutingModePreset.VPN))
    }
}
