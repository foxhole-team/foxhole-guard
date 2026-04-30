package com.foxhole.beta.ui

import com.foxhole.beta.core.model.ConnectionState
import com.foxhole.beta.core.model.LatencyProbeMethod
import com.foxhole.beta.core.model.ProtocolHint
import com.foxhole.beta.core.model.SmartProfilePreference
import com.foxhole.beta.core.profile.AutoConnectProbeCandidate
import com.foxhole.beta.core.smart.AdaptiveProtocolCandidateScore
import com.foxhole.beta.vpn.FoxholeVpnService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeAutoConnectWaitPolicyTest {
    @Test
    fun `extends auto connect wait while tunnel is still connecting and vpn network exists`() {
        assertTrue(
            shouldAwaitAutoConnectValidationGrace(
                connectionState = ConnectionState.CONNECTING,
                vpnNetworkAvailable = true,
            ),
        )
    }

    @Test
    fun `does not extend auto connect wait without vpn network`() {
        assertFalse(
            shouldAwaitAutoConnectValidationGrace(
                connectionState = ConnectionState.CONNECTING,
                vpnNetworkAvailable = false,
            ),
        )
    }

    @Test
    fun `does not extend auto connect wait after connection already settled`() {
        assertFalse(
            shouldAwaitAutoConnectValidationGrace(
                connectionState = ConnectionState.CONNECTED,
                vpnNetworkAvailable = true,
            ),
        )
    }

    @Test
    fun `disconnect settle requires previous vpn handle to disappear`() {
        assertFalse(
            isAutoConnectDisconnectSettled(
                connectionState = ConnectionState.IDLE,
                currentVpnNetworkHandle = 42L,
                previousVpnNetworkHandle = 42L,
            ),
        )
        assertTrue(
            isAutoConnectDisconnectSettled(
                connectionState = ConnectionState.IDLE,
                currentVpnNetworkHandle = 84L,
                previousVpnNetworkHandle = 42L,
            ),
        )
    }

    @Test
    fun `fallback ranking prefers remembered latency over slow probe wall clock`() {
        assertEquals(
            960L,
            resolveAutoConnectFallbackRankingLatency(
                validatedConnectDurationMs = 4_200L,
                rememberedLatencyMs = 210L,
                penaltyMs = 750L,
                protocolHint = ProtocolHint.TROJAN,
                latencyProbeMethod = LatencyProbeMethod.HTTP,
            ),
        )
    }

    @Test
    fun `fallback ranking uses validated connect duration when no remembered latency exists`() {
        assertEquals(
            2_550L,
            resolveAutoConnectFallbackRankingLatency(
                validatedConnectDurationMs = 1_800L,
                rememberedLatencyMs = null,
                penaltyMs = 750L,
                protocolHint = ProtocolHint.TROJAN,
                latencyProbeMethod = LatencyProbeMethod.HTTP,
            ),
        )
    }

    @Test
    fun `udp fallback ranking does not add endpoint penalty`() {
        assertEquals(
            180L,
            resolveAutoConnectFallbackRankingLatency(
                validatedConnectDurationMs = 180L,
                rememberedLatencyMs = null,
                penaltyMs = 750L,
                protocolHint = ProtocolHint.WIREGUARD,
                latencyProbeMethod = LatencyProbeMethod.HTTP,
            ),
        )
    }

    @Test
    fun `icmp fallback ranking does not add endpoint penalty`() {
        assertEquals(
            180L,
            resolveAutoConnectFallbackRankingLatency(
                validatedConnectDurationMs = 180L,
                rememberedLatencyMs = null,
                penaltyMs = 750L,
                protocolHint = ProtocolHint.TROJAN,
                latencyProbeMethod = LatencyProbeMethod.ICMP,
            ),
        )
    }

    @Test
    fun `auto connect timeout budget follows service validation budget`() {
        assertEquals(
            FoxholeVpnService.CONNECTIVITY_PROBE_TOTAL_TIMEOUT_MS +
                FoxholeVpnService.CONNECTIVITY_PROBE_NETWORK_WAIT_TIMEOUT_MS +
                FoxholeVpnService.CONNECTIVITY_PROBE_GRACE_MAX_TIMEOUT_MS,
            HomeViewModel.AUTO_CONNECT_CONNECTION_TIMEOUT_MS,
        )
        assertEquals(
            FoxholeVpnService.VPN_NETWORK_WAIT_TIMEOUT_MS,
            HomeViewModel.AUTO_CONNECT_VALIDATION_GRACE_TIMEOUT_MS,
        )
        assertEquals(3, HomeViewModel.AUTO_CONNECT_MAX_ATTEMPTS)
        assertEquals(60_000L, HomeViewModel.AUTO_CONNECT_TOTAL_TIMEOUT_MS)
    }

    @Test
    fun `candidate probe timeout uses smart start setting defaults`() {
        assertEquals(15_000L, autoConnectCandidateProbeTimeoutMs())
        assertEquals(20_000L, protocolMetricsCandidateProbeTimeoutMs())
        assertEquals(10_000L, autoConnectCandidateProbeTimeoutMs(timeoutSeconds = 3))
        assertEquals(60_000L, protocolMetricsCandidateProbeTimeoutMs(timeoutSeconds = 99))
        assertTrue(protocolMetricsCandidateProbeTimeoutMs() > autoConnectCandidateProbeTimeoutMs())
        assertTrue(autoConnectCandidateProbeTimeoutMs() < HomeViewModel.AUTO_CONNECT_TOTAL_TIMEOUT_MS)
    }

    @Test
    fun `auto connect permits a single supported candidate`() {
        assertFalse(canStartAutoConnect(emptyList()))
        assertTrue(canStartAutoConnect(listOf(candidate("vless"))))
    }

    @Test
    fun `auto connect remaining wall clock budget is bounded`() {
        assertEquals(
            60_000L,
            remainingAutoConnectBudgetMs(
                startedAtElapsedMs = 100L,
                nowElapsedMs = 100L,
                totalTimeoutMs = 60_000L,
            ),
        )
        assertEquals(
            10_000L,
            remainingAutoConnectBudgetMs(
                startedAtElapsedMs = 100L,
                nowElapsedMs = 50_100L,
                totalTimeoutMs = 60_000L,
            ),
        )
        assertEquals(
            0L,
            remainingAutoConnectBudgetMs(
                startedAtElapsedMs = 100L,
                nowElapsedMs = 70_100L,
                totalTimeoutMs = 60_000L,
            ),
        )
    }

    @Test
    fun `auto connect continues after candidate timeout while wall clock budget remains`() {
        assertTrue(
            shouldContinueAutoConnectAfterProbe(
                success = false,
                timedOut = true,
                remainingBudgetMs = 48_000L,
            ),
        )
        assertFalse(
            shouldContinueAutoConnectAfterProbe(
                success = false,
                timedOut = true,
                remainingBudgetMs = 0L,
            ),
        )
        assertTrue(
            shouldContinueAutoConnectAfterProbe(
                success = false,
                timedOut = false,
                remainingBudgetMs = 0L,
            ),
        )
        assertFalse(
            shouldContinueAutoConnectAfterProbe(
                success = true,
                timedOut = false,
                remainingBudgetMs = 48_000L,
            ),
        )
    }

    @Test
    fun `auto connect latency waits for the settled dashboard refresh window`() {
        assertEquals(
            HomeViewModel.CONNECTED_LATENCY_FIRST_DELAY_MS,
            HomeViewModel.AUTO_CONNECT_LATENCY_MEASUREMENT_SETTLE_MS,
        )
    }

    @Test
    fun `auto connect retries high warmup latency before recording display latency`() {
        assertFalse(shouldRetryAutoConnectLatencyMeasurement(999L))
        assertTrue(shouldRetryAutoConnectLatencyMeasurement(1_000L))
        assertEquals(
            430L,
            resolveAutoConnectLatencyMeasurementResult(
                warmupLatencyMs = 2_181L,
                settledLatencyMs = 430L,
            ),
        )
    }

    @Test
    fun `auto connect keeps warmup latency when settled retry is unavailable`() {
        assertEquals(
            2_181L,
            resolveAutoConnectLatencyMeasurementResult(
                warmupLatencyMs = 2_181L,
                settledLatencyMs = null,
            ),
        )
    }

    @Test
    fun `runner selects cold scan when baseline is missing`() {
        val state =
            SmartStartAutoConnectRunner.resolveState(
                fullScanCandidates = listOf(candidate("vless")),
                enabledProtocolSetHash = "hash",
                preference = SmartProfilePreference(profileId = 1L),
                rankedCandidates = listOf(score(candidate("vless"))),
            )

        assertTrue(state is SmartStartAutoConnectState.ColdScan)
        assertEquals(listOf("vless"), state.candidates.map(AutoConnectProbeCandidate::optionId))
    }

    @Test
    fun `runner keeps every cold scan candidate for first analysis`() {
        val state =
            SmartStartAutoConnectRunner.resolveState(
                fullScanCandidates =
                    listOf(
                        candidate("vless"),
                        candidate("trojan"),
                        candidate("hysteria"),
                        candidate("wireguard"),
                    ),
                enabledProtocolSetHash = "hash",
                preference = SmartProfilePreference(profileId = 1L),
                rankedCandidates = emptyList(),
            )

        assertEquals(
            listOf("vless", "trojan", "hysteria", "wireguard"),
            state.candidates.map(AutoConnectProbeCandidate::optionId),
        )
    }

    @Test
    fun `runner keeps recommended attempts before fallback candidates`() {
        val vless = candidate("vless")
        val trojan = candidate("trojan")
        val hysteria = candidate("hysteria")
        val state =
            SmartStartAutoConnectRunner.resolveState(
                fullScanCandidates = listOf(vless, trojan, hysteria),
                enabledProtocolSetHash = "hash",
                preference =
                    SmartProfilePreference(
                        profileId = 1L,
                        smartStartBaselineReady = true,
                        recommendedProtocolIds = listOf("trojan"),
                        enabledProtocolSetHash = "hash",
                    ),
                rankedCandidates = listOf(score(vless), score(trojan), score(hysteria)),
            )

        assertEquals(
            listOf("trojan", "vless", "hysteria"),
            state.candidates.map(AutoConnectProbeCandidate::optionId),
        )
    }

    private fun candidate(optionId: String): AutoConnectProbeCandidate =
        AutoConnectProbeCandidate(
            profileId = 1L,
            optionId = optionId,
            protocolHint = ProtocolHint.VLESS,
            displayName = optionId,
        )

    private fun score(candidate: AutoConnectProbeCandidate): AdaptiveProtocolCandidateScore =
        AdaptiveProtocolCandidateScore(
            candidate = candidate,
            scoringVersion = 1,
            score = 0,
            successRate = 0.0,
            lastKnownGoodBonus = 0,
            networkMatchBonus = 0,
            latencyPenalty = 0,
            recentFailurePenalty = 0,
            validationFailurePenalty = 0,
            explorationBonus = 0,
        )
}
