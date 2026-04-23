package com.foxhole.beta.core.diagnostics

import com.foxhole.beta.core.model.DiagnosticsRetention
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsSessionStoreTest {
    @Test
    fun `append writes raw entries and another store loads them`() {
        val directory = Files.createTempDirectory("foxhole-diagnostics-journal").toFile()
        val firstStore =
            DiagnosticsSessionStore(
                journalDir = directory,
                sessionIdProvider = { "first" },
            )
        firstStore.append(
            DiagnosticEntry(timestamp = 1_000L, tag = "network", message = "remote=203.0.113.10 url=https://example.test/sub"),
            DiagnosticsRetention.HOURS_24,
        )

        val secondStore =
            DiagnosticsSessionStore(
                journalDir = directory,
                sessionIdProvider = { "second" },
            )
        val entries = secondStore.loadRecentEntries(now = 2_000L, retention = DiagnosticsRetention.HOURS_24)

        assertEquals(1, entries.size)
        assertEquals("remote=203.0.113.10 url=https://example.test/sub", entries.single().message)
        assertTrue(directory.listFiles().orEmpty().single().readText().contains("203.0.113.10"))
    }

    @Test
    fun `retention removes expired session files and keeps recent entries`() {
        val directory = Files.createTempDirectory("foxhole-diagnostics-retention").toFile()
        val store =
            DiagnosticsSessionStore(
                journalDir = directory,
                sessionIdProvider = { "retention" },
            )
        store.append(DiagnosticEntry(timestamp = 1_000L, tag = "old", message = "expired"), DiagnosticsRetention.HOURS_6)
        directory.listFiles().orEmpty().forEach { file -> file.setLastModified(1_000L) }

        val later =
            DiagnosticsSessionStore(
                journalDir = directory,
                sessionIdProvider = { "later" },
            )
        later.append(DiagnosticEntry(timestamp = 8L * 60L * 60L * 1000L, tag = "new", message = "kept"), DiagnosticsRetention.HOURS_6)
        val entries = later.loadRecentEntries(now = 8L * 60L * 60L * 1000L, retention = DiagnosticsRetention.HOURS_6)

        assertEquals(listOf("kept"), entries.map(DiagnosticEntry::message))
    }
}
