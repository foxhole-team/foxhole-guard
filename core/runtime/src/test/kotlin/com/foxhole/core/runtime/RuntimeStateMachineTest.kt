package com.foxhole.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeStateMachineTest {
    // Mirrors RuntimeSupervisor.beginTransition: mint from the shared clock, adopt on the machine.
    private fun RuntimeStateMachine.beginTestTransition(
        reason: String,
        phase: RuntimePhase,
    ): Long =
        RuntimeGenerationClock.next().also { generation ->
            adoptTransition(generation = generation, reason = reason, phase = phase)
        }

    @Test
    fun `transition advances generation and phase`() {
        val stateMachine = RuntimeStateMachine(diagnosticsLogger = null)

        val generation = stateMachine.beginTestTransition("connect", RuntimePhase.StartingNative)

        assertTrue(generation > 0L)
        assertEquals(generation, stateMachine.currentGeneration())
        assertEquals(generation, stateMachine.state.value.generation)
        assertEquals(RuntimePhase.StartingNative, stateMachine.state.value.phase)
    }

    @Test
    fun `current generation rejects stale transition owners`() {
        val stateMachine = RuntimeStateMachine(diagnosticsLogger = null)
        val first = stateMachine.beginTestTransition("connect", RuntimePhase.StartingNative)
        val second = stateMachine.beginTestTransition("disconnect", RuntimePhase.Stopping)

        assertFalse(stateMachine.isCurrentGeneration(first, owner = "connect_result"))
        assertTrue(stateMachine.isCurrentGeneration(second, owner = "disconnect_result"))
    }

    @Test
    fun `stale validation success cannot replace the active transition`() {
        val stateMachine = RuntimeStateMachine(diagnosticsLogger = null)
        val oldGeneration = stateMachine.beginTestTransition("connect", RuntimePhase.StartingNative)
        stateMachine.beginTestTransition("disconnect", RuntimePhase.Stopping)

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
        val generation = stateMachine.beginTestTransition("kill", RuntimePhase.Killing)

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
