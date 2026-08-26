package com.foxhole.guard.ui.cli.logs

import com.foxhole.core.model.DiagnosticEntry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CliJournalExportTest {
    @Test
    fun `logs screen exports preserve raw safe marker fields for every journal`() {
        listOf("application journal", "network journal", "security journal").forEach { title ->
            val export = formatRawJournalExport(
                title = title,
                entries = listOf(
                    DiagnosticEntry(
                        timestamp = 1_700_000_000_000L,
                        tag = "activity",
                        message = "endpoint=SAFE_MARKER_ALPHA • token=SAFE_MARKER_BETA",
                    ),
                ),
            )

            assertTrue(export.contains("endpoint=SAFE_MARKER_ALPHA"))
            assertTrue(export.contains("token=SAFE_MARKER_BETA"))
            assertFalse(export.contains("[redacted]", ignoreCase = true))
        }
    }

    @Test
    fun `raw journal export flattens line breaks without changing field values`() {
        val export =
            formatRawJournalExport(
                title = "network journal",
                entries = listOf(
                    DiagnosticEntry(
                        timestamp = 1_700_000_000_000L,
                        tag = "activity",
                        message = "endpoint=SAFE_MARKER_GAMMA\ntoken=SAFE_MARKER_DELTA",
                    ),
                ),
            )

        assertTrue(export.contains("endpoint=SAFE_MARKER_GAMMA token=SAFE_MARKER_DELTA"))
        assertFalse(export.contains("[redacted]", ignoreCase = true))
    }
}
