package com.foxhole.core.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeTunDescriptorProbeTest {
    @Test
    fun `master tun closes only after native ownership is definitely released`() {
        assertTrue(shouldCloseMasterTun(NativeTunDescriptorProbe.CLOSED))
        assertFalse(shouldCloseMasterTun(NativeTunDescriptorProbe.OPEN))
        assertFalse(shouldCloseMasterTun(NativeTunDescriptorProbe.UNKNOWN))
    }
}
