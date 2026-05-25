package com.foxhole.beta.vpn

import com.foxhole.beta.core.model.IpInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class RuntimeStateReducerTest {
    @Test
    fun `ip refresh keeps previous ip visible while loading`() {
        val previousIp = ipInfo("203.0.113.10")
        val ipState =
            readyTunnelIp(previousIp)
        val state =
            RuntimeUiState(generation = 7L).copy(
                ip = ipState,
            )

        val next =
            reduceRuntimeState(
                state,
                RuntimeEvent.IpRefreshRequested(
                    generation = 7L,
                    target = RuntimeIpRefreshTarget.TUNNEL,
                    reason = RuntimeIpRefreshReason.POST_CONNECT,
                ),
            )

        val panel = next.ip.tunnel as RuntimeIpPanelState.Loading
        assertEquals(previousIp, panel.previous)
        assertEquals(RuntimeIpRefreshReason.POST_CONNECT, panel.reason)
    }

    @Test
    fun `stale ip probe result is ignored by generation`() {
        val previousIp = ipInfo("203.0.113.10")
        val ipState =
            readyTunnelIp(previousIp)
        val state =
            RuntimeUiState(generation = 8L).copy(
                ip = ipState,
            )

        val next =
            reduceRuntimeState(
                state,
                RuntimeEvent.IpProbeFinished(
                    generation = 7L,
                    target = RuntimeIpRefreshTarget.TUNNEL,
                    result = Result.success(ipInfo("198.51.100.20")),
                ),
            )

        assertSame(state, next)
    }

    @Test
    fun `failed ip probe retains previous ip`() {
        val previousIp = ipInfo("203.0.113.10")
        val ipState =
            RuntimeIpState.empty().withPanel(
                RuntimeIpRefreshTarget.TUNNEL,
                RuntimeIpPanelState.Loading(
                    target = RuntimeIpRefreshTarget.TUNNEL,
                    previous = previousIp,
                    reason = RuntimeIpRefreshReason.MANUAL,
                ),
            )
        val loading =
            RuntimeUiState(generation = 9L).copy(
                ip = ipState,
            )

        val next =
            reduceRuntimeState(
                loading,
                RuntimeEvent.IpProbeFinished(
                    generation = 9L,
                    target = RuntimeIpRefreshTarget.TUNNEL,
                    result = Result.failure(IllegalStateException("probe failed")),
                ),
            )

        val panel = next.ip.tunnel as RuntimeIpPanelState.Failed
        assertEquals(previousIp, panel.previous)
        assertEquals("probe failed", panel.message)
    }

    private fun readyTunnelIp(ipInfo: IpInfo): RuntimeIpState =
        RuntimeIpState.empty().withPanel(
            RuntimeIpRefreshTarget.TUNNEL,
            RuntimeIpPanelState.Ready(
                target = RuntimeIpRefreshTarget.TUNNEL,
                info = ipInfo,
            ),
        )

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
