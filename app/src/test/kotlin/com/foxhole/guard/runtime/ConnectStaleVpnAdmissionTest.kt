package com.foxhole.guard.runtime

import com.foxhole.core.model.ConnectionState
import com.foxhole.core.runtime.NativeRuntimeSnapshot
import com.foxhole.core.runtime.RuntimeCommandQueueSnapshot
import com.foxhole.core.runtime.RuntimeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ConnectStaleVpnAdmissionTest {
    @Test
    fun `a positively observed foreign descriptor is still released before connect`() {
        assertTrue(
            shouldReleaseStaleVpnTunnelBeforeConnect(
                activeVpnNetwork = true,
                tunDescriptorProbe = ProcessTunDescriptorProbe.OPEN,
            ),
        )
    }

    @Test
    fun `the master descriptor this process holds on purpose is not a stale tunnel`() {
        assertFalse(
            shouldReleaseStaleVpnTunnelBeforeConnect(
                activeVpnNetwork = true,
                tunDescriptorProbe = ProcessTunDescriptorProbe.CLOSED,
            ),
        )
    }

    @Test
    fun `an unreadable proc self fd cannot invent a stale tunnel`() {
        assertFalse(
            shouldReleaseStaleVpnTunnelBeforeConnect(
                activeVpnNetwork = true,
                tunDescriptorProbe = ProcessTunDescriptorProbe.UNKNOWN,
            ),
        )
    }

    @Test
    fun `no vpn network means nothing to release`() {
        assertFalse(
            shouldReleaseStaleVpnTunnelBeforeConnect(
                activeVpnNetwork = false,
                tunDescriptorProbe = ProcessTunDescriptorProbe.OPEN,
            ),
        )
    }

    @Test
    fun `failure cleanup cannot be preempted by a retrying switch`() {
        val failureSource =
            File("src/main/kotlin/com/foxhole/guard/runtime/FoxholeVpnServiceFailureAndBridgeSupport.kt")
                .readText()

        assertTrue(
            failureSource.contains(
                "launchPriorityCommand(RuntimeCommandPriority.USER_STOP, \"fail_disconnect\")",
            ),
        )
    }

    @Test
    fun `connect waits for every real teardown owner but not a healthy local guard`() {
        val emptyQueue = queue()
        val idleNative = NativeRuntimeSnapshot.NONE

        assertFalse(
            shouldWaitForRuntimeTeardownBeforeConnect(
                connectionState = ConnectionState.IDLE,
                activeVpnNetwork = false,
                commandQueue = emptyQueue,
                nativeRuntime = idleNative,
            ),
        )
        assertFalse(
            shouldWaitForRuntimeTeardownBeforeConnect(
                connectionState = ConnectionState.CONNECTED,
                activeVpnNetwork = true,
                commandQueue = emptyQueue,
                nativeRuntime = idleNative.copy(nativeState = RuntimeState.RUNNING),
            ),
        )
        assertTrue(
            shouldWaitForRuntimeTeardownBeforeConnect(
                connectionState = ConnectionState.DISCONNECTING,
                activeVpnNetwork = true,
                commandQueue = emptyQueue,
                nativeRuntime = idleNative,
            ),
        )
        assertTrue(
            shouldWaitForRuntimeTeardownBeforeConnect(
                connectionState = ConnectionState.IDLE,
                activeVpnNetwork = false,
                commandQueue = emptyQueue,
                nativeRuntime = idleNative.copy(
                    nativeState = RuntimeState.STOPPING,
                    cleanupDraining = true,
                ),
            ),
        )
    }

    @Test
    fun `an error waits only while a real cleanup owner remains`() {
        val queuedCleanup = queue(priorityBuffered = 1)
        assertTrue(
            shouldWaitForRuntimeTeardownBeforeConnect(
                connectionState = ConnectionState.ERROR,
                activeVpnNetwork = false,
                commandQueue = queuedCleanup,
                nativeRuntime = NativeRuntimeSnapshot.NONE,
            ),
        )
        assertEquals(1, queuedCleanup.commandQueueDepth)

        assertFalse(
            shouldWaitForRuntimeTeardownBeforeConnect(
                connectionState = ConnectionState.ERROR,
                activeVpnNetwork = true,
                commandQueue = queue(),
                nativeRuntime = NativeRuntimeSnapshot.NONE,
            ),
        )
        assertTrue(
            shouldWaitForRuntimeTeardownBeforeConnect(
                connectionState = ConnectionState.ERROR,
                activeVpnNetwork = true,
                commandQueue = queue(),
                nativeRuntime = NativeRuntimeSnapshot.NONE.copy(
                    nativeState = RuntimeState.ERROR,
                    hasTunFileDescriptor = true,
                ),
            ),
        )
    }

    private fun queue(priorityBuffered: Int = 0): RuntimeCommandQueueSnapshot =
        RuntimeCommandQueueSnapshot(
            closed = false,
            normalBuffered = 0,
            priorityBuffered = priorityBuffered,
            pending = 0,
            running = false,
            runningPriority = null,
            runningReason = null,
            lastSequence = 0L,
        )
}
