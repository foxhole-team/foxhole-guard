package com.foxhole.core.network

import org.junit.Assert.assertEquals
import org.junit.Test

class PublicUrlPolicyTest {
    @Test
    fun `accepts public https url`() {
        assertEquals("https://example.com/path", "https://example.com/path".ensurePublicHttpsUrl().toString())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects localhost hostnames`() {
        "https://localhost/config".ensurePublicHttpsUrl()
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects private ipv4 literals`() {
        "https://192.168.0.10/config".ensurePublicHttpsUrl()
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects hostname that resolves to private address`() {
        "https://vpn.example.com/config".ensurePublicHttpsUrl(
            resolveHost = true,
            resolver =
            testRemoteHostResolver(
                overrides = mapOf("vpn.example.com" to ipv4TestAddress("10.10.0.5")),
            ),
        )
    }
}
