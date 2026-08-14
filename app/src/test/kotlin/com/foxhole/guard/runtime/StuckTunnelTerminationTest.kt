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
        // The defect, from a Pixel: switching protocol inside the smart-profile
        // test killed the app on the third candidate. The core had already
        // reported native_engine=false and native_tun=false, so the only
        // /dev/tun descriptor in the process was the master this app keeps in
        // Java on purpose — the thing that lets an outbound change replace the
        // engine without dropping the OS VPN. The probe could not recognise it
        // and reported the design as a leak, whose remedy is SIGKILL.
        assertFalse(
            shouldTerminateProcessForStuckTunnel(
                nativeSnapshot = snapshot(masterTunFd = 42),
                tunDescriptorProbe = ProcessTunDescriptorProbe.CLOSED,
            ),
        )
    }

    @Test
    fun `a descriptor that is not ours is still a leak worth killing for`() {
        // The kill has to survive: it exists because a tunnel left open in this
        // process keeps carrying traffic after the user asked for it to stop.
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
        // Pre-existing rule, pinned here so the new exclusion cannot loosen it:
        // treating UNKNOWN as OPEN turns a harmless framework lag into SIGKILL.
        assertFalse(
            shouldTerminateProcessForStuckTunnel(
                nativeSnapshot = snapshot(),
                tunDescriptorProbe = ProcessTunDescriptorProbe.UNKNOWN,
            ),
        )
    }
}
