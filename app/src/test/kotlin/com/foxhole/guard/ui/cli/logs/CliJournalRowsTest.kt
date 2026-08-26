package com.foxhole.guard.ui.cli.logs

import com.foxhole.core.model.DiagnosticEntry
import com.foxhole.core.model.DiagnosticSeverity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CliJournalRowsTest {
    @Test
    fun `application row separates type from structured description`() {
        val row = diagnosticJournalRow(
            DiagnosticEntry(
                timestamp = 123L,
                tag = "runtime",
                message = "connection failed · endpoint=SAFE_MARKER_ALPHA\ntoken=SAFE_MARKER_BETA",
                severity = DiagnosticSeverity.FAILURE,
            ),
        )

        assertEquals(123L, row.timestamp)
        assertEquals("runtime", row.eventType)
        assertEquals(
            listOf("connection failed", "endpoint=SAFE_MARKER_ALPHA", "token=SAFE_MARKER_BETA"),
            row.description,
        )
        assertEquals(CliJournalRowTone.ERROR, row.tone)
    }

    @Test
    fun `network row preserves raw safe marker fields`() {
        val row = networkJournalRow(
            DiagnosticEntry(
                timestamp = 456L,
                tag = "activity",
                message = "App connection: endpoint=SAFE_MARKER_GAMMA • " +
                    "token=SAFE_MARKER_DELTA • protocol=udp • error=SAFE_MARKER_ERROR",
            ),
        )

        assertEquals("App connection", row.eventType)
        assertTrue(row.description.contains("endpoint=SAFE_MARKER_GAMMA"))
        assertTrue(row.description.contains("token=SAFE_MARKER_DELTA"))
        assertTrue(row.description.contains("protocol=udp"))
        assertTrue(row.description.contains("error=SAFE_MARKER_ERROR"))
        assertEquals(CliJournalRowTone.INFO, row.tone)
    }

    @Test
    fun `network flag drops on the first line without moving its payload`() {
        val source = File("src/main/kotlin/com/foxhole/guard/ui/cli/logs/CliLogsScreen.kt").readText()
        val flag = File("src/main/kotlin/com/foxhole/guard/ui/cli/components/CliFlagIcon.kt").readText()
        val row = source
            .substringAfter("private fun CliJournalTableRow(")
            .substringBefore("private fun CliJournalDescriptionLine(")
        val description = source
            .substringAfter("private fun CliJournalDescriptionLine(")
            .substringBefore("private fun journalToneIcon(")

        assertTrue(flag.contains("visualOffsetY: Dp = CLI_ICON_OPTICAL_OFFSET"))
        assertTrue(flag.contains("y = visualOffsetY"))
        assertTrue(row.contains("visualOffsetY = CLI_FIRST_LINE_GLYPH_DROP"))
        assertFalse(row.contains("JOURNAL_FLAG_DROP"))
        assertFalse(row.contains("JOURNAL_FLAGGED_TEXT_LIFT"))
        assertFalse(description.contains("modifier: Modifier = Modifier"))
        assertFalse(description.contains("Modifier.offset"))
        assertFalse(row.contains("LocalCliPixelArtEnabled"))
    }
}
