package com.foxhole.guard.runtime

import com.foxhole.core.runtime.NativeRuntimeSnapshot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnTeardownKillDecisionTest {
    @Test
    fun `confirmed process tun keeps fail closed process escalation`() {
        assertTrue(
            shouldTerminateProcessForStuckTunnel(
                nativeSnapshot = NativeRuntimeSnapshot.NONE,
                tunDescriptorProbe = ProcessTunDescriptorProbe.OPEN,
            ),
        )
    }

    @Test
    fun `native engine or master tun keeps fail closed process escalation`() {
        assertTrue(
            shouldTerminateProcessForStuckTunnel(
                nativeSnapshot = NativeRuntimeSnapshot.NONE.copy(hasEngineHandle = true),
                tunDescriptorProbe = ProcessTunDescriptorProbe.CLOSED,
            ),
        )
        assertTrue(
            shouldTerminateProcessForStuckTunnel(
                nativeSnapshot = NativeRuntimeSnapshot.NONE.copy(hasTunFileDescriptor = true),
                tunDescriptorProbe = ProcessTunDescriptorProbe.UNKNOWN,
            ),
        )
    }

    @Test
    fun `unknown proc result cannot kill a resource free runtime`() {
        assertFalse(
            shouldTerminateProcessForStuckTunnel(
                nativeSnapshot = NativeRuntimeSnapshot.NONE,
                tunDescriptorProbe = ProcessTunDescriptorProbe.UNKNOWN,
            ),
        )
        assertFalse(
            shouldTerminateProcessForStuckTunnel(
                nativeSnapshot = NativeRuntimeSnapshot.NONE,
                tunDescriptorProbe = ProcessTunDescriptorProbe.CLOSED,
            ),
        )
    }
}
