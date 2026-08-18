package com.foxhole.core.runtime

import android.net.VpnService
import com.foxhole.core.model.ConnectionSnapshot
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.TrafficMode
import com.foxhole.guard.runtime.FoxholeConnectionServiceContract
import com.foxhole.guard.runtime.FoxholeVpnService
import com.foxhole.guard.runtime.isFailClosedRuntimeServiceCommand
import com.foxhole.guard.runtime.runtimeCommandForServiceAction
import com.foxhole.guard.runtime.shouldSkipRestoreForActiveProfileRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RuntimeServiceCommandSupportTest {
    @Test
    fun `unknown commands fail closed but null lifecycle intents do not`() {
        assertFalse(isFailClosedRuntimeServiceCommand(null))
        assertFalse(isFailClosedRuntimeServiceCommand(VpnService.SERVICE_INTERFACE))
        assertTrue(isFailClosedRuntimeServiceCommand("com.foxhole.guard.action.UNKNOWN"))

        assertNull(
            runtimeCommandForServiceAction(
                action = VpnService.SERVICE_INTERFACE,
                trafficMode = TrafficMode.TUNNEL,
            ),
        )
        assertFalse(isFailClosedRuntimeServiceCommand(FoxholeConnectionServiceContract.ACTION_CONNECT))
        assertFalse(isFailClosedRuntimeServiceCommand(FoxholeConnectionServiceContract.ACTION_DISCONNECT))
        assertFalse(isFailClosedRuntimeServiceCommand(FoxholeConnectionServiceContract.ACTION_KILL))
        assertFalse(isFailClosedRuntimeServiceCommand(FoxholeConnectionServiceContract.ACTION_KILL_TOR))
        assertFalse(isFailClosedRuntimeServiceCommand(FoxholeConnectionServiceContract.ACTION_RELOAD))
        assertFalse(isFailClosedRuntimeServiceCommand(FoxholeConnectionServiceContract.ACTION_ENFORCE_QUARANTINE))
        assertFalse(isFailClosedRuntimeServiceCommand(FoxholeConnectionServiceContract.ACTION_RESTORE))
        assertFalse(isFailClosedRuntimeServiceCommand(FoxholeConnectionServiceContract.ACTION_START_LOCAL_GUARD))
    }

    @Test
    fun `service connect maps to typed tunnel command`() {
        val command =
            runtimeCommandForServiceAction(
                action = FoxholeConnectionServiceContract.ACTION_CONNECT,
                trafficMode = TrafficMode.TUNNEL,
                profileId = 7L,
                protocolOptionId = "hysteria2-main",
                previousVpnNetworkHandle = 42L,
            )

        assertEquals(
            RuntimeCommand.StartTunnel(
                profileId = 7L,
                optionId = "hysteria2-main",
                previousVpnNetworkHandle = 42L,
                source = RuntimeCommandSource.SERVICE,
            ),
            command,
        )
        assertEquals(RuntimeCommandPriority.SWITCH, command?.priority)
        assertEquals("connect:7:hysteria2-main", command?.queueReason)
    }

    @Test
    fun `service connect maps proxy service to proxy command`() {
        val command =
            runtimeCommandForServiceAction(
                action = FoxholeConnectionServiceContract.ACTION_CONNECT,
                trafficMode = TrafficMode.PROXY,
                profileId = 8L,
                protocolOptionId = null,
                previousVpnNetworkHandle = 55L,
            )

        assertEquals(
            RuntimeCommand.StartProxy(
                profileId = 8L,
                optionId = null,
                source = RuntimeCommandSource.SERVICE,
            ),
            command,
        )
        assertEquals(RuntimeCommandPriority.SWITCH, command?.priority)
        assertEquals("proxy:8:default", command?.queueReason)
    }

    @Test
    fun `prepared subscription connect cannot coalesce with a mandatory refresh connect`() {
        val ordinary =
            runtimeCommandForServiceAction(
                action = FoxholeConnectionServiceContract.ACTION_CONNECT,
                trafficMode = TrafficMode.TUNNEL,
                profileId = 7L,
                protocolOptionId = "vless",
            )
        val prepared =
            runtimeCommandForServiceAction(
                action = FoxholeConnectionServiceContract.ACTION_CONNECT,
                trafficMode = TrafficMode.TUNNEL,
                profileId = 7L,
                protocolOptionId = "vless",
                subscriptionRefreshPrepared = true,
            )

        assertEquals("connect:7:vless", ordinary?.queueReason)
        assertEquals("connect:7:vless:subscription_prepared", prepared?.queueReason)
        assertTrue((prepared as RuntimeCommand.StartTunnel).subscriptionRefreshPrepared)
    }

    @Test
    fun `protocol test connect has an isolated fail closed queue identity`() {
        val command =
            runtimeCommandForServiceAction(
                action = FoxholeConnectionServiceContract.ACTION_CONNECT,
                trafficMode = TrafficMode.TUNNEL,
                profileId = 7L,
                protocolOptionId = "wireguard",
                protocolTestTrafficFreeze = true,
                replaceActiveTunnel = true,
            ) as RuntimeCommand.StartTunnel

        assertTrue(command.protocolTestTrafficFreeze)
        assertTrue(command.replaceActiveTunnel)
        assertEquals(
            "connect:7:wireguard:protocol_test_freeze:replace_active",
            command.queueReason,
        )
    }

    @Test
    fun `service stop reload restore and local guard map to runtime commands`() {
        val disconnect =
            runtimeCommandForServiceAction(
                action = FoxholeConnectionServiceContract.ACTION_DISCONNECT,
                trafficMode = TrafficMode.TUNNEL,
            )
        val reload =
            runtimeCommandForServiceAction(
                action = FoxholeConnectionServiceContract.ACTION_RELOAD,
                trafficMode = TrafficMode.TUNNEL,
                profileId = 9L,
            )
        val restore =
            runtimeCommandForServiceAction(
                action = FoxholeConnectionServiceContract.ACTION_RESTORE,
                trafficMode = TrafficMode.TUNNEL,
            )
        val enforceQuarantine =
            runtimeCommandForServiceAction(
                action = FoxholeConnectionServiceContract.ACTION_ENFORCE_QUARANTINE,
                trafficMode = TrafficMode.TUNNEL,
                quarantinePolicyRevision = 12L,
            )
        val localGuard =
            runtimeCommandForServiceAction(
                action = FoxholeConnectionServiceContract.ACTION_START_LOCAL_GUARD,
                trafficMode = TrafficMode.TUNNEL,
                localGuardMode = LocalGuardMode.FIREWALL,
            )

        assertEquals(RuntimeCommand.Stop("disconnect", RuntimeCommandSource.SERVICE), disconnect)
        assertEquals(RuntimeCommandPriority.USER_STOP, disconnect?.priority)
        assertEquals("disconnect", disconnect?.queueReason)

        assertEquals(RuntimeCommand.Reload("9", RuntimeCommandSource.SERVICE), reload)
        assertEquals(RuntimeCommandPriority.NORMAL, reload?.priority)
        assertEquals("reload:9", reload?.queueReason)

        assertEquals(
            RuntimeCommand.EnforceQuarantine(12L, RuntimeCommandSource.SERVICE),
            enforceQuarantine,
        )
        assertEquals(RuntimeCommandPriority.NORMAL, enforceQuarantine?.priority)
        assertEquals("enforce_quarantine", enforceQuarantine?.queueReason)

        assertEquals(RuntimeCommand.Restore(RuntimeCommandSource.SERVICE), restore)
        assertEquals(RuntimeCommandPriority.NORMAL, restore?.priority)
        assertEquals("restore", restore?.queueReason)

        assertEquals(RuntimeCommand.StartLocalGuard(LocalGuardMode.FIREWALL, RuntimeCommandSource.SERVICE), localGuard)
        assertEquals(RuntimeCommandPriority.SWITCH, localGuard?.priority)
        assertEquals("local_guard:firewall:cfg=0", localGuard?.queueReason)
    }

    @Test
    fun `duplicate restore is skipped once a profile runtime is active`() {
        assertTrue(shouldSkipRestoreForActiveProfileRuntime(activeProfileSessionPresent = true))
        assertFalse(shouldSkipRestoreForActiveProfileRuntime(activeProfileSessionPresent = false))
    }

    @Test
    fun `local guard start is deferred while a profile runtime is active`() {
        listOf(
            ConnectionState.CONNECTING,
            ConnectionState.CONNECTED,
            ConnectionState.RECONNECTING,
        ).forEach { state ->
            assertTrue(
                shouldDeferLocalGuardStartForActiveProfileRuntime(
                    snapshot =
                    ConnectionSnapshot(
                        state = state,
                        trafficMode = TrafficMode.TUNNEL,
                        profileId = 7L,
                    ),
                    activeProfileSessionPresent = false,
                ),
            )
        }
    }

    @Test
    fun `local guard start is not deferred while a tor-only runtime is active`() {
        listOf(
            ConnectionState.CONNECTING,
            ConnectionState.CONNECTED,
            ConnectionState.RECONNECTING,
        ).forEach { state ->
            assertFalse(
                shouldDeferLocalGuardStartForActiveProfileRuntime(
                    snapshot =
                    ConnectionSnapshot(
                        state = state,
                        trafficMode = TrafficMode.TUNNEL,
                        profileId = FoxholeVpnService.TOR_ONLY_PROFILE_ID,
                    ),
                    activeProfileSessionPresent = false,
                ),
            )
        }
    }

    @Test
    fun `local guard start is not deferred for idle or existing local guard runtime`() {
        assertFalse(
            shouldDeferLocalGuardStartForActiveProfileRuntime(
                snapshot =
                ConnectionSnapshot(
                    state = ConnectionState.IDLE,
                    trafficMode = TrafficMode.TUNNEL,
                    profileId = 7L,
                ),
                activeProfileSessionPresent = false,
            ),
        )
        assertFalse(
            shouldDeferLocalGuardStartForActiveProfileRuntime(
                snapshot =
                ConnectionSnapshot(
                    state = ConnectionState.CONNECTED,
                    trafficMode = TrafficMode.TUNNEL,
                    profileId = FoxholeVpnService.LOCAL_GUARD_PROFILE_ID,
                ),
                activeProfileSessionPresent = false,
            ),
        )
    }

    @Test
    fun `disabling local guard does not stop an active profile runtime`() {
        assertFalse(
            shouldStopRuntimeAfterLocalGuardDisabled(
                ConnectionSnapshot(
                    state = ConnectionState.CONNECTED,
                    trafficMode = TrafficMode.TUNNEL,
                    profileId = 7L,
                ),
            ),
        )
        assertFalse(
            shouldStopRuntimeAfterLocalGuardDisabled(
                ConnectionSnapshot(
                    state = ConnectionState.IDLE,
                    trafficMode = TrafficMode.TUNNEL,
                    profileId = FoxholeVpnService.LOCAL_GUARD_PROFILE_ID,
                ),
            ),
        )
        assertTrue(
            shouldStopRuntimeAfterLocalGuardDisabled(
                ConnectionSnapshot(
                    state = ConnectionState.CONNECTED,
                    trafficMode = TrafficMode.TUNNEL,
                    profileId = FoxholeVpnService.LOCAL_GUARD_PROFILE_ID,
                ),
            ),
        )
        assertTrue(
            shouldStopRuntimeAfterLocalGuardDisabled(
                ConnectionSnapshot(
                    state = ConnectionState.ERROR,
                    trafficMode = TrafficMode.TUNNEL,
                    profileId = FoxholeVpnService.LOCAL_GUARD_PROFILE_ID,
                ),
            ),
        )
    }

    @Test
    fun `local guard start is deferred when the service still owns a profile session`() {
        assertTrue(
            shouldDeferLocalGuardStartForActiveProfileRuntime(
                snapshot =
                ConnectionSnapshot(
                    state = ConnectionState.IDLE,
                    trafficMode = TrafficMode.TUNNEL,
                    profileId = null,
                ),
                activeProfileSessionPresent = true,
            ),
        )
    }

    @Test
    fun `profile snapshot without a live owned vpn cannot defer local guard repair`() {
        assertFalse(
            shouldDeferLocalGuardStartForActiveProfileRuntime(
                snapshot =
                ConnectionSnapshot(
                    state = ConnectionState.CONNECTED,
                    trafficMode = TrafficMode.TUNNEL,
                    profileId = 7L,
                ),
                activeProfileSessionPresent = false,
                activeProfileVpnNetworkPresent = false,
            ),
        )
    }

    @Test
    fun `unknown service action has no runtime command`() {
        assertNull(
            runtimeCommandForServiceAction(
                action = "com.foxhole.guard.action.UNKNOWN",
                trafficMode = TrafficMode.TUNNEL,
            ),
        )
    }

    @Test
    fun `runtime service ownership follows active snapshot mode`() {
        val proxySnapshot =
            ConnectionSnapshot(
                state = ConnectionState.CONNECTED,
                trafficMode = TrafficMode.PROXY,
            )

        assertTrue(proxySnapshot.isActiveRuntimeFor(TrafficMode.PROXY))
        assertFalse(proxySnapshot.isActiveRuntimeFor(TrafficMode.TUNNEL))
        assertTrue(proxySnapshot.isActiveRuntimeForAnotherMode(TrafficMode.TUNNEL))
    }

    @Test
    fun `dashboard stop tor uses dedicated kill tor service action`() {
        val toggleSource = sourceFile("guard/ui/HomeViewModelConnectionToggleSupport.kt").readText()
        val controllerSource = sourceFile("guard/runtime/FoxholeConnectionController.kt").readText()
        val lifecycleSource = sourceFile("guard/runtime/FoxholeConnectionLifecycle.kt").readText()

        assertTrue(toggleSource.contains("toggleTorOnlyRuntimeConnection(state)"))
        assertTrue(toggleSource.contains("state.connection.profileId == FoxholeVpnService.TOR_ONLY_PROFILE_ID"))
        assertTrue(toggleSource.contains("state.torOperation.active && !state.connection.isPrimaryConnectionRuntime()"))
        assertTrue(toggleSource.contains("container.connectionController.disconnectTorOnly()"))
        assertTrue(controllerSource.contains("fun disconnectTorOnly(userInitiated: Boolean = true)"))
        assertTrue(lifecycleSource.contains("fun disconnectTorOnly(userInitiated: Boolean = true)"))
        assertTrue(lifecycleSource.contains("action = FoxholeConnectionServiceContract.ACTION_KILL_TOR"))
        assertTrue(lifecycleSource.contains("suppressLocalGuard = true"))
    }

    private fun sourceFile(relativePath: String): File =
        listOf(
            File("src/main/kotlin/com/foxhole/$relativePath"),
            File("app/src/main/kotlin/com/foxhole/$relativePath"),
            File("../app/src/main/kotlin/com/foxhole/$relativePath"),
        ).first(File::isFile)
}
