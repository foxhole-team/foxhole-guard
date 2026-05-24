package com.foxhole.beta.vpn

import com.foxhole.beta.core.model.ConnectionSnapshot
import com.foxhole.beta.core.model.ConnectionState
import com.foxhole.beta.core.model.TrafficMode
import org.junit.Assert.assertFalse
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
