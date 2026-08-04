package com.foxhole.guard.core.diagnostics
import com.foxhole.core.model.DiagnosticEntry
import com.foxhole.core.model.RetentionPolicy
import com.foxhole.core.model.RetentionPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsLoggerTest {
    @Test
    fun `live diagnostic message sanitizer removes raw profile uris`() {
        val sanitized =
            liveDiagnosticMessage(
                message = "import failed raw=vless://11111111-1111-1111-1111-111111111111@edge.example.com:443?security=tls#main",
            )

        assertTrue(sanitized.contains("[profile-uri-redacted]"))
        assertFalse(sanitized.contains("vless://"))
        assertFalse(sanitized.contains("edge.example.com"))
        assertFalse(sanitized.contains("11111111-1111-1111-1111-111111111111"))
    }

    @Test
    fun `network journal opt in never makes general diagnostics raw`() {
        val message =
            "import failed raw=vless://11111111-1111-1111-1111-111111111111@edge.example.com:443?security=tls#main"

        val liveMessage = liveDiagnosticMessage(message = message)

        assertTrue(liveMessage.contains("[profile-uri-redacted]"))
        assertFalse(liveMessage.contains("edge.example.com"))
        assertFalse(liveMessage.contains("11111111-1111-1111-1111-111111111111"))
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
}
