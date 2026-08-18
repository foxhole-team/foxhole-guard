package com.foxhole.core.runtime

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

internal class I2pB32ResolutionTest {

    @Test
    fun `a router with no tunnels is not ready however well its socket negotiates`() {
        assertFalse(i2pdTunnelsBuilt(null))
        assertFalse(i2pdTunnelsBuilt(0))
        assertFalse("one tunnel can only be half a pair", i2pdTunnelsBuilt(1))
        assertTrue(i2pdTunnelsBuilt(2))
        assertTrue(i2pdTunnelsBuilt(6))
        assertEquals(2, I2PD_MIN_CLIENT_TUNNELS)
    }

    @Test
    fun `the journal separates a router that never started from one still building`() {
        assertEquals("i2pd proxy ready", i2pdReadyOutcome(ready = true, listenerUp = true))
        assertEquals(
            "i2pd proxy up but tunnels not built",
            i2pdReadyOutcome(ready = false, listenerUp = true),
        )
        assertEquals("i2pd proxy wait failed", i2pdReadyOutcome(ready = false, listenerUp = false))
    }

    private fun connectRequest(host: String): ByteArray =
        byteArrayOf(0x05, 0x01, 0x00, 0x03, host.length.toByte()) +
            host.toByteArray(Charsets.US_ASCII) +
            byteArrayOf(0x00, 0x50)

    @Test
    fun `a domain request is read byte-exact and leaves pipelined payload untouched`() {
        val host = "ukeu3k5oycgaauneqgtnvselmt4yemvoilkln7jpvamvfx7dnkdq.b32.i2p"
        val request = connectRequest(host)
        val payload = "GET / HTTP/1.1\r\n".toByteArray(Charsets.US_ASCII)
        val stream = ByteArrayInputStream(request + payload)

        val parsed = readSocksRequest(stream)!!

        assertArrayEquals("a retry must resend exactly what the client asked", request, parsed.raw)
        assertEquals(host, parsed.host)
        assertTrue(parsed.retryable)
        assertEquals(payload.size, stream.available())
    }

    @Test
    fun `only i2p destinations are retried`() {
        assertTrue(readSocksRequest(ByteArrayInputStream(connectRequest("site.i2p")))!!.retryable)
        assertFalse(readSocksRequest(ByteArrayInputStream(connectRequest("example.com")))!!.retryable)
        val literal = byteArrayOf(0x05, 0x01, 0x00, 0x01, 10, 0, 0, 1, 0x00, 0x50)
        val parsed = readSocksRequest(ByteArrayInputStream(literal))!!
        assertNull(parsed.host)
        assertFalse(parsed.retryable)
        assertArrayEquals(literal, parsed.raw)
    }

    @Test
    fun `replies are framed by address type and report success only on code zero`() {
        val ok = byteArrayOf(0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1, 0x00, 0x50)
        val refused = byteArrayOf(0x05, 0x04, 0x00, 0x01, 0, 0, 0, 0, 0x00, 0x00)
        val domain =
            byteArrayOf(0x05, 0x00, 0x00, 0x03, 4) + "host".toByteArray(Charsets.US_ASCII) +
                byteArrayOf(0x00, 0x50)
        val ipv6 = byteArrayOf(0x05, 0x00, 0x00, 0x04) + ByteArray(16) + byteArrayOf(0x00, 0x50)

        assertTrue(readSocksReply(ByteArrayInputStream(ok))!!.succeeded)
        assertFalse(readSocksReply(ByteArrayInputStream(refused))!!.succeeded)
        assertArrayEquals(domain, readSocksReply(ByteArrayInputStream(domain))!!.raw)
        assertArrayEquals(ipv6, readSocksReply(ByteArrayInputStream(ipv6))!!.raw)
        assertNull(readSocksReply(ByteArrayInputStream(ok.copyOf(6))))
        assertNull(readSocksReply(ByteArrayInputStream(byteArrayOf(0x04, 0x00, 0x00, 0x01))))
    }

    @Test
    fun `browsing is chosen by port, so a fake-IP literal still reaches the HTTP proxy`() {
        val named = readSocksRequest(ByteArrayInputStream(connectRequest("site.i2p")))!!
        assertTrue(named.browsable)

        val literalPort80 = byteArrayOf(0x05, 0x01, 0x00, 0x01, 198.toByte(), 18, 0, 7, 0x00, 0x50)
        val viaFakeIp = readSocksRequest(ByteArrayInputStream(literalPort80))!!
        assertNull("the engine gave no name", viaFakeIp.host)
        assertTrue("but the port says this is browsing", viaFakeIp.browsable)

        val tls =
            byteArrayOf(0x05, 0x01, 0x00, 0x03, 8) + "site.i2p".toByteArray(Charsets.US_ASCII) +
                byteArrayOf(0x01, 0xBB.toByte())
        val overTls = readSocksRequest(ByteArrayInputStream(tls))!!
        assertEquals(443, overTls.port)
        assertFalse(overTls.browsable)
    }

    @Test
    fun `a synthesised SOCKS success is a well-formed reply`() {
        val reply = readSocksRequest(ByteArrayInputStream(connectRequest("site.i2p")))!!.successReply()
        val parsed = readSocksReply(ByteArrayInputStream(reply))!!
        assertTrue(parsed.succeeded)
        assertArrayEquals(reply, parsed.raw)
    }

    @Test
    fun `the retry budget is bounded so a dead eepsite still reports as dead`() {
        assertTrue(B32_RETRY_BUDGET_MS in 5_000..60_000)
        assertTrue(B32_RETRY_DELAY_MS in 500..5_000)
        assertTrue("a budget must allow more than one attempt", B32_RETRY_DELAY_MS < B32_RETRY_BUDGET_MS)
    }
}
