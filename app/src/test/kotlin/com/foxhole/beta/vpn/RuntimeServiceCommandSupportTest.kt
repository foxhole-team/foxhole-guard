package com.foxhole.beta.vpn

import com.foxhole.beta.core.model.ConnectionSnapshot
import com.foxhole.beta.core.model.ConnectionState
import com.foxhole.beta.core.model.TrafficMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeServiceCommandSupportTest {
    @Test
    fun `user runtime transitions and teardown commands are priority commands`() {
        assertTrue(isPriorityRuntimeServiceCommand(FoxholeConnectionServiceContract.ACTION_CONNECT))
        assertTrue(isPriorityRuntimeServiceCommand(FoxholeConnectionServiceContract.ACTION_DISCONNECT))
        assertTrue(isPriorityRuntimeServiceCommand(FoxholeConnectionServiceContract.ACTION_KILL))
        assertTrue(isPriorityRuntimeServiceCommand(FoxholeConnectionServiceContract.ACTION_KILL_TOR))
        assertTrue(isPriorityRuntimeServiceCommand(FoxholeConnectionServiceContract.ACTION_START_LOCAL_GUARD))
    }

    @Test
    fun `background maintenance commands stay serialized`() {
        assertFalse(isPriorityRuntimeServiceCommand(FoxholeConnectionServiceContract.ACTION_RELOAD))
        assertFalse(isPriorityRuntimeServiceCommand(FoxholeConnectionServiceContract.ACTION_RESTORE))
        assertFalse(isPriorityRuntimeServiceCommand(null))
    }

    @Test
    fun `unknown commands fail closed but null lifecycle intents do not`() {
        assertFalse(isFailClosedRuntimeServiceCommand(null))
        assertTrue(isFailClosedRuntimeServiceCommand("com.foxhole.beta.action.UNKNOWN"))

        assertFalse(isFailClosedRuntimeServiceCommand(FoxholeConnectionServiceContract.ACTION_CONNECT))
        assertFalse(isFailClosedRuntimeServiceCommand(FoxholeConnectionServiceContract.ACTION_DISCONNECT))
        assertFalse(isFailClosedRuntimeServiceCommand(FoxholeConnectionServiceContract.ACTION_KILL))
        assertFalse(isFailClosedRuntimeServiceCommand(FoxholeConnectionServiceContract.ACTION_KILL_TOR))
        assertFalse(isFailClosedRuntimeServiceCommand(FoxholeConnectionServiceContract.ACTION_RELOAD))
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
        assertEquals(RuntimeCommandPriority.SWITCH, reload?.priority)
        assertEquals("reload:9", reload?.queueReason)

        assertEquals(RuntimeCommand.Restore(RuntimeCommandSource.SERVICE), restore)
        assertEquals(RuntimeCommandPriority.NORMAL, restore?.priority)
        assertEquals("restore", restore?.queueReason)

        assertEquals(RuntimeCommand.StartLocalGuard(LocalGuardMode.FIREWALL, RuntimeCommandSource.SERVICE), localGuard)
        assertEquals(RuntimeCommandPriority.SWITCH, localGuard?.priority)
        assertEquals("local_guard:firewall", localGuard?.queueReason)
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
    fun `unknown service action has no runtime command`() {
        assertNull(
            runtimeCommandForServiceAction(
                action = "com.foxhole.beta.action.UNKNOWN",
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
}
