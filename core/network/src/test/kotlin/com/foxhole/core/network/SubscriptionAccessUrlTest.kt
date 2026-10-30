package com.foxhole.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.InetAddress

class SubscriptionAccessUrlTest {
    @Test
    fun `Outline dynamic keys use public HTTPS without losing opaque path or fragment`() {
        val input = "ssconf://example.com/opaque%2Fkey?token=value#My%20VPN"
        assertEquals(
            "https://example.com/opaque%2Fkey?token=value#My%20VPN",
            input.ensurePublicSubscriptionUrl().toString()
        )
        assertEquals("https://example.com/key", "SSCONF://example.com/key".ensurePublicSubscriptionUrl().toString())
        assertThrows(IllegalArgumentException::class.java) { input.ensurePublicHttpsUrl() }
    }

    @Test
    fun `dynamic key cannot admit private endpoints credentials or a missing authority`() {
        for (input in listOf(
            "ssconf://localhost/key",
            "ssconf://192.168.1.1/key",
            "ssconf://user:password@1.1.1.1/key",
            "ssconf:///example.com/key",
            "ssconf://[::1]/key"
        )) {
            assertThrows(IllegalArgumentException::class.java) { input.ensurePublicSubscriptionUrl() }
        }
        assertThrows(IllegalArgumentException::class.java) {
            "ssconf://edge.example/key".ensurePublicSubscriptionUrl(true) { listOf(InetAddress.getByName("10.0.0.1")) }
        }
    }

    @Test
    fun `ordinary public HTTPS source keeps the same policy`() {
        assertEquals("https://example.com/sub", "https://example.com/sub".ensurePublicSubscriptionUrl().toString())
        assertThrows(IllegalArgumentException::class.java) { "http://example.com/sub".ensurePublicSubscriptionUrl() }
    }
}
