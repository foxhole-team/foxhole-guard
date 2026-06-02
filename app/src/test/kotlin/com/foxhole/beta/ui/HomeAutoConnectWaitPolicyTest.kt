package com.foxhole.beta.ui

import com.foxhole.beta.core.model.AutoConnectReasonCode
import com.foxhole.beta.core.model.ConnectionState
import com.foxhole.beta.core.model.LatencyProbeMethod
import com.foxhole.beta.core.model.ProtocolHint
import com.foxhole.beta.core.model.SmartProfilePreference
import com.foxhole.beta.core.profile.AutoConnectProbeCandidate
import com.foxhole.beta.core.profile.AutoConnectProbeResult
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
                profileId = null,
                currentVpnNetworkHandle = 42L,
                previousVpnNetworkHandle = 42L,
            ),
        )
        assertTrue(
            isAutoConnectDisconnectSettled(
                connectionState = ConnectionState.IDLE,
                profileId = null,
                currentVpnNetworkHandle = 84L,
                previousVpnNetworkHandle = 42L,
            ),
        )
    }

    @Test
    fun `disconnect settle accepts replacement local guard vpn during smart start`() {
        assertTrue(
            isAutoConnectDisconnectSettled(
                connectionState = ConnectionState.CONNECTED,
                profileId = FoxholeVpnService.LOCAL_GUARD_PROFILE_ID,
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
    fun `connected auto connect fallback keeps remembered latency ranking only`() {
        val source =
            listOf(
                java.io.File("src/main/kotlin/com/foxhole/beta/ui/HomeViewModelAutoConnectSupport.kt"),
                java.io.File("app/src/main/kotlin/com/foxhole/beta/ui/HomeViewModelAutoConnectSupport.kt"),
                java.io.File("../app/src/main/kotlin/com/foxhole/beta/ui/HomeViewModelAutoConnectSupport.kt"),
            ).first { file -> file.isFile }.readText()
        val fallbackBlock =
            source.substringAfter("private fun HomeViewModel.buildConnectedAutoConnectFallbackResult")
                .substringBefore("private suspend fun HomeViewModel.restoreConnectionAfterMetricsRefresh")

        assertTrue(fallbackBlock.contains("rememberedLatencyMs = rememberedLatencyMs"))
        assertTrue(fallbackBlock.contains("displayLatencyMs = null"))
        assertFalse(fallbackBlock.contains("displayLatencyMs = rememberedLatencyMs"))
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
        assertTrue(
            HomeViewModel.AUTO_CONNECT_TOTAL_TIMEOUT_MS >=
                HomeViewModel.AUTO_CONNECT_MAX_ATTEMPTS * minimumAutoConnectCandidateProbeBudgetMs(),
        )
    }

    @Test
    fun `candidate probe timeout uses smart start setting defaults`() {
        assertEquals(minimumAutoConnectCandidateProbeBudgetMs(), autoConnectCandidateProbeTimeoutMs())
        assertEquals(minimumAutoConnectCandidateProbeBudgetMs(), protocolMetricsCandidateProbeTimeoutMs())
        assertEquals(minimumAutoConnectCandidateProbeBudgetMs(), autoConnectCandidateProbeTimeoutMs(timeoutSeconds = 3))
        assertEquals(60_000L, protocolMetricsCandidateProbeTimeoutMs(timeoutSeconds = 99))
        assertTrue(protocolMetricsCandidateProbeTimeoutMs(timeoutSeconds = 99) > autoConnectCandidateProbeTimeoutMs())
        assertTrue(autoConnectCandidateProbeTimeoutMs() <= HomeViewModel.AUTO_CONNECT_TOTAL_TIMEOUT_MS)
        assertTrue(autoConnectCandidateProbeTimeoutMs() >= HomeViewModel.AUTO_CONNECT_CONNECTION_TIMEOUT_MS)
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
    fun `dashboard tunnel ping and server ping keep explicit timeout budgets`() {
        assertEquals(
            HomeViewModel.CONNECTED_LATENCY_TIMEOUT_MS,
            HomeViewModel.CONNECTED_DASHBOARD_PING_TIMEOUT_MS,
        )
        assertEquals(2_500L, HomeViewModel.CONNECTED_SERVER_PING_TIMEOUT_MS)
        assertEquals(3_000L, HomeViewModel.CONNECTED_SERVER_PING_TOTAL_TIMEOUT_MS)
    }

    @Test
    fun `cellular speed-test guard keeps first dashboard ping and stops recurring refreshes`() {
        assertTrue(shouldContinueDashboardLatencyRefreshAfterInitialSample(skipSpeedTestsOnCurrentNetwork = false))
        assertFalse(shouldContinueDashboardLatencyRefreshAfterInitialSample(skipSpeedTestsOnCurrentNetwork = true))
    }

    @Test
    fun `connected dashboard refresh measures public latency and direct server tcp ping separately`() {
        val source =
            listOf(
                java.io.File("src/main/kotlin/com/foxhole/beta/ui/HomeViewModelAutoConnectSupport.kt"),
                java.io.File("app/src/main/kotlin/com/foxhole/beta/ui/HomeViewModelAutoConnectSupport.kt"),
                java.io.File("../app/src/main/kotlin/com/foxhole/beta/ui/HomeViewModelAutoConnectSupport.kt"),
            ).first { file -> file.isFile }.readText()
        val connectedDashboardBlock =
            source.substringAfter("internal fun HomeViewModel.scheduleActiveProfileLatencyRefreshInternal")
                .substringBefore("private fun HomeViewModel.clearActiveProfileConnectionMetrics")

        assertTrue(connectedDashboardBlock.contains("measureConnectedDashboardPublicLatency"))
        assertTrue(connectedDashboardBlock.contains("measureConnectedServerTcpPing"))
        assertTrue(connectedDashboardBlock.contains("measureCurrentConnectionLatency"))
        assertTrue(connectedDashboardBlock.contains("measureCurrentVpnServerPing"))
        assertTrue(connectedDashboardBlock.contains("cacheProtocolTunnelPingInternal"))
        assertTrue(connectedDashboardBlock.contains("cacheProtocolServerPingInternal"))
    }

    @Test
    fun `connected server tcp ping cache is separate from tunnel latency cache`() {
        val source =
            listOf(
                java.io.File("src/main/kotlin/com/foxhole/beta/ui/HomeViewModelAutoConnectSupport.kt"),
                java.io.File("app/src/main/kotlin/com/foxhole/beta/ui/HomeViewModelAutoConnectSupport.kt"),
                java.io.File("../app/src/main/kotlin/com/foxhole/beta/ui/HomeViewModelAutoConnectSupport.kt"),
            ).first { file -> file.isFile }.readText()
        val serverPingBlock =
            source.substringAfter("private fun HomeViewModel.cacheConnectedServerTcpPing")
                .substringBefore("private fun HomeViewModel.clearActiveProfileConnectionMetrics")

        assertTrue(serverPingBlock.contains("cacheProtocolServerPingInternal"))
        assertTrue(serverPingBlock.contains("markProtocolServerPingUnavailableInternal"))
        assertFalse(serverPingBlock.contains("cacheProtocolTunnelPingInternal"))
        assertFalse(serverPingBlock.contains("cacheProtocolLatency"))
        assertFalse(serverPingBlock.contains("recordConnectedProtocolSmartStartMemory"))
    }

    @Test
    fun `auto connect candidate probe does not measure provider server tcp ping`() {
        val source =
            listOf(
                java.io.File("src/main/kotlin/com/foxhole/beta/ui/HomeViewModelAutoConnectSupport.kt"),
                java.io.File("app/src/main/kotlin/com/foxhole/beta/ui/HomeViewModelAutoConnectSupport.kt"),
                java.io.File("../app/src/main/kotlin/com/foxhole/beta/ui/HomeViewModelAutoConnectSupport.kt"),
            ).first { file -> file.isFile }.readText()
        val candidateProbeBlock =
            source.substringAfter("internal suspend fun HomeViewModel.probeAutoConnectCandidateInternal")
                .substringBefore("internal suspend fun HomeViewModel.measureAutoConnectCandidateLatency")

        assertTrue(candidateProbeBlock.contains("measureAutoConnectCandidateLatency"))
        assertTrue(candidateProbeBlock.contains("cacheProtocolLatency"))
        assertFalse(candidateProbeBlock.contains("measureCurrentVpnServerPing"))
        assertFalse(candidateProbeBlock.contains("recordSmartProfileServerPing"))
        assertFalse(candidateProbeBlock.contains("cacheProtocolServerPingInternal"))
    }

    @Test
    fun `manual smart metrics refresh records direct tcp server ping separately from dashboard tunnel ping`() {
        val source =
            listOf(
                java.io.File("src/main/kotlin/com/foxhole/beta/ui/HomeViewModelAutoConnectSupport.kt"),
                java.io.File("app/src/main/kotlin/com/foxhole/beta/ui/HomeViewModelAutoConnectSupport.kt"),
                java.io.File("../app/src/main/kotlin/com/foxhole/beta/ui/HomeViewModelAutoConnectSupport.kt"),
            ).first { file -> file.isFile }.readText()
        val metricsRefreshBlock =
            source.substringAfter("private suspend fun HomeViewModel.refreshActiveServerTcpPingForMetrics")
                .substringBefore("private fun HomeViewModel.protocolMetricsProbeTimeoutResult")

        assertTrue(metricsRefreshBlock.contains("measureCurrentVpnServerPing"))
        assertTrue(metricsRefreshBlock.contains("cacheProtocolServerPingInternal"))
        assertTrue(metricsRefreshBlock.contains("recordSmartProfileServerPing"))
        assertFalse(metricsRefreshBlock.contains("cacheProtocolTunnelPingInternal"))
    }

    @Test
    fun `connected dashboard latency rejects timeout-shaped values`() {
        assertTrue(shouldUseConnectedDashboardLatency(1L))
        assertTrue(shouldUseConnectedDashboardLatency(999L))
        assertFalse(shouldUseConnectedDashboardLatency(1_000L))
        assertFalse(shouldUseConnectedDashboardLatency(2_181L))
    }

    @Test
    fun `auto connect retries high warmup latency before recording display latency`() {
        assertFalse(shouldRetryAutoConnectLatencyMeasurement(349L))
        assertTrue(shouldRetryAutoConnectLatencyMeasurement(350L))
        assertEquals(
            430L,
            resolveAutoConnectLatencyMeasurementResult(
                warmupLatencyMs = 2_181L,
                settledLatencyMs = 430L,
            ),
        )
    }

    @Test
    fun `auto connect prefers settled latency when retry is available`() {
        assertEquals(
            430L,
            resolveAutoConnectLatencyMeasurementResult(
                warmupLatencyMs = 380L,
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
    fun `runner keeps full cold scan candidates for first analysis`() {
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
    fun `runner ignores stale ranked order for cold scan candidates`() {
        val vless = candidate("vless")
        val trojan = candidate("trojan")
        val hysteria = candidate("hysteria")
        val wireguard = candidate("wireguard")
        val state =
            SmartStartAutoConnectRunner.resolveState(
                fullScanCandidates = listOf(vless, trojan, hysteria, wireguard),
                enabledProtocolSetHash = "hash",
                preference = SmartProfilePreference(profileId = 1L),
                rankedCandidates = listOf(score(wireguard), score(trojan), score(vless), score(hysteria)),
            )

        assertEquals(
            listOf("vless", "trojan", "hysteria", "wireguard"),
            state.candidates.map(AutoConnectProbeCandidate::optionId),
        )
    }

    @Test
    fun `runner tries fresh leaders before saved recommended candidates`() {
        val vless = candidate("vless")
        val outline = candidate("outline")
        val wireguard = candidate("wireguard")
        val trojan = candidate("trojan")
        val state =
            SmartStartAutoConnectRunner.resolveState(
                fullScanCandidates = listOf(vless, outline, wireguard, trojan),
                enabledProtocolSetHash = "hash",
                preference =
                SmartProfilePreference(
                    profileId = 1L,
                    smartStartBaselineReady = true,
                    recommendedProtocolIds = listOf("trojan"),
                    enabledProtocolSetHash = "hash",
                ),
                rankedCandidates = listOf(score(vless), score(outline), score(wireguard), score(trojan)),
            )

        assertEquals(
            listOf("vless", "outline", "trojan"),
            state.candidates.map(AutoConnectProbeCandidate::optionId),
        )
    }

    @Test
    fun `recommendations require measured display latency`() {
        val vless = candidate("vless")
        val trojan = candidate("trojan")
        val wireguard = candidate("wireguard")

        assertEquals(
            listOf("trojan"),
            recommendedProtocolIdsFromProbeResults(
                listOf(
                    AutoConnectProbeResult(
                        candidate = vless,
                        success = true,
                        latencyMs = 350L,
                        rankingLatencyMs = 350L,
                        displayLatencyMs = null,
                        reasonCode = AutoConnectReasonCode.LATENCY_ENDPOINT_BLOCKED,
                    ),
                    AutoConnectProbeResult(
                        candidate = wireguard,
                        success = true,
                        latencyMs = 80L,
                        rankingLatencyMs = 80L,
                        displayLatencyMs = null,
                        reasonCode = AutoConnectReasonCode.LATENCY_ENDPOINT_BLOCKED,
                    ),
                    AutoConnectProbeResult(
                        candidate = trojan,
                        success = true,
                        latencyMs = 220L,
                        displayLatencyMs = 220L,
                    ),
                ),
            ),
        )
    }

    @Test
    fun `all unavailable latency probes do not fabricate a recommendation`() {
        assertEquals(
            emptyList<String>(),
            recommendedProtocolIdsFromProbeResults(
                listOf(
                    AutoConnectProbeResult(
                        candidate = candidate("vless"),
                        success = true,
                        latencyMs = 340L,
                        rankingLatencyMs = 340L,
                        displayLatencyMs = null,
                        reasonCode = AutoConnectReasonCode.LATENCY_ENDPOINT_BLOCKED,
                    ),
                    AutoConnectProbeResult(
                        candidate = candidate("wireguard"),
                        success = true,
                        latencyMs = 90L,
                        rankingLatencyMs = 90L,
                        displayLatencyMs = null,
                        reasonCode = AutoConnectReasonCode.LATENCY_ENDPOINT_BLOCKED,
                    ),
                ),
            ),
        )
    }

    @Test
    fun `manual smart profile metrics analysis keeps every protocol candidate`() {
        val candidates =
            listOf(
                candidate("vless"),
                candidate("trojan"),
                candidate("wireguard"),
                candidate("vless"),
            )

        assertEquals(
            listOf("vless", "trojan", "wireguard"),
            smartProfileMetricsAnalysisCandidates(candidates).map(AutoConnectProbeCandidate::optionId),
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
