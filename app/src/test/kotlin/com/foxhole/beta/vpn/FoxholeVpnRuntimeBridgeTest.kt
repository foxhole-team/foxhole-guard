package com.foxhole.beta.vpn

import com.foxhole.beta.core.model.ConnectionSnapshot
import com.foxhole.beta.core.model.ConnectionState
import com.foxhole.beta.core.model.IpInfo
import com.foxhole.beta.core.model.TrafficMode
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FoxholeVpnRuntimeBridgeTest {
    @Test
    fun `immediate traffic sample requests are emitted without waiting for polling cadence`() =
        runBlocking {
            val request =
                async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeout(250L) {
                        FoxholeVpnRuntimeBridge.immediateTrafficSampleRequests.first()
                    }
                    true
                }

            FoxholeVpnRuntimeBridge.requestImmediateTrafficSample()

            assertTrue(request.await())
        }

    @Test
    fun `upstream refresh signal can preserve connection change timestamp`() {
        val previous = FoxholeVpnRuntimeBridge.snapshot.value
        try {
            FoxholeVpnRuntimeBridge.update(
                ConnectionSnapshot(
                    state = ConnectionState.CONNECTED,
                    lastChangeAt = 123L,
                    upstreamNetworkRevision = 1L,
                ),
                refreshLastChangeAt = false,
            )

            FoxholeVpnRuntimeBridge.update(
                FoxholeVpnRuntimeBridge.snapshot.value.copy(upstreamNetworkRevision = 2L),
                refreshLastChangeAt = false,
            )

            assertEquals(123L, FoxholeVpnRuntimeBridge.snapshot.value.lastChangeAt)
            assertEquals(2L, FoxholeVpnRuntimeBridge.snapshot.value.upstreamNetworkRevision)
        } finally {
            FoxholeVpnRuntimeBridge.update(previous, refreshLastChangeAt = false)
        }
    }

    @Test
    fun `runtime ui state marks previous tunnel ip loading without clearing bridge ip`() {
        val previousSnapshot = FoxholeVpnRuntimeBridge.snapshot.value
        val previousIpInfo = FoxholeVpnRuntimeBridge.ipInfo.value
        val previousDeviceIpInfo = FoxholeVpnRuntimeBridge.deviceIpInfo.value
        val tunnelIp = ipInfo("203.0.113.10")
        try {
            FoxholeVpnRuntimeBridge.update(
                ConnectionSnapshot(
                    state = ConnectionState.CONNECTED,
                    trafficMode = TrafficMode.TUNNEL,
                    profileId = 42L,
                    lastChangeAt = 500L,
                ),
                refreshLastChangeAt = false,
            )
            FoxholeVpnRuntimeBridge.updateIpInfo(tunnelIp)

            FoxholeVpnRuntimeBridge.markIpInfoRefreshPending(RuntimeIpRefreshReason.POST_CONNECT)

            val panel = FoxholeVpnRuntimeBridge.runtimeUiState.value.ip.tunnel as RuntimeIpPanelState.Loading
            assertEquals(tunnelIp, FoxholeVpnRuntimeBridge.ipInfo.value)
            assertEquals(tunnelIp, panel.previous)
            assertEquals(RuntimeIpRefreshReason.POST_CONNECT, panel.reason)
        } finally {
            FoxholeVpnRuntimeBridge.update(previousSnapshot, refreshLastChangeAt = false)
            FoxholeVpnRuntimeBridge.updateIpInfo(previousIpInfo)
            FoxholeVpnRuntimeBridge.updateDeviceIpInfo(previousDeviceIpInfo)
        }
    }

    @Test
    fun `runtime ui state clears pending loading when new tunnel ip arrives`() {
        val previousSnapshot = FoxholeVpnRuntimeBridge.snapshot.value
        val previousIpInfo = FoxholeVpnRuntimeBridge.ipInfo.value
        val previousDeviceIpInfo = FoxholeVpnRuntimeBridge.deviceIpInfo.value
        val tunnelIp = ipInfo("203.0.113.10")
        val refreshedIp = ipInfo("198.51.100.20")
        try {
            FoxholeVpnRuntimeBridge.update(
                ConnectionSnapshot(
                    state = ConnectionState.CONNECTED,
                    trafficMode = TrafficMode.TUNNEL,
                    profileId = 42L,
                    lastChangeAt = 500L,
                ),
                refreshLastChangeAt = false,
            )
            FoxholeVpnRuntimeBridge.updateIpInfo(tunnelIp)
            FoxholeVpnRuntimeBridge.markIpInfoRefreshPending(RuntimeIpRefreshReason.POST_CONNECT)

            FoxholeVpnRuntimeBridge.updateIpInfo(refreshedIp)

            val panel = FoxholeVpnRuntimeBridge.runtimeUiState.value.ip.tunnel as RuntimeIpPanelState.Ready
            assertEquals(refreshedIp, panel.info)
        } finally {
            FoxholeVpnRuntimeBridge.update(previousSnapshot, refreshLastChangeAt = false)
            FoxholeVpnRuntimeBridge.updateIpInfo(previousIpInfo)
            FoxholeVpnRuntimeBridge.updateDeviceIpInfo(previousDeviceIpInfo)
        }
    }

    @Test
    fun `device ip bridge rejects first active tunnel address when real device ip is unknown`() {
        val previousDeviceIpInfo = FoxholeVpnRuntimeBridge.deviceIpInfo.value
        val tunnelIp = ipInfo("203.0.113.10")
        try {
            FoxholeVpnRuntimeBridge.updateDeviceIpInfo(null)

            val accepted =
                FoxholeVpnRuntimeBridge.updateDeviceIpInfo(
                    value = tunnelIp,
                    allowNewAddress = false,
                )

            assertFalse(accepted)
            assertNull(FoxholeVpnRuntimeBridge.deviceIpInfo.value)
        } finally {
            FoxholeVpnRuntimeBridge.updateDeviceIpInfo(previousDeviceIpInfo)
        }
    }

    @Test
    fun `device ip bridge keeps pre vpn device ip when active tunnel address differs`() {
        val previousDeviceIpInfo = FoxholeVpnRuntimeBridge.deviceIpInfo.value
        val deviceIp = ipInfo("198.51.100.20")
        val tunnelIp = ipInfo("203.0.113.10")
        try {
            FoxholeVpnRuntimeBridge.updateDeviceIpInfo(deviceIp)

            val accepted =
                FoxholeVpnRuntimeBridge.updateDeviceIpInfo(
                    value = tunnelIp,
                    allowNewAddress = false,
                )

            assertFalse(accepted)
            assertEquals(deviceIp, FoxholeVpnRuntimeBridge.deviceIpInfo.value)
        } finally {
            FoxholeVpnRuntimeBridge.updateDeviceIpInfo(previousDeviceIpInfo)
        }
    }

    private fun ipInfo(address: String): IpInfo =
        IpInfo(
            ip = address,
            ipv4 = address,
            countryCode = "NL",
            countryName = "Netherlands",
            city = "Amsterdam",
            isp = "Example",
            fetchedAt = 1_000L,
        )
}
