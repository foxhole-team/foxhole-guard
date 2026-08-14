package com.foxhole.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeTeardownSettlementTest {
    @Test
    fun `a released runtime frees the app to reconnect even when the master descriptor is still open`() {
        // The defect this pins: requiring both facts made a transient one
        // permanent. A quarantined native worker keeps its own duplicate of the
        // TUN descriptor, so the probe reads OPEN after a perfectly successful
        // kill — and the app then refused every later connect with
        // ALREADY_RUNNING until the process was killed.
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
        // The other half of the invariant: a closed master descriptor must never
        // be read as permission to start a second engine over a live one.
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
    fun `force kill releases the handle on timeout because the core drops it first`() {
        // `nativeForceKill` removes the registry entry before it can report a
        // timeout, and documents that the app may then treat the handle as gone.
        assertTrue(forceKillReleasedHandle(FoxholeNativeEngine.STOP_TIMED_OUT))
        assertTrue(forceKillReleasedHandle(FoxholeNativeEngine.STOPPED))
        assertTrue(forceKillReleasedHandle(FoxholeNativeEngine.ALREADY_STOPPED))
        assertTrue(forceKillReleasedHandle(FoxholeNativeEngine.STOP_UNKNOWN_HANDLE))
    }

    @Test
    fun `a panicked force kill never counts as a released handle`() {
        // That call did not reach the point where the registry entry is removed,
        // so the handle is still the app's problem.
        assertFalse(forceKillReleasedHandle(FoxholeNativeEngine.STOP_PANICKED))
    }
}
