package com.foxhole.core.runtime

import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.VpnSession
import com.foxhole.core.runtime.network.PublicDnsFallback
import com.foxhole.guard.runtime.TcpRuntimeReadinessResult
import com.foxhole.guard.runtime.activeServerPingTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.UnknownHostException

class ConnectionTelemetryProbeTest {
    @Test
    fun `server ping resolver falls back to public dns when upstream dns misses`() {
        val fallbackAddress = InetAddress.getByName("93.184.216.34")

        val addresses =
            resolveServerPingAddresses(
                host = "edge.example.com",
                primaryResolver = { throw UnknownHostException("upstream miss") },
                fallback = PublicDnsFallback { listOf(fallbackAddress) },
            )

        assertEquals(listOf(fallbackAddress), addresses)
    }

    @Test
    fun `server ping resolver prefers upstream public addresses`() {
        val upstreamAddress = InetAddress.getByName("93.184.216.34")
        val fallbackAddress = InetAddress.getByName("104.26.12.205")

        val addresses =
            resolveServerPingAddresses(
                host = "edge.example.com",
                primaryResolver = { listOf(upstreamAddress) },
                fallback = PublicDnsFallback { listOf(fallbackAddress) },
            )

        assertEquals(listOf(upstreamAddress), addresses)
    }

    @Test
    fun `active server ping target matches same profile and option`() {
        val active =
            ActiveServerPingTarget(
                profileId = 42L,
                protocolOptionId = "vless-1",
                target = VpnHealthProbeTarget("edge.example.com", 443, VpnHealthProbeTransport.TCP),
            )

        assertEquals(true, active.matchesRequest(42L, "vless-1"))
        assertEquals(false, active.matchesRequest(42L, "trojan-1"))
        assertEquals(false, active.matchesRequest(43L, "vless-1"))
    }

    @Test
    fun `active server ping target accepts blank requested option for single profile dashboards`() {
        val active =
            ActiveServerPingTarget(
                profileId = 42L,
                protocolOptionId = "vless-1",
                target = VpnHealthProbeTarget("edge.example.com", 443, VpnHealthProbeTransport.TCP),
            )

        assertEquals(true, active.matchesRequest(42L, null))
        assertEquals(true, active.matchesRequest(42L, ""))
    }

    @Test
    fun `readiness target carries resolved address but not stale dashboard latency`() {
        val address = InetAddress.getByName("93.184.216.34")
        val active =
            activeServerPingTarget(
                session = VpnSession(
                    profileId = 42L,
                    profileName = "Example",
                    protocolHint = ProtocolHint.VLESS,
                    protocolOptionId = "vless-1",
                    configJson = "{}",
                    correlationId = "test-session",
                ),
                target = VpnHealthProbeTarget("edge.example.com", 443, VpnHealthProbeTransport.TCP),
                readiness = TcpRuntimeReadinessResult(address = address, latencyMs = 123L),
            )

        assertNotNull(active)
        assertEquals(address, active?.resolvedAddress)
        assertEquals(true, active?.matchesRequest(42L, "vless-1"))
    }

    @Test
    fun `server ping address candidates keep readiness address before resolved fallbacks`() {
        val readinessAddress = InetAddress.getByName("93.184.216.34")
        val fallbackAddress = InetAddress.getByName("104.26.12.205")

        val addresses =
            serverPingAddressCandidates(
                resolvedAddress = readinessAddress,
                resolvedAddresses = listOf(fallbackAddress, readinessAddress),
            )

        assertEquals(listOf(readinessAddress, fallbackAddress), addresses)
    }

    @Test
    fun `server tcp ping refuses vpn network handle`() {
        assertEquals(
            true,
            shouldUseNetworkForDirectServerPing(upstreamNetworkHandle = 101L, vpnNetworkHandle = 202L),
        )
        assertEquals(
            false,
            shouldUseNetworkForDirectServerPing(upstreamNetworkHandle = 101L, vpnNetworkHandle = 101L),
        )
        assertEquals(
            false,
            shouldUseNetworkForDirectServerPing(upstreamNetworkHandle = null, vpnNetworkHandle = 101L),
        )
        assertEquals(
            true,
            shouldUseNetworkForDirectServerPing(upstreamNetworkHandle = 101L, vpnNetworkHandle = null),
        )
    }

