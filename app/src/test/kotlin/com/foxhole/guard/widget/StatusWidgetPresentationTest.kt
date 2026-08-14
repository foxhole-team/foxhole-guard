package com.foxhole.guard.widget

import com.foxhole.core.model.AnomalySettings
import com.foxhole.core.model.AppTunnelLane
import com.foxhole.core.model.ConnectionSnapshot
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.ExpertSettings
import com.foxhole.core.model.IpInfo
import com.foxhole.core.model.LOCAL_GUARD_PROFILE_ID
import com.foxhole.core.model.PerAppRoutingMode
import com.foxhole.core.model.PrivacyRouteMode
import com.foxhole.core.model.PrivacyRouteScope
import com.foxhole.core.model.PrivacyRouteSettings
import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.Settings
import com.foxhole.core.model.SmartProfilePreference
import com.foxhole.core.model.TOR_ONLY_PROFILE_ID
import com.foxhole.guard.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal class StatusWidgetPresentationTest {
    @Test
    fun `offline exposes only start while active route exposes stop and restart`() {
        assertEquals(
            listOf(StatusWidgetControlAction.START),
            statusWidgetControlActions(StatusWidgetConnection.DISCONNECTED, primaryActive = false),
        )
        assertEquals(
            listOf(StatusWidgetControlAction.STOP, StatusWidgetControlAction.RESTART),
            statusWidgetControlActions(StatusWidgetConnection.CONNECTED, primaryActive = true),
        )
        assertEquals(
            listOf(StatusWidgetControlAction.STOP),
            statusWidgetControlActions(StatusWidgetConnection.RECONNECTING, primaryActive = true),
        )
        assertTrue(
            statusWidgetControlActions(
                StatusWidgetConnection.DISCONNECTING,
                primaryActive = true,
            ).isEmpty(),
        )
    }

    @Test
    fun `local guard to primary route changes the widget runtime key`() {
        val guard =
            ConnectionSnapshot(
                state = ConnectionState.CONNECTED,
                profileId = LOCAL_GUARD_PROFILE_ID,
            )
        val primary =
            ConnectionSnapshot(
                state = ConnectionState.CONNECTED,
                profileId = 7L,
            )

        assertFalse(guard.statusWidgetRuntimeKey() == primary.statusWidgetRuntimeKey())
    }

    @Test
    fun `refresh spinner keeps the same restart glyph through four pixel frames`() {
        assertEquals(R.drawable.widget_refresh_pixel, statusWidgetRefreshSpinnerFrame(0))
        assertEquals(R.drawable.widget_refresh_spinner_90, statusWidgetRefreshSpinnerFrame(1))
        assertEquals(R.drawable.widget_refresh_spinner_180, statusWidgetRefreshSpinnerFrame(2))
        assertEquals(R.drawable.widget_refresh_spinner_270, statusWidgetRefreshSpinnerFrame(3))
        assertEquals(R.drawable.widget_refresh_pixel, statusWidgetRefreshSpinnerFrame(4))
    }

    @Test
    fun `fox status frame is closed offline and open when static connected`() {
        assertEquals(R.drawable.fhg_status_frame_4, foxStatusFrame(false, true, R.drawable.fhg_status_frame_2))
        assertEquals(R.drawable.fhg_status_frame_1, foxStatusFrame(true, false, R.drawable.fhg_status_frame_4))
        assertEquals(R.drawable.fhg_status_frame_6, foxStatusFrame(true, true, R.drawable.fhg_status_frame_6))
    }

    @Test
    fun `idle widget keeps connection facts empty but shows the configured mode and scenario`() {
        val presentation =
            statusWidgetPresentation(
                snapshot = ConnectionSnapshot(),
                settings = Settings(),
                vpnIpInfo = ipInfo("203.0.113.2", "NL"),
                torIpInfo = ipInfo("198.51.100.3", "DE"),
                i2pConnected = false,
            )

        assertEquals(StatusWidgetConnection.DISCONNECTED, presentation.connection)
        assertEquals(StatusWidgetMode.VPN, presentation.mode)
        assertEquals(StatusWidgetScope.WHOLE_DEVICE, presentation.scenario?.vpn)
        assertNull(presentation.scenario?.tor)
        assertNull(presentation.vpnProfile)
        assertNull(presentation.vpnIdentity)
        assertNull(presentation.torIdentity)
    }

    @Test
    fun `idle widget projects the configured vpn tor mode and both independent scopes`() {
        val presentation =
            statusWidgetPresentation(
                snapshot = ConnectionSnapshot(),
                settings =
                Settings(
                    expert =
                    ExpertSettings(
                        perAppRoutingMode = PerAppRoutingMode.EXCLUDE_SELECTED_APPS,
                        appAssignments = mapOf("org.example.direct" to AppTunnelLane.VPN),
                    ),
                    privacyRoute =
                    PrivacyRouteSettings(
                        mode = PrivacyRouteMode.TOR_OVER_VPN,
                        scope = PrivacyRouteScope.SELECTED_APPS,
                    ),
                ),
                vpnIpInfo = null,
                torIpInfo = null,
                i2pConnected = false,
            )

        assertEquals(StatusWidgetConnection.DISCONNECTED, presentation.connection)
        assertEquals(StatusWidgetMode.VPN_TOR, presentation.mode)
        assertEquals(StatusWidgetScope.EXCEPT_SELECTED, presentation.scenario?.vpn)
        assertEquals(StatusWidgetScope.SELECTED_APPS, presentation.scenario?.tor)
    }

    @Test
    fun `live snapshot mode wins over a different configured mode during transitions`() {
        val presentation =
            statusWidgetPresentation(
                snapshot =
                ConnectionSnapshot(
                    state = ConnectionState.RECONNECTING,
                    profileId = TOR_ONLY_PROFILE_ID,
                    torActive = true,
                ),
                settings = Settings(),
                vpnIpInfo = null,
                torIpInfo = null,
                i2pConnected = false,
            )

        assertEquals(StatusWidgetConnection.RECONNECTING, presentation.connection)
        assertEquals(StatusWidgetMode.TOR, presentation.mode)
        assertNull(presentation.scenario?.vpn)
        assertEquals(StatusWidgetScope.WHOLE_DEVICE, presentation.scenario?.tor)
    }

    @Test
    fun `connecting vpn exposes its applied live mode instead of a dash`() {
        val presentation =
            statusWidgetPresentation(
                snapshot =
                ConnectionSnapshot(
                    state = ConnectionState.CONNECTING,
                    profileId = 7L,
                    profileName = "Fox profile",
                    protocolHint = ProtocolHint.VLESS,
                ),
                settings =
                Settings(
                    privacyRoute =
                    PrivacyRouteSettings(
                        mode = PrivacyRouteMode.TOR_OVER_VPN,
                        scope = PrivacyRouteScope.SELECTED_APPS,
                    ),
                ),
                vpnIpInfo = null,
                torIpInfo = null,
                i2pConnected = false,
            )

        assertEquals(StatusWidgetConnection.CONNECTING, presentation.connection)
        assertEquals(StatusWidgetMode.VPN, presentation.mode)
        assertEquals(StatusWidgetScope.WHOLE_DEVICE, presentation.scenario?.vpn)
        assertNull(presentation.scenario?.tor)
    }

    @Test
    fun `empty exclude selection is truthfully the whole-device scenario`() {
        val presentation =
            statusWidgetPresentation(
                snapshot = ConnectionSnapshot(),
                settings =
                Settings(
                    expert = ExpertSettings(perAppRoutingMode = PerAppRoutingMode.EXCLUDE_SELECTED_APPS),
                ),
                vpnIpInfo = null,
                torIpInfo = null,
                i2pConnected = false,
            )

        assertEquals(StatusWidgetMode.VPN, presentation.mode)
        assertEquals(StatusWidgetScope.WHOLE_DEVICE, presentation.scenario?.vpn)
    }

    @Test
    fun `firewall carrier never turns the only start control into stop`() {
        val snapshot =
            ConnectionSnapshot(
                state = ConnectionState.CONNECTED,
                profileId = LOCAL_GUARD_PROFILE_ID,
                protocolHint = ProtocolHint.LOCAL_GUARD,
            )
        val presentation =
            statusWidgetPresentation(
                snapshot = snapshot,
                settings = Settings(expert = ExpertSettings(firewallEnabled = true)),
                vpnIpInfo = null,
                torIpInfo = null,
                i2pConnected = false,
            )

        assertEquals(StatusWidgetConnection.DISCONNECTED, presentation.connection)
        assertEquals(listOf(StatusWidgetComponent.FIREWALL), presentation.components)
        assertFalse(widgetHasPrimaryConnection(snapshot))
    }

    @Test
    fun `vpn tor table exposes both identities and exact active components`() {
        val snapshot =
            ConnectionSnapshot(
                state = ConnectionState.CONNECTED,
                profileId = 7L,
                profileName = "Fox profile",
                protocolHint = ProtocolHint.VLESS,
                torActive = true,
            )
        val settings =
            Settings(
                expert =
                ExpertSettings(
                    firewallEnabled = true,
                    perAppRoutingMode = PerAppRoutingMode.INCLUDE_SELECTED_APPS,
                ),
                privacyRoute = PrivacyRouteSettings(scope = PrivacyRouteScope.ALL_APPS),
                anomaly = AnomalySettings(enabled = true),
                smartProfilePreferences =
                listOf(SmartProfilePreference(profileId = 7L, lastKnownGoodLatencyMs = 41L)),
            )
        val vpn = ipInfo("203.0.113.7", "NL")
        val tor = ipInfo("198.51.100.8", "DE")

        val presentation =
            statusWidgetPresentation(
                snapshot = snapshot,
                settings = settings,
                vpnIpInfo = vpn,
                torIpInfo = tor,
                i2pConnected = true,
            )

        assertEquals(StatusWidgetConnection.CONNECTED, presentation.connection)
        assertEquals(StatusWidgetMode.VPN_TOR, presentation.mode)
        assertEquals(StatusWidgetScope.SELECTED_APPS, presentation.scenario?.vpn)
        assertEquals(StatusWidgetScope.WHOLE_DEVICE, presentation.scenario?.tor)
        assertEquals(
            listOf(
                StatusWidgetComponent.FIREWALL,
                StatusWidgetComponent.TOR,
                StatusWidgetComponent.I2P,
                StatusWidgetComponent.SENTINEL,
            ),
            presentation.components,
        )
        assertEquals(vpn, presentation.vpnIdentity)
        assertEquals(tor, presentation.torIdentity)
        assertEquals(41L, presentation.vpnLatencyMs)
        assertNull(presentation.torLatencyMs)
        assertTrue(widgetHasPrimaryConnection(snapshot))
    }

    private fun ipInfo(ip: String, country: String) =
        IpInfo(
            ip = ip,
            countryCode = country,
            countryName = null,
            city = null,
            isp = null,
            fetchedAt = 1L,
        )
}
