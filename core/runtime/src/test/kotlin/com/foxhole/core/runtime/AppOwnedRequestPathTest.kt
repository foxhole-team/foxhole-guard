package com.foxhole.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketException
import java.net.UnknownHostException

class AppOwnedRequestPathTest {
    @Test
    fun `tunnel validation requests keep explicit vpn network binding`() {
        assertEquals("vpn-network", tunnelValidationRequestNetwork("vpn-network"))
    }

    @Test
    fun `app-owned network fallback catches explicit bind failures`() {
        val bindDenied = SocketException("Binding socket to network 100 failed: EPERM (Operation not permitted)")
        assertTrue(
            shouldFallbackAppOwnedNetworkRequest(
                bindDenied,
            ),
        )
        assertTrue(isAppOwnedNetworkBindingDenied(bindDenied))
        assertTrue(shouldFallbackAppOwnedNetworkRequest(UnknownHostException("ipwho.is")))
        assertFalse(shouldFallbackAppOwnedNetworkRequest(IllegalStateException("bad response")))
        assertFalse(isAppOwnedNetworkBindingDenied(IllegalStateException("bad response")))
    }
}
