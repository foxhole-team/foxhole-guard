package com.foxhole.guard.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkRuleCommandRequestsTest {
    @Test
    fun `issued capability is bound to token profile option and monotonic lifetime`() {
        val request = NetworkRuleCommandRequest("issued", 12L, "vless", 1_000L)
        assertTrue(request.matches("issued", 12L, "vless", 1_001L))
        assertFalse(request.matches(null, 12L, "vless", 1_001L))
        assertFalse(request.matches("forged", 12L, "vless", 1_001L))
        assertFalse(request.matches("issued", 13L, "vless", 1_001L))
        assertFalse(request.matches("issued", 12L, "trojan", 1_001L))
        assertFalse(request.matches("issued", 12L, "vless", 999L))
        assertFalse(request.matches("issued", 12L, "vless", 1_000L + NetworkRuleCommandRequest.EXPIRY_MS))
    }
}
