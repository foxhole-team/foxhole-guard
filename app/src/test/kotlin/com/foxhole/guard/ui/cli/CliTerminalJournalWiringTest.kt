package com.foxhole.guard.ui.cli

import com.foxhole.guard.core.sentinel.FileCipher
import com.foxhole.guard.ui.cli.home.CliLineTone
import com.foxhole.guard.ui.cli.home.CliTerminalLine
import com.foxhole.guard.ui.cli.home.CliTerminalState
import com.foxhole.guard.ui.cli.home.CliTerminalStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CliTerminalJournalWiringTest {

    private val history = listOf(
        CliTerminalLine(timestampMs = 1L, text = "yesterday: reconnect", tone = CliLineTone.WARN),
        CliTerminalLine(timestampMs = 2L, text = "yesterday: tunnel up", tone = CliLineTone.OK),
    )

    @Test
    fun `every committed line is handed to the journal`() {
        val recorded = mutableListOf<CliTerminalLine>()
        val terminal = CliTerminalState(strings = strings(), onCommitted = recorded::add)

        terminal.welcome("1.2.3")
        terminal.publishColdStart()

        assertEquals(
            terminal.lines.filter { it.id != Long.MIN_VALUE }.map { it.text },
            recorded.map { it.text },
        )
        assertTrue(recorded.isNotEmpty())
    }

    @Test
    fun `a cold start shows the decrypting notice and nothing else`() {
        val recorded = mutableListOf<CliTerminalLine>()
        val terminal = CliTerminalState(strings = strings(), onCommitted = recorded::add)

        terminal.welcome("1.2.3")
        terminal.onBootStage(profilesLoaded = false)

        assertTrue(terminal.lines.isEmpty())
        assertEquals("loading", terminal.bootProgress?.text)
        assertTrue(recorded.isEmpty())
    }

    @Test
    fun `the log stays held until both the journal and the profile store report in`() {
        val terminal = CliTerminalState(strings = strings())
        terminal.welcome("1.2.3")
        terminal.onBootStage(profilesLoaded = false)

        terminal.onJournalRestored(history)

        assertTrue(terminal.lines.isEmpty())
        assertEquals("loading", terminal.bootProgress?.text)
    }

    @Test
    fun `the whole log appears at once when the profile store opens`() {
        val terminal = CliTerminalState(strings = strings())
        terminal.welcome("1.2.3")
        terminal.onBootStage(profilesLoaded = false)
        terminal.onJournalRestored(history)

        terminal.onBootStage(profilesLoaded = true)

        assertEquals(
            listOf("yesterday: reconnect", "yesterday: tunnel up", "FoxHole Guard · v1.2.3", "ready"),
            terminal.lines.map { it.text },
        )
        assertEquals(terminal.lines.size, terminal.lines.map { it.id }.distinct().size)
    }

    @Test
    fun `restored history goes above what this session already printed`() {
        val terminal = CliTerminalState(strings = strings())
        terminal.welcome("1.2.3")

        terminal.publishColdStart(history)

        assertEquals(
            listOf("yesterday: reconnect", "yesterday: tunnel up", "FoxHole Guard · v1.2.3", "ready"),
            terminal.lines.map { it.text },
        )
        assertEquals(terminal.lines.size, terminal.lines.map { it.id }.distinct().size)
    }

    @Test
    fun `restoring nothing changes nothing`() {
        val terminal = CliTerminalState(strings = strings())
        terminal.welcome("1.2.3")
        terminal.publishColdStart()
        val before = terminal.lines.toList()

        terminal.onJournalRestored(emptyList())

        assertEquals(before, terminal.lines.toList())
    }

    @Test
    fun `clear drops old state but keeps the version and ready facts`() {
        var clearCalls = 0
        val terminal = CliTerminalState(
            strings = strings(),
            onCleared = { clearCalls += 1 },
            startsHeld = false,
        )
        terminal.welcome("1.2.3")
        terminal.onBootStage(profilesLoaded = true)
        terminal.note("visible")
        terminal.command("typing", output = "queued output")

        terminal.clearHistory()
        terminal.onJournalRestored(history)

        assertEquals(1, clearCalls)
        assertEquals(
            listOf("FoxHole Guard · v1.2.3", "ready"),
            terminal.lines.map { it.text },
        )
        assertNull(terminal.promptText)
        assertFalse(terminal.blockPending)
        terminal.note("fresh")
        assertEquals(
            listOf("FoxHole Guard · v1.2.3", "ready", "fresh"),
            terminal.lines.map { it.text },
        )
    }

    @Test
    fun `the replacement version and ready facts survive the journal clear generation`() {
        val file = File(Files.createTempDirectory("foxhole-cli-terminal-reset").toFile(), "terminal.jsonl.enc")
        val store = CliTerminalStore(file = file, fileCipher = PlainTestFileCipher)
        val writer = CliTerminalJournalWriter(
            store = store,
            retentionHours = { 24 },
            nowMs = { NOW_MS },
        )
        val terminal = CliTerminalState(
            strings = strings(),
            onCommitted = writer::record,
            onCleared = writer::clear,
            startsHeld = false,
        )
        terminal.welcome("1.2.3")
        terminal.onBootStage(profilesLoaded = true)
        terminal.note("old history")

        terminal.clearHistory()
        writer.shutdown()

        assertTrue(
            "journal reset did not retain the canonical session facts",
            awaitJournalText(file, listOf("FoxHole Guard · v1.2.3")),
        )
    }

    @Test
    fun `a journal read that lands after publication still goes above the session`() {
        val terminal = CliTerminalState(strings = strings())
        terminal.welcome("1.2.3")
        terminal.publishColdStart()

        terminal.onJournalRestored(history)

        assertEquals(
            listOf("yesterday: reconnect", "yesterday: tunnel up", "FoxHole Guard · v1.2.3", "ready"),
            terminal.lines.map { it.text },
        )
    }

    private fun CliTerminalState.publishColdStart(restored: List<CliTerminalLine> = emptyList()) {
        onBootStage(profilesLoaded = true)
        onJournalRestored(restored)
    }

    @Test
    fun `the writer coalesces a burst and still flushes it on shutdown`() {
        val file = File(Files.createTempDirectory("foxhole-cli-terminal-writer").toFile(), "terminal.jsonl.enc")
        val store = CliTerminalStore(file = file, fileCipher = PlainTestFileCipher)
        val writer = CliTerminalJournalWriter(
            store = store,
            retentionHours = { 24 },
            nowMs = { NOW_MS },
        )

        (1..5).forEach { index ->
            writer.record(CliTerminalLine(timestampMs = NOW_MS, text = "line $index", tone = CliLineTone.INFO))
        }
        writer.shutdown()
        assertTrue("journal writer did not finish", awaitJournal(file))

        val reread = CliTerminalStore(file = file, fileCipher = PlainTestFileCipher)
            .load(nowMs = NOW_MS, retentionHours = 24, limit = 120)
        assertEquals((1..5).map { "line $it" }, reread.map { it.text })
    }

    @Test
    fun `clear cannot be undone by an older queued write`() {
        val file = File(Files.createTempDirectory("foxhole-cli-terminal-clear").toFile(), "terminal.jsonl.enc")
        val store = CliTerminalStore(file = file, fileCipher = PlainTestFileCipher)
        val writer = CliTerminalJournalWriter(
            store = store,
            retentionHours = { 24 },
            nowMs = { NOW_MS },
        )
        writer.record(CliTerminalLine(timestampMs = NOW_MS, text = "before clear"))
        writer.clear()
        writer.record(CliTerminalLine(timestampMs = NOW_MS, text = "after clear"))
        writer.shutdown()

        assertTrue("journal clear/write sequence did not finish", awaitJournalText(file, listOf("after clear")))
    }

    private fun awaitJournal(file: File): Boolean {
        val deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (file.isFile && file.length() > 0L) {
                return true
            }
            Thread.sleep(AWAIT_POLL_MS)
        }
        return false
    }

    private fun awaitJournalText(file: File, expected: List<String>): Boolean {
        val deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val actual = CliTerminalStore(file = file, fileCipher = PlainTestFileCipher)
                .load(nowMs = NOW_MS, retentionHours = 24, limit = 120)
                .map { it.text }
            if (actual == expected) return true
            Thread.sleep(AWAIT_POLL_MS)
        }
        return false
    }

    private fun strings() = CliTerminalStrings(
        bootLoading = "loading",
        bootReady = "ready",
        connecting = "connecting",
        connected = "connected",
        tunnelUp = "up",
        tunnelUpNamed = "up · %s",
        vpnEstablished = "up VPN",
        vpnExitFailed = "vpn ip failed",
        i2pEstablished = "up I2P",
        reconnecting = "reconnecting",
        error = "error: %s",
        unknown = "unknown",
        closed = "closed",
        torBesideVpn = "tor beside vpn",
        torStarting = "tor starting",
        torConnecting = "tor connecting",
        torCircuits = "tor circuits",
        torConnected = "tor connected",
        torExitLookup = "determining TOR IP",
        torExitFailed = "tor ip failed",
        torStopped = "tor stopped",
        i2pStarting = "i2p starting",
        i2pDiscovering = "i2p discovering",
        i2pTunnels = "i2p tunnels",
        i2pTunnelsCount = "i2p tunnels %s",
        i2pConnected = "i2p connected",
        i2pStopped = "i2p stopped",
        disconnectingVpn = "disconnecting vpn",
        disconnectingTor = "disconnecting tor",
        disconnectingI2p = "disconnecting i2p",
        disconnectingAndroidTunnel = "disconnecting android tunnel",
        exitKeyIp = "ip",
        exitKeyGeo = "geo",
        exitKeyIsp = "isp",
    )

    private object PlainTestFileCipher : FileCipher {
        override fun readBytes(file: File): ByteArray = file.readBytes()

        override fun writeBytesAtomic(file: File, plaintext: ByteArray) {
            file.parentFile?.mkdirs()
            file.writeBytes(plaintext)
        }
    }

    private companion object {
        const val NOW_MS = 1_000_000L
        const val AWAIT_TIMEOUT_MS = 5_000L
        const val AWAIT_POLL_MS = 20L
    }
}
