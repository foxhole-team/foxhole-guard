package com.foxhole.beta.ui

import com.foxhole.beta.core.model.ConnectionState
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
    fun `auto connect latency waits for the settled dashboard refresh window`() {
        assertEquals(
            HomeViewModel.CONNECTED_PROTOCOL_LATENCY_REFRESH_DELAY_MS,
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
}
