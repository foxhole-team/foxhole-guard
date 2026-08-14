package com.foxhole.core.runtime

import com.foxhole.core.model.AutoConnectReasonCode
import com.foxhole.core.model.IpInfo
import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.RuntimeFailureCode
import com.foxhole.core.model.RuntimeFailureException
import com.foxhole.core.model.VpnSession
import com.foxhole.guard.runtime.FoxholeVpnService
import com.foxhole.guard.runtime.RuntimeProxyEgressValidationResult
import com.foxhole.guard.runtime.acceptsRuntimeProxyValidationResult
import com.foxhole.guard.runtime.matchesRuntimeValidationSession
import com.foxhole.guard.runtime.runtimeValidationFailureReasonCode
import com.foxhole.guard.runtime.runtimeValidationProbePlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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
                maxTunnelValidationGraceTimeoutMs(),
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
                maxTunnelValidationGraceTimeoutMs()

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
                    protocolHint = ProtocolHint.CUSTOM_CONFIG,
                    protocolOptionId = null,
                    configJson = torConfigJson,
                ),
            )
        val ordinaryTotalTimeoutMs =
            FoxholeVpnService.CONNECTIVITY_PROBE_TOTAL_TIMEOUT_MS +
                maxTunnelValidationGraceTimeoutMs()

        assertTrue(plan.attempts > FoxholeVpnService.CONNECTIVITY_PROBE_ATTEMPTS)
        assertTrue(plan.callTimeoutMs > FoxholeVpnService.CONNECTIVITY_PROBE_CALL_TIMEOUT_MS)
        assertTrue(plan.totalTimeoutMs > ordinaryTotalTimeoutMs)
    }

    @Test
    fun `tor only validation requires proved public egress through the runtime route`() {
        val source = validationRunSource().readText()
        val attempt =
            source
                .substringAfter("private suspend fun FoxholeVpnService.runTunnelValidationAttempt(")
                .substringBefore("private suspend fun FoxholeVpnService.awaitRuntimeValidationNetworkContext(")

        assertTrue(attempt.contains("tryRuntimeProxyTunnelValidation"))
        assertTrue(attempt.contains("check(runtimeProxyAccepted)"))
        assertTrue(attempt.contains("tor-only tunnel did not prove public egress through tor"))
        assertFalse(source.contains("requireTorBootstrapForValidation"))
        assertFalse(source.contains("tor-only tunnel accepted"))
    }

    @Test
    fun `tor-only accepts only an ip refresh backed runtime proxy result`() {
        val torOnly = session().copy(profileId = com.foxhole.core.model.TOR_ONLY_PROFILE_ID)
        val endpointOnly =
            RuntimeProxyEgressValidationResult(
                kind = TunnelValidationProbeKind.TUNNEL_RUNTIME_PROXY_VALIDATION_ENDPOINT,
            )
        val missingIp =
            RuntimeProxyEgressValidationResult(
                kind = TunnelValidationProbeKind.TUNNEL_RUNTIME_PROXY_IP_REFRESH,
            )
        val verifiedIp =
            missingIp.copy(
                ipInfo =
                IpInfo(
                    ip = "198.51.100.7",
                    ipv4 = "198.51.100.7",
                    countryCode = null,
                    countryName = null,
                    city = null,
                    isp = null,
                    fetchedAt = 1L,
                ),
            )

        assertFalse(acceptsRuntimeProxyValidationResult(torOnly, endpointOnly))
        assertFalse(acceptsRuntimeProxyValidationResult(torOnly, missingIp))
        assertTrue(acceptsRuntimeProxyValidationResult(torOnly, verifiedIp))
        assertTrue(acceptsRuntimeProxyValidationResult(session(), endpointOnly))
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

    @Test
    fun `missing VPN network maps to connect error even through probe timeout`() {
        val missingNetwork =
            RuntimeFailureException(RuntimeFailureCode.VPN_NETWORK_MISSING, "vpn network unavailable")
        val timeout =
            TunnelConnectivityProbeTimeoutException(
                attemptsDone = 4,
                timeoutMs = 24_000L,
                cause = missingNetwork,
            )

        assertEquals(
            AutoConnectReasonCode.CONNECT_ERROR,
            runtimeValidationFailureReasonCode(
                error = IllegalStateException("Connectivity check failed", timeout),
                dnsProbeFailedMessage = "Connectivity check failed",
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

    private fun validationRunSource(): File =
        listOf(
            File("src/main/kotlin/com/foxhole/guard/runtime/RuntimeValidationRun.kt"),
            File("app/src/main/kotlin/com/foxhole/guard/runtime/RuntimeValidationRun.kt"),
            File("../app/src/main/kotlin/com/foxhole/guard/runtime/RuntimeValidationRun.kt"),
        ).first(File::isFile)
}
