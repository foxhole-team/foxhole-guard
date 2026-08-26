package com.foxhole.core.runtime

import com.foxhole.core.model.ConnectionSnapshot
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.IpInfo
import com.foxhole.core.model.TorNetworkPhase
import com.foxhole.core.model.TrafficMode
import com.foxhole.core.model.TrafficSnapshot
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
    fun `the tor phase follows an engaged session instead of sitting on offline forever`() {
        FoxholeVpnRuntimeBridge.update(ConnectionSnapshot(state = ConnectionState.IDLE))
        assertEquals(TorNetworkPhase.OFFLINE, FoxholeVpnRuntimeBridge.torPhase.value.phase)
        assertEquals(RuntimeTorUiState.Off, FoxholeVpnRuntimeBridge.runtimeUiState.value.tor)

        FoxholeVpnRuntimeBridge.update(
            ConnectionSnapshot(state = ConnectionState.CONNECTING, torActive = true, profileId = 7L),
        )
        assertEquals(TorNetworkPhase.CONNECTING, FoxholeVpnRuntimeBridge.torPhase.value.phase)

        FoxholeVpnRuntimeBridge.update(
            ConnectionSnapshot(state = ConnectionState.CONNECTED, torActive = true, profileId = 7L),
        )
        assertEquals(TorNetworkPhase.CONNECTED, FoxholeVpnRuntimeBridge.torPhase.value.phase)

        assertTrue(FoxholeVpnRuntimeBridge.runtimeUiState.value.tor is RuntimeTorUiState.Bootstrapping)
        FoxholeVpnRuntimeBridge.updateTorRouteIpInfo(ipInfo("203.0.113.9"))
        val ready = FoxholeVpnRuntimeBridge.runtimeUiState.value.tor
        assertTrue(ready is RuntimeTorUiState.Ready)
        assertEquals("203.0.113.9", (ready as RuntimeTorUiState.Ready).exit.ip)

        FoxholeVpnRuntimeBridge.update(ConnectionSnapshot(state = ConnectionState.IDLE))
        assertEquals(TorNetworkPhase.OFFLINE, FoxholeVpnRuntimeBridge.torPhase.value.phase)
        assertEquals(RuntimeTorUiState.Off, FoxholeVpnRuntimeBridge.runtimeUiState.value.tor)
    }

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
    fun `runtime bridge fails closed without direct socket protector`() {
        val socket = Socket()
        try {
            FoxholeVpnRuntimeBridge.updateSocketProtector(null)

            assertFalse(FoxholeVpnRuntimeBridge.protectDirectSocket(socket))
        } finally {
            socket.close()
            FoxholeVpnRuntimeBridge.updateSocketProtector(null)
        }
    }

    @Test
    fun `traffic updates do not republish runtime ui state`() {
        val previousSnapshot = FoxholeVpnRuntimeBridge.snapshot.value
        val previousTraffic = FoxholeVpnRuntimeBridge.traffic.value
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
            val runtimeUiState = FoxholeVpnRuntimeBridge.runtimeUiState.value

            FoxholeVpnRuntimeBridge.updateTraffic(
                TrafficSnapshot(
                    rxTotalBytes = 1_024L,
                    txTotalBytes = 2_048L,
                    rxBytesPerSec = 64L,
                    txBytesPerSec = 128L,
                    sampledAt = 1_000L,
                ),
            )

            assertEquals(1_024L, FoxholeVpnRuntimeBridge.traffic.value.rxTotalBytes)
            assertSame(runtimeUiState, FoxholeVpnRuntimeBridge.runtimeUiState.value)
        } finally {
            FoxholeVpnRuntimeBridge.updateTraffic(previousTraffic)
            FoxholeVpnRuntimeBridge.update(previousSnapshot, refreshLastChangeAt = false)
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

    @Test
    fun `stale mode error publication is rejected by the writer`() {
        FoxholeVpnRuntimeBridge.update(
            ConnectionSnapshot(state = ConnectionState.CONNECTED, trafficMode = TrafficMode.PROXY),
        )

        val accepted =
            FoxholeVpnRuntimeBridge.writer(TrafficMode.TUNNEL).update(
                ConnectionSnapshot(state = ConnectionState.ERROR, trafficMode = TrafficMode.TUNNEL),
            )

        assertFalse(accepted)
        assertEquals(ConnectionState.CONNECTED, FoxholeVpnRuntimeBridge.snapshot.value.state)
        assertEquals(TrafficMode.PROXY, FoxholeVpnRuntimeBridge.snapshot.value.trafficMode)
        FoxholeVpnRuntimeBridge.update(ConnectionSnapshot())
    }

    @Test
    fun `connecting takeover from another mode is accepted`() {
        FoxholeVpnRuntimeBridge.update(
            ConnectionSnapshot(state = ConnectionState.CONNECTED, trafficMode = TrafficMode.PROXY),
        )

        val accepted =
            FoxholeVpnRuntimeBridge.writer(TrafficMode.TUNNEL).update(
                ConnectionSnapshot(state = ConnectionState.CONNECTING, trafficMode = TrafficMode.TUNNEL),
            )

        assertTrue(accepted)
        assertEquals(ConnectionState.CONNECTING, FoxholeVpnRuntimeBridge.snapshot.value.state)
        assertEquals(TrafficMode.TUNNEL, FoxholeVpnRuntimeBridge.snapshot.value.trafficMode)
        FoxholeVpnRuntimeBridge.update(ConnectionSnapshot())
    }

    @Test
    fun `stale mode transient clear leaves the live mode ip untouched`() {
        FoxholeVpnRuntimeBridge.update(
            ConnectionSnapshot(state = ConnectionState.CONNECTED, trafficMode = TrafficMode.PROXY),
        )
        FoxholeVpnRuntimeBridge.updateIpInfo(
            IpInfo(ip = "203.0.113.9", countryCode = null, countryName = null, city = null, isp = null, fetchedAt = 1L)
        )

        val accepted = FoxholeVpnRuntimeBridge.writer(TrafficMode.TUNNEL).clearTransientState()

        assertFalse(accepted)
        assertEquals("203.0.113.9", FoxholeVpnRuntimeBridge.ipInfo.value?.ip)
        FoxholeVpnRuntimeBridge.clearTransientState()
        FoxholeVpnRuntimeBridge.update(ConnectionSnapshot())
    }

    @Test
    fun `matching mode writes pass through the writer`() {
        FoxholeVpnRuntimeBridge.update(
            ConnectionSnapshot(state = ConnectionState.CONNECTED, trafficMode = TrafficMode.TUNNEL),
        )

        val writer = FoxholeVpnRuntimeBridge.writer(TrafficMode.TUNNEL)

        assertTrue(writer.updateTraffic(TrafficSnapshot(rxBytesPerSec = 7L)))
        assertEquals(7L, FoxholeVpnRuntimeBridge.traffic.value.rxBytesPerSec)
        assertTrue(
            writer.updateIpInfo(
                IpInfo(
                    ip = "203.0.113.10",
                    countryCode = null,
                    countryName = null,
                    city = null,
                    isp = null,
                    fetchedAt = 2L
                )
            )
        )
        FoxholeVpnRuntimeBridge.clearTransientState()
        FoxholeVpnRuntimeBridge.update(ConnectionSnapshot())
    }

    @Test
    fun `older same mode writer cannot publish after a newer session takes ownership`() {
        val oldWriter = FoxholeVpnRuntimeBridge.writer(TrafficMode.TUNNEL)
        assertTrue(
            oldWriter.update(
                ConnectionSnapshot(
                    state = ConnectionState.CONNECTING,
                    trafficMode = TrafficMode.TUNNEL,
                    profileId = 1L,
                ),
            ),
        )

        val currentWriter = FoxholeVpnRuntimeBridge.writer(TrafficMode.TUNNEL)
        assertTrue(
            currentWriter.update(
                ConnectionSnapshot(
                    state = ConnectionState.CONNECTING,
                    trafficMode = TrafficMode.TUNNEL,
                    profileId = 2L,
                ),
            ),
        )

        assertFalse(
            oldWriter.update(
                ConnectionSnapshot(
                    state = ConnectionState.ERROR,
                    trafficMode = TrafficMode.TUNNEL,
                    profileId = 1L,
                ),
            ),
        )
        assertFalse(oldWriter.updateTraffic(TrafficSnapshot(rxBytesPerSec = 99L)))
        assertEquals(ConnectionState.CONNECTING, FoxholeVpnRuntimeBridge.snapshot.value.state)
        assertEquals(2L, FoxholeVpnRuntimeBridge.snapshot.value.profileId)
        assertEquals(0L, FoxholeVpnRuntimeBridge.traffic.value.rxBytesPerSec)
        FoxholeVpnRuntimeBridge.update(ConnectionSnapshot())
    }

    @Test
    fun `delayed connecting from an older writer cannot reclaim after cross mode takeover`() {
        val delayedProxyWriter = FoxholeVpnRuntimeBridge.writer(TrafficMode.PROXY)
        val tunnelWriter = FoxholeVpnRuntimeBridge.writer(TrafficMode.TUNNEL)

        assertTrue(
            tunnelWriter.update(
                ConnectionSnapshot(
                    state = ConnectionState.CONNECTING,
                    trafficMode = TrafficMode.TUNNEL,
                    profileId = 2L,
                ),
            ),
        )

        assertFalse(
            delayedProxyWriter.update(
                ConnectionSnapshot(
                    state = ConnectionState.CONNECTING,
                    trafficMode = TrafficMode.PROXY,
                    profileId = 1L,
                ),
            ),
        )
        assertEquals(ConnectionState.CONNECTING, FoxholeVpnRuntimeBridge.snapshot.value.state)
        assertEquals(TrafficMode.TUNNEL, FoxholeVpnRuntimeBridge.snapshot.value.trafficMode)
        assertEquals(2L, FoxholeVpnRuntimeBridge.snapshot.value.profileId)
        FoxholeVpnRuntimeBridge.update(ConnectionSnapshot())
    }

    @Test
    fun `same mode control-plane transition keeps the live service writer valid`() {
        val writer = FoxholeVpnRuntimeBridge.writer(TrafficMode.TUNNEL)
        FoxholeVpnRuntimeBridge.update(
            ConnectionSnapshot(
                state = ConnectionState.CONNECTING,
                trafficMode = TrafficMode.TUNNEL,
                profileId = 41L,
            ),
        )

        assertTrue(
            writer.update(
                ConnectionSnapshot(
                    state = ConnectionState.CONNECTED,
                    trafficMode = TrafficMode.TUNNEL,
                    profileId = 41L,
                ),
            ),
        )
        assertEquals(ConnectionState.CONNECTED, FoxholeVpnRuntimeBridge.snapshot.value.state)
        FoxholeVpnRuntimeBridge.update(ConnectionSnapshot())
    }

    @Test
    fun `control-plane cross mode takeover fences the previous service writer immediately`() {
        val tunnelWriter = FoxholeVpnRuntimeBridge.writer(TrafficMode.TUNNEL)
        assertTrue(
            tunnelWriter.update(
                ConnectionSnapshot(
                    state = ConnectionState.CONNECTED,
                    trafficMode = TrafficMode.TUNNEL,
                    profileId = 41L,
                ),
            ),
        )

        FoxholeVpnRuntimeBridge.update(
            ConnectionSnapshot(
                state = ConnectionState.CONNECTING,
                trafficMode = TrafficMode.PROXY,
                profileId = 42L,
            ),
        )

        assertFalse(tunnelWriter.updateTraffic(TrafficSnapshot(rxBytesPerSec = 99L)))
        assertEquals(0L, FoxholeVpnRuntimeBridge.traffic.value.rxBytesPerSec)
        FoxholeVpnRuntimeBridge.update(ConnectionSnapshot())
    }

    @Test
    fun `writer minted after a fence survives interleaved clock activity`() {
        val fencedWriter = FoxholeVpnRuntimeBridge.writer(TrafficMode.TUNNEL)
        assertTrue(
            fencedWriter.update(
                ConnectionSnapshot(
                    state = ConnectionState.CONNECTING,
                    trafficMode = TrafficMode.TUNNEL,
                    profileId = 1L,
                ),
            ),
        )

        FoxholeVpnRuntimeBridge.update(ConnectionSnapshot())
        repeat(5) { RuntimeGenerationClock.next() }
        val freshWriter = FoxholeVpnRuntimeBridge.writer(TrafficMode.TUNNEL)

        assertFalse(
            fencedWriter.update(
                ConnectionSnapshot(
                    state = ConnectionState.ERROR,
                    trafficMode = TrafficMode.TUNNEL,
                    profileId = 1L,
                ),
            ),
        )
        assertTrue(
            freshWriter.update(
                ConnectionSnapshot(
                    state = ConnectionState.CONNECTING,
                    trafficMode = TrafficMode.TUNNEL,
                    profileId = 2L,
                ),
            ),
        )
        assertEquals(2L, FoxholeVpnRuntimeBridge.snapshot.value.profileId)
        FoxholeVpnRuntimeBridge.update(ConnectionSnapshot())
    }
}
