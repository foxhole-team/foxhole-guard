package com.foxhole.core.runtime

import com.foxhole.core.model.AppliedTorRoute
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.IpInfo
import com.foxhole.core.model.NotificationSnapshot
import com.foxhole.core.model.PrivacyRouteScope
import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.RuntimeTeardownPhase
import com.foxhole.core.model.TorNetworkPhase
import com.foxhole.core.model.TrafficMode
import com.foxhole.core.model.TrafficSnapshot
import com.foxhole.core.model.VpnSession
import com.foxhole.guard.R
import com.foxhole.guard.runtime.FoxholeConnectionServiceContract
import com.foxhole.guard.runtime.FoxholeNotificationRouteKind
import com.foxhole.guard.runtime.FoxholeNotificationSplitScope
import com.foxhole.guard.runtime.appliedTorNotificationSplitScope
import com.foxhole.guard.runtime.notificationActionForState
import com.foxhole.guard.runtime.notificationRouteKind
import com.foxhole.guard.runtime.notificationTeardownBodyRes
import com.foxhole.guard.runtime.notificationTorTransitionTitleRes
import com.foxhole.guard.runtime.shouldShowConnectionNotificationAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionNotificationSupportTest {
    @Test
    fun `active states keep stop action in the controller notification`() {
        listOf(
            ConnectionState.CONNECTING,
            ConnectionState.CONNECTED,
            ConnectionState.RECONNECTING,
        ).forEach { state ->
            val action = notificationActionForState(state)

            assertEquals(FoxholeConnectionServiceContract.ACTION_DISCONNECT, action.serviceAction)
            assertEquals(R.string.disconnect, action.labelRes)
            assertTrue(action.ongoing)
        }
    }

    @Test
    fun `idle states switch the controller notification back to start`() {
        listOf(ConnectionState.IDLE, ConnectionState.ERROR).forEach { state ->
            val action = notificationActionForState(state)

            assertEquals(FoxholeConnectionServiceContract.ACTION_RESTORE, action.serviceAction)
            assertEquals(R.string.connect, action.labelRes)
            assertFalse(action.ongoing)
        }
    }

    @Test
    fun `connected snapshot with a status message keeps the standard stop command`() {
        val snapshot =
            NotificationSnapshot(
                state = ConnectionState.CONNECTED,
                statusMessage = "Analysis",
            )
        val action = notificationActionForState(snapshot.state)

        assertEquals(FoxholeConnectionServiceContract.ACTION_DISCONNECT, action.serviceAction)
        assertEquals(R.string.disconnect, action.labelRes)
        assertTrue(action.ongoing)
    }

    @Test
    fun `disconnecting notification keeps foreground ownership without a duplicate action`() {
        assertTrue(notificationActionForState(ConnectionState.DISCONNECTING).ongoing)
        assertFalse(shouldShowConnectionNotificationAction(ConnectionState.DISCONNECTING))
        assertTrue(shouldShowConnectionNotificationAction(ConnectionState.CONNECTED))
    }

    @Test
    fun `Tor route is not announced connected before the confirmed phase`() {
        assertEquals(
            R.string.notification_status_tor_connecting,
            notificationTorTransitionTitleRes(
                FoxholeNotificationRouteKind.TOR_IN_VPN,
                TorNetworkPhase.CONNECTING,
            ),
        )
        assertEquals(
            R.string.notification_status_tor_building,
            notificationTorTransitionTitleRes(
                FoxholeNotificationRouteKind.TOR_ONLY,
                TorNetworkPhase.BUILDING_CIRCUITS,
            ),
        )
        assertNull(
            notificationTorTransitionTitleRes(
                FoxholeNotificationRouteKind.TOR_IN_VPN,
                TorNetworkPhase.CONNECTED,
            ),
        )
    }

    @Test
    fun `notification route follows the applied TOR descriptor`() {
        val inVpn = session().copy(torActive = true, appliedTorRoute = torRoute(bypassVpn = false))
        val besideVpn = session().copy(torActive = true, appliedTorRoute = torRoute(bypassVpn = true))
        val torOnly =
            session().copy(profileId = com.foxhole.guard.runtime.FoxholeVpnService.TOR_ONLY_PROFILE_ID)

        assertEquals(FoxholeNotificationRouteKind.TOR_IN_VPN, notificationRouteKind(inVpn, TrafficMode.TUNNEL))
        assertEquals(FoxholeNotificationRouteKind.TOR_BESIDE_VPN, notificationRouteKind(besideVpn, TrafficMode.TUNNEL))
        assertEquals(FoxholeNotificationRouteKind.TOR_ONLY, notificationRouteKind(torOnly, TrafficMode.TUNNEL))
        assertEquals(FoxholeNotificationRouteKind.VPN_TUNNEL, notificationRouteKind(session(), TrafficMode.TUNNEL))
        assertEquals(FoxholeNotificationRouteKind.VPN_PROXY, notificationRouteKind(session(), TrafficMode.PROXY))
        assertNull(notificationRouteKind(null, TrafficMode.TUNNEL))
    }

    @Test
    fun `notification scope follows the applied selected-app TOR descriptor`() {
        val selected =
            session().copy(
                torActive = true,
                appliedTorRoute =
                AppliedTorRoute(
                    scope = PrivacyRouteScope.SELECTED_APPS,
                    bypassVpnTunnel = false,
                    selectedPackages = listOf("org.example.browser"),
                ),
            )
        val allApps = selected.copy(appliedTorRoute = torRoute(bypassVpn = false))

        assertEquals(FoxholeNotificationSplitScope.INCLUDE, appliedTorNotificationSplitScope(selected))
        assertNull(appliedTorNotificationSplitScope(allApps))
        assertNull(appliedTorNotificationSplitScope(selected.copy(torActive = false)))
    }

    @Test
    fun `disconnecting details follow the runtime teardown owner`() {
        assertEquals(
            R.string.notification_body_disconnecting_vpn,
            notificationTeardownBodyRes(RuntimeTeardownPhase.VPN),
        )
        assertEquals(
            R.string.notification_body_disconnecting_tor,
            notificationTeardownBodyRes(RuntimeTeardownPhase.TOR),
        )
        assertEquals(
            R.string.notification_body_disconnecting_i2p,
            notificationTeardownBodyRes(RuntimeTeardownPhase.I2P),
        )
        assertEquals(
            R.string.notification_body_disconnecting_android_tunnel,
            notificationTeardownBodyRes(RuntimeTeardownPhase.ANDROID_TUNNEL),
        )
    }

    @Test
    fun `clearing transient state can preserve the last ip info`() {
        val previousIpInfo = FoxholeVpnRuntimeBridge.ipInfo.value
        val previousTraffic = FoxholeVpnRuntimeBridge.traffic.value
        val previousHighFrequency = FoxholeVpnRuntimeBridge.highFrequencyTrafficUpdates.value
        val retainedIp =
            IpInfo(
                ip = "198.51.100.10",
                ipv4 = "198.51.100.10",
                countryCode = "NL",
                countryName = "Netherlands",
                city = "Amsterdam",
                isp = "Foxhole Test ISP",
                fetchedAt = 1234L,
            )

        try {
            FoxholeVpnRuntimeBridge.updateIpInfo(retainedIp)
            FoxholeVpnRuntimeBridge.updateTraffic(
                TrafficSnapshot(
                    available = true,
                    rxTotalBytes = 1024,
                    txTotalBytes = 2048,
                ),
            )
            FoxholeVpnRuntimeBridge.setHighFrequencyTrafficUpdates(true)

            FoxholeVpnRuntimeBridge.clearTransientState(clearIpInfo = false)

            assertEquals(retainedIp, FoxholeVpnRuntimeBridge.ipInfo.value)
            assertEquals(TrafficSnapshot(), FoxholeVpnRuntimeBridge.traffic.value)
            assertFalse(FoxholeVpnRuntimeBridge.highFrequencyTrafficUpdates.value)
        } finally {
            FoxholeVpnRuntimeBridge.updateIpInfo(previousIpInfo)
            FoxholeVpnRuntimeBridge.updateTraffic(previousTraffic)
            FoxholeVpnRuntimeBridge.setHighFrequencyTrafficUpdates(previousHighFrequency)
        }
    }

    @Test
    fun `runtime ip update keeps known location when same address refresh is partial`() {
        val previousIpInfo = FoxholeVpnRuntimeBridge.ipInfo.value
        val rich =
            IpInfo(
                ip = "198.51.100.10",
                ipv4 = "198.51.100.10",
                countryCode = "NL",
                countryName = "Netherlands",
                city = "Amsterdam",
                isp = "Foxhole Test ISP",
                fetchedAt = 1234L,
            )
        val partial =
            rich.copy(
                countryCode = null,
                countryName = null,
                city = null,
                isp = null,
                fetchedAt = 2234L,
            )

        try {
            FoxholeVpnRuntimeBridge.updateIpInfo(rich)
            FoxholeVpnRuntimeBridge.updateIpInfo(partial)

            assertEquals(
                rich.copy(fetchedAt = partial.fetchedAt),
                FoxholeVpnRuntimeBridge.ipInfo.value,
            )
        } finally {
            FoxholeVpnRuntimeBridge.updateIpInfo(previousIpInfo)
        }
    }

    private fun session() =
        VpnSession(
            profileId = 42L,
            profileName = "test",
            protocolHint = ProtocolHint.VLESS,
            configJson = "{}",
            correlationId = "notification-route",
        )

    private fun torRoute(bypassVpn: Boolean) =
        AppliedTorRoute(
            scope = PrivacyRouteScope.ALL_APPS,
            bypassVpnTunnel = bypassVpn,
        )
}
