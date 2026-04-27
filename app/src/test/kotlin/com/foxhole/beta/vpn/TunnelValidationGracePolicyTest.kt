package com.foxhole.beta.vpn

import com.foxhole.beta.core.model.ProtocolHint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun `all protocols use the bounded default validation grace budget`() {
        val vlessPolicy =
            selectTunnelValidationGracePolicy(
                protocolHint = ProtocolHint.VLESS,
                evidence = TunnelValidationEvidence(hasSuccessfulTunnelActivity = true),
            )
        val trojanPolicy =
            selectTunnelValidationGracePolicy(
                protocolHint = ProtocolHint.TROJAN,
                evidence = TunnelValidationEvidence(hasSuccessfulTunnelActivity = true),
            )
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
        val wireGuardPolicy =
            selectTunnelValidationGracePolicy(
                protocolHint = ProtocolHint.WIREGUARD,
                evidence = TunnelValidationEvidence(hasSuccessfulTunnelActivity = true),
            )
        val vmessPolicy =
            selectTunnelValidationGracePolicy(
                protocolHint = ProtocolHint.VMESS,
                evidence = TunnelValidationEvidence(hasSuccessfulTunnelActivity = true),
            )

        assertEquals(CONNECTIVITY_PROBE_GRACE_MAX_TIMEOUT_MS, vlessPolicy?.totalTimeoutMs)
        assertEquals(CONNECTIVITY_PROBE_GRACE_MAX_TIMEOUT_MS, trojanPolicy?.totalTimeoutMs)
        assertEquals(CONNECTIVITY_PROBE_GRACE_MAX_TIMEOUT_MS, hysteria2Policy?.totalTimeoutMs)
        assertEquals(CONNECTIVITY_PROBE_GRACE_MAX_TIMEOUT_MS, shadowsocksPolicy?.totalTimeoutMs)
        assertEquals(CONNECTIVITY_PROBE_GRACE_MAX_TIMEOUT_MS, wireGuardPolicy?.totalTimeoutMs)
        assertEquals(CONNECTIVITY_PROBE_GRACE_MAX_TIMEOUT_MS, vmessPolicy?.totalTimeoutMs)
        assertEquals(2, hysteria2Policy?.attempts)
        assertEquals(2, vlessPolicy?.attempts)
        assertEquals(2, wireGuardPolicy?.attempts)
    }

    @Test
    fun `wireguard keeps the default outer validation grace budget`() {
        assertEquals(CONNECTIVITY_PROBE_GRACE_MAX_TIMEOUT_MS, maxTunnelValidationGraceTimeoutMs(ProtocolHint.WIREGUARD))
    }

    @Test
    fun `unknown protocols use the same bounded default grace retry`() {
        val policy =
            selectTunnelValidationGracePolicy(
                protocolHint = ProtocolHint.UNKNOWN,
                evidence = TunnelValidationEvidence(hasSuccessfulTunnelActivity = true),
            )

        assertEquals(CONNECTIVITY_PROBE_GRACE_MAX_TIMEOUT_MS, policy?.totalTimeoutMs)
        assertEquals(2, policy?.attempts)
    }
}
