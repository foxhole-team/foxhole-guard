package com.foxhole.core.runtime
import com.foxhole.core.model.DiagnosticEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelValidationEvidenceClassifierTest {
    @Test
    fun `accepts FoxCore activity without fatal runtime entries`() {
        val evidence =
            TunnelValidationEvidenceClassifier.classify(
                entries =
                listOf(
                    DiagnosticEntry(
                        timestamp = 1_000L,
                        tag = "activity",
                        message = "App connection: app=[redacted] • protocol=TCP • remote=[redacted]",
                    ),
                    DiagnosticEntry(
                        timestamp = 1_100L,
                        tag = "dns",
                        message = "validation endpoint failed: https://example.com",
                    ),
                ),
                sinceMs = 900L,
            )

        assertTrue(evidence.hasSuccessfulTunnelActivity)
        assertTrue(evidence.hasOutboundTunnelActivity)
        assertEquals(null, evidence.fatalRuntimeMessage)
    }

    @Test
    fun `rejects fatal FoxCore startup failures`() {
        val evidence =
            TunnelValidationEvidenceClassifier.classify(
                entries =
                listOf(
                    DiagnosticEntry(
                        timestamp = 1_000L,
                        tag = "foxcore",
                        message = "native start rejected",
                    ),
                    DiagnosticEntry(
                        timestamp = 1_100L,
                        tag = "activity",
                        message = "App connection: app=[redacted] • protocol=TCP • remote=[redacted]",
                    ),
                ),
                sinceMs = 900L,
            )

        assertTrue(evidence.hasSuccessfulTunnelActivity)
        assertTrue(evidence.hasOutboundTunnelActivity)
        assertEquals(
            "native start rejected",
            evidence.fatalRuntimeMessage,
        )
    }

    @Test
    fun `ignores entries before validation window`() {
        val evidence =
            TunnelValidationEvidenceClassifier.classify(
                entries =
                listOf(
                    DiagnosticEntry(
                        timestamp = 500L,
                        tag = "activity",
                        message = "App connection: app=[redacted] • protocol=TCP • remote=[redacted]",
                    ),
                    DiagnosticEntry(
                        timestamp = 1_500L,
                        tag = "dns",
                        message = "validation endpoint failed: https://example.com",
                    ),
                ),
                sinceMs = 1_000L,
            )

        assertFalse(evidence.hasSuccessfulTunnelActivity)
        assertFalse(evidence.hasOutboundTunnelActivity)
        assertEquals(null, evidence.fatalRuntimeMessage)
    }

    @Test
    fun `treats runtime start failure as fatal`() {
        val evidence =
            TunnelValidationEvidenceClassifier.classify(
                entries =
                listOf(
                    DiagnosticEntry(
                        timestamp = 1_000L,
                        tag = "runtime",
                        message = "start failed: decode config: outbounds[0].server: json: unknown field \"server\"",
                    ),
                ),
                sinceMs = 900L,
            )

        assertFalse(evidence.hasSuccessfulTunnelActivity)
        assertFalse(evidence.hasOutboundTunnelActivity)
        assertEquals(
            "start failed: decode config: outbounds[0].server: json: unknown field \"server\"",
            evidence.fatalRuntimeMessage,
        )
    }

    @Test
    fun `sanitized activity log counts as tunnel activity evidence`() {
        val evidence =
            TunnelValidationEvidenceClassifier.classify(
                entries =
                listOf(
                    DiagnosticEntry(
                        timestamp = 1_000L,
                        tag = "activity",
                        message = "App connection: app=[redacted] • protocol=TCP • local=[redacted] • remote=[redacted]",
                    ),
                ),
                sinceMs = 900L,
            )

        assertTrue(evidence.hasSuccessfulTunnelActivity)
        assertTrue(evidence.hasOutboundTunnelActivity)
        assertEquals(null, evidence.fatalRuntimeMessage)
    }

    @Test
    fun `vpn-bound validation success counts as tunnel activity evidence`() {
        val evidence =
            TunnelValidationEvidenceClassifier.classify(
                entries =
                listOf(
                    DiagnosticEntry(
                        timestamp = 1_000L,
                        tag = "dns",
                        message = "vpn network passed in-process ip refresh",
                    ),
                ),
                sinceMs = 900L,
            )

        assertTrue(evidence.hasSuccessfulTunnelActivity)
        assertTrue(evidence.hasOutboundTunnelActivity)
        assertEquals(null, evidence.fatalRuntimeMessage)
    }

    @Test
    fun `android vpn validation success does not count as tunnel activity evidence`() {
        val evidence =
            TunnelValidationEvidenceClassifier.classify(
                entries =
                listOf(
                    DiagnosticEntry(
                        timestamp = 1_000L,
                        tag = "dns",
                        message = "android validated vpn network accepted",
                    ),
                ),
                sinceMs = 900L,
            )

        assertFalse(evidence.hasSuccessfulTunnelActivity)
        assertFalse(evidence.hasOutboundTunnelActivity)
        assertEquals(null, evidence.fatalRuntimeMessage)
    }

    @Test
    fun `literal public endpoint probe does not count as tunnel activity evidence`() {
        val evidence =
            TunnelValidationEvidenceClassifier.classify(
                entries =
                listOf(
                    DiagnosticEntry(
                        timestamp = 1_000L,
                        tag = "dns",
                        message = "vpn-bound literal public endpoint passed after ip refresh failed but is not accepted as tunnel validation",
                    ),
                ),
                sinceMs = 900L,
            )

        assertFalse(evidence.hasSuccessfulTunnelActivity)
        assertFalse(evidence.hasOutboundTunnelActivity)
        assertEquals(null, evidence.fatalRuntimeMessage)
    }
}
