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

    @Test
    fun `runtime lifecycle events advance tunnel phases`() {
        val generation = 11L
        val starting = RuntimeUiState(generation = generation, phase = RuntimePhase.StartingNative)

        val nativeStarted =
            reduceRuntimeState(
                starting,
                RuntimeEvent.NativeStarted(generation = generation, sessionId = "session-1"),
            )
        assertEquals(RuntimePhase.WaitingVpnNetwork, nativeStarted.phase)
        assertEquals("session-1", nativeStarted.sessionId)

        val vpnAvailable =
            reduceRuntimeState(
                nativeStarted,
                RuntimeEvent.VpnNetworkAvailable(generation = generation, networkHandle = 42L),
            )
        assertEquals(RuntimePhase.ValidatingTunnel, vpnAvailable.phase)
        assertEquals(42L, vpnAvailable.network.vpnNetworkHandle)

        val connected =
            reduceRuntimeState(
                vpnAvailable,
                RuntimeEvent.ValidationSucceeded(
                    generation = generation,
                    sessionId = "session-1",
                    vpnNetworkHandle = 42L,
                ),
            )
        assertEquals(RuntimePhase.Connected, connected.phase)
        assertEquals(null, connected.error)
    }

    @Test
    fun `cleanup unresolved becomes safe error state`() {
        val state = RuntimeUiState(generation = 12L, phase = RuntimePhase.Killing)

        val next =
            reduceRuntimeState(
                state,
                RuntimeEvent.NativeCleanupUnresolved(
                    generation = 12L,
                    message = "Runtime requires app restart",
                ),
            )

        assertEquals(RuntimePhase.Error(RuntimeErrorUi("Runtime requires app restart")), next.phase)
        assertEquals(RuntimeErrorUi("Runtime requires app restart"), next.error)
    }

    @Test
    fun `stale lifecycle events are ignored`() {
        val state = RuntimeUiState(generation = 13L, phase = RuntimePhase.Connected)

        val next =
            reduceRuntimeState(
                state,
                RuntimeEvent.ValidationFailed(
                    generation = 12L,
                    sessionId = "old",
                    message = "old failure",
                ),
            )

        assertSame(state, next)
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
