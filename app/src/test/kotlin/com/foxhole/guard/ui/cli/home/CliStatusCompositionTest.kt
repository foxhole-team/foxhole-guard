package com.foxhole.guard.ui.cli.home

import com.foxhole.core.model.AppTunnelLane
import com.foxhole.core.model.ConnectionSnapshot
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.DiagnosticEntry
import com.foxhole.core.model.DiagnosticSeverity
import com.foxhole.core.model.ExpertSettings
import com.foxhole.core.model.IpInfo
import com.foxhole.core.model.LOCAL_GUARD_PROFILE_ID
import com.foxhole.core.model.PerAppRoutingMode
import com.foxhole.core.model.PrivacyRouteScope
import com.foxhole.core.model.PrivacyRouteSettings
import com.foxhole.core.model.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CliStatusCompositionTest {

    private fun runtimes(
        vpn: Boolean = false,
        proxy: Boolean = false,
        tor: Boolean = false,
        torBesideVpn: Boolean = false,
        i2p: Boolean = false,
    ) = CliActiveRuntimes(vpn = vpn, proxy = proxy, tor = tor, torBesideVpn = torBesideVpn, i2p = i2p)

    private fun settings(
        perApp: PerAppRoutingMode = PerAppRoutingMode.FULL_TUNNEL,
        assignments: Map<String, AppTunnelLane> = emptyMap(),
        torScope: PrivacyRouteScope = PrivacyRouteScope.ALL_APPS,
        firewall: Boolean = false,
    ) = Settings(
        expert = ExpertSettings(
            perAppRoutingMode = perApp,
            appAssignments = assignments,
            firewallEnabled = firewall,
        ),
        privacyRoute = PrivacyRouteSettings(scope = torScope),
    )

    @Test
    fun `both legs live report the combined mode`() {
        assertEquals(
            CliStatusMode.VPN_TOR,
            cliStatusMode(runtimes = runtimes(vpn = true, tor = true)),
        )
    }

    @Test
    fun `a single leg reports itself`() {
        assertEquals(CliStatusMode.VPN, cliStatusMode(runtimes = runtimes(vpn = true)))
        assertEquals(CliStatusMode.TOR, cliStatusMode(runtimes = runtimes(tor = true)))
    }

    @Test
    fun `the firewall is never a mode`() {
        assertEquals(CliStatusMode.NONE, cliStatusMode(runtimes = runtimes()))
        assertEquals(CliStatusMode.VPN, cliStatusMode(runtimes = runtimes(vpn = true)))
    }

    @Test
    fun `nothing running is no active route`() {
        assertEquals(CliStatusMode.NONE, cliStatusMode(runtimes = runtimes()))
    }

    @Test
    fun `firewall handover keeps disconnecting visible until its replacement tunnel is ready`() {
        val handover = ConnectionSnapshot(
            state = ConnectionState.DISCONNECTING,
            profileId = LOCAL_GUARD_PROFILE_ID,
        )
        val ready = handover.copy(state = ConnectionState.CONNECTED)

        assertEquals(ConnectionState.DISCONNECTING, handover.routeState())
        assertEquals(ConnectionState.IDLE, ready.routeState())
    }

    @Test
    fun `tor status stays pending instead of borrowing the vpn country`() {
        val vpnNl = ipInfo(country = "nl")
        val identities = cliRouteIdentitiesForInfo(
            vpnInfo = vpnNl,
            torInfo = null,
            runtimes = runtimes(vpn = true, tor = true),
        )

        assertEquals("NL", identities.first().info?.countryCode?.uppercase())
        assertNull(identities.last().info)
        assertNull(extendedRouteIdentityInfo(vpnNl, null, torLive = true))
    }

    @Test
    fun `vpn and confirmed tor countries remain separate in status and extended identity`() {
        val vpnNl = ipInfo(country = "nl")
        val torDe = ipInfo(country = "de", ip = "5.6.7.8")
        val identities = cliRouteIdentitiesForInfo(
            vpnInfo = vpnNl,
            torInfo = torDe,
            runtimes = runtimes(vpn = true, tor = true),
        )

        assertEquals(listOf("nl", "DE"), identities.map { identity -> identity.info?.countryCode })
        assertEquals("DE", extendedRouteIdentityInfo(vpnNl, torDe, torLive = true)?.countryCode)
    }

    @Test
    fun `extended pending package summary keeps three names and folds the rest`() {
        assertEquals(
            "com.a · com.b · com.c · +1",
            compactPendingPackages(listOf(" com.a ", "com.b", "com.c", "com.d", "com.a")),
        )
    }

    private fun ipInfo(country: String, ip: String = "1.2.3.4") = IpInfo(
        ip = ip,
        countryCode = country,
        countryName = null,
        city = null,
        isp = null,
        fetchedAt = 1L,
    )

    @Test
    fun `a stored per-app mode is not a scenario while nothing runs`() {
        assertNull(
            cliVpnScenario(
                settings = settings(perApp = PerAppRoutingMode.INCLUDE_SELECTED_APPS),
                vpnLive = false,
                lanProxyServing = false,
                localSurfaceServing = false,
            ),
        )
        assertNull(cliTorScenario(settings = settings(), torLive = false))
    }

    @Test
    fun `the vpn scenario follows the per-app mode`() {
        assertEquals(
            CliStatusScenario.WHOLE_DEVICE,
            vpnScenario(settings(perApp = PerAppRoutingMode.FULL_TUNNEL)),
        )
        assertEquals(
            CliStatusScenario.PROXY_SELECTED,
            vpnScenario(settings(perApp = PerAppRoutingMode.INCLUDE_SELECTED_APPS)),
        )
    }

    @Test
    fun `exclude mode without exceptions is the whole device`() {
        assertEquals(
            CliStatusScenario.WHOLE_DEVICE,
            vpnScenario(settings(perApp = PerAppRoutingMode.EXCLUDE_SELECTED_APPS)),
        )
        assertEquals(
            CliStatusScenario.PROXY_EXCEPT_SELECTED,
            vpnScenario(
                settings(
                    perApp = PerAppRoutingMode.EXCLUDE_SELECTED_APPS,
                    assignments = mapOf("org.example.app" to AppTunnelLane.VPN),
                ),
            ),
        )
    }

    @Test
    fun `a served lan proxy outranks the per-app mode`() {
        assertEquals(
            CliStatusScenario.PROXY_SERVER_LAN,
            cliVpnScenario(
                settings = settings(perApp = PerAppRoutingMode.INCLUDE_SELECTED_APPS),
                vpnLive = true,
                lanProxyServing = true,
                localSurfaceServing = false,
            ),
        )
        assertEquals(
            CliStatusScenario.PROXY_SERVER,
            cliVpnScenario(
                settings = settings(),
                vpnLive = true,
                lanProxyServing = false,
                localSurfaceServing = true,
            ),
        )
    }

    @Test
    fun `the tor scenario is independent of the vpn one`() {
        val perAppVpn = settings(
            perApp = PerAppRoutingMode.INCLUDE_SELECTED_APPS,
            torScope = PrivacyRouteScope.ALL_APPS,
        )
        assertEquals(CliStatusScenario.WHOLE_DEVICE, cliTorScenario(settings = perAppVpn, torLive = true))
        assertEquals(
            CliStatusScenario.PROXY_SELECTED,
            cliTorScenario(settings = settings(torScope = PrivacyRouteScope.SELECTED_APPS), torLive = true),
        )
    }

    @Test
    fun `compact status matrix covers every vpn tor and proxy transition`() {
        val vpnWhole = settings()
        val vpnProxy = settings(perApp = PerAppRoutingMode.INCLUDE_SELECTED_APPS)
        val torWhole = settings(torScope = PrivacyRouteScope.ALL_APPS)
        val torProxy = settings(torScope = PrivacyRouteScope.SELECTED_APPS)
        val cases = listOf(
            Triple(vpnWhole, runtimes(vpn = true), CliCompactRouteStatus.VPN),
            Triple(vpnProxy, runtimes(vpn = true), CliCompactRouteStatus.VPN_PROXY),
            Triple(vpnWhole, runtimes(vpn = true, proxy = true), CliCompactRouteStatus.VPN_PROXY),
            Triple(torWhole, runtimes(tor = true), CliCompactRouteStatus.TOR),
            Triple(torProxy, runtimes(tor = true), CliCompactRouteStatus.TOR_PROXY),
            Triple(vpnWhole, runtimes(vpn = true, tor = true, torBesideVpn = true), CliCompactRouteStatus.VPN_TOR),
            Triple(
                torProxy,
                runtimes(vpn = true, tor = true, torBesideVpn = true),
                CliCompactRouteStatus.VPN_TOR_PROXY,
            ),
            Triple(
                vpnProxy,
                runtimes(vpn = true, tor = true, torBesideVpn = true),
                CliCompactRouteStatus.VPN_PROXY_TOR,
            ),
            Triple(
                settings(
                    perApp = PerAppRoutingMode.INCLUDE_SELECTED_APPS,
                    torScope = PrivacyRouteScope.SELECTED_APPS,
                ),
                runtimes(vpn = true, tor = true, torBesideVpn = true),
                CliCompactRouteStatus.VPN_PROXY_TOR_PROXY,
            ),
            Triple(vpnWhole, runtimes(vpn = true, tor = true), CliCompactRouteStatus.TOR_IN_VPN),
            Triple(torProxy, runtimes(vpn = true, tor = true), CliCompactRouteStatus.TOR_PROXY_IN_VPN),
            Triple(vpnProxy, runtimes(vpn = true, tor = true), CliCompactRouteStatus.TOR_IN_VPN_PROXY),
            Triple(
                settings(
                    perApp = PerAppRoutingMode.INCLUDE_SELECTED_APPS,
                    torScope = PrivacyRouteScope.SELECTED_APPS,
                ),
                runtimes(vpn = true, tor = true),
                CliCompactRouteStatus.TOR_PROXY_IN_VPN_PROXY,
            ),
        )

        cases.forEach { (configured, live, expected) ->
            assertEquals(expected, cliCompactRouteStatus(configured, live))
        }
        assertEquals(CliCompactRouteStatus.entries.size, cases.map { it.third }.distinct().size)
    }

    @Test
    fun `app and site rules are printed only while a route carries traffic`() {
        assertFalse(cliRouteRulesInForce(runtimes()))
        assertFalse(cliRouteRulesInForce(runtimes(i2p = true)))
        assertTrue(cliRouteRulesInForce(runtimes(vpn = true)))
        assertTrue(cliRouteRulesInForce(runtimes(tor = true)))
    }

    @Test
    fun `an armed firewall without a host is not live`() {
        val armed = settings(firewall = true)
        assertFalse(
            cliFirewallLive(settings = armed, connection = ConnectionSnapshot(), runtimes = runtimes()),
        )
        assertTrue(
            cliFirewallLive(settings = armed, connection = ConnectionSnapshot(), runtimes = runtimes(vpn = true)),
        )
        assertTrue(
            cliFirewallLive(
                settings = armed,
                connection = ConnectionSnapshot(
                    state = ConnectionState.CONNECTED,
                    profileId = LOCAL_GUARD_PROFILE_ID,
                ),
                runtimes = runtimes(),
            ),
        )
        assertFalse(
            cliFirewallLive(
                settings = settings(firewall = false),
                connection = ConnectionSnapshot(),
                runtimes = runtimes(vpn = true),
            ),
        )
    }

    @Test
    fun `the state word names the routes that are actually up`() {
        assertEquals(CliStatusWord.VPN, word(runtimes(vpn = true)))
        assertEquals(CliStatusWord.TOR, word(runtimes(tor = true)))
        assertEquals(CliStatusWord.VPN_TOR, word(runtimes(vpn = true, tor = true)))
        assertEquals(CliStatusWord.NONE, word(runtimes()))
    }

    @Test
    fun `i2p claims the word only once its network is up`() {
        assertEquals(CliStatusWord.I2P, word(runtimes(i2p = true), i2pConnected = true))
        assertEquals(CliStatusWord.NONE, word(runtimes(i2p = true), i2pConnected = false))
    }

    @Test
    fun `the firewall word yields to every real connection`() {
        assertEquals(CliStatusWord.FIREWALL, word(runtimes(), firewallLive = true))
        assertEquals(CliStatusWord.VPN, word(runtimes(vpn = true), firewallLive = true))
        assertEquals(
            CliStatusWord.I2P,
            word(runtimes(i2p = true), i2pConnected = true, firewallLive = true),
        )
    }

    @Test
    fun `a transition outranks whatever is still up`() {
        assertEquals(
            CliStatusWord.CONNECTING,
            word(runtimes(vpn = true), state = ConnectionState.CONNECTING),
        )
        assertEquals(
            CliStatusWord.RECONNECTING,
            word(runtimes(vpn = true), state = ConnectionState.RECONNECTING),
        )
        assertEquals(
            CliStatusWord.DISCONNECTING,
            word(runtimes(vpn = true, tor = true), state = ConnectionState.DISCONNECTING),
        )
        assertEquals(CliStatusWord.ERROR, word(runtimes(), state = ConnectionState.ERROR))
    }

    @Test
    fun `the tor lane is silent while its module is off`() {
        val pinned = Settings(
            expert = ExpertSettings(appAssignments = mapOf("org.example.app" to AppTunnelLane.TOR)),
            privacyRoute = PrivacyRouteSettings(permitted = false),
        )

        assertFalse(cliTorLaneVisible(pinned))
        assertTrue(cliTorLaneVisible(pinned.copy(privacyRoute = PrivacyRouteSettings(permitted = true))))
    }

    @Test
    fun `only entries recorded as failures reach the extended block`() {
        assertTrue(isCriticalDiagnostic(failure("connect failed: handshake timeout")))
        assertTrue(isCriticalDiagnostic(failure("boot receiver timed out action=BOOT_COMPLETED")))
        assertFalse(isCriticalDiagnostic(entry("connect requested")))
        assertFalse(isCriticalDiagnostic(entry("active profile changed")))
    }

    @Test
    fun `the wording of a message decides nothing`() {
        assertFalse(
            isCriticalDiagnostic(
                entry("scheduled refresh completed targeted=0 success=0 retryableFailures=0"),
            ),
        )
        assertFalse(isCriticalDiagnostic(entry("bridge list refresh failed error=IOException")))
        assertTrue(isCriticalDiagnostic(failure("bridge list refresh failed error=IOException")))
    }

    private fun failure(message: String) =
        entry(message).copy(severity = DiagnosticSeverity.FAILURE)

    private fun entry(message: String) = DiagnosticEntry(timestamp = 0L, tag = "connection", message = message)

    private fun vpnScenario(settings: Settings) =
        cliVpnScenario(
            settings = settings,
            vpnLive = true,
            lanProxyServing = false,
            localSurfaceServing = false,
        )

    private fun word(
        runtimes: CliActiveRuntimes,
        state: ConnectionState = ConnectionState.CONNECTED,
        i2pConnected: Boolean = false,
        firewallLive: Boolean = false,
    ) = cliStatusWordFor(
        state = state,
        runtimes = runtimes,
        i2pConnected = i2pConnected,
        firewallLive = firewallLive,
    )
}
