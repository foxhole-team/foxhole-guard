package com.foxhole.guard.ui.cli

import com.foxhole.guard.core.sentinel.FileCipher
import com.foxhole.guard.ui.cli.home.CliLineTone
import com.foxhole.guard.ui.cli.home.CliTerminalLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CliTerminalStoreTest {

    @Test
    fun `lines outlive the process and expire exactly at the retention edge`() {
        val file = journalFile()
        val nowMs = 10L * HOUR

        CliTerminalStore(file = file, fileCipher = ReversingTestFileCipher).append(
            newLines = listOf(line(timestampMs = nowMs, text = "reconnect: handshake timeout")),
            nowMs = nowMs,
            retentionHours = 6,
        )

        val afterRestart = CliTerminalStore(file = file, fileCipher = ReversingTestFileCipher)
        assertEquals(
            listOf("reconnect: handshake timeout"),
            afterRestart.load(nowMs = nowMs + HOUR, retentionHours = 6, limit = 120).map { it.text },
        )

        val expired = CliTerminalStore(file = file, fileCipher = ReversingTestFileCipher)
        assertEquals(
            emptyList<String>(),
            expired.load(nowMs = nowMs + 7L * HOUR, retentionHours = 6, limit = 120).map { it.text },
        )
    }

    @Test
    fun `a write applies retention to what is already on disk`() {
        val file = journalFile()
        val store = CliTerminalStore(file = file, fileCipher = ReversingTestFileCipher)
        store.append(listOf(line(timestampMs = 1L * HOUR, text = "old")), nowMs = 1L * HOUR, retentionHours = 6)

        store.append(listOf(line(timestampMs = 9L * HOUR, text = "new")), nowMs = 9L * HOUR, retentionHours = 6)

        val reread = CliTerminalStore(file = file, fileCipher = ReversingTestFileCipher)
        assertEquals(
            listOf("new"),
            reread.load(nowMs = 9L * HOUR, retentionHours = 6, limit = 120).map { it.text },
        )
        assertEquals(
            listOf("new"),
            CliTerminalStore(file = file, fileCipher = ReversingTestFileCipher)
                .load(nowMs = 9L * HOUR, retentionHours = CliTerminalPrefs.MAX_HOURS, limit = 120)
                .map { it.text },
        )
    }

    @Test
    fun `shortening the retention drops what fell outside it`() {
        val file = journalFile()
        val nowMs = 100L * HOUR
        val store = CliTerminalStore(file = file, fileCipher = ReversingTestFileCipher)
        store.append(
            newLines = listOf(
                line(timestampMs = nowMs - 40L * HOUR, text = "two days back"),
                line(timestampMs = nowMs - 2L * HOUR, text = "recent"),
            ),
            nowMs = nowMs,
            retentionHours = 48,
        )

        val shortened = CliTerminalStore(file = file, fileCipher = ReversingTestFileCipher)
        assertEquals(
            listOf("recent"),
            shortened.load(nowMs = nowMs, retentionHours = 6, limit = 120).map { it.text },
        )
    }

    @Test
    fun `the whole line survives the round trip, not just its text`() {
        val file = journalFile()
        val original = CliTerminalLine(
            timestampMs = 5L * HOUR,
            text = "exit",
            tone = CliLineTone.TOR,
            prompt = true,
            flagCountry = "de",
            value = "10.0.0.1",
            valueTone = CliLineTone.OK,
            packages = listOf("org.example.app"),
            valueLeading = true,
            inlineValue = true,
            typed = false,
            id = 42L,
        )
        CliTerminalStore(file = file, fileCipher = ReversingTestFileCipher)
            .append(listOf(original), nowMs = 5L * HOUR, retentionHours = 24)

        val restored = CliTerminalStore(file = file, fileCipher = ReversingTestFileCipher)
            .load(nowMs = 5L * HOUR, retentionHours = 24, limit = 120)
            .single()

        assertEquals(original.copy(id = 0L), restored)
    }

    @Test
    fun `the screen window is the tail, and the journal keeps the rest`() {
        val file = journalFile()
        val store = CliTerminalStore(file = file, fileCipher = ReversingTestFileCipher)
        store.append(
            newLines = (1..10).map { index -> line(timestampMs = HOUR + index, text = "line $index") },
            nowMs = 2L * HOUR,
            retentionHours = 24,
        )

        val window = CliTerminalStore(file = file, fileCipher = ReversingTestFileCipher)
            .load(nowMs = 2L * HOUR, retentionHours = 24, limit = 3)

        assertEquals(listOf("line 8", "line 9", "line 10"), window.map { it.text })
        assertEquals(
            10,
            CliTerminalStore(file = file, fileCipher = ReversingTestFileCipher)
                .load(nowMs = 2L * HOUR, retentionHours = 24, limit = 120)
                .size,
        )
    }

    @Test
    fun `an unreadable journal opens the terminal empty instead of failing`() {
        val file = journalFile()
        file.parentFile?.mkdirs()
        file.writeText("this is not an encrypted journal", Charsets.UTF_8)

        val loaded = CliTerminalStore(file = file, fileCipher = FailingReadTestFileCipher)
            .load(nowMs = HOUR, retentionHours = 24, limit = 120)

        assertEquals(emptyList<CliTerminalLine>(), loaded)
        assertFalse("a journal that cannot be read must not be left behind", file.exists())
    }

    @Test
    fun `clear removes the journal from disk`() {
        val file = journalFile()
        val store = CliTerminalStore(file = file, fileCipher = ReversingTestFileCipher)
        store.append(listOf(line(timestampMs = HOUR, text = "line")), nowMs = HOUR, retentionHours = 24)
        assertTrue(file.exists())

        store.clear()

        assertFalse(file.exists())
        assertEquals(
            emptyList<CliTerminalLine>(),
            CliTerminalStore(file = file, fileCipher = ReversingTestFileCipher)
                .load(nowMs = HOUR, retentionHours = 24, limit = 120),
        )
    }

    @Test
    fun `an absurd retention neither overflows nor empties the journal`() {
        val file = journalFile()
        val nowMs = 1_000L * HOUR
        val store = CliTerminalStore(file = file, fileCipher = ReversingTestFileCipher)

        store.append(listOf(line(timestampMs = nowMs, text = "line")), nowMs = nowMs, retentionHours = 999_999)

        assertEquals(
            listOf("line"),
            CliTerminalStore(file = file, fileCipher = ReversingTestFileCipher)
                .load(nowMs = nowMs, retentionHours = Int.MAX_VALUE, limit = 120)
                .map { it.text },
        )
    }

    private fun line(timestampMs: Long, text: String) =
        CliTerminalLine(timestampMs = timestampMs, text = text, tone = CliLineTone.INFO)

    private fun journalFile(): File =
        File(Files.createTempDirectory("foxhole-cli-terminal").toFile(), "terminal.jsonl.enc")

    private object ReversingTestFileCipher : FileCipher {
        override fun readBytes(file: File): ByteArray = file.readBytes().reversedArray()

        override fun writeBytesAtomic(file: File, plaintext: ByteArray) {
            file.parentFile?.mkdirs()
            file.writeBytes(plaintext.reversedArray())
        }
    }

    private object FailingReadTestFileCipher : FileCipher {
        override fun readBytes(file: File): ByteArray = error("cannot decrypt ${file.name}")

        override fun writeBytesAtomic(file: File, plaintext: ByteArray) {
            file.parentFile?.mkdirs()
            file.writeBytes(plaintext)
        }
    }

    private companion object {
        const val HOUR = 3_600_000L
    }
}
