package com.foxhole.guard.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnNetworkHandoverIdentityTest {
    @Test
    fun `a reused Android network handle is fresh when the tun interface changed`() {
        assertTrue(
            vpnNetworkIdentityDiffersFrom(
                currentHandle = 418L,
                currentInterfaceName = "tun1",
                previousHandle = 418L,
                previousInterfaceName = "tun0",
            ),
        )
    }

    @Test
    fun `the retired interface remains excluded while Android reports it`() {
        assertFalse(
            vpnNetworkIdentityDiffersFrom(
                currentHandle = 418L,
                currentInterfaceName = "tun0",
                previousHandle = 418L,
                previousInterfaceName = "tun0",
            ),
        )
    }

    @Test
    fun `a genuinely new Android network handle is fresh without an interface name`() {
        assertTrue(
            vpnNetworkIdentityDiffersFrom(
                currentHandle = 419L,
                currentInterfaceName = null,
                previousHandle = 418L,
                previousInterfaceName = null,
            ),
        )
    }

    @Test
    fun `unknown identity does not silently accept the same stale handle`() {
        assertFalse(
            vpnNetworkIdentityDiffersFrom(
                currentHandle = 418L,
                currentInterfaceName = "tun1",
                previousHandle = 418L,
                previousInterfaceName = null,
            ),
        )
    }
}
