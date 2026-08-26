package com.foxhole.guard.core.diagnostics

import com.foxhole.core.model.DiagnosticEntry
import com.foxhole.core.model.DiagnosticSeverity
import com.foxhole.core.model.RetentionPolicy
import com.foxhole.core.model.RetentionPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsLoggerTest {
    @Test
    fun `live diagnostic messages preserve safe marker values`() {
        val message = "endpoint=SAFE_MARKER_ALPHA"

        assertEquals(message, rawDiagnosticMessage(message))
        assertFalse(rawDiagnosticMessage(message).contains("[redacted]", ignoreCase = true))
    }

    @Test
    fun `network journal setting does not change general diagnostic content`() {
        val message = "token=SAFE_MARKER_BETA"

        val liveMessage = rawDiagnosticMessage(message = message)

        assertEquals(message, liveMessage)
        assertFalse(liveMessage.contains("[redacted]", ignoreCase = true))
    }

    @Test
    fun `live diagnostic entries are capped below long retention limits`() {
        val entries =
            (1..1_500).map { index ->
                DiagnosticEntry(
                    timestamp = index.toLong(),
                    tag = "activity",
                    message = "entry=$index",
                )
            }

        val retained =
            trimLiveDiagnosticEntries(
                entries = entries,
                now = 1_500L,
                retention = RetentionPolicy(RetentionPreset.MONTH),
            )

        assertEquals(MAX_LIVE_DIAGNOSTIC_ENTRIES, retained.size)
        assertEquals("entry=1001", retained.first().message)
        assertEquals("entry=1500", retained.last().message)
    }

    @Test
    fun `merge entries keeps distinct entries and drops only exact duplicates`() {
        val first =
            listOf(
                DiagnosticEntry(1L, "tunnel", "start requested"),
                DiagnosticEntry(2L, "tunnel", "start confirmed"),
            )
        val second =
            listOf(
                DiagnosticEntry(2L, "tunnel", "start confirmed"),
                DiagnosticEntry(3L, "dns", "filter loaded"),
                DiagnosticEntry(3L, "dns", "filter reloaded"),
            )

        val merged = mergeEntries(first, second)

        assertEquals(
            listOf(
                DiagnosticEntry(1L, "tunnel", "start requested"),
                DiagnosticEntry(2L, "tunnel", "start confirmed"),
                DiagnosticEntry(3L, "dns", "filter loaded"),
                DiagnosticEntry(3L, "dns", "filter reloaded"),
            ),
            merged,
        )
    }

    @Test
    fun `input queue is bounded and drops oldest with an exact counter`() {
        val queue = BoundedDiagnosticRecordQueue(capacity = 2)

        queue.offer(DiagnosticEntrySeed(1L, "test", "first"))
        queue.offer(DiagnosticEntrySeed(2L, "test", "second"))
        queue.offer(DiagnosticEntrySeed(3L, "test", "third", DiagnosticSeverity.FAILURE))

        assertEquals(2, queue.size())
        assertEquals(1L, queue.takeDropCount())
        assertEquals("second", queue.poll()?.message)
        assertEquals("third", queue.poll()?.message)
        assertTrue(queue.isEmpty())
    }

    @Test
    fun `queued seed keeps full diagnostic content`() {
        val raw = "endpoint=SAFE_MARKER_GAMMA"
        val queue = BoundedDiagnosticRecordQueue(capacity = 1)

        queue.offer(DiagnosticEntrySeed(1L, "profile", rawDiagnosticMessage(raw)))

        val queued = requireNotNull(queue.poll())
        assertEquals(raw, queued.message)
        assertFalse(queued.message.contains("[redacted]", ignoreCase = true))
    }

    @Test
    fun `process termination tombstone keeps full details for in-app diagnostics`() {
        val message =
            processTerminationTombstoneMessage(
                headline = "fail-closed process termination",
                details =
                listOf(
                    "reason=SAFE_MARKER_DELTA",
                    "endpoint=SAFE_MARKER_EPSILON",
                ),
            )

        assertTrue(message.contains("reason=SAFE_MARKER_DELTA"))
        assertTrue(message.contains("endpoint=SAFE_MARKER_EPSILON"))
        assertFalse(message.contains("[redacted]", ignoreCase = true))
    }
}
