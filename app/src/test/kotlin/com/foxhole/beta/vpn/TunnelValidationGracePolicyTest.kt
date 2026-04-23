package com.foxhole.beta.vpn

import com.foxhole.beta.core.model.ProtocolHint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelValidationGracePolicyTest {
    @Test
    fun `does not grant validation grace without successful tunnel activity`() {
        assertNull(
            selectTunnelValidationGracePolicy(
                protocolHint = ProtocolHint.HYSTERIA2,
                evidence = TunnelValidationEvidence(hasSuccessfulTunnelActivity = false),
            ),
        )
    }

    @Test
    fun `does not grant validation grace when runtime already exposed a fatal error`() {
        assertNull(
            selectTunnelValidationGracePolicy(
                protocolHint = ProtocolHint.SHADOWSOCKS,
                evidence =
                    TunnelValidationEvidence(
                        hasSuccessfulTunnelActivity = true,
                        fatalRuntimeMessage = "authentication failed",
                    ),
            ),
        )
    }

    @Test
    fun `slow protocols receive the longer validation grace budget`() {
        val hysteria2Policy =
            selectTunnelValidationGracePolicy(
                protocolHint = ProtocolHint.HYSTERIA2,
                evidence = TunnelValidationEvidence(hasSuccessfulTunnelActivity = true),
            )
        val shadowsocksPolicy =
            selectTunnelValidationGracePolicy(
                protocolHint = ProtocolHint.SHADOWSOCKS,
                evidence = TunnelValidationEvidence(hasSuccessfulTunnelActivity = true),
            )

        assertEquals(CONNECTIVITY_PROBE_GRACE_MAX_TIMEOUT_MS, hysteria2Policy?.totalTimeoutMs)
        assertEquals(CONNECTIVITY_PROBE_GRACE_MAX_TIMEOUT_MS, shadowsocksPolicy?.totalTimeoutMs)
        assertTrue((hysteria2Policy?.attempts ?: 0) > 2)
    }

    @Test
    fun `other protocols still get a bounded shorter grace retry`() {
        val policy =
            selectTunnelValidationGracePolicy(
                protocolHint = ProtocolHint.TROJAN,
                evidence = TunnelValidationEvidence(hasSuccessfulTunnelActivity = true),
            )

        assertEquals(8_000L, policy?.totalTimeoutMs)
        assertEquals(2, policy?.attempts)
    }
}
