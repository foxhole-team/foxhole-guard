package com.foxhole.guard.ui.cli.logs

import com.foxhole.core.model.DiagnosticEntry
import com.foxhole.core.model.DiagnosticSeverity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CliJournalRowsTest {
    @Test
    fun `application row separates type from structured description`() {
        val row = diagnosticJournalRow(
            DiagnosticEntry(
                timestamp = 123L,
                tag = "runtime",
                message = "connection failed · profile=vless\nreason=dns",
                severity = DiagnosticSeverity.FAILURE,
            ),
        )

        assertEquals(123L, row.timestamp)
        assertEquals("runtime", row.eventType)
        assertEquals(listOf("connection failed", "profile=vless", "reason=dns"), row.description)
        assertEquals(CliJournalRowTone.ERROR, row.tone)
    }

    @Test
    fun `network row preserves raw endpoint and port in separate fields`() {
        val row = networkJournalRow(
            DiagnosticEntry(
                timestamp = 456L,
                tag = "activity",
                message =
                "App connection: packages=org.example • protocol=udp • " +
                    "endpoint=203.0.113.7 • port=53 • rx=10B • tx=20B",
            ),
        )

        assertEquals("App connection", row.eventType)
        assertTrue(row.description.contains("endpoint=203.0.113.7"))
        assertTrue(row.description.contains("port=53"))
        assertTrue(row.description.contains("protocol=udp"))
        assertEquals(CliJournalRowTone.INFO, row.tone)
    }
}
