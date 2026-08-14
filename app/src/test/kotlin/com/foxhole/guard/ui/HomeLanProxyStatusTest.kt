package com.foxhole.guard.ui

import com.foxhole.core.model.ConnectionSnapshot
import com.foxhole.core.model.ExpertSettings
import com.foxhole.core.model.LanProxyPhase
import com.foxhole.core.model.LanProxyStatusSnapshot
import com.foxhole.core.model.LanProxyUnavailableReason
import com.foxhole.core.model.LanProxyUpstream
import com.foxhole.core.model.LocalAuthSettings
import com.foxhole.core.model.LocalSurfaceSettings
import com.foxhole.core.model.ProxyInboundSettings
import com.foxhole.core.model.ProxySurfaceMode
import com.foxhole.core.model.Settings
import com.foxhole.core.model.TOR_ONLY_PROFILE_ID
import com.foxhole.core.model.UiSettings
import com.foxhole.core.runtime.LanNetworkBinding
import com.foxhole.guard.runtime.lanProxyRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The dashboard pill used to render the saved switch, so a LAN proxy that never bound still showed
 * as ON to everyone on the Wi-Fi. These tests pin the replacement: green means the core reported
 * bound listeners, and nothing else does.
 */
class HomeLanProxyStatusTest {
    private fun state(
        allowLanAccess: Boolean,
        lanProxy: LanProxyStatusSnapshot,
    ) = HomeRouteUiState(
        settings =
        Settings(
            ui = UiSettings(showLanProxyQuickAccess = true),
            expert =
            ExpertSettings(
                localSurfaces = LocalSurfaceSettings(allowLanAccess = allowLanAccess),
            ),
        ),
        lanProxy = lanProxy,
    )

    @Test
    fun `a switch that is off is the only off`() {
        val indicator =
            homeLanProxyFeatureStatus(
                state(allowLanAccess = false, lanProxy = LanProxyStatusSnapshot(phase = LanProxyPhase.READY)),
            )
        assertEquals(HomeConnectionFeatureStatus.OFF, indicator)
    }

    @Test
    fun `only bound listeners read as on`() {
        assertEquals(
            HomeConnectionFeatureStatus.ON,
            homeLanProxyFeatureStatus(
                state(allowLanAccess = true, lanProxy = LanProxyStatusSnapshot(phase = LanProxyPhase.READY)),
            ),
        )
    }

    @Test
    fun `asked for but not serving is amber, never green`() {
        listOf(
            LanProxyPhase.OFF,
            LanProxyPhase.ARMING,
            LanProxyPhase.DEGRADED,
            LanProxyPhase.NETWORK_LOST,
            LanProxyPhase.FAILED,
            LanProxyPhase.UNAVAILABLE,
        ).forEach { phase ->
            assertEquals(
                phase.name,
                HomeConnectionFeatureStatus.PENDING,
                homeLanProxyFeatureStatus(
                    state(allowLanAccess = true, lanProxy = LanProxyStatusSnapshot(phase = phase)),
                ),
            )
        }
    }

    @Test
    fun `the reason survives onto the state so the screen can explain the amber pill`() {
        val status =
            state(
                allowLanAccess = true,
                lanProxy =
                LanProxyStatusSnapshot(
                    phase = LanProxyPhase.UNAVAILABLE,
                    reason = LanProxyUnavailableReason.PACKET_TUNNEL,
                ),
            )
        assertEquals(LanProxyUnavailableReason.PACKET_TUNNEL, status.lanProxy.reason)
    }
}

/** The request the service hands the core: ports per protocol, upstream from the live session. */
class LanProxyRequestTest {
    private val binding =
        LanNetworkBinding(
            networkHandle = 7L,
            localAddress = "10.0.0.5",
            interfaceName = "wlan0",
            transport = "wifi",
        )

    private fun surfaces(mode: ProxySurfaceMode) =
        LocalSurfaceSettings(
            lanProxyMode = mode,
            socks = ProxyInboundSettings(port = 10808),
            http = ProxyInboundSettings(port = 10809),
            allowLanAccess = true,
            lanAuth = LocalAuthSettings(enabled = true, username = "u", password = "p"),
        )

    @Test
    fun `socks only offers the socks port`() {
        val request = surfaces(ProxySurfaceMode.SOCKS5).lanProxyRequest(ConnectionSnapshot(), binding)
        assertEquals(10808, request?.socksPort)
        assertEquals(0, request?.httpPort)
    }

    @Test
    fun `both is two listeners on two ports`() {
        val request = surfaces(ProxySurfaceMode.ALL).lanProxyRequest(ConnectionSnapshot(), binding)
        assertEquals(10808, request?.socksPort)
        assertEquals(10809, request?.httpPort)
    }

    @Test
    fun `both alongside an engaged tor route is the mixed preset`() {
        val request =
            surfaces(ProxySurfaceMode.ALL)
                .lanProxyRequest(ConnectionSnapshot(torActive = true), binding)
        assertEquals(LanProxyUpstream.MIXED, request?.upstream)
    }

    @Test
    fun `a tor-only session can only offer tor`() {
        val request =
            surfaces(ProxySurfaceMode.SOCKS5)
                .lanProxyRequest(ConnectionSnapshot(profileId = TOR_ONLY_PROFILE_ID), binding)
        assertEquals(LanProxyUpstream.TOR, request?.upstream)
    }

    @Test
    fun `an empty password produces no request at all`() {
        val request =
            surfaces(ProxySurfaceMode.SOCKS5)
                .copy(lanAuth = LocalAuthSettings(enabled = true, username = "u", password = " "))
                .lanProxyRequest(ConnectionSnapshot(), binding)
        assertNull(request)
    }
}
