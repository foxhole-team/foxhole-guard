package com.foxhole.guard.ui.cli

import com.foxhole.core.model.ConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CliConnectionHapticsTest {
    @Test
    fun `successful connection emits one positive edge`() {
        var tracker = CliConnectionHapticTracker()
        tracker = tracker.step(ConnectionState.IDLE, expected = null)
        tracker = tracker.step(ConnectionState.CONNECTING, expected = null)
        tracker = tracker.step(ConnectionState.CONNECTED, expected = CliConnectionHapticEvent.CONNECTED)
        tracker.step(ConnectionState.CONNECTED, expected = null)
    }

    @Test
    fun `reconnect does not fake a disconnect or repeat success`() {
        var tracker = CliConnectionHapticTracker(initialized = true, connectedSession = true)
        tracker = tracker.step(ConnectionState.RECONNECTING, expected = null)
        tracker.step(ConnectionState.CONNECTED, expected = null)
    }

    @Test
    fun `idle and error after a live session emit the distinct negative edge`() {
        val connected = CliConnectionHapticTracker(initialized = true, connectedSession = true)
        connected.step(ConnectionState.IDLE, expected = CliConnectionHapticEvent.DISCONNECTED)
        connected.step(ConnectionState.ERROR, expected = CliConnectionHapticEvent.DISCONNECTED)
    }

    private fun CliConnectionHapticTracker.step(
        state: ConnectionState,
        expected: CliConnectionHapticEvent?,
    ): CliConnectionHapticTracker {
        val (next, event) = next(state)
        if (expected == null) {
            assertNull(event)
        } else {
            assertEquals(expected, event)
        }
        return next
    }
}
