package com.foxhole.guard.ui.cli.map

import com.foxhole.core.model.AppTunnelLane
import com.foxhole.core.model.ConnectionSnapshot
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.ExpertSettings
import com.foxhole.core.model.LOCAL_GUARD_PROFILE_ID
import com.foxhole.core.model.PerAppRoutingMode
import com.foxhole.core.model.PrivacyRouteMode
import com.foxhole.core.model.PrivacyRouteScope
import com.foxhole.core.model.PrivacyRouteSettings
import com.foxhole.core.model.Settings
import com.foxhole.core.model.TOR_ONLY_PROFILE_ID
import com.foxhole.core.model.TrafficMode
import com.foxhole.core.model.TrafficSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Смена режима соединения обязана перерисовывать схему маршрута СРАЗУ, не дожидаясь конца
 * переподключения: рантайм-поля карты (`torActive`, `vpnRoute`, `torExit`) взводятся только по
 * применённому конфигу, поэтому классификация идёт по выбранному режиму.
 */
class CliRouteModeTest {
    private fun settings(
        trafficMode: TrafficMode = TrafficMode.TUNNEL,
        torMode: PrivacyRouteMode = PrivacyRouteMode.OFF,
        torPermitted: Boolean = true,
        torScope: PrivacyRouteScope = PrivacyRouteScope.ALL_APPS,
        bypassVpnTunnel: Boolean = false,
        perAppRoutingMode: PerAppRoutingMode = PerAppRoutingMode.FULL_TUNNEL,
        appAssignments: Map<String, AppTunnelLane> = emptyMap(),
    ): Settings =
        Settings(
            traffic = TrafficSettings(mode = trafficMode),
            privacyRoute =
            PrivacyRouteSettings(
                mode = torMode,
                scope = torScope,
                bypassVpnTunnel = bypassVpnTunnel,
                permitted = torPermitted,
            ),
            expert =
            ExpertSettings(
                perAppRoutingMode = perAppRoutingMode,
                appAssignments = appAssignments,
            ),
        )

    private fun connection(
        state: ConnectionState = ConnectionState.CONNECTED,
        profileId: Long? = 7L,
        torActive: Boolean = false,
    ): ConnectionSnapshot =
        ConnectionSnapshot(state = state, profileId = profileId, torActive = torActive)

    private fun scenario(
        settings: Settings,
        connection: ConnectionSnapshot,
    ): CliRouteScenario = cliRouteMode(settings, connection).routeScenario()

    @Test
    fun `enabling tor over vpn chains the route before the reconnect applies it`() {
        // Рантайм ещё несёт старый конфиг (torActive=false) и уже переподключается.
        val reconnecting = connection(state = ConnectionState.RECONNECTING, torActive = false)
        assertEquals(
            CliRouteScenario.TOR_IN_VPN_CHAIN,
            scenario(settings(torMode = PrivacyRouteMode.TOR_OVER_VPN), reconnecting),
        )
    }

    @Test
    fun `disabling tor drops the tor lane while the tor runtime is still applied`() {
        // Обратный случай: настройка уже OFF, а применённый конфиг всё ещё с Tor.
        val stillTorRuntime = connection(state = ConnectionState.CONNECTED, torActive = true)
        assertEquals(
            CliRouteScenario.VPN_ONLY,
            scenario(settings(torMode = PrivacyRouteMode.OFF), stillTorRuntime),
        )
    }

    @Test
    fun `armed but idle tor route stays an ordinary direct scheme`() {
        val idle = connection(state = ConnectionState.IDLE, profileId = null)
        val mode = cliRouteMode(settings(torMode = PrivacyRouteMode.TOR_OVER_VPN), idle)
        assertEquals(CliRouteMode(), mode)
        assertEquals(CliRouteScenario.DIRECT, mode.routeScenario())
    }

    @Test
    fun `revoked tor core keeps the route on the plain vpn lane`() {
        assertEquals(
            CliRouteScenario.VPN_ONLY,
            scenario(
                settings(torMode = PrivacyRouteMode.TOR_OVER_VPN, torPermitted = false),
                connection(),
            ),
        )
    }

    @Test
    fun `tor bypassing the vpn tunnel forks into parallel lanes instead of the chain`() {
        assertEquals(
            CliRouteScenario.VPN_TOR,
            scenario(
                settings(torMode = PrivacyRouteMode.TOR_OVER_VPN, bypassVpnTunnel = true),
                connection(),
            ),
        )
    }

