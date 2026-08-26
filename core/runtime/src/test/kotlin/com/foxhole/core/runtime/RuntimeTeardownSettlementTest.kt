package com.foxhole.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeTeardownSettlementTest {
    @Test
    fun `a released handle settles independently of the master descriptor`() {
        assertEquals(
            TeardownSettlement.RELEASED_WITH_TUN_OPEN,
            settleTeardown(nativeReleased = true, masterTunClosed = false),
        )
    }

    @Test
    fun `a clean teardown is released`() {
        assertEquals(
            TeardownSettlement.RELEASED,
            settleTeardown(nativeReleased = true, masterTunClosed = true),
        )
    }

    @Test
    fun `a native generation that was not released still blocks a restart`() {
        assertEquals(
            TeardownSettlement.NOT_RELEASED,
            settleTeardown(nativeReleased = false, masterTunClosed = true),
        )
        assertEquals(
            TeardownSettlement.NOT_RELEASED,
            settleTeardown(nativeReleased = false, masterTunClosed = false),
        )
    }

    @Test
    fun `force stop timeout releases the handle but poisons the process`() {
        val outcome =
            nativeForceStopOutcome(
                callCompleted = true,
                code = FoxholeNativeEngine.STOP_TIMED_OUT,
            )

        assertEquals(NativeForceStopOutcome.QUARANTINED, outcome)
        assertTrue(outcome.handleReleased)
        assertTrue(outcome.processPoisoned)
    }

    @Test
    fun `ordinary force stop releases the handle without poisoning the process`() {
        listOf(
            FoxholeNativeEngine.STOPPED,
            FoxholeNativeEngine.ALREADY_STOPPED,
            FoxholeNativeEngine.STOP_UNKNOWN_HANDLE,
        ).forEach { code ->
            val outcome = nativeForceStopOutcome(callCompleted = true, code = code)
            assertEquals(NativeForceStopOutcome.RELEASED, outcome)
            assertTrue(outcome.handleReleased)
            assertFalse(outcome.processPoisoned)
        }
    }

    @Test
    fun `panicked and non returning force stops poison without releasing the handle`() {
        val failed =
            nativeForceStopOutcome(
                callCompleted = true,
                code = FoxholeNativeEngine.STOP_PANICKED,
            )
        val timedOut =
            nativeForceStopOutcome(
                callCompleted = false,
                code = FoxholeNativeEngine.STOPPED,
            )

        assertEquals(NativeForceStopOutcome.FAILED, failed)
        assertEquals(NativeForceStopOutcome.CALL_TIMED_OUT, timedOut)
        assertFalse(failed.handleReleased)
        assertFalse(timedOut.handleReleased)
        assertTrue(failed.processPoisoned)
        assertTrue(timedOut.processPoisoned)
    }
}
