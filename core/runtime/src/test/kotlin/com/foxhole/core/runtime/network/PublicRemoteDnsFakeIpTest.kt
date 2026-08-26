package com.foxhole.core.runtime.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

class PublicRemoteDnsFakeIpTest {
    private val neverCalledFallback =
        PublicDnsFallback { hostname ->
            throw AssertionError("DoH fallback must not be consulted for $hostname")
        }

    @Test
    fun `a fake ip answer is returned instead of being refused as private`() {
        val fake = InetAddress.getByName("198.18.0.10")
        val dns = PublicRemoteDns(delegate = { listOf(fake) }, fallback = neverCalledFallback)

        assertEquals(listOf(fake), dns.lookup("proxy.example.com"))
    }

    @Test
    fun `an ipv6 fake ip answer is returned as well`() {
        val fake = InetAddress.getByName("fc00::a")
        val dns = PublicRemoteDns(delegate = { listOf(fake) }, fallback = neverCalledFallback)

        assertEquals(listOf(fake), dns.lookup("proxy.example.com"))
    }

    @Test
    fun `a real public answer still wins over a fake ip one`() {
        val real = InetAddress.getByName("93.184.216.34")
        val fake = InetAddress.getByName("198.18.0.10")
        val dns = PublicRemoteDns(delegate = { listOf(fake, real) }, fallback = neverCalledFallback)

        assertEquals(listOf(real), dns.lookup("proxy.example.com"))
    }

    @Test
    fun `a private answer still falls back and then fails`() {
        val dns =
            PublicRemoteDns(
                delegate = { listOf(InetAddress.getByName("10.10.0.5")) },
                fallback = { throw UnknownHostException("no doh") },
            )

        val failure = runCatching { dns.lookup("vpn.example.com") }.exceptionOrNull()

        assertTrue(failure is UnknownHostException)
    }
}
