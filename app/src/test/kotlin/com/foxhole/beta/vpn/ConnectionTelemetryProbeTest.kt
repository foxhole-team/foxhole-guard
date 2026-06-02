package com.foxhole.beta.vpn

import com.foxhole.beta.core.network.PublicDnsFallback
import org.junit.Assert.assertEquals
import org.junit.Test
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
}
