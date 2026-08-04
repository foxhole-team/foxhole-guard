package com.foxhole.guard.ui.cli.home

import com.foxhole.core.model.AutoConnectReasonCode
import com.foxhole.core.model.ConnectionSnapshot
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.I2pNetworkPhase
import com.foxhole.core.model.I2pPhaseSnapshot
import com.foxhole.core.model.IpInfo
import com.foxhole.core.model.TOR_ONLY_PROFILE_ID
import com.foxhole.core.model.TorNetworkPhase
import com.foxhole.core.model.TorPhaseSnapshot
import com.foxhole.guard.ui.cli.CliCommands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CliTerminalStateTest {

    private fun strings() = CliTerminalStrings(
        ready = "ready",
        connecting = "connecting",
        tunnelUp = "connection established",
        tunnelUpNamed = "connection established · %s",
        reconnecting = "reconnecting",
        error = "error: %s",
        unknown = "unknown",
        closed = "closed",
        torBesideVpn = "TOR and VPN",
        torConnecting = "connecting to the TOR network",
        torCircuits = "tor circuits",
        torConnected = "connected to the TOR network",
        torStopped = "tor stopped",
        i2pStarting = "connecting to the I2P network",
        i2pDiscovering = "i2p discovering",
        i2pTunnels = "i2p tunnels",
        i2pConnected = "connected to the I2P network",
        i2pStopped = "i2p stopped",
        exitKeyIp = "ip",
        exitKeyGeo = "geo",
        exitKeyIsp = "isp",
        reasonLabels = mapOf(
            AutoConnectReasonCode.HANDSHAKE_TIMEOUT to "handshake timeout",
            AutoConnectReasonCode.DNS_FAILURE to "dns failure",
        ),
    )

    private fun state(retentionHours: Int = 12) =
        CliTerminalState(strings(), retentionHours = { retentionHours })

    @Test
    fun `welcome prints once`() {
        val terminal = state()
        terminal.welcome("1.0")
        terminal.welcome("1.0")
        assertEquals(2, terminal.lines.size)
    }

    @Test
    fun `first idle snapshot is boot state, not a transition`() {
        val terminal = state()
        terminal.onConnection(ConnectionSnapshot(state = ConnectionState.IDLE))
        assertTrue(terminal.lines.isEmpty())
    }

    @Test
    fun `identical snapshots dedupe to one line`() {
        val terminal = state()
        val snapshot = ConnectionSnapshot(state = ConnectionState.CONNECTED, profileName = "fox")
        terminal.onConnection(snapshot)
        terminal.onConnection(snapshot)
        assertEquals(1, terminal.lines.size)
        assertEquals("connection established · fox", terminal.lines.single().text)
    }

    @Test
    fun `i2p phases are reported once and offline boot is silent`() {
        val terminal = state()
        terminal.onI2pPhase(I2pPhaseSnapshot(phase = I2pNetworkPhase.OFFLINE))
        terminal.onI2pPhase(I2pPhaseSnapshot(phase = I2pNetworkPhase.STARTING))
        terminal.onI2pPhase(I2pPhaseSnapshot(phase = I2pNetworkPhase.STARTING))
        terminal.onI2pPhase(I2pPhaseSnapshot(phase = I2pNetworkPhase.CONNECTED))

        assertEquals(
            listOf("  connecting to the I2P network", "connected to the I2P network"),
            terminal.lines.map { it.text },
        )
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

    @Test
    fun `pure vpn prints command then connecting then established then ip block`() {
        val terminal = state()
        terminal.type(CliCommands.startVpn("fox"))
        terminal.onConnection(vpn(ConnectionState.CONNECTING))
        terminal.onConnection(vpn(ConnectionState.CONNECTED))
        terminal.onIpInfo(ipInfo("1.2.3.4"))

        // Сводка выхода идёт блочной очередью: до тика панели её строк в логе нет.
        assertTrue(terminal.blockPending)
        assertTrue(terminal.drainBlockRow())

        assertEquals(
            listOf(
                "start VPN -p fox",
                "  connecting",
                "connection established · fox",
                "ip",
            ),
            terminal.lines.map { it.text },
        )
        assertEquals("1.2.3.4", terminal.lines.last().value)
    }

    @Test
    fun `pure tor announces the network before the circuit lines`() {
        val terminal = state()
        terminal.type(CliCommands.START_TOR)
        terminal.onTorPhase(torPhase(TorNetworkPhase.CONNECTING))
        terminal.onTorPhase(torPhase(TorNetworkPhase.BUILDING_CIRCUITS))
        terminal.onTorPhase(torPhase(TorNetworkPhase.CONNECTED))
        terminal.onIpInfo(ipInfo("5.6.7.8"))
        assertTrue(terminal.drainBlockRow())

        assertEquals(
            listOf(
                "start TOR",
                "  connecting to the TOR network",
                "connected to the TOR network",
                "  tor circuits",
                "ip",
            ),
            terminal.lines.map { it.text },
        )
        assertEquals("5.6.7.8", terminal.lines.last().value)
    }

    @Test
    fun `tor over vpn prints the vpn steps first and no parallel-legs header`() {
        val terminal = state()
        terminal.type(CliCommands.startVpnTor("fox"))
        terminal.onConnection(vpn(ConnectionState.CONNECTING, tor = true))
        terminal.onConnection(vpn(ConnectionState.CONNECTED, tor = true))
        terminal.onTorPhase(torPhase(TorNetworkPhase.CONNECTING))
        terminal.onTorPhase(torPhase(TorNetworkPhase.BUILDING_CIRCUITS))

        assertEquals(
            listOf(
                "start VPN+TOR -p fox",
                "  connecting",
                "connection established · fox",
                "  connecting to the TOR network",
                "connected to the TOR network",
                "  tor circuits",
            ),
            terminal.lines.map { it.text },
        )
    }

    @Test
    fun `tor beside a live vpn is announced as two parallel legs`() {
        val terminal = state()
        terminal.type(CliCommands.startVpn("fox"))
        terminal.onConnection(vpn(ConnectionState.CONNECTED))
        // Bypass-маршрут: канон `mode TOR` ставит bypassVpnTunnel=true поверх живого туннеля.
        terminal.type(CliCommands.MODE_TOR)
        terminal.onTorPhase(torPhase(TorNetworkPhase.CONNECTING))

        assertEquals(
            listOf(
                "start VPN -p fox",
                "connection established · fox",
                "mode TOR",
                "  TOR and VPN",
                "  connecting to the TOR network",
            ),
            terminal.lines.map { it.text },
        )
    }

    @Test
    fun `profile name containing tor is not read as a tor order`() {
        val terminal = state()
        // «victoria» содержит подстроку tor: заказ маршрута обязан читать только
        // целевой токен после start/mode, а не всю команду.
        terminal.type(CliCommands.startVpn("victoria"))
        terminal.onConnection(vpn(ConnectionState.CONNECTED))
        terminal.onTorPhase(torPhase(TorNetworkPhase.CONNECTING))

        assertEquals(
            listOf(
                "start VPN -p victoria",
                "connection established · fox",
                "  connecting to the TOR network",
            ),
            terminal.lines.map { it.text },
        )
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
        assertEquals(listOf("  reconnecting · dns failure"), terminal.lines.map { it.text })
    }

    @Test
    fun `i2p announces the network before the tunnel-build line`() {
        val terminal = state()
        terminal.onI2pPhase(I2pPhaseSnapshot(phase = I2pNetworkPhase.STARTING))
        terminal.onI2pPhase(I2pPhaseSnapshot(phase = I2pNetworkPhase.DISCOVERING_PEERS))
        terminal.onI2pPhase(I2pPhaseSnapshot(phase = I2pNetworkPhase.BUILDING_TUNNELS))
        terminal.onI2pPhase(I2pPhaseSnapshot(phase = I2pNetworkPhase.CONNECTED))

        assertEquals(
            listOf(
                "  connecting to the I2P network",
                "  i2p discovering",
                "connected to the I2P network",
                "  i2p tunnels",
            ),
            terminal.lines.map { it.text },
        )
    }

    private fun ipInfo(ip: String) = IpInfo(
        ip = ip,
        countryCode = null,
        countryName = null,
        city = null,
        isp = null,
        fetchedAt = 0L,
    )

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
    fun `a new command commits the still-typing previous one`() {
        val terminal = state()
        terminal.command("start")
        terminal.command("stop")
        assertEquals("stop", terminal.promptText)
        assertEquals("start", terminal.lines.single().text)
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
}
