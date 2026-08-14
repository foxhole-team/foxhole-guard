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
import com.foxhole.core.model.Profile
import com.foxhole.core.model.ProfileProtocolOption
import com.foxhole.core.model.ProfileSourceType
import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.Settings
import com.foxhole.core.model.TOR_ONLY_PROFILE_ID
import com.foxhole.core.model.TrafficMapPoint
import com.foxhole.core.model.TrafficMapUiState
import com.foxhole.core.model.TrafficMode
import com.foxhole.core.model.TrafficSettings
import com.foxhole.guard.ui.TrafficMapAppRouteProjection
import com.foxhole.guard.ui.trafficMapAppRouteProjection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Схема маршрута, статус и терминал обязаны описывать один применённый рантайм. Сохранённый выбор
 * не становится живой линией до `torActive`, а старая линия не исчезает раньше успешного reload.
 */
class CliRouteModeTest {
    private fun settings(
        trafficMode: TrafficMode = TrafficMode.TUNNEL,
        torMode: PrivacyRouteMode = PrivacyRouteMode.OFF,
        torPermitted: Boolean = true,
        torScope: PrivacyRouteScope = PrivacyRouteScope.ALL_APPS,
        bypassVpnTunnel: Boolean = false,
        blockTorWhenUnavailable: Boolean = false,
        perAppRoutingMode: PerAppRoutingMode = PerAppRoutingMode.FULL_TUNNEL,
        appAssignments: Map<String, AppTunnelLane> = emptyMap(),
        blockedPackagesEnabled: Boolean = false,
    ): Settings =
        Settings(
            traffic = TrafficSettings(mode = trafficMode),
            privacyRoute =
            PrivacyRouteSettings(
                mode = torMode,
                scope = torScope,
                bypassVpnTunnel = bypassVpnTunnel,
                blockAppsWhenTorUnavailable = blockTorWhenUnavailable,
                permitted = torPermitted,
            ),
            expert =
            ExpertSettings(
                perAppRoutingMode = perAppRoutingMode,
                appAssignments = appAssignments,
                blockedPackagesEnabled = blockedPackagesEnabled,
            ),
        )

    private fun connection(
        state: ConnectionState = ConnectionState.CONNECTED,
        profileId: Long? = 7L,
        torActive: Boolean = false,
        protocolOptionId: String? = null,
    ): ConnectionSnapshot =
        ConnectionSnapshot(
            state = state,
            profileId = profileId,
            torActive = torActive,
            protocolOptionId = protocolOptionId,
        )

    private fun scenario(
        settings: Settings,
        connection: ConnectionSnapshot,
    ): CliRouteScenario = cliRouteMode(settings, connection).routeScenario()

    @Test
    fun `tor lane appears only after the reconnect applies it`() {
        // Рантайм ещё несёт старый конфиг (torActive=false) и уже переподключается: карта не должна
        // выдавать выбранную настройку за применённый маршрут.
        val reconnecting = connection(state = ConnectionState.RECONNECTING, torActive = false)
        assertEquals(
            CliRouteScenario.VPN_ONLY,
            scenario(settings(torMode = PrivacyRouteMode.TOR_OVER_VPN), reconnecting),
        )
    }

