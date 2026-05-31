package com.foxhole.beta.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketException
import java.net.UnknownHostException

class AppOwnedRequestPathTest {
    @Test
    fun `app-owned requests stay on the normal process path`() {
        assertEquals(
            AppOwnedRequestPath.NORMAL_PROCESS,
            appOwnedRequestPath(),
        )
    }

    @Test
    fun `app-owned requests do not keep an explicit binding candidate`() {
        assertNull(boundNetworkForAppOwnedRequest("vpn-network"))
    }

    @Test
    fun `tunnel validation requests keep explicit vpn network binding`() {
        assertEquals("vpn-network", tunnelValidationRequestNetwork("vpn-network"))
    }

    @Test
    fun `app-owned network fallback catches explicit bind failures`() {
        assertTrue(
            shouldFallbackAppOwnedNetworkRequest(
                SocketException("Binding socket to network 100 failed: EPERM (Operation not permitted)"),
            ),
        )
        assertTrue(shouldFallbackAppOwnedNetworkRequest(UnknownHostException("ipwho.is")))
        assertFalse(shouldFallbackAppOwnedNetworkRequest(IllegalStateException("bad response")))
    }
}
