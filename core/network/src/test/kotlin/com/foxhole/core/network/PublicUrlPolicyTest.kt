package com.foxhole.core.network

import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.InetAddress

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
    fun `rejects the reserved ietf assignment block`() {
        "https://192.0.0.8/config".ensurePublicHttpsUrl()
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects the documentation block`() {
        "https://192.0.2.10/config".ensurePublicHttpsUrl()
    }

    @Test
    fun `accepts routable space inside 192 0 0 0 slash 16`() {
        assertEquals(
            "https://192.0.5.10/config",
            "https://192.0.5.10/config".ensurePublicHttpsUrl().toString(),
        )
    }

    @Test
    fun `accepts hostname answered out of the fake ip pool`() {
        assertEquals(
            "proxy.example.com",
            "proxy.example.com".requirePublicRemoteHost(
                resolveHost = true,
                resolver = { listOf(ipv4TestAddress("198.18.0.10")) },
            ),
        )
    }

    @Test
    fun `accepts hostname answered out of the ipv6 fake ip pool`() {
        assertEquals(
            "proxy.example.com",
            "proxy.example.com".requirePublicRemoteHost(
                resolveHost = true,
                resolver = { listOf(InetAddress.getByName("fc00::a")) },
            ),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `still rejects a fake ip literal`() {
        "https://198.18.0.10/config".ensurePublicHttpsUrl()
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
