package com.foxhole.beta.vpn

import com.foxhole.beta.core.model.ConnectionSnapshot
import com.foxhole.beta.core.model.ConnectionState
import com.foxhole.beta.core.model.LatencyProbeMethod
import com.foxhole.beta.core.model.Settings
import com.foxhole.beta.core.model.TrafficMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FoxholeConnectionControllerLatencyTest {
    @Test
    fun `latency probe endpoints use live neutral public https targets`() {
        assertEquals(
            FoxholeVpnService.CONNECTIVITY_PROBE_ENDPOINTS,
            latencyProbeEndpoints(),
        )
    }

    @Test
    fun `proxy validation probes lightweight endpoints before ip info endpoint`() {
        assertEquals(
            FoxholeVpnService.CONNECTIVITY_PROBE_ENDPOINTS + "https://ipwho.is/",
            proxyConnectivityProbeEndpoints(
                preferredEndpoint = "https://ipwho.is/",
                fallbackEndpoints = FoxholeVpnService.CONNECTIVITY_PROBE_ENDPOINTS,
            ),
        )
    }

    @Test
    fun `proxy validation does not duplicate preferred lightweight endpoint`() {
        assertEquals(
            FoxholeVpnService.CONNECTIVITY_PROBE_ENDPOINTS,
            proxyConnectivityProbeEndpoints(
                preferredEndpoint = "https://cp.cloudflare.com/generate_204",
                fallbackEndpoints = FoxholeVpnService.CONNECTIVITY_PROBE_ENDPOINTS,
            ),
        )
    }

    @Test
    fun `runtime validation uses responsive lightweight endpoint before fallback endpoints`() {
        assertEquals(
            listOf(
                "https://cp.cloudflare.com/generate_204",
                "https://www.gstatic.com/generate_204",
                "https://1.1.1.1/cdn-cgi/trace",
                "https://www.google.com/generate_204",
            ),
            runtimeValidationProbeEndpoints(
                fallbackEndpoints = FoxholeVpnService.CONNECTIVITY_PROBE_ENDPOINTS,
            ),
        )
    }

    @Test
    fun `runtime validation does not duplicate bootstrap fallback endpoint`() {
        assertEquals(
            listOf(
                "https://cp.cloudflare.com/generate_204",
                "https://www.gstatic.com/generate_204",
                "https://1.1.1.1/cdn-cgi/trace",
            ),
            runtimeValidationProbeEndpoints(
                fallbackEndpoints = listOf(
                    "https://cp.cloudflare.com/generate_204",
                    "https://www.gstatic.com/generate_204",
                ),
            ),
        )
    }

    @Test
    fun `representative latency is null when there are no successful probes`() {
        assertNull(representativeLatencyMs(emptyList()))
    }

    @Test
    fun `representative latency keeps a single successful probe`() {
        assertEquals(184L, representativeLatencyMs(listOf(184L)))
    }

    @Test
    fun `representative latency averages two successful probes`() {
        assertEquals(155L, representativeLatencyMs(listOf(130L, 180L)))
    }

    @Test
    fun `representative latency uses the median and ignores outlier spikes`() {
        assertEquals(122L, representativeLatencyMs(listOf(900L, 122L, 97L)))
    }

    @Test
    fun `tunnel latency uses configured probe method`() {
        assertEquals(
            LatencyProbeMethod.ICMP,
            effectiveLatencyProbeMethod(TrafficMode.TUNNEL, LatencyProbeMethod.ICMP),
        )
    }

    @Test
    fun `proxy latency keeps http probe path`() {
        assertEquals(
            LatencyProbeMethod.HTTP,
            effectiveLatencyProbeMethod(TrafficMode.PROXY, LatencyProbeMethod.ICMP),
        )
    }

    @Test
    fun `tunnel latency falls back to http and tcp when configured probe is blocked`() {
        assertEquals(
            listOf(LatencyProbeMethod.ICMP, LatencyProbeMethod.HTTP, LatencyProbeMethod.TCP),
            latencyProbeMethodOrder(TrafficMode.TUNNEL, LatencyProbeMethod.ICMP),
        )
    }

    @Test
    fun `proxy latency only uses http probe path`() {
        assertEquals(
            listOf(LatencyProbeMethod.HTTP),
            latencyProbeMethodOrder(TrafficMode.PROXY, LatencyProbeMethod.ICMP),
        )
    }

    @Test
    fun `strict tunnel latency uses runtime proxy http path`() {
        val snapshot =
            ConnectionSnapshot(
                state = ConnectionState.CONNECTED,
                trafficMode = TrafficMode.TUNNEL,
                profileId = 42L,
            )

        assertTrue(shouldUseRuntimeProxyForTunnelLatency(TrafficMode.TUNNEL, snapshot, Settings()))
        assertEquals(
            listOf(LatencyProbeMethod.HTTP),
            latencyProbeMethodOrder(
                trafficMode = TrafficMode.TUNNEL,
                configuredMethod = LatencyProbeMethod.ICMP,
                useRuntimeProxyForTunnel = true,
            ),
        )
    }

    @Test
    fun `idle tunnel latency does not use runtime proxy path`() {
        val snapshot =
            ConnectionSnapshot(
                state = ConnectionState.IDLE,
                trafficMode = TrafficMode.TUNNEL,
                profileId = 42L,
            )

        assertFalse(shouldUseRuntimeProxyForTunnelLatency(TrafficMode.TUNNEL, snapshot, Settings()))
    }

    @Test
    fun `icmp ping command uses bounded second timeout`() {
        assertEquals(
            listOf("/system/bin/ping", "-n", "-c", "1", "-W", "3", "cp.cloudflare.com"),
            icmpPingCommand(host = "cp.cloudflare.com", timeoutMs = 2_500L),
        )
    }

    @Test
    fun `icmp ping command binds to safe vpn interface when available`() {
        assertEquals(
            listOf("/system/bin/ping", "-n", "-c", "1", "-W", "3", "-I", "tun0", "cp.cloudflare.com"),
            icmpPingCommand(host = "cp.cloudflare.com", timeoutMs = 2_500L, interfaceName = "tun0"),
        )
    }

    @Test
    fun `icmp ping command ignores unsafe interface names`() {
        assertEquals(
            listOf("/system/bin/ping", "-n", "-c", "1", "-W", "3", "cp.cloudflare.com"),
            icmpPingCommand(host = "cp.cloudflare.com", timeoutMs = 2_500L, interfaceName = "tun0;id"),
        )
    }

    @Test
    fun `icmp ping latency parser accepts common android output`() {
        assertEquals(18L, parseIcmpPingLatencyMs("64 bytes from 1.1.1.1: icmp_seq=1 ttl=56 time=18.4 ms"))
        assertEquals(1L, parseIcmpPingLatencyMs("64 bytes from 1.1.1.1: icmp_seq=1 ttl=56 time<1 ms"))
        assertNull(parseIcmpPingLatencyMs("packet loss"))
    }
}
