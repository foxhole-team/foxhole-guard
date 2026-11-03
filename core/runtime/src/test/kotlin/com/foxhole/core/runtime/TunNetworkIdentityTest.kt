package com.foxhole.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetAddress

class TunNetworkIdentityTest {
    private val dns = listOf("198.18.0.2", "2001:db8::53")
    private val addresses = dns.map(InetAddress::getByName)

    @Test
    fun `fdinfo identifies the current tun without accepting malformed names`() {
        assertEquals("tun7", tunInterfaceName("pos:\t0\niff:\ttun7\n"))
        listOf("", "iff:\t", "iff:\t../../tun7", "iff:\ttun 7", "iff:\t1234567890123456").forEach {
            assertNull(tunInterfaceName(it))
        }
    }

    @Test
    fun `old agent remains rejected even with the same owner and advertised DNS`() {
        assertFalse(matches(networkInterface = "tun6"))
        assertTrue(matches(networkInterface = "tun7"))
    }

    @Test
    fun `current handle may be reused after its tun identity and DNS are updated`() {
        assertTrue(matches(networkDns = addresses.reversed()))
        assertTrue(matches(expectedDns = listOf("198.18.0.2", "2001:0db8:0:0:0:0:0:53")))
    }

    @Test
    fun `missing or stale published metadata does not admit an owned tun`() {
        assertFalse(matches(networkInterface = null))
        assertFalse(matches(networkDns = emptyList()))
        assertFalse(matches(networkDns = listOf(InetAddress.getByName("192.0.2.53"))))
        assertFalse(matches(networkDns = addresses + InetAddress.getByName("1.1.1.1")))
    }

    @Test
    fun `DNS identity must be complete numerical data without hostname resolution`() {
        assertFalse(matches(expectedDns = emptyList()))
        listOf("resolver.example", "198.18.0.999", "198.18.0.2.example", "2001:db8::53%wlan0").forEach {
            assertFalse(matches(expectedDns = listOf(it)))
        }
    }

    @Test
    fun `closed fd and unavailable fdinfo fail closed`() {
        assertFalse(matches(readFdInfo = { throw IOException("closed") }))
        assertFalse(matches(readFdInfo = { "pos:\t0\n" }))
    }

    @Test
    fun `replacement during fdinfo acquisition cannot validate a recycled descriptor`() {
        var owned = true
        assertFalse(matches(readFdInfo = { owned = false; "iff:\ttun7\n" }, isCurrent = { owned }))
    }

    @Test
    fun `retired owner cannot read a descriptor belonging to its replacement`() {
        var read = false
        assertFalse(matches(readFdInfo = { read = true; "iff:\ttun7\n" }, isCurrent = { false }))
        assertFalse(read)
    }

    private fun matches(
        networkInterface: String? = "tun7",
        expectedDns: List<String> = dns,
        networkDns: List<InetAddress> = addresses,
        readFdInfo: (Int) -> String = { "iff:\ttun7\n" },
        isCurrent: () -> Boolean = { true },
    ): Boolean = matchesOwnedTunNetwork(
        tunFd = 42,
        advertisedDnsServers = expectedDns,
        networkInterfaceName = networkInterface,
        networkDnsServers = networkDns,
        readFdInfo = readFdInfo,
        isCurrent = isCurrent,
    )
}
