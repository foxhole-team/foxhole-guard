package com.foxhole.guard.widget

import androidx.compose.ui.unit.dp
import com.foxhole.core.model.AnomalySettings
import com.foxhole.core.model.AppLockMode
import com.foxhole.core.model.AppLockSettings
import com.foxhole.core.model.AppTunnelLane
import com.foxhole.core.model.AppliedTorRoute
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
    fun `status widget layout parser retains stored compatibility while unknown values use simple`() {
        assertEquals(StatusWidgetLayoutMode.SIMPLE, parseStatusWidgetLayoutMode(null))
        assertEquals(StatusWidgetLayoutMode.SIMPLE, parseStatusWidgetLayoutMode(""))
        assertEquals(StatusWidgetLayoutMode.SIMPLE, parseStatusWidgetLayoutMode("future"))
        assertEquals(StatusWidgetLayoutMode.EXPANDED, parseStatusWidgetLayoutMode("full"))
        assertEquals(StatusWidgetLayoutMode.EXPANDED, parseStatusWidgetLayoutMode(" expanded "))
        assertEquals(StatusWidgetLayoutMode.SIMPLE, parseStatusWidgetLayoutMode(" SIMPLE "))
        assertEquals(StatusWidgetLayoutMode.SIMPLE, parseStatusWidgetLayoutMode("compact"))
    }

    @Test
    fun `simplified identity status only replaces locations during transitions or refresh`() {
        assertNull(StatusWidgetRefreshPhase.IDLE.simpleIdentityStatusRes())
        assertEquals(
            R.string.widget_status_updating_status,
            StatusWidgetRefreshPhase.LOADING.simpleIdentityStatusRes(),
        )
        assertEquals(
            R.string.widget_status_refresh_failed,
            StatusWidgetRefreshPhase.FAILED.simpleIdentityStatusRes(),
        )
        listOf(
            StatusWidgetConnection.CONNECTING,
            StatusWidgetConnection.RECONNECTING,
            StatusWidgetConnection.DISCONNECTING,
        ).forEach { connection ->
            assertEquals(
                R.string.widget_status_updating_status,
                statusWidgetSimpleIdentityStatusRes(connection, StatusWidgetRefreshPhase.IDLE),
            )
        }
        assertNull(
            statusWidgetSimpleIdentityStatusRes(
                StatusWidgetConnection.CONNECTED,
                StatusWidgetRefreshPhase.IDLE,
            ),
        )
        assertNull(
            statusWidgetSimpleIdentityStatusRes(
                StatusWidgetConnection.DISCONNECTED,
                StatusWidgetRefreshPhase.IDLE,
            ),
        )
    }

    @Test
    fun `simplified widget keeps status text compact and scales down on either narrow axis`() {
        val normal = statusWidgetSimpleMetrics(width = 400.dp, height = 80.dp)
        val narrow = statusWidgetSimpleMetrics(width = 110.dp, height = 80.dp)
        val short = statusWidgetSimpleMetrics(width = 400.dp, height = 48.dp)

        assertEquals(14, normal.statusFontSize)
        assertEquals(13, normal.locationFontSize)
        assertEquals(36.dp, normal.circleSize)
        assertEquals(48.dp, statusWidgetSimpleSurfaceHeight(normal))
        listOf(narrow, short).forEach { compact ->
            assertEquals(11, compact.statusFontSize)
            assertEquals(10, compact.locationFontSize)
            assertEquals(30.dp, compact.circleSize)
            assertEquals(34.dp, statusWidgetSimpleSurfaceHeight(compact))
        }
    }

    @Test
    fun `simplified widget distinguishes every runtime state`() {
        assertEquals(
            StatusWidgetSimpleStatus.VPN,
            simplePresentation(
                connection = StatusWidgetConnection.CONNECTED,
                primaryActive = true,
                mode = StatusWidgetMode.VPN,
            ).simpleStatus(),
        )
        assertEquals(
            StatusWidgetSimpleStatus.TOR,
            simplePresentation(
                connection = StatusWidgetConnection.CONNECTED,
                primaryActive = true,
                mode = StatusWidgetMode.TOR,
            ).simpleStatus(),
        )
        assertEquals(
            StatusWidgetSimpleStatus.VPN_TOR,
            simplePresentation(
                connection = StatusWidgetConnection.CONNECTED,
                primaryActive = true,
                mode = StatusWidgetMode.VPN_TOR,
            ).simpleStatus(),
        )
        assertEquals(
            StatusWidgetSimpleStatus.I2P,
            simplePresentation(
                connection = StatusWidgetConnection.CONNECTED,
                components = listOf(StatusWidgetComponent.I2P),
            ).simpleStatus(),
        )
        assertEquals(
            StatusWidgetSimpleStatus.FIREWALL,
            simplePresentation(
                connection = StatusWidgetConnection.DISCONNECTED,
                components = listOf(StatusWidgetComponent.FIREWALL),
            ).simpleStatus(),
        )
        assertEquals(
            StatusWidgetSimpleStatus.CONNECTING,
            simplePresentation(connection = StatusWidgetConnection.CONNECTING).simpleStatus(),
        )
        assertEquals(
            StatusWidgetSimpleStatus.RECONNECTING,
            simplePresentation(connection = StatusWidgetConnection.RECONNECTING).simpleStatus(),
        )
        assertEquals(
            StatusWidgetSimpleStatus.DISCONNECTING,
            simplePresentation(connection = StatusWidgetConnection.DISCONNECTING).simpleStatus(),
        )
        assertEquals(
            StatusWidgetSimpleStatus.ERROR,
            simplePresentation(connection = StatusWidgetConnection.ERROR).simpleStatus(),
        )
        assertEquals(
            StatusWidgetSimpleStatus.OFF,
            simplePresentation(connection = StatusWidgetConnection.DISCONNECTED).simpleStatus(),
        )
        assertEquals(R.string.widget_status_unprotected, StatusWidgetSimpleStatus.OFF.labelRes)
    }

    @Test
    fun `expanded widget shows device identity only while fully disconnected`() {
        val device = ipInfo("192.0.2.9", "FR")
        val vpn = ipInfo("203.0.113.7", "NL")

        assertEquals(
            device,
            simplePresentation(
                connection = StatusWidgetConnection.DISCONNECTED,
                deviceIdentity = device,
            ).expandedIdentity(),
        )
        assertNull(
            simplePresentation(
                connection = StatusWidgetConnection.CONNECTING,
                primaryActive = true,
                deviceIdentity = device,
            ).expandedIdentity(),
        )
        assertNull(
            simplePresentation(
                connection = StatusWidgetConnection.ERROR,
                deviceIdentity = device,
            ).expandedIdentity(),
        )
        assertEquals(
            vpn,
            simplePresentation(
                connection = StatusWidgetConnection.CONNECTED,
                primaryActive = true,
                vpnIdentity = vpn,
                deviceIdentity = device,
            ).expandedIdentity(),
        )
    }

    @Test
    fun `simplified widget keeps identity order with honest missing exits`() {
        val vpn = ipInfo("203.0.113.7", "NL")
        val tor = ipInfo("198.51.100.8", "DE")
        val device = ipInfo("192.0.2.9", "FR")
        val combined =
            simplePresentation(
                connection = StatusWidgetConnection.CONNECTED,
                primaryActive = true,
                mode = StatusWidgetMode.VPN_TOR,
                components = listOf(StatusWidgetComponent.TOR, StatusWidgetComponent.I2P),
                vpnIdentity = vpn,
                torIdentity = tor,
                deviceIdentity = device,
            )

        assertEquals(
            listOf(
                StatusWidgetSimpleIdentityToken(StatusWidgetSimpleIdentityKind.VPN, vpn),
                StatusWidgetSimpleIdentityToken(StatusWidgetSimpleIdentityKind.TOR, tor),
            ),
            combined.simpleIdentityTokens(),
        )
        assertEquals(
            listOf(
                StatusWidgetSimpleIdentityToken(StatusWidgetSimpleIdentityKind.VPN, vpn),
                StatusWidgetSimpleIdentityToken(StatusWidgetSimpleIdentityKind.TOR, null),
            ),
            simplePresentation(
                connection = StatusWidgetConnection.CONNECTED,
                primaryActive = true,
                mode = StatusWidgetMode.VPN_TOR,
                vpnIdentity = vpn,
                deviceIdentity = device,
            ).simpleIdentityTokens(),
        )
    }

    @Test
    fun `simplified widget uses relevant single identity and device identity while offline`() {
        val vpn = ipInfo("203.0.113.7", "NL")
        val tor = ipInfo("198.51.100.8", "DE")
        val device = ipInfo("192.0.2.9", "FR")

        assertEquals(
            listOf(StatusWidgetSimpleIdentityToken(StatusWidgetSimpleIdentityKind.VPN, vpn)),
            simplePresentation(
                connection = StatusWidgetConnection.CONNECTED,
                primaryActive = true,
                mode = StatusWidgetMode.VPN,
                vpnIdentity = vpn,
                deviceIdentity = device,
            ).simpleIdentityTokens(),
        )
        assertEquals(
            listOf(StatusWidgetSimpleIdentityToken(StatusWidgetSimpleIdentityKind.TOR, tor)),
            simplePresentation(
                connection = StatusWidgetConnection.CONNECTED,
                primaryActive = true,
                mode = StatusWidgetMode.TOR,
                torIdentity = tor,
                deviceIdentity = device,
            ).simpleIdentityTokens(),
        )
        assertEquals(
            listOf(StatusWidgetSimpleIdentityToken(StatusWidgetSimpleIdentityKind.I2P, device)),
            simplePresentation(
                connection = StatusWidgetConnection.CONNECTED,
                components = listOf(StatusWidgetComponent.I2P),
                deviceIdentity = device,
            ).simpleIdentityTokens(),
        )
        assertEquals(
            listOf(StatusWidgetSimpleIdentityToken(StatusWidgetSimpleIdentityKind.DEVICE, device)),
            simplePresentation(
                connection = StatusWidgetConnection.DISCONNECTED,
                deviceIdentity = device,
            ).simpleIdentityTokens(),
        )
        assertEquals(
            listOf(StatusWidgetSimpleIdentityToken(StatusWidgetSimpleIdentityKind.DEVICE, device)),
            simplePresentation(
                connection = StatusWidgetConnection.RECONNECTING,
                primaryActive = true,
                mode = StatusWidgetMode.VPN,
                deviceIdentity = device,
            ).simpleIdentityTokens(),
        )
    }

    @Test
    fun `simplified widget device icon has all five semantic tones with i2p precedence`() {
        assertEquals(
            StatusWidgetSimpleDeviceTone.DISCONNECTED,
            simplePresentation(StatusWidgetConnection.DISCONNECTED).simpleDeviceTone(),
        )
        assertEquals(
            StatusWidgetSimpleDeviceTone.VPN,
            simplePresentation(
                connection = StatusWidgetConnection.CONNECTED,
                primaryActive = true,
                mode = StatusWidgetMode.VPN,
            ).simpleDeviceTone(),
        )
        assertEquals(
            StatusWidgetSimpleDeviceTone.TOR,
            simplePresentation(
                connection = StatusWidgetConnection.CONNECTED,
                primaryActive = true,
                mode = StatusWidgetMode.TOR,
            ).simpleDeviceTone(),
        )
        assertEquals(
            StatusWidgetSimpleDeviceTone.VPN_TOR,
            simplePresentation(
                connection = StatusWidgetConnection.CONNECTED,
                primaryActive = true,
                mode = StatusWidgetMode.VPN_TOR,
            ).simpleDeviceTone(),
        )
        assertEquals(
            StatusWidgetSimpleDeviceTone.I2P,
            simplePresentation(
                connection = StatusWidgetConnection.CONNECTED,
                primaryActive = true,
                mode = StatusWidgetMode.VPN_TOR,
                components = listOf(StatusWidgetComponent.TOR, StatusWidgetComponent.I2P),
            ).simpleDeviceTone(),
        )
    }

    @Test
    fun `simplified power button exposes connect green action while idle and stop red action while active`() {
        assertEquals(
            StatusWidgetSimplePowerAction.CONNECT,
            simplePresentation(StatusWidgetConnection.DISCONNECTED).simplePowerAction(),
        )
        assertEquals(
            StatusWidgetSimplePowerAction.STOP,
            simplePresentation(
                connection = StatusWidgetConnection.CONNECTED,
                primaryActive = true,
                mode = StatusWidgetMode.VPN,
            ).simplePowerAction(),
        )
        listOf(
            StatusWidgetConnection.CONNECTING,
            StatusWidgetConnection.RECONNECTING,
            StatusWidgetConnection.DISCONNECTING,
        ).forEach { connection ->
            assertEquals(
                StatusWidgetSimplePowerAction.STOP,
                simplePresentation(
                    connection = connection,
                ).simplePowerAction(),
            )
        }
        assertEquals(
            StatusWidgetSimplePowerAction.CONNECT,
            simplePresentation(
                connection = StatusWidgetConnection.CONNECTED,
                components = listOf(StatusWidgetComponent.I2P),
            ).simplePowerAction(),
        )
    }

    @Test
    fun `widget country flag accepts only normalized iso alpha two codes`() {
        assertEquals("nl", statusWidgetCountryFlagCode(" NL "))
        assertNull(statusWidgetCountryFlagCode(null))
        assertNull(statusWidgetCountryFlagCode("NLD"))
        assertNull(statusWidgetCountryFlagCode("N1"))
    }

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
    fun `applied Tor placement and scope change the widget runtime key`() {
        val insideVpn =
            ConnectionSnapshot(
                state = ConnectionState.CONNECTED,
                profileId = 7L,
                torActive = true,
                appliedTorRoute =
                AppliedTorRoute(
                    scope = PrivacyRouteScope.ALL_APPS,
                    bypassVpnTunnel = false,
                ),
            )
        val besideVpn =
            insideVpn.copy(
                appliedTorRoute = insideVpn.appliedTorRoute?.copy(bypassVpnTunnel = true),
            )
        val selectedInsideVpn =
            insideVpn.copy(
                appliedTorRoute =
                AppliedTorRoute(
                    scope = PrivacyRouteScope.SELECTED_APPS,
                    bypassVpnTunnel = false,
                    selectedPackages = listOf("org.example.tor"),
                ),
            )

        assertFalse(insideVpn.statusWidgetRuntimeKey() == besideVpn.statusWidgetRuntimeKey())
        assertFalse(insideVpn.statusWidgetRuntimeKey() == selectedInsideVpn.statusWidgetRuntimeKey())
    }

    @Test
    fun `refresh spinner keeps the same restart glyph through four pixel frames`() {
        assertEquals(R.drawable.lin_update, statusWidgetRefreshSpinnerFrame(0))
        assertEquals(R.drawable.widget_refresh_spinner_90, statusWidgetRefreshSpinnerFrame(1))
        assertEquals(R.drawable.widget_refresh_spinner_180, statusWidgetRefreshSpinnerFrame(2))
        assertEquals(R.drawable.widget_refresh_spinner_270, statusWidgetRefreshSpinnerFrame(3))
        assertEquals(R.drawable.lin_update, statusWidgetRefreshSpinnerFrame(4))
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
                    appliedTorRoute =
                    AppliedTorRoute(
                        scope = PrivacyRouteScope.ALL_APPS,
                        bypassVpnTunnel = true,
                    ),
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
    fun `live VPN Tor presentation follows applied route instead of desired settings`() {
        val vpn = ipInfo("203.0.113.7", "NL")
        val tor = ipInfo("198.51.100.8", "DE")
        val presentation =
            statusWidgetPresentation(
                snapshot =
                ConnectionSnapshot(
                    state = ConnectionState.CONNECTED,
                    profileId = 7L,
                    profileName = "Fox profile",
                    protocolHint = ProtocolHint.VLESS,
                    torActive = true,
                    appliedTorRoute =
                    AppliedTorRoute(
                        scope = PrivacyRouteScope.SELECTED_APPS,
                        bypassVpnTunnel = false,
                        selectedPackages = listOf("org.example.tor"),
                    ),
                ),
                settings =
                Settings(
                    privacyRoute =
                    PrivacyRouteSettings(
                        mode = PrivacyRouteMode.TOR_OVER_VPN,
                        scope = PrivacyRouteScope.ALL_APPS,
                        bypassVpnTunnel = true,
                    ),
                ),
                vpnIpInfo = vpn,
                torIpInfo = tor,
                i2pConnected = false,
            )

        assertEquals(StatusWidgetMode.VPN_TOR, presentation.mode)
        assertEquals(StatusWidgetScope.SELECTED_APPS, presentation.scenario?.tor)
        assertEquals(
            com.foxhole.guard.ui.cli.home.CliCompactRouteStatus.TOR_PROXY_IN_VPN,
            presentation.routeStatus,
        )
        assertEquals(
            listOf(
                StatusWidgetSimpleIdentityToken(StatusWidgetSimpleIdentityKind.VPN, vpn),
                StatusWidgetSimpleIdentityToken(StatusWidgetSimpleIdentityKind.TOR, tor),
            ),
            presentation.simpleIdentityTokens(),
        )
    }

    @Test
    fun `live VPN Tor identities never replace a missing applied exit with the device identity`() {
        val device = ipInfo("192.0.2.9", "FR")
        val tor = ipInfo("198.51.100.8", "DE")
        val presentation =
            statusWidgetPresentation(
                snapshot =
                ConnectionSnapshot(
                    state = ConnectionState.CONNECTED,
                    profileId = 7L,
                    torActive = true,
                    appliedTorRoute =
                    AppliedTorRoute(
                        scope = PrivacyRouteScope.ALL_APPS,
                        bypassVpnTunnel = false,
                    ),
                ),
                settings = Settings(),
                vpnIpInfo = null,
                torIpInfo = tor,
                i2pConnected = false,
                deviceIpInfo = device,
            )

        assertEquals(
            listOf(
                StatusWidgetSimpleIdentityToken(StatusWidgetSimpleIdentityKind.VPN, null),
                StatusWidgetSimpleIdentityToken(StatusWidgetSimpleIdentityKind.TOR, tor),
            ),
            presentation.simpleIdentityTokens(),
        )
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
                appliedTorRoute =
                AppliedTorRoute(
                    scope = PrivacyRouteScope.ALL_APPS,
                    bypassVpnTunnel = false,
                ),
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

    @Test
    fun `sentinel stays absent when its master switch is off`() {
        val presentation =
            statusWidgetPresentation(
                snapshot = ConnectionSnapshot(state = ConnectionState.IDLE),
                settings =
                Settings(
                    anomaly = AnomalySettings(enabled = false),
                    appLock =
                    AppLockSettings(
                        mode = AppLockMode.PASSWORD,
                        eventMonitoringEnabled = true,
                    ),
                ),
                vpnIpInfo = null,
                torIpInfo = null,
                i2pConnected = false,
            )

        assertFalse(StatusWidgetComponent.SENTINEL in presentation.components)
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

    private fun simplePresentation(
        connection: StatusWidgetConnection,
        primaryActive: Boolean = false,
        mode: StatusWidgetMode? = null,
        components: List<StatusWidgetComponent> = emptyList(),
        deviceIdentity: IpInfo? = null,
        vpnIdentity: IpInfo? = null,
        torIdentity: IpInfo? = null,
    ) =
        StatusWidgetPresentation(
            connection = connection,
            primaryActive = primaryActive,
            vpnProfile = null,
            vpnProtocol = null,
            mode = mode,
            scenario = null,
            routeStatus = null,
            i2pConnected = StatusWidgetComponent.I2P in components,
            components = components,
            deviceIdentity = deviceIdentity,
            vpnIdentity = vpnIdentity,
            vpnLatencyMs = null,
            torIdentity = torIdentity,
            torLatencyMs = null,
            dnsServer = "",
        )
}
