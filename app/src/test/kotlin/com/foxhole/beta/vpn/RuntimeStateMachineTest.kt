package com.foxhole.beta.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeStateMachineTest {
    @Test
    fun `transition advances generation and phase`() {
        val stateMachine = RuntimeStateMachine(diagnosticsLogger = null)

        val generation = stateMachine.beginTransition("connect", RuntimePhase.StartingNative)

        assertEquals(1L, generation)
        assertEquals(1L, stateMachine.currentGeneration())
        assertEquals(1L, stateMachine.state.value.generation)
        assertEquals(RuntimePhase.StartingNative, stateMachine.state.value.phase)
    }

    @Test
    fun `current generation rejects stale transition owners`() {
        val stateMachine = RuntimeStateMachine(diagnosticsLogger = null)
        val first = stateMachine.beginTransition("connect", RuntimePhase.StartingNative)
        val second = stateMachine.beginTransition("disconnect", RuntimePhase.Stopping)

        assertFalse(stateMachine.isCurrentGeneration(first, owner = "connect_result"))
        assertTrue(stateMachine.isCurrentGeneration(second, owner = "disconnect_result"))
    }

    @Test
    fun `stale validation success cannot replace the active transition`() {
        val stateMachine = RuntimeStateMachine(diagnosticsLogger = null)
        val oldGeneration = stateMachine.beginTransition("connect", RuntimePhase.StartingNative)
        stateMachine.beginTransition("disconnect", RuntimePhase.Stopping)

        stateMachine.dispatch(
            RuntimeEvent.ValidationSucceeded(
                generation = oldGeneration,
                sessionId = "old-session",
                vpnNetworkHandle = 42L,
            ),
        )

        assertEquals(RuntimePhase.Stopping, stateMachine.state.value.phase)
        assertEquals(null, stateMachine.state.value.sessionId)
        assertEquals(null, stateMachine.state.value.network.vpnNetworkHandle)
    }

    @Test
    fun `cleanup unresolved becomes fatal error UI state`() {
        val stateMachine = RuntimeStateMachine(diagnosticsLogger = null)
        val generation = stateMachine.beginTransition("kill", RuntimePhase.Killing)

        stateMachine.dispatch(
            RuntimeEvent.NativeCleanupUnresolved(
                generation = generation,
                message = "Runtime requires app restart",
            ),
        )

        val error = RuntimeErrorUi("Runtime requires app restart")
        assertEquals(RuntimePhase.Error(error), stateMachine.state.value.phase)
        assertEquals(error, stateMachine.state.value.error)
    }
}