    @Test
    fun `direct server ping falls back from upstream bind to protected direct socket`() {
        val source = testVpnSourceFile("ConnectionTelemetryProbe.kt").readText()
        val directServerPingBlock =
            source.substringAfter("private fun measureServerTcpConnectLatency(")
                .substringBefore("private class LatencyProbeAttempts")

        assertEquals(true, directServerPingBlock.contains("boundAttempt"))
        assertEquals(true, directServerPingBlock.contains("boundError !is IOException"))
        assertEquals(true, directServerPingBlock.contains("network = null"))
        assertEquals(true, directServerPingBlock.contains("Socket().use { socket ->"))
        assertEquals(true, directServerPingBlock.contains("check(protectDirectSocket(socket))"))
        assertEquals(true, directServerPingBlock.contains("if (network == null)"))
        assertEquals(true, directServerPingBlock.contains("network.bindSocket(socket)"))
        assertEquals(false, directServerPingBlock.contains("network?.socketFactory?.createSocket()"))
    }

    @Test
    fun `direct server ping protector has no fail open default`() {
        val probeSource = testVpnSourceFile("ConnectionTelemetryProbe.kt").readText()
        val controllerSource = testVpnSourceFile("FoxholeVpnRuntimeBridge.kt").readText()

        assertEquals(true, probeSource.contains("private val protectDirectSocket: (Socket) -> Boolean,"))
        assertEquals(false, probeSource.contains("protectDirectSocket: (Socket) -> Boolean = { true }"))
        assertEquals(false, probeSource.contains("protectDirectSocket: (Socket) -> Boolean = { false }"))
        assertEquals(true, controllerSource.contains("socketProtector?.invoke(socket) ?: false"))
        assertEquals(false, controllerSource.contains("socketProtector?.invoke(socket) ?: true"))
    }

    @Test
    fun `current vpn server ping can use protected direct socket without upstream network`() {
        val source = testVpnSourceFile("ConnectionTelemetryProbe.kt").readText()
        val currentServerPingBlock =
            source.substringAfter("suspend fun measureCurrentVpnServerPing(")
                .substringBefore("private fun measureServerTcpConnectLatency(")

        assertEquals(true, currentServerPingBlock.contains("val upstreamNetwork = currentUpstreamNetwork()"))
        assertEquals(true, currentServerPingBlock.contains("if (upstreamNetwork != null)"))
        assertEquals(true, currentServerPingBlock.contains("network = upstreamNetwork"))
        assertEquals(false, currentServerPingBlock.contains("error(\"upstream network unavailable\")"))
    }

    @Test
    fun `tcp readiness preflight uses protected raw socket before upstream bind`() {
        val source = testVpnSourceFile("FoxholeVpnServiceTcpReadinessSupport.kt").readText()
        val preflightBlock =
            source.substringAfter("val probeResult = runCatching")
                .substringBefore("TcpRuntimeReadinessResult(")

        assertEquals(true, preflightBlock.contains("Socket().use { socket ->"))
        assertEquals(true, preflightBlock.contains("upstreamNetwork.bindSocket(socket)"))
        assertEquals(false, preflightBlock.contains("check(protect(socket))"))
        assertEquals(false, preflightBlock.contains("upstreamNetwork.socketFactory.createSocket()"))
    }

    private fun testVpnSourceFile(name: String): File =
        listOf(
            File("src/main/kotlin/com/foxhole/core/runtime/$name"),
            File("../core/runtime/src/main/kotlin/com/foxhole/core/runtime/$name"),
            File("src/main/kotlin/com/foxhole/guard/runtime/$name"),
            File("app/src/main/kotlin/com/foxhole/core/runtime/$name"),
            File("../core/runtime/src/main/kotlin/com/foxhole/core/runtime/$name"),
            File("app/src/main/kotlin/com/foxhole/guard/runtime/$name"),
            File("../app/src/main/kotlin/com/foxhole/core/runtime/$name"),
            File("../core/runtime/src/main/kotlin/com/foxhole/core/runtime/$name"),
            File("../app/src/main/kotlin/com/foxhole/guard/runtime/$name"),
        ).first { file -> file.isFile }
}
