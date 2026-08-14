package com.foxhole.guard.ui.cli.logs

import com.foxhole.core.model.DiagnosticEntry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CliJournalExportTest {
    @Test
    fun `journal export always redacts network identity and profile secrets`() {
        val export =
            formatSanitizedJournalExport(
                title = "security journal",
                entries = listOf(
                    DiagnosticEntry(
                        timestamp = 1_700_000_000_000L,
                        tag = "activity",
                        message = "packages=com.private.app, com.second.app • " +
                            "endpoint=203.0.113.7 • country=United States (US) • New York • " +
                            "rx=1 KiB • profile=family profile • session=session-secret • " +
                            "url=https://private.example/path raw=vless://secret@edge.example:443",
                    ),
                ),
            )

        assertTrue(export.contains("[redacted]") || export.contains("[profile-uri-redacted]"))
        assertFalse(export.contains("com.private.app"))
        assertFalse(export.contains("com.second.app"))
        assertFalse(export.contains("203.0.113.7"))
        assertFalse(export.contains("United States"))
        assertFalse(export.contains("New York"))
        assertFalse(export.contains("family profile"))
        assertFalse(export.contains("session-secret"))
        assertFalse(export.contains("private.example"))
        assertFalse(export.contains("vless://"))
        assertFalse(export.contains("secret"))
    }

    @Test
    fun `network journal export preserves actual endpoint and port`() {
        val export =
            formatNetworkJournalExport(
                title = "network journal",
                entries = listOf(
                    DiagnosticEntry(
                        timestamp = 1_700_000_000_000L,
                        tag = "activity",
                        message = "App connection: packages=com.example.browser • " +
                            "protocol=tcp • endpoint=203.0.113.7 • port=443 • total=1 KiB",
                    ),
                ),
            )

        assertTrue(export.contains("packages=com.example.browser"))
        assertTrue(export.contains("endpoint=203.0.113.7"))
        assertTrue(export.contains("port=443"))
        assertFalse(export.contains("[ip]"))
        assertFalse(export.contains("[host]"))
        assertFalse(export.contains("[redacted]"))
    }
}
