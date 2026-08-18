package com.foxhole.guard.runtime

import com.foxhole.core.runtime.NativeRuntimeSnapshot
import com.foxhole.core.runtime.RuntimeState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StuckTunnelTerminationTest {
    private fun snapshot(
        engine: Boolean = false,
        tun: Boolean = false,
        masterTunFd: Int? = null,
    ) = NativeRuntimeSnapshot(
        hasEngineHandle = engine,
        hasTunFileDescriptor = tun,
        hasHost = engine,
        hasConfig = engine,
        dnsServerAddress = null,
        nativeGeneration = 0L,
        nativeState = RuntimeState.IDLE,
        cleanupDraining = false,
        lastStopReason = null,
        lastCloseDetached = false,
        masterTunFd = masterTunFd,
    )

    @Test
    fun `a released core with only our own descriptor left must not kill the process`() {
        assertFalse(
            shouldTerminateProcessForStuckTunnel(
                nativeSnapshot = snapshot(masterTunFd = 42),
                tunDescriptorProbe = ProcessTunDescriptorProbe.CLOSED,
            ),
        )
    }

    @Test
    fun `a descriptor that is not ours is still a leak worth killing for`() {
        assertTrue(
            shouldTerminateProcessForStuckTunnel(
                nativeSnapshot = snapshot(masterTunFd = 42),
                tunDescriptorProbe = ProcessTunDescriptorProbe.OPEN,
            ),
        )
    }

    @Test
    fun `a stale native snapshot is diagnostic only without a positive foreign fd`() {
        assertFalse(
            shouldTerminateProcessForStuckTunnel(
                nativeSnapshot = snapshot(engine = true),
                tunDescriptorProbe = ProcessTunDescriptorProbe.CLOSED,
            ),
        )
        assertFalse(
            shouldTerminateProcessForStuckTunnel(
                nativeSnapshot = snapshot(tun = true),
                tunDescriptorProbe = ProcessTunDescriptorProbe.CLOSED,
            ),
        )
    }

    @Test
    fun `an unreadable proc result is not evidence of a leak`() {
        assertFalse(
            shouldTerminateProcessForStuckTunnel(
                nativeSnapshot = snapshot(),
                tunDescriptorProbe = ProcessTunDescriptorProbe.UNKNOWN,
            ),
        )
    }
}
