package com.foxhole.core.runtime

import com.foxhole.core.model.AppliedTorRoute
import com.foxhole.core.model.AutoConnectReasonCode
import com.foxhole.core.model.IpInfo
import com.foxhole.core.model.PrivacyRouteScope
import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.RuntimeFailureCode
import com.foxhole.core.model.RuntimeFailureException
import com.foxhole.core.model.VpnSession
import com.foxhole.guard.runtime.FoxholeVpnService
import com.foxhole.guard.runtime.RUNTIME_VALIDATION_SCHEDULER_SLACK_MS
import com.foxhole.guard.runtime.RuntimeProxyEgressValidationResult
import com.foxhole.guard.runtime.STRICT_TOR_CONTROL_PROBE_RESERVE_MS
import com.foxhole.guard.runtime.acceptsRuntimeProxyValidationResult
import com.foxhole.guard.runtime.boundedRuntimeValidationCallTimeoutMs
import com.foxhole.guard.runtime.matchesRuntimeValidationSession
import com.foxhole.guard.runtime.remainingRuntimeValidationMs
import com.foxhole.guard.runtime.remainingRuntimeValidationProofMs
import com.foxhole.guard.runtime.requiresVerifiedTorExitForValidation
import com.foxhole.guard.runtime.runtimeValidationDeadlineAt
import com.foxhole.guard.runtime.runtimeValidationFailureReasonCode
import com.foxhole.guard.runtime.runtimeValidationProbePlan
import com.foxhole.guard.runtime.runtimeValidationWatchdogTimeoutMs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        assertEquals(0L, plan.controlProbeReserveMs)
        assertNull(plan.maxRuntimeProxyWarmupAttempts)
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

        assertEquals(1, plan.attempts)
        assertEquals(12_000L, plan.callTimeoutMs)
        assertTrue(plan.totalTimeoutMs > ordinaryTotalTimeoutMs)
        assertEquals(0L, plan.controlProbeReserveMs)
        assertNull(plan.maxRuntimeProxyWarmupAttempts)
    }

    @Test
    fun `tor only sessions get an extended validation probe plan`() {
        val torConfigJson =
            """{"outbounds":[{"tag":"proxy","type":"tor","bridges":["obfs4 192.0.2.8:443 fixture"]}]}"""
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

        assertEquals(1, plan.attempts)
        assertEquals(80_000L, plan.callTimeoutMs)
        assertTrue(plan.totalTimeoutMs > ordinaryTotalTimeoutMs)
        assertEquals(120_000L, plan.totalTimeoutMs)
        assertEquals(STRICT_TOR_CONTROL_PROBE_RESERVE_MS, plan.controlProbeReserveMs)
        assertEquals(1, plan.maxRuntimeProxyWarmupAttempts)

        val strictRouteWithoutConfigShape = runtimeValidationProbePlan(session().copy(torActive = true))
        assertEquals(1, strictRouteWithoutConfigShape.attempts)
        assertEquals(12_000L, strictRouteWithoutConfigShape.callTimeoutMs)
        assertEquals(STRICT_TOR_CONTROL_PROBE_RESERVE_MS, strictRouteWithoutConfigShape.controlProbeReserveMs)
        assertNull(strictRouteWithoutConfigShape.maxRuntimeProxyWarmupAttempts)

        val unbridgedTorOnly =
            runtimeValidationProbePlan(
                session(
                    profileId = FoxholeVpnService.TOR_ONLY_PROFILE_ID,
                    configJson = """{"outbounds":[{"tag":"proxy","type":"tor"}]}""",
                ),
            )
        assertEquals(12_000L, unbridgedTorOnly.callTimeoutMs)
        assertNull(unbridgedTorOnly.maxRuntimeProxyWarmupAttempts)
    }

    @Test
    fun `strict tor proof and control calls share one monotonic deadline`() {
        val startedAtMs = 10_000L
        val totalTimeoutMs = 120_000L
        val deadlineAtMs = runtimeValidationDeadlineAt(startedAtMs, totalTimeoutMs)
        val afterOuterInitialDelayMs = startedAtMs + 2_000L

        assertEquals(118_000L, remainingRuntimeValidationMs(deadlineAtMs, afterOuterInitialDelayMs))
        assertEquals(
            80_000L,
            boundedRuntimeValidationCallTimeoutMs(
                requestedTimeoutMs = 80_000L,
                remainingMs =
                remainingRuntimeValidationProofMs(
                    deadlineAtElapsedRealtimeMs = deadlineAtMs,
                    nowElapsedRealtimeMs = afterOuterInitialDelayMs,
                    controlProbeReserveMs = STRICT_TOR_CONTROL_PROBE_RESERVE_MS,
                ),
            ),
        )
        assertEquals(
            12_000L,
            boundedRuntimeValidationCallTimeoutMs(
                requestedTimeoutMs = 12_000L,
                remainingMs =
                remainingRuntimeValidationProofMs(
                    deadlineAtElapsedRealtimeMs = deadlineAtMs,
                    nowElapsedRealtimeMs = startedAtMs + 103_000L,
                    controlProbeReserveMs = STRICT_TOR_CONTROL_PROBE_RESERVE_MS,
                ),
            ),
        )
        assertEquals(
            500L,
            boundedRuntimeValidationCallTimeoutMs(
                requestedTimeoutMs = 12_000L,
                remainingMs =
                remainingRuntimeValidationProofMs(
                    deadlineAtElapsedRealtimeMs = deadlineAtMs,
                    nowElapsedRealtimeMs = startedAtMs + 114_500L,
                    controlProbeReserveMs = STRICT_TOR_CONTROL_PROBE_RESERVE_MS,
                ),
            ),
        )
        val controlStartedAtMs = startedAtMs + 115_000L
        val controlCallMs =
            boundedRuntimeValidationCallTimeoutMs(
                requestedTimeoutMs = STRICT_TOR_CONTROL_PROBE_RESERVE_MS,
                remainingMs = remainingRuntimeValidationMs(deadlineAtMs, controlStartedAtMs),
            )
        assertEquals(STRICT_TOR_CONTROL_PROBE_RESERVE_MS, controlCallMs)
        assertEquals(totalTimeoutMs, controlStartedAtMs + controlCallMs - startedAtMs)
        assertEquals(
            totalTimeoutMs + RUNTIME_VALIDATION_SCHEDULER_SLACK_MS,
            runtimeValidationWatchdogTimeoutMs(deadlineAtMs, startedAtMs),
        )
    }

    @Test
    fun `deadline arithmetic saturates and never returns a negative call budget`() {
        val saturatedDeadline = runtimeValidationDeadlineAt(Long.MAX_VALUE - 5L, 10L)

        assertEquals(Long.MAX_VALUE, saturatedDeadline)
        assertEquals(0L, remainingRuntimeValidationMs(100L, 100L))
        assertEquals(0L, remainingRuntimeValidationProofMs(100L, 90L, 20L))
        assertEquals(0L, boundedRuntimeValidationCallTimeoutMs(12_000L, -1L))
    }

    @Test
    fun `every applied tor runtime requires proved public egress through tor`() {
        val source = validationRunSource().readText()
        val attempt =
            source
                .substringAfter("private suspend fun FoxholeVpnService.runTunnelValidationAttempt(")
                .substringBefore("private suspend fun FoxholeVpnService.awaitRuntimeValidationNetworkContext(")

        assertTrue(attempt.contains("tryRuntimeProxyTunnelValidation"))
        assertTrue(attempt.contains("check(runtimeProxyAccepted)"))
        assertTrue(attempt.contains("requiresVerifiedTorExitForValidation()"))
        assertTrue(attempt.contains("tor-carrying tunnel did not prove public egress through tor"))
        assertTrue(attempt.indexOf("check(runtimeProxyAccepted)") < attempt.indexOf("val earlyAccepted"))
        assertFalse(source.contains("requireTorBootstrapForValidation"))
        assertFalse(source.contains("tor-only tunnel accepted"))
    }

    @Test
    fun `failed strict tor proof cannot accept or publish generic control evidence`() {
        val source = validationRunSource().readText()
        val proxyAttempt =
            source
                .substringAfter("private suspend fun FoxholeVpnService.tryRuntimeProxyTunnelValidation(")
                .substringBefore("private suspend fun FoxholeVpnService.tryEarlyUdpLiteralValidation(")
        val failureBranch =
            proxyAttempt
                .substringAfter("return if (runtimeProxyValidation.isFailure)")
                .substringBefore("        false\n    } else {")
        val successBranch = proxyAttempt.substringAfter("        false\n    } else {")

        assertFalse(failureBranch.contains("publishRuntimeProxyValidatedIpInfo"))
        assertTrue(successBranch.contains("publishRuntimeProxyValidatedIpInfo"))
        assertTrue(
            proxyAttempt.contains(
                "maxAttempts = validationRun.probePlan.maxRuntimeProxyWarmupAttempts",
            ),
        )

        val attempt =
            source
                .substringAfter("private suspend fun FoxholeVpnService.runTunnelValidationAttempt(")
                .substringBefore("private suspend fun FoxholeVpnService.requireVpnBoundDnsResolution(")
        assertTrue(attempt.indexOf("check(runtimeProxyAccepted)") < attempt.indexOf("val earlyAccepted"))

        val proxySource = runtimeProxyValidationSource().readText()
        val warmup =
            proxySource
                .substringAfter("internal suspend fun FoxholeVpnService.validateRuntimeProxyEgressWithWarmup(")
                .substringBefore("private fun FoxholeVpnService.logStrictTorValidationRetry(")
        assertTrue(warmup.contains("classifyStrictTorControlProbe("))
        val cappedAttemptBranch =
            warmup
                .substringAfter("!warmupPolicy.permitsAttempt(attempt)")
                .substringBefore("ipRefreshCallBudgetMs =")
        assertTrue(cappedAttemptBranch.contains("break"))
        assertFalse(cappedAttemptBranch.contains("delay("))
        assertTrue(warmup.indexOf("classifyStrictTorControlProbe(") < warmup.indexOf("throw strictFailure"))

        val classification =
            proxySource
                .substringAfter("private suspend fun FoxholeVpnService.classifyStrictTorControlProbe(")
                .substringBefore("private suspend fun FoxholeVpnService.probeServiceOwnedTorControlEndpoint(")
        val controlProbe =
            proxySource
                .substringAfter("private suspend fun FoxholeVpnService.probeServiceOwnedTorControlEndpoint(")
                .substringBefore("private suspend fun FoxholeVpnService.fetchRuntimeProxyValidationIpInfo(")
        val controlPath = classification + controlProbe
        assertEquals(1, Regex("""probeServiceOwnedTorControlEndpoint\(""").findAll(classification).count())
        assertTrue(controlProbe.contains("withAuthenticatedServiceOwnedTorProbeLease(session)"))
        assertTrue(controlProbe.contains("container.ipInfoRepository.probe("))
        assertTrue(classification.contains("strict_tor_control_probe"))
        assertTrue(classification.contains("classified=reachable"))
        assertTrue(classification.contains("classified=unavailable"))
        assertTrue(classification.contains("failure_class="))
        assertFalse(controlPath.contains("probeConnectivityEndpointsOverLocalProxy"))
        assertFalse(controlPath.contains("acceptsRuntimeProxyValidationResult"))
        assertFalse(controlPath.contains("publishRuntimeProxyValidatedIpInfo"))
        assertFalse(controlPath.contains("bridgeWriter"))
        assertFalse(controlPath.contains("failure.message"))
    }

    @Test
    fun `tor sessions accept only explicitly verified tor exit results`() {
        val torOnly = session().copy(profileId = com.foxhole.core.model.TOR_ONLY_PROFILE_ID)
        val torActiveWithoutRoute = session().copy(torActive = true)
        val vpnTorAllApps = torSession(PrivacyRouteScope.ALL_APPS)
        val vpnTorSelectedApps = torSession(PrivacyRouteScope.SELECTED_APPS)
        val vpnBesideTor = torSession(PrivacyRouteScope.ALL_APPS, bypassVpnTunnel = true)
        val appliedRouteWithoutFlag =
            session().copy(
                appliedTorRoute = AppliedTorRoute(PrivacyRouteScope.ALL_APPS, bypassVpnTunnel = false),
            )
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
                verifiedTorExit = true,
            )
        val genericIp = verifiedIp.copy(verifiedTorExit = false)

        assertFalse(acceptsRuntimeProxyValidationResult(torOnly, endpointOnly))
        assertFalse(acceptsRuntimeProxyValidationResult(torOnly, missingIp))
        assertFalse(acceptsRuntimeProxyValidationResult(torOnly, genericIp))
        assertTrue(acceptsRuntimeProxyValidationResult(torOnly, verifiedIp))
        listOf(
            torActiveWithoutRoute,
            vpnTorAllApps,
            vpnTorSelectedApps,
            vpnBesideTor,
            appliedRouteWithoutFlag,
        ).forEach { strictSession ->
            assertTrue(strictSession.requiresVerifiedTorExitForValidation())
            assertFalse(acceptsRuntimeProxyValidationResult(strictSession, endpointOnly))
            assertFalse(acceptsRuntimeProxyValidationResult(strictSession, genericIp))
            assertTrue(acceptsRuntimeProxyValidationResult(strictSession, verifiedIp))
        }
        val deferredVpnFirstStage =
            session(configJson = """{"outbounds":[{"tag":"proxy","type":"vless"}]}""")
        assertFalse(deferredVpnFirstStage.requiresVerifiedTorExitForValidation())
        assertTrue(acceptsRuntimeProxyValidationResult(deferredVpnFirstStage, endpointOnly))
    }

    @Test
    fun `strict tor validation uses only the service-owned authenticated probe`() {
        val source = runtimeProxyValidationSource().readText()
        val strictFetch =
            source
                .substringAfter("private suspend fun FoxholeVpnService.fetchServiceOwnedVerifiedTorExit(")
                .substringBefore("internal suspend fun FoxholeVpnService.publishRuntimeProxyValidatedIpInfo(")

        assertTrue(strictFetch.contains("syncTorProbeProxy()"))
        assertTrue(strictFetch.contains("runtime.torProbeProxyLease()"))
        assertTrue(strictFetch.contains("fetchVerifiedTorExit("))
        assertTrue(strictFetch.contains("lease.access"))
        assertTrue(strictFetch.contains("TorProbeProxyOwner("))
        assertTrue(strictFetch.contains("runtimeSupervisor.currentGeneration()"))
        assertTrue(strictFetch.contains("requireCurrentTorValidationSession(session, expectedOwner)"))
        assertTrue(strictFetch.contains("currentLease.access != lease.access"))
        assertTrue(strictFetch.contains("access.type != ProxyAccessType.HTTP"))
        assertTrue(strictFetch.contains("access.username.isNullOrBlank()"))
        assertTrue(strictFetch.contains("access.password.isNullOrBlank()"))
        assertTrue(strictFetch.contains("TorProbeProxyFailure.STALE_GENERATION"))
        assertFalse(strictFetch.contains("tunnelRuntimeProxyAccess()"))
        assertFalse(strictFetch.contains("fetchIpv4("))
    }

    @Test
    fun `tor only proof publishes both the primary and tor identity channels`() {
        val source = runtimeProxyValidationSource().readText()
        val torOnlyBranch =
            source
                .substringAfter("if (session?.profileId == FoxholeVpnService.TOR_ONLY_PROFILE_ID)")
                .substringBefore("} else if (session.requiresVerifiedTorExitForValidation())")

        assertTrue(torOnlyBranch.contains("bridgeWriter.updateIpInfo(info)"))
        assertTrue(torOnlyBranch.contains("bridgeWriter.updateTorRouteIpInfo(info)"))
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

    private fun torSession(
        scope: PrivacyRouteScope,
        bypassVpnTunnel: Boolean = false,
    ): VpnSession =
        session().copy(
            torActive = true,
            appliedTorRoute =
            AppliedTorRoute(
                scope = scope,
                bypassVpnTunnel = bypassVpnTunnel,
                selectedPackages =
                if (scope == PrivacyRouteScope.SELECTED_APPS) {
                    listOf("org.torproject.torbrowser")
                } else {
                    emptyList()
                },
            ),
        )

    private fun validationRunSource(): File =
        listOf(
            File("src/main/kotlin/com/foxhole/guard/runtime/RuntimeValidationRun.kt"),
            File("app/src/main/kotlin/com/foxhole/guard/runtime/RuntimeValidationRun.kt"),
            File("../app/src/main/kotlin/com/foxhole/guard/runtime/RuntimeValidationRun.kt"),
        ).first(File::isFile)

    private fun runtimeProxyValidationSource(): File =
        listOf(
            File("src/main/kotlin/com/foxhole/guard/runtime/RuntimeProxyEgressValidationSupport.kt"),
            File("app/src/main/kotlin/com/foxhole/guard/runtime/RuntimeProxyEgressValidationSupport.kt"),
            File("../app/src/main/kotlin/com/foxhole/guard/runtime/RuntimeProxyEgressValidationSupport.kt"),
        ).first(File::isFile)
}
