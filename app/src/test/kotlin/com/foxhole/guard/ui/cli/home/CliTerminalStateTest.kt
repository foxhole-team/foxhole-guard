package com.foxhole.guard.ui.cli.home

import com.foxhole.core.model.AutoConnectReasonCode
import com.foxhole.core.model.ConnectionSnapshot
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.I2pNetworkPhase
import com.foxhole.core.model.I2pPhaseSnapshot
import com.foxhole.core.model.IpInfo
import com.foxhole.core.model.RuntimeTeardownPhase
import com.foxhole.core.model.TOR_ONLY_PROFILE_ID
import com.foxhole.core.model.TorNetworkPhase
import com.foxhole.core.model.TorPhaseSnapshot
import com.foxhole.core.model.TrafficMode
import com.foxhole.guard.ui.TorIdentityProbePhase
import com.foxhole.guard.ui.TorIdentityProbeState
import com.foxhole.guard.ui.cli.CliCommands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CliTerminalStateTest {

    private fun strings() = CliTerminalStrings(
        bootLoading = "loading environment",
        bootReady = "application ready",
        connecting = "connecting",
        connected = "checking connection",
        tunnelUp = "connection established",
        tunnelUpNamed = "connection established · %s",
        vpnEstablished = "connection established · VPN",
        vpnExitFailed = "vpn ip failed",
        i2pEstablished = "connection established · I2P",
        reconnecting = "reconnecting",
        error = "error: %s",
        unknown = "unknown",
        closed = "closed",
        torBesideVpn = "TOR and VPN",
        torConnecting = "connecting to the TOR network",
        torCircuits = "tor circuits",
        torConnected = "tor connected",
        torExitLookup = "determining TOR IP",
        torExitFailed = "tor ip failed",
        torStopped = "tor stopped",
        i2pStarting = "connecting to the I2P network",
        i2pDiscovering = "i2p discovering",
        i2pTunnels = "i2p tunnels",
        i2pTunnelsCount = "i2p tunnels %1\$d",
        i2pConnected = "connected to the I2P network",
        i2pStopped = "i2p stopped",
        disconnectingVpn = "disconnecting vpn",
        disconnectingTor = "disconnecting tor",
        disconnectingI2p = "disconnecting i2p",
        disconnectingAndroidTunnel = "disconnecting android tunnel",
        exitKeyIp = "ip",
        exitKeyGeo = "geo",
        exitKeyIsp = "isp",
        vpnExitKeyIp = "VPN IP",
        torExitKeyIp = "TOR IP",
        reasonLabels = mapOf(
            AutoConnectReasonCode.HANDSHAKE_TIMEOUT to "handshake timeout",
            AutoConnectReasonCode.DNS_FAILURE to "dns failure",
        ),
    )

    // Не удержанный: этот класс про то, ЧТО печатает терминал, а не про то, когда холодный старт
    // публикует лог — за это отвечает CliTerminalJournalWiringTest.
    private fun state(retentionHours: Int = 12) =
        CliTerminalState(strings(), retentionHours = { retentionHours }, startsHeld = false)

    @Test
    fun `welcome prints once`() {
        val terminal = state()
        terminal.welcome("1.0")
        terminal.welcome("1.0")
        assertEquals(1, terminal.lines.size)
    }

    @Test
    fun `pending firewall reminder is silent when empty and dedupes the same package set`() {
        val terminal = state()

        terminal.onPendingFirewallActions(emptyList(), "action required")
        terminal.onPendingFirewallActions(listOf("com.b", "com.a"), "two actions required")
        terminal.onPendingFirewallActions(listOf("com.a", "com.b"), "duplicate")

        assertEquals(listOf("two actions required"), terminal.lines.map { line -> line.text })
        assertEquals(CliLineTone.INFO, terminal.lines.single().tone)
    }

    @Test
    fun `pending firewall reminder announces a changed set and resets after resolution`() {
        val terminal = state()

        terminal.onPendingFirewallActions(listOf("com.a"), "one")
        terminal.onPendingFirewallActions(listOf("com.a", "com.b"), "two")
        terminal.onPendingFirewallActions(emptyList(), "none")
        terminal.onPendingFirewallActions(listOf("com.a"), "new one")

        assertEquals(listOf("one", "two", "new one"), terminal.lines.map { line -> line.text })
    }

    @Test
    fun `cold boot narrates loading then ready exactly once`() {
        val terminal = state()
        terminal.onBootStage(profilesLoaded = false)
        terminal.onBootStage(profilesLoaded = false)
        assertEquals(listOf("loading environment"), terminal.lines.map { it.text })
        terminal.onBootStage(profilesLoaded = true)
        terminal.onBootStage(profilesLoaded = true)
        assertEquals(listOf("loading environment", "application ready"), terminal.lines.map { it.text })
    }

    @Test
    fun `warm entry with loaded profiles prints only the ready line`() {
        val terminal = state()
        terminal.onBootStage(profilesLoaded = true)
        assertEquals(listOf("application ready"), terminal.lines.map { it.text })
    }

    @Test
    fun `first idle snapshot is boot state, not a transition`() {
        val terminal = state()
        terminal.onConnection(ConnectionSnapshot(state = ConnectionState.IDLE))
        assertTrue(terminal.lines.isEmpty())
    }

    @Test
    fun `identical connected snapshots keep one live row`() {
        val terminal = state()
        val snapshot = ConnectionSnapshot(state = ConnectionState.CONNECTED, profileName = "fox")
        terminal.onConnection(snapshot)
        val progressId = terminal.progress?.id
        terminal.onConnection(snapshot)
        assertTrue(terminal.lines.isEmpty())
        assertEquals(progressId, terminal.progress?.id)
        assertEquals("checking connection", terminal.progress?.text)
    }

    @Test
    fun `warm home navigation replay is terminal read only`() {
        val terminal = state()
        val connecting = vpn(ConnectionState.CONNECTING).copy(lastChangeAt = 1_000L)
        val connected = vpn(ConnectionState.CONNECTED).copy(lastChangeAt = 2_000L)
        val identity = ipInfo("1.2.3.4").copy(countryCode = "de", fetchedAt = 1_500L)

        terminal.onConnection(connecting)
        terminal.onConnection(connected)
        terminal.onTorPhase(TorPhaseSnapshot(phase = TorNetworkPhase.OFFLINE))
        terminal.onTorIdentityProbe(TorIdentityProbeState())
        terminal.onI2pPhase(I2pPhaseSnapshot(phase = I2pNetworkPhase.OFFLINE))
        terminal.onRouteIpInfo(identity, null)
        val settledLines = terminal.lines.toList()
        assertNull(terminal.progress)

        // CliHomeNarrationEffects is recreated on each HOME composition and replays the current
        // StateFlow values. That replay may observe them, but it must not mint a new transition.
        repeat(3) {
            terminal.onConnection(connected)
            terminal.onTorPhase(TorPhaseSnapshot(phase = TorNetworkPhase.OFFLINE))
            terminal.onTorIdentityProbe(TorIdentityProbeState())
            terminal.onI2pPhase(I2pPhaseSnapshot(phase = I2pNetworkPhase.OFFLINE))
            terminal.onRouteIpInfo(identity, null)
        }

        assertEquals(settledLines, terminal.lines)
        assertNull(terminal.progress)
    }

    @Test
    fun `connected first observation keeps the bounded validation publication lead`() {
        val terminal = state()
        val connectedAt = 20_000L

        terminal.onConnection(
            vpn(ConnectionState.CONNECTED).copy(lastChangeAt = connectedAt),
        )

        assertEquals(terminalConnectedIdentityNotBefore(connectedAt), terminal.vpnIdentityNotBeforeMs)
    }

    @Test
    fun `i2p phases are reported once and offline boot is silent`() {
        val terminal = state()
        terminal.onI2pPhase(I2pPhaseSnapshot(phase = I2pNetworkPhase.OFFLINE))
        terminal.onI2pPhase(I2pPhaseSnapshot(phase = I2pNetworkPhase.STARTING))
        terminal.onI2pPhase(I2pPhaseSnapshot(phase = I2pNetworkPhase.STARTING))
        terminal.onI2pPhase(I2pPhaseSnapshot(phase = I2pNetworkPhase.CONNECTED))

        assertEquals(listOf("connection established · I2P"), terminal.lines.map { it.text })
        assertNull(terminal.progress)
    }

    // ---- M3/M5: последовательный рассказ о подключении, формулировки по режиму ----

    /** Прогон команды до конца печати: строки статуса ждут коммита промпта. */
    private fun CliTerminalState.type(text: String) {
        command(text)
        commitPrompt()
    }

    private fun vpn(state: ConnectionState, tor: Boolean = false) =
        ConnectionSnapshot(
            state = state,
            profileId = 7L,
            profileName = "fox",
            torActive = tor,
        )

    private fun torPhase(phase: TorNetworkPhase) = TorPhaseSnapshot(phase = phase)

    private fun torProbe(phase: TorIdentityProbePhase, generation: Long = 1L) =
        TorIdentityProbeState(generation = generation, phase = phase)

    private fun torInfo(ip: String = "5.6.7.8", country: String = "de") =
        ipInfo(ip).copy(countryCode = country)

    @Test
    fun `pure vpn updates one live row then commits the final ip and country`() {
        val terminal = state()
        terminal.type(CliCommands.startVpn("fox"))
        terminal.onConnection(vpn(ConnectionState.CONNECTING))
        val progressId = terminal.progress?.id
        assertEquals("connecting", terminal.progress?.text)
        terminal.onConnection(vpn(ConnectionState.CONNECTED))
        assertEquals(progressId, terminal.progress?.id)
        assertEquals("checking connection", terminal.progress?.text)
        terminal.onRouteIpInfo(ipInfo("1.2.3.4").copy(countryCode = "de"), null)

        assertEquals(
            listOf(
                "start VPN -p fox",
                "VPN IP",
                "connection established",
            ),
            terminal.lines.map { it.text },
        )
        val vpnLine = terminal.lines.single { it.text == "VPN IP" }
        assertEquals("1.2.3.4 · DE", vpnLine.value)
        assertEquals("de", vpnLine.flagCountry)
        assertNull(terminal.progress)
    }

    @Test
    fun `vpn and tor identities append as separate ordered rows`() {
        val terminal = state()
        terminal.onConnection(vpn(ConnectionState.CONNECTED, tor = true))
        terminal.onTorPhase(torPhase(TorNetworkPhase.CONNECTED))
        terminal.onRouteIpInfo(ipInfo("1.2.3.4"), torInfo())
        terminal.drainTorNarration()

        assertEquals(
            listOf("VPN IP", "connection established · VPN", "TOR IP", "connection established"),
            terminal.lines.map { it.text },
        )
        assertEquals(listOf("1.2.3.4", null, "5.6.7.8 · DE", null), terminal.lines.map { it.value })
        assertEquals(
            listOf(CliLineTone.VPN, CliLineTone.VPN, CliLineTone.TOR, CliLineTone.OK),
            terminal.lines.map { it.valueTone },
        )
    }

    @Test
    fun `unchanged route identity never replaces or repeats earlier rows`() {
        val terminal = state()
        terminal.onConnection(vpn(ConnectionState.CONNECTED, tor = true))
        terminal.onTorPhase(torPhase(TorNetworkPhase.CONNECTED))
        terminal.onRouteIpInfo(ipInfo("1.2.3.4"), torInfo())
        terminal.onRouteIpInfo(ipInfo("1.2.3.4"), torInfo())
        terminal.drainTorNarration()

        assertEquals(4, terminal.lines.size)
        assertFalse(terminal.blockPending)
    }

    @Test
    fun `connected metadata update does not reopen a completed vpn row`() {
        val terminal = state()
        terminal.onConnection(vpn(ConnectionState.CONNECTED))
        terminal.onRouteIpInfo(ipInfo("1.2.3.4"), null)

        terminal.onConnection(
            vpn(ConnectionState.CONNECTED).copy(profileName = "renamed"),
        )

        assertEquals(
            listOf("VPN IP", "connection established"),
            terminal.lines.map { it.text },
        )
        assertNull(terminal.progress)
    }

    @Test
    fun `geo enrichment for the same route ip does not duplicate the final result`() {
        val terminal = state()
        terminal.onConnection(vpn(ConnectionState.CONNECTED))
        terminal.onRouteIpInfo(ipInfo("1.2.3.4"), null)
        terminal.onRouteIpInfo(
            ipInfo("1.2.3.4").copy(countryCode = "de", city = "Berlin", isp = "Example ISP"),
            null,
        )

        assertEquals(listOf("1.2.3.4", null), terminal.lines.map { it.value })
        assertFalse(terminal.blockPending)
    }

    @Test
    fun `connected tor keeps one live row until confirmed ip and country`() {
        val terminal = state()
        terminal.type(CliCommands.START_TOR)
        assertEquals("connecting to the TOR network", terminal.progress?.text)
        terminal.onTorPhase(torPhase(TorNetworkPhase.CONNECTING))
        val progressId = terminal.progress?.id
        assertEquals("connecting to the TOR network", terminal.progress?.text)
        terminal.onTorPhase(torPhase(TorNetworkPhase.BUILDING_CIRCUITS))
        assertEquals(progressId, terminal.progress?.id)
        terminal.advanceTorTo("tor circuits")
        assertEquals("tor circuits", terminal.progress?.text)
        terminal.onTorPhase(torPhase(TorNetworkPhase.CONNECTED))
        assertEquals(progressId, terminal.progress?.id)
        terminal.advanceTorTo("tor connected")
        assertEquals("tor connected", terminal.progress?.text)
        terminal.onTorIdentityProbe(torProbe(TorIdentityProbePhase.LOOKING_UP))
        terminal.advanceTorTo("determining TOR IP")
        assertEquals("determining TOR IP", terminal.progress?.text)
        // An IP without a country is partial display data, not a successful Tor identity.
        terminal.onRouteIpInfo(null, ipInfo("5.6.7.8"))
        assertEquals("determining TOR IP", terminal.progress?.text)
        assertEquals(listOf("start TOR"), terminal.lines.map { it.text })
        terminal.onRouteIpInfo(null, ipInfo("5.6.7.8").copy(countryCode = "ZZ"))
        assertEquals("determining TOR IP", terminal.progress?.text)
        terminal.onRouteIpInfo(null, torInfo())
        terminal.drainTorNarration()

        assertEquals(
            listOf(
                "start TOR",
                "TOR IP",
                "connection established",
            ),
            terminal.lines.map { it.text },
        )
        val torLine = terminal.lines.single { it.text == "TOR IP" }
        assertEquals("5.6.7.8 · DE", torLine.value)
        assertEquals("DE", torLine.flagCountry)
        assertNull(terminal.progress)
    }

    @Test
    fun `a mode selection never opens tor progress before the runtime applies it`() {
        val terminal = state()
        terminal.onConnection(vpn(ConnectionState.CONNECTED))
        terminal.onRouteIpInfo(ipInfo("1.2.3.4"), null)
        terminal.onTorPhase(torPhase(TorNetworkPhase.OFFLINE))

        terminal.command(CliCommands.MODE_VPN_TOR)
        terminal.commitPrompt()

        assertNull(terminal.progress)
    }

    @Test
    fun `the first authoritative offline snapshot clears a primed tor row`() {
        val terminal = state()
        terminal.command(CliCommands.START_TOR)
        terminal.commitPrompt()
        assertEquals("connecting to the TOR network", terminal.progress?.text)

        terminal.onTorPhase(torPhase(TorNetworkPhase.OFFLINE))

        assertNull(terminal.progress)
        assertEquals(0, terminal.lines.count { it.text == "tor stopped" })
    }

    @Test
    fun `vpn plus tor keeps one live row and commits both identities only after success`() {
        val terminal = state()
        terminal.type(CliCommands.startVpnTor("fox"))
        terminal.onConnection(vpn(ConnectionState.CONNECTING, tor = true))
        val progressId = terminal.progress?.id
        terminal.onConnection(vpn(ConnectionState.CONNECTED, tor = true))
        terminal.onTorPhase(torPhase(TorNetworkPhase.CONNECTING))
        terminal.onTorPhase(torPhase(TorNetworkPhase.BUILDING_CIRCUITS))
        // Пока VPN не получил адрес, Tor не вытесняет его живую строку.
        assertEquals("checking connection", terminal.progress?.text)
        terminal.onRouteIpInfo(ipInfo("1.2.3.4"), null)
        assertEquals(progressId, terminal.progress?.id)
        terminal.advanceTorTo("tor circuits")
        assertEquals("tor circuits", terminal.progress?.text)
        terminal.onTorPhase(torPhase(TorNetworkPhase.CONNECTED))
        terminal.advanceTorTo("tor connected")
        assertEquals("tor connected", terminal.progress?.text)
        terminal.onTorIdentityProbe(torProbe(TorIdentityProbePhase.LOOKING_UP))
        terminal.advanceTorTo("determining TOR IP")
        assertEquals("determining TOR IP", terminal.progress?.text)
        terminal.onRouteIpInfo(ipInfo("1.2.3.4"), torInfo())
        terminal.drainTorNarration()

        assertEquals(
            listOf(
                "start VPN+TOR -p fox",
                "VPN IP",
                "connection established · VPN",
                "TOR IP",
                "connection established",
            ),
            terminal.lines.map { it.text },
        )
        assertEquals(listOf(null, "1.2.3.4", null, "5.6.7.8 · DE", null), terminal.lines.map { it.value })
        assertNull(terminal.progress)
    }

    @Test
    fun `vpn plus tor cannot finish when tor identity arrives before vpn identity`() {
        val terminal = state()
        terminal.type(CliCommands.startVpnTor("fox"))
        terminal.onConnection(vpn(ConnectionState.CONNECTING, tor = true))
        terminal.onConnection(vpn(ConnectionState.CONNECTED, tor = true))
        terminal.onTorPhase(torPhase(TorNetworkPhase.CONNECTED))
        terminal.onTorIdentityProbe(torProbe(TorIdentityProbePhase.LOOKING_UP))

        terminal.onRouteIpInfo(null, torInfo())
        assertEquals(listOf("start VPN+TOR -p fox"), terminal.lines.map { it.text })
        assertEquals("checking connection", terminal.progress?.text)

        terminal.onRouteIpInfo(ipInfo("1.2.3.4"), torInfo())
        terminal.drainTorNarration()
        assertEquals(
            listOf(
                "start VPN+TOR -p fox",
                "VPN IP",
                "connection established · VPN",
                "TOR IP",
                "connection established",
            ),
            terminal.lines.map { it.text },
        )
        assertNull(terminal.progress)
    }

    @Test
    fun `warm vpn tor and i2p snapshot commits final legs in route order`() {
        val terminal = state()

        // Composition can receive all three already-connected snapshots in its first frame. The
        // reducer must still preserve the product order and must not leave a stale live row.
        terminal.onConnection(vpn(ConnectionState.CONNECTED, tor = true))
        terminal.onTorPhase(torPhase(TorNetworkPhase.CONNECTED))
        terminal.onI2pPhase(I2pPhaseSnapshot(phase = I2pNetworkPhase.CONNECTED))
        assertEquals("checking connection", terminal.progress?.text)

        terminal.onRouteIpInfo(ipInfo("1.2.3.4"), torInfo())
        terminal.drainTorNarration()

        assertEquals(
            listOf(
                "VPN IP",
                "connection established · VPN",
                "TOR IP",
                "connection established",
                "connection established · VPN + Tor + I2P",
            ),
            terminal.lines.map { it.text },
        )
        assertNull(terminal.progress)
    }

    /**
     * «Весь трафик через TOR внутри VPN» стартует VPN-first: первая сессия собирается без
     * tor-маршрута, поэтому мост отдаёт OFFLINE, а когда отложенный hot reload доезжает —
     * сразу CONNECTED. Фазы CONNECTING не бывает вовсе, и раньше вся нога печаталась в никуда:
     * пользователь видел только последнюю строку.
     */
    @Test
    fun `a deferred tor route still uses one row after the vpn identity`() {
        val terminal = state()
        terminal.type(CliCommands.startVpnTor("fox"))
        terminal.onConnection(vpn(ConnectionState.CONNECTING, tor = true))
        terminal.onConnection(vpn(ConnectionState.CONNECTED, tor = true))
        // Мост: torActive=false → OFFLINE, затем отложенный апгрейд → CONNECTED, минуя CONNECTING.
        terminal.onTorPhase(torPhase(TorNetworkPhase.OFFLINE))
        terminal.onTorPhase(torPhase(TorNetworkPhase.CONNECTED))
        assertEquals("checking connection", terminal.progress?.text)
        terminal.onRouteIpInfo(ipInfo("1.2.3.4"), null)
        terminal.advanceTorTo("tor connected")
        assertEquals("tor connected", terminal.progress?.text)
        terminal.onTorIdentityProbe(torProbe(TorIdentityProbePhase.LOOKING_UP))
        terminal.advanceTorTo("determining TOR IP")
        assertEquals("determining TOR IP", terminal.progress?.text)
        assertEquals(
            listOf(
                "start VPN+TOR -p fox",
                "VPN IP",
                "connection established · VPN",
            ),
            terminal.lines.map { it.text },
        )
    }

    @Test
    fun `whole device deferred vpn tor keeps validation identity published before connected`() {
        val terminal = state()
        terminal.type(CliCommands.startVpnTor("fox"))
        terminal.onConnection(
            vpn(ConnectionState.CONNECTING, tor = false).copy(lastChangeAt = 1_000L),
        )
        val progressId = terminal.progress?.id
        terminal.onTorPhase(torPhase(TorNetworkPhase.OFFLINE))

        // Tunnel validation publishes this while the first (VPN-only) deferred session still
        // reports CONNECTING. It belongs to the generation and must survive CONNECTED's newer
        // lastChangeAt instead of leaving the terminal at "checking connection" forever.
        terminal.onRouteIpInfo(ipInfo("1.2.3.4"), null)
        assertEquals("connecting", terminal.progress?.text)
        terminal.onConnection(
            vpn(ConnectionState.CONNECTED, tor = false).copy(lastChangeAt = 2_000L),
        )

        assertEquals(progressId, terminal.progress?.id)
        assertEquals("connecting to the TOR network", terminal.progress?.text)
        assertEquals(
            listOf("start VPN+TOR -p fox", "VPN IP", "connection established · VPN"),
            terminal.lines.map { it.text },
        )

        // A restored/legacy bridge may coalesce the short RECONNECTING edge. The single live row
        // still catches up in order and remains until the independently confirmed Tor identity.
        terminal.onTorPhase(torPhase(TorNetworkPhase.CONNECTED))
        terminal.advanceTorTo("tor circuits")
        terminal.advanceTorTo("tor connected")
        terminal.onTorIdentityProbe(torProbe(TorIdentityProbePhase.LOOKING_UP))
        terminal.advanceTorTo("determining TOR IP")
        terminal.onRouteIpInfo(ipInfo("1.2.3.4"), torInfo())
        terminal.drainTorNarration()

        assertNull(terminal.progress)
        assertEquals(
            listOf(
                "start VPN+TOR -p fox",
                "VPN IP",
                "connection established · VPN",
                "TOR IP",
                "connection established",
            ),
            terminal.lines.map { it.text },
        )
    }

    @Test
    fun `vpn tor tunnel and proxy snapshots share one sequential live row`() {
        listOf(TrafficMode.TUNNEL, TrafficMode.PROXY).forEach { trafficMode ->
            listOf(true, false).forEach { identityBeforeConnected ->
                val terminal = state()
                terminal.type(CliCommands.startVpnTor("fox"))
                terminal.onConnection(
                    vpn(ConnectionState.CONNECTING, tor = true).copy(trafficMode = trafficMode),
                )
                val progressId = terminal.progress?.id
                terminal.onTorPhase(torPhase(TorNetworkPhase.CONNECTING))
                assertEquals(
                    "VPN must remain first for $trafficMode before=$identityBeforeConnected",
                    "connecting",
                    terminal.progress?.text,
                )

                if (identityBeforeConnected) terminal.onRouteIpInfo(ipInfo("1.2.3.4"), null)
                terminal.onConnection(
                    vpn(ConnectionState.CONNECTED, tor = true).copy(trafficMode = trafficMode),
                )
                if (!identityBeforeConnected) terminal.onRouteIpInfo(ipInfo("1.2.3.4"), null)
                terminal.onTorPhase(torPhase(TorNetworkPhase.BUILDING_CIRCUITS))
                terminal.onTorPhase(torPhase(TorNetworkPhase.CONNECTED))
                assertEquals(progressId, terminal.progress?.id)
                terminal.advanceTorTo("tor connected")
                terminal.onTorIdentityProbe(torProbe(TorIdentityProbePhase.LOOKING_UP))
                terminal.advanceTorTo("determining TOR IP")
                terminal.onRouteIpInfo(ipInfo("1.2.3.4"), torInfo())
                terminal.drainTorNarration()

                assertNull(
                    "$trafficMode before=$identityBeforeConnected must not leave a spinner",
                    terminal.progress,
                )
                assertEquals(1, terminal.lines.count { it.text == "VPN IP" })
                assertEquals(1, terminal.lines.count { it.text == "TOR IP" })
                assertTrue(
                    terminal.lines.indexOfFirst { it.text == "VPN IP" } <
                        terminal.lines.indexOfFirst { it.text == "TOR IP" },
                )
                assertEquals("connection established", terminal.lines.last().text)
            }
        }
    }

    @Test
    fun `settled vpn and vpn tor proxy identities ignore connected in place validation`() {
        listOf(false, true).forEach { torActive ->
            val terminal = state()
            val connected =
                vpn(ConnectionState.CONNECTED, tor = torActive).copy(
                    trafficMode = TrafficMode.PROXY,
                    lastChangeAt = 1_000L,
                )
            terminal.onConnection(connected)
            if (torActive) terminal.onTorPhase(torPhase(TorNetworkPhase.CONNECTED))
            terminal.onRouteIpInfo(ipInfo("1.2.3.4"), torInfo().takeIf { torActive })
            terminal.drainTorNarration()
            val settledLines = terminal.lines.toList()
            assertNull(terminal.progress)

            // Pixel ordering: both route identities have already committed, then a service hot
            // apply validates a replacement on the same Android tunnel. It remains CONNECTED and
            // only carries an ownership marker; this must be presentation-read-only.
            terminal.onConnection(
                connected.copy(
                    inPlaceRuntimeReload = true,
                    lastChangeAt = 2_000L,
                ),
            )
            terminal.onConnection(connected.copy(lastChangeAt = 3_000L))
            terminal.onRouteIpInfo(ipInfo("1.2.3.4"), torInfo().takeIf { torActive })

            assertEquals("tor=$torActive", settledLines, terminal.lines)
            assertNull("tor=$torActive must not leave a validation spinner", terminal.progress)
        }
    }

    @Test
    fun `attaching tor in place keeps settled vpn identity and narrates only the tor leg`() {
        listOf(TrafficMode.TUNNEL, TrafficMode.PROXY).forEach { trafficMode ->
            val terminal = state()
            val connected =
                vpn(ConnectionState.CONNECTED, tor = false).copy(
                    trafficMode = trafficMode,
                    lastChangeAt = 1_000L,
                )
            terminal.onConnection(vpn(ConnectionState.CONNECTING, tor = false).copy(trafficMode = trafficMode))
            terminal.onConnection(connected)
            terminal.onRouteIpInfo(ipInfo("1.2.3.4"), null)
            assertNull(terminal.progress)
            assertEquals(1, terminal.lines.count { it.text == "VPN IP" })

            terminal.onConnection(connected.copy(inPlaceRuntimeReload = true))
            terminal.onTorPhase(torPhase(TorNetworkPhase.CONNECTING))
            terminal.onConnection(connected.copy(torActive = true, inPlaceRuntimeReload = false))
            terminal.onTorPhase(torPhase(TorNetworkPhase.CONNECTED))
            terminal.onTorIdentityProbe(torProbe(TorIdentityProbePhase.LOOKING_UP, generation = 11L))
            terminal.onRouteIpInfo(ipInfo("1.2.3.4"), torInfo())
            terminal.drainTorNarration()

            assertNull("$trafficMode must finish the Tor leg", terminal.progress)
            assertEquals("$trafficMode must retain one VPN identity", 1, terminal.lines.count { it.text == "VPN IP" })
            assertEquals("$trafficMode must append one Tor identity", 1, terminal.lines.count { it.text == "TOR IP" })
            assertFalse(terminal.lines.any { it.text == "reconnecting" })
        }
    }

    @Test
    fun `real reconnect and cold tor removal still open progress after settled proxy identities`() {
        val realReconnect = state()
        val vpnConnected =
            vpn(ConnectionState.CONNECTED).copy(
                trafficMode = TrafficMode.PROXY,
                lastChangeAt = 1_000L,
            )
        realReconnect.onConnection(vpnConnected)
        realReconnect.onRouteIpInfo(ipInfo("1.2.3.4"), null)
        realReconnect.onConnection(
            vpnConnected.copy(
                state = ConnectionState.RECONNECTING,
                message = "network changed",
                lastChangeAt = 2_000L,
            ),
        )
        assertEquals("reconnecting", realReconnect.progress?.text)

        val coldTorRemoval = state()
        val vpnTorConnected =
            vpn(ConnectionState.CONNECTED, tor = true).copy(
                trafficMode = TrafficMode.PROXY,
                lastChangeAt = 1_000L,
            )
        coldTorRemoval.onConnection(vpnTorConnected)
        coldTorRemoval.onTorPhase(torPhase(TorNetworkPhase.CONNECTED))
        coldTorRemoval.onRouteIpInfo(ipInfo("1.2.3.4"), torInfo())
        coldTorRemoval.drainTorNarration()
        assertNull(coldTorRemoval.progress)

        coldTorRemoval.onConnection(
            vpnTorConnected.copy(
                state = ConnectionState.RECONNECTING,
                torActive = false,
                message = "cold restart",
                inPlaceRuntimeReload = false,
                lastChangeAt = 2_000L,
            ),
        )

        assertEquals("reconnecting", coldTorRemoval.progress?.text)
    }

    @Test
    fun `tor identity arriving before vpn identity is retained and committed in route order`() {
        val terminal = state()
        terminal.type(CliCommands.startVpnTor("fox"))
        terminal.onConnection(vpn(ConnectionState.CONNECTING, tor = true))
        terminal.onTorPhase(torPhase(TorNetworkPhase.CONNECTED))
        terminal.onTorIdentityProbe(torProbe(TorIdentityProbePhase.LOOKING_UP, generation = 9L))
        terminal.onRouteIpInfo(null, torInfo())
        assertEquals("connecting", terminal.progress?.text)

        terminal.onConnection(vpn(ConnectionState.CONNECTED, tor = true))
        terminal.onRouteIpInfo(ipInfo("1.2.3.4"), torInfo())
        terminal.drainTorNarration()

        assertNull(terminal.progress)
        assertTrue(
            terminal.lines.indexOfFirst { it.text == "VPN IP" } <
                terminal.lines.indexOfFirst { it.text == "TOR IP" },
        )
    }

    @Test
    fun `bounded vpn identity failure releases tor and a prior tor failure ends the row`() {
        val terminal = state()
        terminal.type(CliCommands.startVpnTor("fox"))
        terminal.onConnection(vpn(ConnectionState.CONNECTING, tor = true))
        val progressId = requireNotNull(terminal.progress?.id)
        terminal.onTorPhase(torPhase(TorNetworkPhase.CONNECTED))
        terminal.onTorIdentityProbe(torProbe(TorIdentityProbePhase.LOOKING_UP, generation = 11L))
        terminal.onTorIdentityProbe(torProbe(TorIdentityProbePhase.FAILED, generation = 11L))
        terminal.onConnection(vpn(ConnectionState.CONNECTED, tor = true))

        terminal.expireProgress(progressId)

        assertNull(terminal.progress)
        assertEquals(
            listOf("start VPN+TOR -p fox", "vpn ip failed", "tor ip failed"),
            terminal.lines.map { it.text },
        )
    }

    @Test
    fun `the catch-up phase waits for a confirmed tor identity`() {
        val terminal = state()
        terminal.type(CliCommands.startVpnTor("fox"))
        terminal.onConnection(vpn(ConnectionState.CONNECTED, tor = true))
        terminal.onRouteIpInfo(ipInfo("1.2.3.4"), null)
        terminal.onTorPhase(torPhase(TorNetworkPhase.CONNECTING))
        terminal.onTorPhase(torPhase(TorNetworkPhase.CONNECTED))

        terminal.advanceTorTo("tor connected")
        assertEquals("tor connected", terminal.progress?.text)
        terminal.onTorIdentityProbe(torProbe(TorIdentityProbePhase.LOOKING_UP))
        terminal.advanceTorTo("determining TOR IP")
        assertEquals("determining TOR IP", terminal.progress?.text)
        assertEquals(
            listOf("start VPN+TOR -p fox", "VPN IP", "connection established · VPN"),
            terminal.lines.map { it.text },
        )
    }

    @Test
    fun `a restarted tor leg gets a new live row`() {
        val terminal = state()
        terminal.type(CliCommands.START_TOR)
        terminal.onTorPhase(torPhase(TorNetworkPhase.CONNECTING))
        val firstProgressId = terminal.progress?.id
        terminal.onTorPhase(torPhase(TorNetworkPhase.CONNECTED))
        terminal.onTorIdentityProbe(torProbe(TorIdentityProbePhase.LOOKING_UP))
        terminal.onRouteIpInfo(null, torInfo())
        terminal.drainTorNarration()
        terminal.onTorPhase(torPhase(TorNetworkPhase.OFFLINE))
        terminal.onTorPhase(torPhase(TorNetworkPhase.CONNECTING))
        val secondProgressId = terminal.progress?.id
        terminal.onTorPhase(torPhase(TorNetworkPhase.CONNECTED))
        terminal.onTorIdentityProbe(torProbe(TorIdentityProbePhase.LOOKING_UP, generation = 2L))

        assertTrue(firstProgressId != secondProgressId)
        terminal.advanceTorTo("determining TOR IP")
        assertEquals("determining TOR IP", terminal.progress?.text)
        assertEquals(1, terminal.lines.count { it.text == "TOR IP" })
    }

    @Test
    fun `duplicate identity commits once and stop clears a live row without fake success`() {
        val terminal = state()
        terminal.onTorPhase(torPhase(TorNetworkPhase.CONNECTING))
        terminal.onTorPhase(torPhase(TorNetworkPhase.BUILDING_CIRCUITS))
        terminal.onTorPhase(torPhase(TorNetworkPhase.CONNECTED))
        terminal.onTorPhase(torPhase(TorNetworkPhase.CONNECTED))

        terminal.advanceTorTo("tor connected")
        assertEquals("tor connected", terminal.progress?.text)
        terminal.onTorIdentityProbe(torProbe(TorIdentityProbePhase.LOOKING_UP))
        terminal.advanceTorTo("determining TOR IP")
        assertEquals("determining TOR IP", terminal.progress?.text)
        assertEquals(0, terminal.lines.count { it.text == "TOR IP" })
        terminal.onRouteIpInfo(null, torInfo())
        terminal.onRouteIpInfo(null, torInfo())
        terminal.drainTorNarration()
        assertNull(terminal.progress)
        assertEquals(1, terminal.lines.count { it.text == "TOR IP" })

        terminal.onTorPhase(torPhase(TorNetworkPhase.OFFLINE))
        terminal.onTorPhase(torPhase(TorNetworkPhase.CONNECTING))
        assertEquals("connecting to the TOR network", terminal.progress?.text)
        terminal.type(CliCommands.STOP)
        assertNull(terminal.progress)
        assertEquals(1, terminal.lines.count { it.text == "TOR IP" })
    }

    @Test
    fun `disconnect keeps one live row through every real teardown phase and closes last`() {
        val terminal = state()
        terminal.type(CliCommands.STOP)
        terminal.commitPrompt()

        terminal.onConnection(
            vpn(ConnectionState.DISCONNECTING).copy(teardownPhase = RuntimeTeardownPhase.VPN),
        )
        val progressId = terminal.progress?.id
        assertEquals("disconnecting vpn", terminal.progress?.text)

        terminal.onConnection(
            vpn(ConnectionState.DISCONNECTING).copy(teardownPhase = RuntimeTeardownPhase.TOR),
        )
        assertEquals(progressId, terminal.progress?.id)
        assertEquals("disconnecting tor", terminal.progress?.text)

        terminal.onTorPhase(torPhase(TorNetworkPhase.OFFLINE))
        terminal.onConnection(
            vpn(ConnectionState.DISCONNECTING).copy(teardownPhase = RuntimeTeardownPhase.I2P),
        )
        terminal.onI2pPhase(I2pPhaseSnapshot(phase = I2pNetworkPhase.OFFLINE))
        assertEquals(progressId, terminal.progress?.id)
        assertEquals("disconnecting i2p", terminal.progress?.text)

        terminal.onConnection(
            vpn(ConnectionState.DISCONNECTING).copy(teardownPhase = RuntimeTeardownPhase.ANDROID_TUNNEL),
        )
        assertEquals(progressId, terminal.progress?.id)
        assertEquals("disconnecting android tunnel", terminal.progress?.text)

        terminal.onConnection(vpn(ConnectionState.IDLE))

        assertNull(terminal.progress)
        assertEquals("closed", terminal.lines.last().text)
        assertEquals(0, terminal.lines.count { it.text == "tor stopped" || it.text == "i2p stopped" })
    }

    @Test
    fun `bounded tor identity failure ends the row and rejects a late result`() {
        val terminal = state()
        terminal.type(CliCommands.START_TOR)
        terminal.onTorPhase(torPhase(TorNetworkPhase.CONNECTED))
        terminal.onTorIdentityProbe(torProbe(TorIdentityProbePhase.LOOKING_UP, generation = 7L))

        terminal.onTorIdentityProbe(torProbe(TorIdentityProbePhase.FAILED, generation = 7L))
        terminal.onTorIdentityProbe(torProbe(TorIdentityProbePhase.FAILED, generation = 7L))
        terminal.onRouteIpInfo(null, torInfo())

        assertNull(terminal.progress)
        assertEquals(listOf("start TOR", "tor ip failed"), terminal.lines.map { it.text })
        assertEquals(0, terminal.lines.count { it.text == "TOR IP" })
        assertEquals(0, terminal.lines.count { it.text == "connection established" })
    }

    @Test
    fun `i2p success after tor identity failure never claims tor success`() {
        val terminal = state()
        terminal.type(CliCommands.startVpnTor("fox"))
        terminal.onConnection(vpn(ConnectionState.CONNECTING, tor = true))
        terminal.onConnection(vpn(ConnectionState.CONNECTED, tor = true))
        terminal.onRouteIpInfo(ipInfo("1.2.3.4"), null)
        terminal.onTorPhase(torPhase(TorNetworkPhase.CONNECTED))
        terminal.onTorIdentityProbe(torProbe(TorIdentityProbePhase.LOOKING_UP, generation = 7L))
        terminal.onI2pPhase(I2pPhaseSnapshot(phase = I2pNetworkPhase.CONNECTED))

        terminal.onTorIdentityProbe(torProbe(TorIdentityProbePhase.FAILED, generation = 7L))

        assertNull(terminal.progress)
        assertEquals(
            listOf(
                "start VPN+TOR -p fox",
                "VPN IP",
                "connection established · VPN",
                "tor ip failed",
                "connection established · VPN + I2P",
            ),
            terminal.lines.map { it.text },
        )
        assertEquals(0, terminal.lines.count { it.text.contains("Tor + I2P") })
    }

    @Test
    fun `cancel fences a late tor identity and a new generation may finish once`() {
        val terminal = state()
        terminal.type(CliCommands.START_TOR)
        terminal.onTorPhase(torPhase(TorNetworkPhase.CONNECTED))
        terminal.onTorIdentityProbe(torProbe(TorIdentityProbePhase.LOOKING_UP, generation = 3L))
        terminal.onTorIdentityProbe(torProbe(TorIdentityProbePhase.CANCELLED, generation = 4L))
        terminal.onRouteIpInfo(null, torInfo())

        assertNull(terminal.progress)
        assertEquals(listOf("start TOR"), terminal.lines.map { it.text })

        terminal.onTorPhase(torPhase(TorNetworkPhase.OFFLINE))
        terminal.onTorPhase(torPhase(TorNetworkPhase.CONNECTING))
        terminal.onTorPhase(torPhase(TorNetworkPhase.CONNECTED))
        terminal.onTorIdentityProbe(torProbe(TorIdentityProbePhase.LOOKING_UP, generation = 5L))
        terminal.onRouteIpInfo(null, torInfo())
        terminal.onRouteIpInfo(null, torInfo())
        terminal.drainTorNarration()

        assertEquals(1, terminal.lines.count { it.text == "TOR IP" })
        assertEquals(1, terminal.lines.count { it.text == "connection established" })
    }

    @Test
    fun `runtime error clears a live tor row`() {
        val terminal = state()
        terminal.onTorPhase(torPhase(TorNetworkPhase.CONNECTING))
        terminal.onConnection(vpn(ConnectionState.ERROR).copy(message = "boom"))

        assertNull(terminal.progress)
        assertEquals(0, terminal.lines.count { it.text == "TOR IP" })
    }

    @Test
    fun `a mode request on a live tunnel waits for an applied tor phase`() {
        val terminal = state()
        terminal.type(CliCommands.startVpn("fox"))
        terminal.onConnection(vpn(ConnectionState.CONNECTED))
        terminal.onRouteIpInfo(ipInfo("1.2.3.4"), null)

        terminal.type(CliCommands.MODE_VPN_TOR)

        assertEquals(
            listOf(
                "start VPN -p fox",
                "VPN IP",
                "connection established",
                "mode VPN+TOR",
            ),
            terminal.lines.map { it.text },
        )
        assertNull(terminal.progress)
    }

    @Test
    fun `an applied connecting phase opens the tor row exactly once`() {
        val terminal = state()
        terminal.type(CliCommands.startVpn("fox"))
        terminal.onConnection(vpn(ConnectionState.CONNECTED))
        terminal.onRouteIpInfo(ipInfo("1.2.3.4"), null)
        terminal.type(CliCommands.MODE_VPN_TOR)
        assertNull(terminal.progress)

        terminal.onTorPhase(torPhase(TorNetworkPhase.CONNECTING))
        val progressId = terminal.progress?.id
        terminal.onTorPhase(torPhase(TorNetworkPhase.CONNECTING))

        assertEquals(progressId, terminal.progress?.id)
        assertEquals("connecting to the TOR network", terminal.progress?.text)
    }

    @Test
    fun `tor beside a live vpn uses the same compact tor progress row`() {
        val terminal = state()
        terminal.type(CliCommands.startVpn("fox"))
        terminal.onConnection(vpn(ConnectionState.CONNECTED))
        terminal.onRouteIpInfo(ipInfo("1.2.3.4"), null)
        // Bypass-маршрут: канон `mode TOR` ставит bypassVpnTunnel=true поверх живого туннеля.
        terminal.type(CliCommands.MODE_TOR)
        assertNull(terminal.progress)
        terminal.onTorPhase(torPhase(TorNetworkPhase.CONNECTING))

        assertEquals(
            listOf(
                "start VPN -p fox",
                "VPN IP",
                "connection established",
                "mode TOR",
            ),
            terminal.lines.map { it.text },
        )
        assertEquals("connecting to the TOR network", terminal.progress?.text)
    }

    @Test
    fun `profile name containing tor is not read as a tor order`() {
        val terminal = state()
        // «victoria» содержит подстроку tor: заказ маршрута обязан читать только
        // целевой токен после start/mode, а не всю команду.
        terminal.type(CliCommands.startVpn("victoria"))
        assertNull(terminal.progress)
        terminal.onConnection(vpn(ConnectionState.CONNECTED))
        terminal.onRouteIpInfo(ipInfo("1.2.3.4"), null)

        assertEquals(
            listOf(
                "start VPN -p victoria",
                "VPN IP",
                "connection established",
            ),
            terminal.lines.map { it.text },
        )
        assertNull(terminal.progress)
    }

    @Test
    fun `tor-only runtime never borrows the vpn wording`() {
        val terminal = state()
        val torOnly = ConnectionSnapshot(
            state = ConnectionState.CONNECTED,
            profileId = TOR_ONLY_PROFILE_ID,
        )
        terminal.onConnection(vpn(ConnectionState.IDLE))
        terminal.onConnection(torOnly)
        assertTrue(terminal.lines.isEmpty())
    }

    // ---- Причины разрыва: локализованный код вместо сырого текста исключения ----

    @Test
    fun `error prints the localized reason, not the raw exception text`() {
        val terminal = state()
        terminal.onConnection(
            vpn(ConnectionState.ERROR).copy(
                message = "java.net.SocketTimeoutException: connect timed out",
                reasonCode = AutoConnectReasonCode.HANDSHAKE_TIMEOUT,
            ),
        )
        assertEquals(listOf("error: handshake timeout"), terminal.lines.map { it.text })
    }

    @Test
    fun `error without a reason code prints unknown plus the raw tail as a dim step`() {
        val terminal = state()
        terminal.onConnection(vpn(ConnectionState.ERROR).copy(message = "boom"))
        assertEquals(listOf("error: unknown", "  boom"), terminal.lines.map { it.text })
        assertEquals(CliLineTone.DIM, terminal.lines.last().tone)
    }

    @Test
    fun `reconnecting prints the localized reason instead of the raw message`() {
        val terminal = state()
        terminal.onConnection(
            vpn(ConnectionState.RECONNECTING).copy(
                message = "raw exception text",
                reasonCode = AutoConnectReasonCode.DNS_FAILURE,
            ),
        )
        assertTrue(terminal.lines.isEmpty())
        assertEquals("reconnecting · dns failure", terminal.progress?.text)
    }

    @Test
    fun `watchdog closes only the connected identity wait it observed`() {
        val terminal = state()
        terminal.onConnection(vpn(ConnectionState.CONNECTING))
        terminal.onConnection(vpn(ConnectionState.CONNECTED))
        val progressId = requireNotNull(terminal.progress?.id)

        terminal.expireProgress(progressId + 1L)
        assertEquals(progressId, terminal.progress?.id)

        terminal.expireProgress(progressId)
        assertNull(terminal.progress)
        assertEquals("vpn ip failed", terminal.lines.last().text)
    }

    @Test
    fun `watchdog never labels a still establishing runtime as an ip failure`() {
        val terminal = state()
        terminal.onConnection(vpn(ConnectionState.CONNECTING))
        val progressId = requireNotNull(terminal.progress?.id)

        terminal.expireProgress(progressId)

        assertEquals(progressId, terminal.progress?.id)
        assertFalse(terminal.lines.any { it.text == "vpn ip failed" })
    }

    @Test
    fun `i2p updates one row and commits only the confirmed result`() {
        val terminal = state()
        terminal.onI2pPhase(I2pPhaseSnapshot(phase = I2pNetworkPhase.STARTING))
        val progressId = terminal.progress?.id
        terminal.onI2pPhase(I2pPhaseSnapshot(phase = I2pNetworkPhase.DISCOVERING_PEERS))
        assertEquals(progressId, terminal.progress?.id)
        terminal.onI2pPhase(I2pPhaseSnapshot(phase = I2pNetworkPhase.BUILDING_TUNNELS))
        assertEquals(progressId, terminal.progress?.id)
        terminal.onI2pPhase(I2pPhaseSnapshot(phase = I2pNetworkPhase.CONNECTED))

        assertEquals(listOf("connection established · I2P"), terminal.lines.map { it.text })
        assertNull(terminal.progress)
    }

    private fun ipInfo(ip: String) = IpInfo(
        ip = ip,
        countryCode = null,
        countryName = null,
        city = null,
        isp = null,
        fetchedAt = 0L,
    )

    private fun CliTerminalState.advanceTorTo(expected: String) {
        repeat(8) {
            if (progress?.text == expected) return
            advanceTorNarration()
        }
        assertEquals(expected, progress?.text)
    }

    private fun CliTerminalState.drainTorNarration() {
        repeat(8) {
            if (!torNarrationPending) return
            advanceTorNarration()
        }
        assertFalse(torNarrationPending)
    }

    @Test
    fun `command types at the prompt and commits into the log`() {
        val terminal = state()
        terminal.command("start")
        assertEquals("start", terminal.promptText)
        assertTrue(terminal.lines.isEmpty())
        terminal.commitPrompt()
        assertNull(terminal.promptText)
        val line = terminal.lines.single()
        assertEquals("start", line.text)
        assertTrue(line.prompt)
    }

    @Test
    fun `live connection row waits until the typed start command commits`() {
        val terminal = state()
        terminal.command(CliCommands.startVpn("fox"))
        terminal.onConnection(vpn(ConnectionState.CONNECTING))

        assertNull(terminalProgressForDisplay(terminal.progress, terminal.promptText))

        terminal.commitPrompt()
        assertEquals(
            terminal.progress,
            terminalProgressForDisplay(terminal.progress, terminal.promptText),
        )
    }

    @Test
    fun `explicit start owns a delayed connection and is never echoed as an autostart`() {
        val terminal = state()
        terminal.type(CliCommands.startVpn("fox"))
        // Regression for subscription refresh + I2P handoff taking longer than the former 6 s
        // duplicate-suppression window. The command's wall-clock timestamp is only journal data;
        // making it arbitrarily old must not change ownership of the pending START transaction.
        terminal.javaClass
            .getDeclaredField("lastUserCommandAtMs")
            .apply { isAccessible = true }
            .setLong(terminal, 0L)

        terminal.onConnection(vpn(ConnectionState.CONNECTING))

        assertEquals(
            1,
            terminal.lines.count { line -> line.prompt && line.text == CliCommands.startVpn("fox") },
        )
        assertEquals("connecting", terminal.progress?.text)
    }

    @Test
    fun `a new command commits the still-typing previous one`() {
        val terminal = state()
        terminal.command("start")
        terminal.command("stop")
        assertEquals("stop", terminal.promptText)
        assertEquals("start", terminal.lines.single().text)
    }

    @Test
    fun `a command committed after a held status line still reads first and in time order`() {
        val terminal = state()
        terminal.command(CliCommands.startVpn("fox"), output = "queued")
        Thread.sleep(CLOCK_STEP_MS)
        // Arrives while the command is still typing, so it carries an earlier source time.
        terminal.onConnection(vpn(ConnectionState.CONNECTING))
        Thread.sleep(CLOCK_STEP_MS)
        terminal.commitPrompt()

        val stamps = terminal.lines.map(CliTerminalLine::timestampMs)
        assertEquals(stamps.sorted(), stamps)
        assertEquals(
            listOf("start VPN -p fox", "queued"),
            terminal.lines.map { it.text },
        )
        assertEquals("connecting", terminal.progress?.text)
    }

    @Test
    fun `expired lines are pruned on append`() {
        val terminal = state(retentionHours = 0)
        terminal.note("old")
        Thread.sleep(5L)
        terminal.note("new")
        // Cutoff == now: the strictly-older first line is gone, the fresh one stays.
        assertEquals(listOf("new"), terminal.lines.map { it.text })
    }

    @Test
    fun `buffer is capped`() {
        val terminal = state()
        repeat(150) { index -> terminal.note("line $index") }
        assertEquals(120, terminal.lines.size)
        assertEquals("line 149", terminal.lines.last().text)
    }

    // ---- Блочный вывод команды (`status`): построчно, ключ + значение ----

    @Test
    fun `a block prints one key-value row per drain, in order`() {
        val terminal = state()
        terminal.emitBlock(
            listOf(
                CliTerminalRow(
                    key = "VPN",
                    value = "1.2.3.4 · NL · Amsterdam",
                    tone = CliLineTone.VPN,
                    flagCountry = "nl",
                ),
                CliTerminalRow(key = "rest", value = "direct", tone = CliLineTone.DIM),
            ),
        )
        // Очередь есть, но в лог сама по себе ничего не роняет — печатает только панель.
        assertTrue(terminal.blockPending)
        assertTrue(terminal.lines.isEmpty())

        assertTrue(terminal.drainBlockRow())
        val first = terminal.lines.single()
        assertEquals("VPN", first.text)
        assertEquals("1.2.3.4 · NL · Amsterdam", first.value)
        assertEquals(CliLineTone.DIM, first.tone)
        assertEquals(CliLineTone.VPN, first.valueTone)
        assertEquals("nl", first.flagCountry)

        assertTrue(terminal.drainBlockRow())
        assertEquals(listOf("VPN", "rest"), terminal.lines.map { it.text })
        assertEquals(listOf("1.2.3.4 · NL · Amsterdam", "direct"), terminal.lines.map { it.value })
        assertFalse(terminal.blockPending)
        assertFalse(terminal.drainBlockRow())
    }

    @Test
    fun `an empty block queues nothing`() {
        val terminal = state()
        terminal.emitBlock(emptyList())
        assertFalse(terminal.blockPending)
        assertFalse(terminal.drainBlockRow())
    }

    @Test
    fun `a block queued under a live prompt lands only after the command commits`() {
        val terminal = state()
        terminal.command(CliCommands.STATUS)
        terminal.emitBlock(listOf(CliTerminalRow(key = "route", value = "no active route")))
        // Даже если панель тикнет очередь раньше коммита, строка придерживается вместе со
        // статусными: сначала команда, потом её ответ.
        assertTrue(terminal.drainBlockRow())
        assertTrue(terminal.lines.isEmpty())

        terminal.commitPrompt()
        assertEquals(listOf(CliCommands.STATUS, "route"), terminal.lines.map { it.text })
    }

    @Test
    fun `a block obeys the line buffer cap`() {
        val terminal = state()
        terminal.emitBlock((1..150).map { CliTerminalRow(key = "row $it", value = "$it") })
        repeat(150) { assertTrue(terminal.drainBlockRow()) }
        assertEquals(120, terminal.lines.size)
        assertEquals("row 150", terminal.lines.last().text)
    }

    // Wall-clock steps: the log stamps lines with System.currentTimeMillis(), so the ordering
    // scenario needs real time to pass between the command and its commit.
    private companion object {
        const val CLOCK_STEP_MS = 5L
    }
}
