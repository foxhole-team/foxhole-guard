package com.foxhole.beta.vpn

import com.foxhole.beta.core.model.AutoConnectReasonCode
import com.foxhole.beta.core.model.ProtocolHint
import com.foxhole.beta.core.model.VpnSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeValidationSessionGuardTest {
    @Test
    fun `accepts exact active runtime validation session`() {
        val session = session()

        assertTrue(session.matchesRuntimeValidationSession(session))
    }

    @Test
    fun `rejects missing or stale runtime validation sessions`() {
        val session = session()

        assertFalse((null as VpnSession?).matchesRuntimeValidationSession(session))
        assertFalse(session.copy(profileId = session.profileId + 1).matchesRuntimeValidationSession(session))
        assertFalse(session.copy(correlationId = "new-session").matchesRuntimeValidationSession(session))
        assertFalse(session.copy(protocolOptionId = "trojan").matchesRuntimeValidationSession(session))
    }

    @Test
    fun `ordinary sessions use the bounded default validation probe plan`() {
        val plan = runtimeValidationProbePlan(session())

        assertEquals(FoxholeVpnService.CONNECTIVITY_PROBE_ATTEMPTS, plan.attempts)
        assertEquals(FoxholeVpnService.CONNECTIVITY_PROBE_CALL_TIMEOUT_MS, plan.callTimeoutMs)
        assertEquals(
            FoxholeVpnService.CONNECTIVITY_PROBE_TOTAL_TIMEOUT_MS +
                maxTunnelValidationGraceTimeoutMs(ProtocolHint.VLESS),
            plan.totalTimeoutMs,
        )
    }

    @Test
    fun `tor over vpn sessions get an extended validation probe plan`() {
        val torConfigJson = """{"outbounds":[{"tag":"proxy","type":"selector"},{"tag":"tor-over-vpn","type":"tor"}]}"""
        val plan =
            runtimeValidationProbePlan(
                session(configJson = torConfigJson),
            )
        val ordinaryTotalTimeoutMs =
            FoxholeVpnService.CONNECTIVITY_PROBE_TOTAL_TIMEOUT_MS +
                maxTunnelValidationGraceTimeoutMs(ProtocolHint.VLESS)

        assertTrue(plan.attempts > FoxholeVpnService.CONNECTIVITY_PROBE_ATTEMPTS)
        assertTrue(plan.callTimeoutMs > FoxholeVpnService.CONNECTIVITY_PROBE_CALL_TIMEOUT_MS)
        assertTrue(plan.totalTimeoutMs > ordinaryTotalTimeoutMs)
    }

    @Test
    fun `tor only sessions get an extended validation probe plan`() {
        val torConfigJson = """{"outbounds":[{"tag":"proxy","type":"tor"}]}"""
        val plan =
            runtimeValidationProbePlan(
                session(
                    profileId = FoxholeVpnService.TOR_ONLY_PROFILE_ID,
                    profileName = "TOR",
                    protocolHint = ProtocolHint.SING_BOX,
                    protocolOptionId = null,
                    configJson = torConfigJson,
                ),
            )
        val ordinaryTotalTimeoutMs =
            FoxholeVpnService.CONNECTIVITY_PROBE_TOTAL_TIMEOUT_MS +
                maxTunnelValidationGraceTimeoutMs(ProtocolHint.SING_BOX)

        assertTrue(plan.attempts > FoxholeVpnService.CONNECTIVITY_PROBE_ATTEMPTS)
        assertTrue(plan.callTimeoutMs > FoxholeVpnService.CONNECTIVITY_PROBE_CALL_TIMEOUT_MS)
        assertTrue(plan.totalTimeoutMs > ordinaryTotalTimeoutMs)
    }

    @Test
    fun `wrapped tunnel timeout still maps to validation timeout`() {
        val timeout =
            TunnelConnectivityProbeTimeoutException(
                attemptsDone = 2,
                timeoutMs = 1_000L,
                cause = IllegalStateException("last endpoint failed"),
            )
        val wrapped = IllegalStateException("DNS probe failed", timeout)

        assertEquals(
            AutoConnectReasonCode.VALIDATION_TIMEOUT,
            runtimeValidationFailureReasonCode(
                error = wrapped,
                dnsProbeFailedMessage = "DNS probe failed",
            ),
        )
    }

    @Test
    fun `generic user-facing dns failure maps to dns failure without timeout cause`() {
        assertEquals(
            AutoConnectReasonCode.DNS_FAILURE,
            runtimeValidationFailureReasonCode(
                error = IllegalStateException("DNS probe failed"),
                dnsProbeFailedMessage = "DNS probe failed",
            ),
        )
    }

    private fun session(
        profileId: Long = 42L,
        profileName: String = "Smart",
        protocolHint: ProtocolHint = ProtocolHint.VLESS,
        protocolOptionId: String? = "vless",
        configJson: String = "{}",
    ): VpnSession =
        VpnSession(
            profileId = profileId,
            profileName = profileName,
            protocolHint = protocolHint,
            protocolOptionId = protocolOptionId,
            configJson = configJson,
            correlationId = "session-1",
        )

}
