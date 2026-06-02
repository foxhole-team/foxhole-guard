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
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Socket

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
    fun `runtime bridge clears active server ping target with transient state`() {
        val target =
            ActiveServerPingTarget(
                profileId = 42L,
                protocolOptionId = "vless-1",
                target = VpnHealthProbeTarget("edge.example.com", 443, VpnHealthProbeTransport.TCP),
            )
        try {
            FoxholeVpnRuntimeBridge.updateActiveServerPingTarget(target)

            assertEquals(target, FoxholeVpnRuntimeBridge.activeServerPingTarget.value)

            FoxholeVpnRuntimeBridge.clearTransientState(clearIpInfo = false)

            assertNull(FoxholeVpnRuntimeBridge.activeServerPingTarget.value)
        } finally {
            FoxholeVpnRuntimeBridge.updateActiveServerPingTarget(null)
        }
    }

    @Test
    fun `runtime bridge protects direct server ping sockets through registered protector`() {
        val socket = Socket()
        var protectedSocket: Socket? = null
        try {
            FoxholeVpnRuntimeBridge.updateSocketProtector { candidate ->
                protectedSocket = candidate
                true
            }

            assertTrue(FoxholeVpnRuntimeBridge.protectDirectSocket(socket))
            assertSame(socket, protectedSocket)
        } finally {
            socket.close()
            FoxholeVpnRuntimeBridge.updateSocketProtector(null)
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
    fun `runtime ip update does not inherit location or ipv4 across different addresses`() {
        val previousIpInfo = FoxholeVpnRuntimeBridge.ipInfo.value
        val richDeviceIp =
            IpInfo(
                ip = "198.51.100.20",
                ipv4 = "198.51.100.20",
                countryCode = "US",
                countryName = "United States",
                city = "New York",
                isp = "Device ISP",
                fetchedAt = 1_000L,
            )
        val newRouteIp =
            IpInfo(
                ip = "2001:db8::10",
                ipv4 = null,
                ipv6 = "2001:db8::10",
                countryCode = "NL",
                countryName = "Netherlands",
                city = null,
                isp = null,
                fetchedAt = 2_000L,
            )
        try {
            FoxholeVpnRuntimeBridge.updateIpInfo(richDeviceIp)

            FoxholeVpnRuntimeBridge.updateIpInfo(newRouteIp)

            assertEquals(newRouteIp, FoxholeVpnRuntimeBridge.ipInfo.value)
        } finally {
            FoxholeVpnRuntimeBridge.updateIpInfo(previousIpInfo)
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
