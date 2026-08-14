package com.foxhole.guard.core.diagnostics
import com.foxhole.core.model.DiagnosticEntry
import com.foxhole.core.model.RetentionPolicy
import com.foxhole.core.model.RetentionPreset
import com.foxhole.guard.core.sentinel.FileCipher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DiagnosticsSessionStoreTest {
    @Test
    fun `append writes encrypted entries and another store loads them`() {
        val directory = Files.createTempDirectory("foxhole-diagnostics-journal").toFile()
        val firstStore =
            DiagnosticsSessionStore(
                journalDir = directory,
                sessionIdProvider = { "first" },
                fileCipher = ReversingTestFileCipher,
            )
        firstStore.append(
            DiagnosticEntry(
                timestamp = 1_000L,
                tag = "network",
                message = "remote=203.0.113.10 url=https://example.test/sub"
            ),
            RetentionPolicy(RetentionPreset.DAY),
        )

        val secondStore =
            DiagnosticsSessionStore(
                journalDir = directory,
                sessionIdProvider = { "second" },
                fileCipher = ReversingTestFileCipher,
            )
        val entries = secondStore.loadRecentEntries(now = 2_000L, retention = RetentionPolicy(RetentionPreset.DAY))

        assertEquals(1, entries.size)
        assertEquals("remote=[redacted] url=https://[redacted]", entries.single().message)
        val persisted = directory.listFiles().orEmpty().single()
        assertTrue(persisted.name.endsWith(".jsonl.enc"))
        assertFalse(persisted.readText().contains("203.0.113.10"))
    }

    @Test
    fun `legacy plaintext session files are migrated away after load`() {
        val directory = Files.createTempDirectory("foxhole-diagnostics-legacy").toFile()
        val legacyFile = File(directory, "session-1000-legacy.jsonl")
        legacyFile.writeText(
            """{"timestamp":1000,"tag":"network","message":"remote=203.0.113.10"}""" + "\n",
            Charsets.UTF_8,
        )
        val store =
            DiagnosticsSessionStore(
                journalDir = directory,
                sessionIdProvider = { "migrated" },
                fileCipher = ReversingTestFileCipher,
            )

        val entries = store.loadRecentEntries(now = 2_000L, retention = RetentionPolicy(RetentionPreset.DAY))

        assertEquals(listOf("remote=[redacted]"), entries.map(DiagnosticEntry::message))
        assertFalse(legacyFile.exists())
        assertTrue(directory.listFiles().orEmpty().single().name.endsWith(".jsonl.enc"))
        assertFalse(directory.listFiles().orEmpty().single().readText().contains("203.0.113.10"))
    }

    @Test
    fun `encrypted journal read failure is surfaced as diagnostics entry`() {
        val directory = Files.createTempDirectory("foxhole-diagnostics-corrupt").toFile()
        File(directory, "session-1000-corrupt.jsonl.enc").writeText("not decryptable", Charsets.UTF_8)
        val store =
            DiagnosticsSessionStore(
                journalDir = directory,
                sessionIdProvider = { "reader" },
                fileCipher = FailingReadTestFileCipher,
            )

        val entries = store.loadRecentEntries(now = 2_000L, retention = RetentionPolicy(RetentionPreset.DAY))

        assertEquals(1, entries.size)
        assertEquals("diagnostics", entries.single().tag)
        assertTrue(entries.single().message.contains("diagnostics journal read failed"))
    }

    @Test
    fun `append rotates away from unreadable current journal and keeps failure signal`() {
        val directory = Files.createTempDirectory("foxhole-diagnostics-append-corrupt").toFile()
        val corruptFile = File(directory, "session-1000-current.jsonl.enc")
        corruptFile.writeText("not decryptable", Charsets.UTF_8)
        val store =
            DiagnosticsSessionStore(
                journalDir = directory,
                sessionIdProvider = { "rotated" },
                fileCipher = FailingFirstReadTestFileCipher(corruptFile),
            )

        store.append(
            DiagnosticEntry(timestamp = 2_000L, tag = "network", message = "kept"),
            RetentionPolicy(RetentionPreset.DAY),
        )
        val entries = store.loadRecentEntries(now = 3_000L, retention = RetentionPolicy(RetentionPreset.DAY))

        assertTrue(
            entries.any { entry -> entry.tag == "diagnostics" && entry.message.contains("diagnostics journal read failed") },
        )
        assertTrue(entries.any { entry -> entry.tag == "network" && entry.message == "kept" })
        assertTrue(directory.listFiles().orEmpty().count { file -> file.name.endsWith(".jsonl.enc") } >= 2)
    }

    @Test
    fun `retention removes expired session files and keeps recent entries`() {
        val directory = Files.createTempDirectory("foxhole-diagnostics-retention").toFile()
        val store =
            DiagnosticsSessionStore(
                journalDir = directory,
                sessionIdProvider = { "retention" },
                fileCipher = ReversingTestFileCipher,
            )
        store.append(
            DiagnosticEntry(timestamp = 1_000L, tag = "old", message = "expired"),
            RetentionPolicy(RetentionPreset.DAY)
        )
        directory.listFiles().orEmpty().forEach { file -> file.setLastModified(1_000L) }

        val later =
            DiagnosticsSessionStore(
                journalDir = directory,
                sessionIdProvider = { "later" },
                fileCipher = ReversingTestFileCipher,
            )
        later.append(
            DiagnosticEntry(timestamp = 30L * 60L * 60L * 1000L, tag = "new", message = "kept"),
            RetentionPolicy(RetentionPreset.DAY)
        )
        val entries = later.loadRecentEntries(
            now = 30L * 60L * 60L * 1000L,
            retention = RetentionPolicy(RetentionPreset.DAY)
        )

        assertEquals(listOf("kept"), entries.map(DiagnosticEntry::message))
    }

    @Test
    fun `smart start replay entries use diagnostics retention cleanup`() {
        val directory = Files.createTempDirectory("foxhole-smart-start-replay-retention").toFile()
        val store =
            DiagnosticsSessionStore(
                journalDir = directory,
                sessionIdProvider = { "replay" },
                fileCipher = ReversingTestFileCipher,
            )
        store.append(
            DiagnosticEntry(
                timestamp = 1_000L,
                tag = "smart-start-replay",
                message = """{"profileId":1,"optionId":"old"}""",
            ),
            RetentionPolicy(RetentionPreset.DAY),
        )
        directory.listFiles().orEmpty().forEach { file -> file.setLastModified(1_000L) }

        val now = 30L * 60L * 60L * 1000L
        store.append(
            DiagnosticEntry(
                timestamp = now,
                tag = "smart-start-replay",
                message = """{"profileId":2,"optionId":"current"}""",
            ),
            RetentionPolicy(RetentionPreset.DAY),
        )

        val entries = store.loadRecentEntries(now = now, retention = RetentionPolicy(RetentionPreset.DAY))

        assertEquals(
            listOf("""{"profileId":"[redacted]","optionId":"[redacted]"}"""),
            entries.map(DiagnosticEntry::message),
        )
    }

    private object ReversingTestFileCipher : FileCipher {
        override fun readBytes(file: File): ByteArray = file.readBytes().reversedArray()

        override fun writeBytesAtomic(
            file: File,
            plaintext: ByteArray,
        ) {
            file.parentFile?.mkdirs()
            file.writeBytes(plaintext.reversedArray())
        }
    }

    private object FailingReadTestFileCipher : FileCipher {
        override fun readBytes(file: File): ByteArray = error("cannot decrypt ${file.name}")

        override fun writeBytesAtomic(
            file: File,
            plaintext: ByteArray,
        ) {
            file.parentFile?.mkdirs()
            file.writeBytes(plaintext)
        }
    }

    private class FailingFirstReadTestFileCipher(
        private val failingFile: File,
    ) : FileCipher {
        override fun readBytes(file: File): ByteArray {
            if (file == failingFile) {
                error("cannot decrypt ${file.name}")
            }
            return file.readBytes()
        }

        override fun writeBytesAtomic(
            file: File,
            plaintext: ByteArray,
        ) {
            file.parentFile?.mkdirs()
            file.writeBytes(plaintext)
        }
    }
}