    @Test
    fun `narrowing the tor scope to selected apps forks the chain the same frame`() {
        val scoped =
            settings(
                torMode = PrivacyRouteMode.TOR_OVER_VPN,
                torScope = PrivacyRouteScope.SELECTED_APPS,
                appAssignments = mapOf("com.example.browser" to AppTunnelLane.TOR),
            )
        val mode = cliRouteMode(scoped, connection())
        assertEquals(listOf("com.example.browser"), mode.torApps)
        assertEquals(CliRouteScenario.VPN_TOR, mode.routeScenario())
    }

    @Test
    fun `switching per app routing to include flips full tunnel to split`() {
        val full = settings()
        val split =
            settings(
                perAppRoutingMode = PerAppRoutingMode.INCLUDE_SELECTED_APPS,
                appAssignments = mapOf("com.example.app" to AppTunnelLane.VPN),
            )
        assertEquals(CliRouteScenario.VPN_ONLY, scenario(full, connection()))
        val splitMode = cliRouteMode(split, connection())
        assertEquals(CliRouteScenario.SPLIT, splitMode.routeScenario())
        assertEquals(listOf("com.example.app"), splitMode.splitApps)
        assertEquals(emptyList<String>(), splitMode.directApps)
    }

    @Test
    fun `exclude mode puts the picked apps on the direct branch`() {
        val excluded =
            settings(
                perAppRoutingMode = PerAppRoutingMode.EXCLUDE_SELECTED_APPS,
                appAssignments = mapOf("com.example.app" to AppTunnelLane.VPN),
            )
        val mode = cliRouteMode(excluded, connection())
        assertEquals(CliRouteScenario.SPLIT, mode.routeScenario())
        assertEquals(emptyList<String>(), mode.splitApps)
        assertEquals(listOf("com.example.app"), mode.directApps)
    }

    @Test
    fun `switching the traffic mode to proxy repaints the route as a proxy branch`() {
        assertEquals(
            CliRouteScenario.PROXY,
            scenario(settings(trafficMode = TrafficMode.PROXY), connection()),
        )
    }

    @Test
    fun `local guard runtime renders the firewall lane`() {
        assertEquals(
            CliRouteScenario.FIREWALL,
            scenario(settings(), connection(profileId = LOCAL_GUARD_PROFILE_ID)),
        )
    }

    @Test
    fun `tor only runtime renders the tor lane without a vpn hop`() {
        assertEquals(
            CliRouteScenario.TOR_ONLY,
            scenario(settings(), connection(profileId = TOR_ONLY_PROFILE_ID)),
        )
    }

    // --- Атрибуция гео хопов: чей exit-IP носит VPN-узел ---

    @Test
    fun `tor over vpn on all apps hands the tunnel exit identity to tor`() {
        // Через Tor уходит всё, включая зонды рантайма, — exit-IP описывает Tor-цепочку, не VPN.
        val mode =
            cliRouteMode(
                settings(torMode = PrivacyRouteMode.TOR_OVER_VPN, torScope = PrivacyRouteScope.ALL_APPS),
                connection(),
            )
        assertTrue(mode.torOwnsExitIdentity)
    }

    @Test
    fun `tor scoped to selected apps leaves the exit identity with the vpn server`() {
        // Зонды рантайма идут обычным VPN-выходом, значит exit-IP — честная страна VPN-сервера.
        val mode =
            cliRouteMode(
                settings(
                    torMode = PrivacyRouteMode.TOR_OVER_VPN,
                    torScope = PrivacyRouteScope.SELECTED_APPS,
                    appAssignments = mapOf("com.example.browser" to AppTunnelLane.TOR),
                ),
                connection(),
            )
        assertFalse(mode.torOwnsExitIdentity)
    }

    @Test
    fun `plain vpn keeps its own exit identity`() {
        assertFalse(cliRouteMode(settings(), connection()).torOwnsExitIdentity)
    }

    @Test
    fun `revoked tor core leaves the exit identity with the vpn server`() {
        assertFalse(
            cliRouteMode(
                settings(torMode = PrivacyRouteMode.TOR_OVER_VPN, torPermitted = false),
                connection(),
            ).torOwnsExitIdentity,
        )
    }

    @Test
    fun `proxy and local runtimes never hand the exit identity to tor`() {
        val torOn = PrivacyRouteMode.TOR_OVER_VPN
        assertFalse(
            cliRouteMode(settings(trafficMode = TrafficMode.PROXY, torMode = torOn), connection())
                .torOwnsExitIdentity,
        )
        assertFalse(
            cliRouteMode(settings(torMode = torOn), connection(profileId = LOCAL_GUARD_PROFILE_ID))
                .torOwnsExitIdentity,
        )
        assertFalse(
            cliRouteMode(settings(torMode = torOn), connection(profileId = TOR_ONLY_PROFILE_ID))
                .torOwnsExitIdentity,
        )
    }
}
