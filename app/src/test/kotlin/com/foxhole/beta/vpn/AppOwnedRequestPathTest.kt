package com.foxhole.beta.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

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
    fun `tunnel validation requests use normal process path`() {
        assertNull(tunnelValidationRequestNetwork("vpn-network"))
    }
}