    @Test
    fun `tor lane stays until the applied runtime removes it`() {
        // Обратный случай: настройка уже OFF, а применённый конфиг всё ещё с Tor.
        val stillTorRuntime = connection(state = ConnectionState.CONNECTED, torActive = true)
        assertEquals(
            CliRouteScenario.TOR_IN_VPN_CHAIN,
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
    fun `applied tor stays visible until runtime removes it even after permission changes`() {
        assertEquals(
            CliRouteScenario.TOR_IN_VPN_CHAIN,
            scenario(
                settings(torMode = PrivacyRouteMode.TOR_OVER_VPN, torPermitted = false),
                connection(torActive = true),
            ),
        )
    }

    @Test
    fun `tor bypassing the vpn tunnel forks into parallel lanes instead of the chain`() {
        assertEquals(
            CliRouteScenario.VPN_TOR,
            scenario(
                settings(torMode = PrivacyRouteMode.TOR_OVER_VPN, bypassVpnTunnel = true),
                connection(torActive = true),
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
        val mode = cliRouteMode(scoped, connection(torActive = true))
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
    fun `tor beside a proxy profile keeps the proxy chain on the scheme`() {
        // Раньше proxy+tor схлопывался в TOR_ONLY и прокси-цепочка исчезала со схемы.
        assertEquals(
            CliRouteScenario.PROXY_TOR,
            scenario(
                settings(trafficMode = TrafficMode.PROXY, torMode = PrivacyRouteMode.TOR_OVER_VPN),
                connection(torActive = true),
            ),
        )
    }

    @Test
    fun `proxy tor is exactly device vpn tor with no fourth globe`() {
        val mode = cliRouteMode(
            settings(trafficMode = TrafficMode.PROXY, torMode = PrivacyRouteMode.TOR_OVER_VPN),
            connection(torActive = true),
        )
        val routeNodes = mode.routeNodeRoles().single()

        assertEquals(listOf(CliRouteNodeRole.VPN, CliRouteNodeRole.TOR), routeNodes)
        assertEquals(3, 1 + routeNodes.size) // one shared device node
        assertFalse(CliRouteNodeRole.INTERNET in routeNodes)
    }

    @Test
    fun `tor is terminal in every tor route topology`() {
        val torModes = listOf(
            CliRouteMode(engaged = true, tor = true),
            CliRouteMode(engaged = true, vpn = true, tor = true, torAllApps = true),
            CliRouteMode(engaged = true, vpn = true, tor = true, torBypassesVpn = true),
            CliRouteMode(engaged = true, vpn = true, tor = true),
            CliRouteMode(engaged = true, proxy = true, tor = true),
        )

        torModes.flatMap(CliRouteMode::routeNodeRoles).filter { CliRouteNodeRole.TOR in it }.forEach { lane ->
            assertEquals(CliRouteNodeRole.TOR, lane.last())
            assertFalse(CliRouteNodeRole.INTERNET in lane.drop(lane.indexOf(CliRouteNodeRole.TOR) + 1))
        }
    }

    @Test
    fun `i2p endpoint is terminal without a trailing internet square`() {
        val topology = cliI2pRouteTopology()

        assertEquals(listOf(CliRouteNodeRole.I2P), topology.nodeRoles)
        assertFalse(CliRouteNodeRole.INTERNET in topology.nodeRoles)
        assertEquals(1, topology.segmentCount)
        assertTrue(topology.dotted)
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
                connection(torActive = true),
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
                connection(torActive = true),
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

    @Test
    fun `vpn flag survives a missing runtime geo sample by using the profile hint`() {
        assertEquals("NL", cliVpnHopCountry(null, "nl"))
        assertEquals("DE", cliVpnHopCountry(" de ", "NL"))
        assertEquals(null, cliVpnHopCountry(null, " "))
    }

    @Test
    fun `device and unprotected route keep the physical flag before the map sampler catches up`() {
        assertEquals("US", cliDeviceRouteCountry(" us ", null))
        assertEquals("DE", cliDeviceRouteCountry(null, "de"))
        assertEquals("US", cliDeviceRouteCountry("US", "DE"))
    }

    @Test
    fun `vpn tor i2p split keeps a direct branch with the physical country source`() {
        val mode =
            cliRouteMode(
                settings(
                    torMode = PrivacyRouteMode.TOR_OVER_VPN,
                    torScope = PrivacyRouteScope.SELECTED_APPS,
                    perAppRoutingMode = PerAppRoutingMode.EXCLUDE_SELECTED_APPS,
                    appAssignments = mapOf(
                        "com.example.tor" to AppTunnelLane.TOR,
                        "com.example.direct" to AppTunnelLane.EXCLUDE,
                    ),
                ),
                connection(torActive = true),
            )
        val i2pMap = TrafficMapUiState(i2pActive = true, originCountryCode = "de")

        assertEquals(CliRouteScenario.VPN_TOR, mode.routeScenario())
        assertTrue(mode.split)
        assertEquals(listOf(CliRouteNodeRole.INTERNET), mode.routeNodeRoles().last())
        assertEquals("DE", cliDeviceRouteCountry(null, i2pMap.originCountryCode))
    }

    @Test
    fun `route app strip keeps six unique icons and folds the rest`() {
        val strip = cliRouteAppStrip(
            listOf("a", "b", "c", "d", "e", "f", "g", "a", " "),
        )
        assertEquals(listOf("a", "b", "c", "d", "e", "f"), strip.visiblePackages)
        assertEquals(1, strip.hiddenCount)
    }

    @Test
    fun `map lane projection follows runtime for vpn tor and explicit-exclude apps`() {
        val assignments =
            mapOf(
                "com.vpn" to AppTunnelLane.VPN,
                "com.tor" to AppTunnelLane.TOR,
                "com.exclude" to AppTunnelLane.EXCLUDE,
            )

        val includeTor =
            settings(
                torMode = PrivacyRouteMode.TOR_OVER_VPN,
                torScope = PrivacyRouteScope.SELECTED_APPS,
                perAppRoutingMode = PerAppRoutingMode.INCLUDE_SELECTED_APPS,
                appAssignments = assignments,
            ).trafficMapAppRouteProjection(torRouteActive = true)
        assertEquals(listOf("com.vpn"), includeTor.vpnApps)
        assertEquals(listOf("com.exclude"), includeTor.directApps)

        val excludeTor =
            settings(
                torMode = PrivacyRouteMode.TOR_OVER_VPN,
                torScope = PrivacyRouteScope.SELECTED_APPS,
                perAppRoutingMode = PerAppRoutingMode.EXCLUDE_SELECTED_APPS,
                appAssignments = assignments,
            ).trafficMapAppRouteProjection(torRouteActive = true)
        assertEquals(emptyList<String>(), excludeTor.vpnApps)
        assertEquals(listOf("com.exclude", "com.vpn"), excludeTor.directApps)
        assertFalse("TOR app must not be duplicated on the direct branch", "com.tor" in excludeTor.directApps)

        val torOff =
            settings(
                perAppRoutingMode = PerAppRoutingMode.EXCLUDE_SELECTED_APPS,
                appAssignments = assignments,
            ).trafficMapAppRouteProjection(torRouteActive = false)
        assertEquals(listOf("com.exclude", "com.vpn"), torOff.directApps)

        val full = settings(appAssignments = assignments).trafficMapAppRouteProjection(torRouteActive = false)
        assertEquals(listOf("com.exclude"), full.directApps)
    }

    @Test
    fun `vpn split and tor scope matrix matches the runtime package policy`() {
        val assignments =
            mapOf(
                "e" to AppTunnelLane.EXCLUDE,
                "t" to AppTunnelLane.TOR,
                "v" to AppTunnelLane.VPN,
            )
        val rows =
            listOf(
                projectionCase(PerAppRoutingMode.FULL_TUNNEL, false, PrivacyRouteScope.SELECTED_APPS, d = listOf("e")),
                projectionCase(
                    PerAppRoutingMode.INCLUDE_SELECTED_APPS,
                    false,
                    PrivacyRouteScope.SELECTED_APPS,
                    v = listOf("v"),
                    d = listOf("e"),
                    remainder = true,
                ),
                projectionCase(
                    PerAppRoutingMode.EXCLUDE_SELECTED_APPS,
                    false,
                    PrivacyRouteScope.SELECTED_APPS,
                    d = listOf("e", "v"),
                ),
                projectionCase(
                    PerAppRoutingMode.FULL_TUNNEL,
                    true,
                    PrivacyRouteScope.SELECTED_APPS,
                    t = listOf("t"),
                    d = listOf("e"),
                ),
                projectionCase(
                    PerAppRoutingMode.INCLUDE_SELECTED_APPS,
                    true,
                    PrivacyRouteScope.SELECTED_APPS,
                    v = listOf("v"),
                    t = listOf("t"),
                    d = listOf("e"),
                    remainder = true,
                ),
                projectionCase(
                    PerAppRoutingMode.EXCLUDE_SELECTED_APPS,
                    true,
                    PrivacyRouteScope.SELECTED_APPS,
                    t = listOf("t"),
                    d = listOf("e", "v"),
                ),
                projectionCase(
                    PerAppRoutingMode.FULL_TUNNEL,
                    true,
                    PrivacyRouteScope.ALL_APPS,
                    d = listOf("e"),
                    torAll = true,
                ),
                projectionCase(
                    PerAppRoutingMode.EXCLUDE_SELECTED_APPS,
                    true,
                    PrivacyRouteScope.ALL_APPS,
                    d = listOf("e", "t", "v"),
                    torAll = true,
                ),
                // RuntimeRouteConfig refuses INCLUDE_ONLY×Tor-ALL; the map must not claim success.
                projectionCase(
                    PerAppRoutingMode.INCLUDE_SELECTED_APPS,
                    true,
                    PrivacyRouteScope.ALL_APPS,
                    v = listOf("v"),
                    d = listOf("e"),
                    remainder = true,
                    buildable = false,
                ),
            )

        rows.forEach { row ->
            val actual =
                settings(
                    perAppRoutingMode = row.mode,
                    torScope = row.scope,
                    appAssignments = assignments,
                ).trafficMapAppRouteProjection(torRouteActive = row.torActive)
            assertProjection(row, actual)
        }
    }

    @Test
    fun `fail closed tor packages appear on no traffic lane while tor is unavailable`() {
        val assignments =
            mapOf(
                "e" to AppTunnelLane.EXCLUDE,
                "t" to AppTunnelLane.TOR,
                "v" to AppTunnelLane.VPN,
            )
        PerAppRoutingMode.entries.forEach { mode ->
            val actual =
                settings(
                    torScope = PrivacyRouteScope.SELECTED_APPS,
                    blockTorWhenUnavailable = true,
                    perAppRoutingMode = mode,
                    appAssignments = assignments,
                ).trafficMapAppRouteProjection(torRouteActive = false)
            assertFalse("$mode must not claim blocked Tor traffic as VPN", "t" in actual.vpnApps)
            assertFalse("$mode must not claim blocked Tor traffic as direct", "t" in actual.directApps)
            assertTrue(actual.torApps.isEmpty())
        }
    }

    @Test
    fun `invalid include plus tor all never renders a successful tor chain`() {
        val mode =
            cliRouteMode(
                settings(
                    torScope = PrivacyRouteScope.ALL_APPS,
                    perAppRoutingMode = PerAppRoutingMode.INCLUDE_SELECTED_APPS,
                    appAssignments = mapOf("v" to AppTunnelLane.VPN),
                ),
                connection(torActive = true),
            )

        assertFalse(mode.tor)
        assertEquals(CliRouteScenario.SPLIT, mode.routeScenario())
        assertEquals(listOf("v"), mode.splitApps)
    }

    @Test
    fun `armed block lane still makes an otherwise empty include policy a direct-rest split`() {
        val projection =
            settings(
                perAppRoutingMode = PerAppRoutingMode.INCLUDE_SELECTED_APPS,
                appAssignments = mapOf("blocked" to AppTunnelLane.BLOCK),
                blockedPackagesEnabled = true,
            ).trafficMapAppRouteProjection(torRouteActive = false)

        assertTrue(projection.directRemainder)
        assertTrue(projection.directBranch)
        assertTrue(projection.vpnApps.isEmpty())
        assertTrue(projection.directApps.isEmpty())
    }

    @Test
    fun `tor only selected apps has a tor lane and a direct rest branch`() {
        val mode =
            cliRouteMode(
                settings(
                    torScope = PrivacyRouteScope.SELECTED_APPS,
                    appAssignments =
                    mapOf(
                        "e" to AppTunnelLane.EXCLUDE,
                        "t" to AppTunnelLane.TOR,
                        "v" to AppTunnelLane.VPN,
                    ),
                ),
                connection(profileId = TOR_ONLY_PROFILE_ID),
            )

        assertEquals(CliRouteScenario.TOR_ONLY, mode.routeScenario())
        assertEquals(listOf("t"), mode.torApps)
        assertEquals(listOf("e"), mode.directApps)
        assertTrue(mode.split)
        assertFalse(mode.torAllApps)
    }

    @Test
    fun `single route maps never relabel apps assigned to the other route`() {
        val assignments =
            mapOf(
                "e" to AppTunnelLane.EXCLUDE,
                "t" to AppTunnelLane.TOR,
                "v" to AppTunnelLane.VPN,
            )
        val vpnOnly =
            settings(
                perAppRoutingMode = PerAppRoutingMode.INCLUDE_SELECTED_APPS,
                appAssignments = assignments,
            ).trafficMapAppRouteProjection(torRouteActive = false)
        val torOnly =
            settings(
                torScope = PrivacyRouteScope.SELECTED_APPS,
                appAssignments = assignments,
            ).trafficMapAppRouteProjection(torRouteActive = true, torOnlyRuntime = true)

        assertEquals(listOf("v"), vpnOnly.vpnApps)
        assertFalse("Tor rule must not be painted as VPN", "t" in vpnOnly.vpnApps)
        assertFalse("Tor rule must not be painted as direct", "t" in vpnOnly.directApps)
        assertEquals(listOf("t"), torOnly.torApps)
        assertFalse("VPN rule must not be painted as direct in Tor-only mode", "v" in torOnly.directApps)
    }

    @Test
    fun `stopped mode cannot revive stale sampled vpn or tor route points`() {
        val point =
            TrafficMapPoint(
                countryCode = "DE",
                label = "stale",
                lat = 1.0,
                lon = 2.0,
                bytes = 1L,
                connections = 1,
            )
        val staleMap = TrafficMapUiState(vpnRoute = point, torExit = point, dnsServer = point)

        assertEquals(
            CliLiveMapRoute(vpnRoute = null, torExit = null, dnsServer = null),
            cliLiveMapRoute(staleMap, CliRouteMode()),
        )
        assertEquals(
            CliLiveMapRoute(vpnRoute = point, torExit = null, dnsServer = point),
            cliLiveMapRoute(staleMap, CliRouteMode(engaged = true, vpn = true)),
        )
    }

    @Test
    fun `vpn country cannot decorate tor while strict tor identity is pending`() {
        val vpnNl = TrafficMapPoint("NL", "vpn", 1.0, 2.0, 1L, 1)
        val staleTorNl = TrafficMapPoint("NL", "tor", 3.0, 4.0, 1L, 1)
        val mode = CliRouteMode(engaged = true, proxy = true, tor = true)
        val route = cliLiveMapRoute(
            TrafficMapUiState(vpnRoute = vpnNl, torExit = staleTorNl),
            mode,
            confirmedTorCountryCode = null,
        )

        assertEquals(vpnNl, route.vpnRoute)
        assertNull(route.torExit)
    }

    @Test
    fun `vpn nl and confirmed tor de remain separate map points`() {
        val vpnNl = TrafficMapPoint("NL", "vpn", 1.0, 2.0, 1L, 1)
        val torDe = TrafficMapPoint("DE", "tor", 3.0, 4.0, 1L, 1)
        val mode = CliRouteMode(engaged = true, proxy = true, tor = true)
        val route = cliLiveMapRoute(
            TrafficMapUiState(vpnRoute = vpnNl, torExit = torDe),
            mode,
            confirmedTorCountryCode = "DE",
        )

        assertEquals("NL", route.vpnRoute?.countryCode)
        assertEquals("DE", route.torExit?.countryCode)
    }

    @Test
    fun `smart profile vpn flag follows the live protocol option before fallbacks`() {
        val profile =
            Profile(
                id = 7L,
                name = "Smart GB",
                sourceType = ProfileSourceType.SUBSCRIPTION_URL,
                secretRef = "secret",
                protocolHint = ProtocolHint.VLESS,
                lastUpdatedAt = null,
                lastEtag = null,
                protocolOptions =
                listOf(
                    ProfileProtocolOption("nl", "VPN NL", ProtocolHint.VLESS, isSelected = true),
                    ProfileProtocolOption("de", "VPN DE", ProtocolHint.WIREGUARD),
                ),
                selectedProtocolOptionId = "nl",
                isActive = true,
            )

        assertEquals(
            "DE",
            cliMapVpnCountry(profile, connection(protocolOptionId = "de"), runtimeCountryCode = "US"),
        )
        assertEquals(
            "US",
            cliMapVpnCountry(profile, connection(protocolOptionId = "missing"), runtimeCountryCode = "US"),
        )
        assertEquals(
            "NL",
            cliMapVpnCountry(profile, connection(protocolOptionId = null), runtimeCountryCode = null),
        )
    }

    private data class ProjectionCase(
        val mode: PerAppRoutingMode,
        val torActive: Boolean,
        val scope: PrivacyRouteScope,
        val vpn: List<String>,
        val tor: List<String>,
        val direct: List<String>,
        val remainder: Boolean,
        val torAll: Boolean,
        val buildable: Boolean,
    )

    private fun projectionCase(
        mode: PerAppRoutingMode,
        torActive: Boolean,
        scope: PrivacyRouteScope,
        v: List<String> = emptyList(),
        t: List<String> = emptyList(),
        d: List<String> = emptyList(),
        remainder: Boolean = false,
        torAll: Boolean = false,
        buildable: Boolean = true,
    ): ProjectionCase = ProjectionCase(mode, torActive, scope, v, t, d, remainder, torAll, buildable)

    private fun assertProjection(
        expected: ProjectionCase,
        actual: TrafficMapAppRouteProjection,
    ) {
        assertEquals(expected.vpn, actual.vpnApps)
        assertEquals(expected.tor, actual.torApps)
        assertEquals(expected.direct, actual.directApps)
        assertEquals(expected.remainder, actual.directRemainder)
        assertEquals(expected.torAll, actual.torAllApps)
        assertEquals(expected.buildable, actual.buildable)
        assertTrue(actual.vpnApps.intersect(actual.torApps.toSet()).isEmpty())
        assertTrue(actual.vpnApps.intersect(actual.directApps.toSet()).isEmpty())
        assertTrue(actual.torApps.intersect(actual.directApps.toSet()).isEmpty())
    }
}
