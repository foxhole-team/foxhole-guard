package com.foxhole.beta.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeAutoReconnectPolicyTest {
    @Test
    fun `limits auto reconnect attempts`() {
        assertFalse(RuntimeAutoReconnectPolicy.shouldSchedule(autoReconnectEnabled = false, attempt = 1))
        assertFalse(RuntimeAutoReconnectPolicy.shouldSchedule(autoReconnectEnabled = true, attempt = 0))
        assertTrue(RuntimeAutoReconnectPolicy.shouldSchedule(autoReconnectEnabled = true, attempt = 1))
        assertTrue(RuntimeAutoReconnectPolicy.shouldSchedule(autoReconnectEnabled = true, attempt = 4))
        assertFalse(RuntimeAutoReconnectPolicy.shouldSchedule(autoReconnectEnabled = true, attempt = 5))
        assertTrue(RuntimeAutoReconnectPolicy.shouldSchedule(autoReconnectEnabled = true, attempt = 3, maxAttempts = 3))
        assertFalse(RuntimeAutoReconnectPolicy.shouldSchedule(autoReconnectEnabled = true, attempt = 4, maxAttempts = 3))
    }

    @Test
    fun `uses bounded reconnect backoff`() {
        assertEquals(0L, RuntimeAutoReconnectPolicy.backoffDelayMs(1))
        assertEquals(1_000L, RuntimeAutoReconnectPolicy.backoffDelayMs(2))
        assertEquals(2_000L, RuntimeAutoReconnectPolicy.backoffDelayMs(3))
        assertEquals(5_000L, RuntimeAutoReconnectPolicy.backoffDelayMs(4))
        assertEquals(5_000L, RuntimeAutoReconnectPolicy.backoffDelayMs(20))
        assertEquals(0L, RuntimeAutoReconnectPolicy.backoffDelayMs(1, retryDelaySeconds = 5))
        assertEquals(5_000L, RuntimeAutoReconnectPolicy.backoffDelayMs(2, retryDelaySeconds = 5))
    }
}
