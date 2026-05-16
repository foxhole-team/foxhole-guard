package com.foxhole.beta.vpn

import com.foxhole.beta.core.diagnostics.DiagnosticEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelValidationEvidenceClassifierTest {
    @Test
    fun `accepts successful tunnel activity without fatal runtime entries`() {
        val evidence =
            TunnelValidationEvidenceClassifier.classify(
                entries =
                    listOf(
                        DiagnosticEntry(
                            timestamp = 1_000L,
                            tag = "libbox",
                            message = "INFO outbound/vless[foxhole-vpn-direct]: outbound connection to [ip]:443",
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
    fun `rejects fatal libbox authentication failures`() {
        val evidence =
            TunnelValidationEvidenceClassifier.classify(
                entries =
                    listOf(
                        DiagnosticEntry(
                            timestamp = 1_000L,
                            tag = "libbox",
                            message = "ERROR outbound/hysteria2[foxhole-vpn-direct]: authentication failed, status code: 404",
                        ),
                        DiagnosticEntry(
                            timestamp = 1_100L,
                            tag = "libbox",
                            message = "INFO inbound/tun[tun-in]: inbound connection to [ip]:5228",
                        ),
                    ),
                sinceMs = 900L,
            )

        assertTrue(evidence.hasSuccessfulTunnelActivity)
        assertFalse(evidence.hasOutboundTunnelActivity)
        assertEquals(
            "ERROR outbound/hysteria2[foxhole-vpn-direct]: authentication failed, status code: 404",
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
                            tag = "libbox",
                            message = "INFO outbound/trojan[foxhole-vpn-direct]: outbound connection to [ip]:443",
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
    fun `inbound activity does not count as outbound platform validation evidence`() {
        val evidence =
            TunnelValidationEvidenceClassifier.classify(
                entries =
                    listOf(
                        DiagnosticEntry(
                            timestamp = 1_000L,
                            tag = "libbox",
                            message = "INFO inbound/tun[tun-in]: inbound connection to [ip]:443",
                        ),
                    ),
                sinceMs = 900L,
            )

        assertTrue(evidence.hasSuccessfulTunnelActivity)
        assertFalse(evidence.hasOutboundTunnelActivity)
        assertEquals(null, evidence.fatalRuntimeMessage)
    }

    @Test
    fun `wireguard handshake response counts as outbound platform validation evidence`() {
        val evidence =
            TunnelValidationEvidenceClassifier.classify(
                entries =
                    listOf(
                        DiagnosticEntry(
                            timestamp = 1_000L,
                            tag = "libbox",
                            message = "DEBUG endpoint/wireguard[wireguard-direct]: peer(abc) - received handshake response",
                        ),
                    ),
                sinceMs = 900L,
            )

        assertTrue(evidence.hasSuccessfulTunnelActivity)
        assertTrue(evidence.hasOutboundTunnelActivity)
        assertEquals(null, evidence.fatalRuntimeMessage)
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
}
