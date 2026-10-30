package com.foxhole.core.runtime.network

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

internal class IpInfoHttpTunnelBodyTest {
    private val chunkedResponse = HttpResponseHead(200, mapOf("transfer-encoding" to listOf("chunked")))

    @Test
    fun `signed chunk sizes are rejected as invalid framing`() {
        listOf("+1", "-1", "-80000000").forEach { size ->
            val failure =
                runCatching { readChunked("$size\r\nx\r\n0\r\n\r\n") }.exceptionOrNull()

            assertTrue("chunk size $size must be rejected", failure is IllegalStateException)
            assertEquals("invalid chunk size", failure?.message)
        }
    }

    @Test
    fun `large chunk is rejected before reading its body`() {
        val failure = runCatching { readChunked("7fffffff\r\n") }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertEquals("http response body too large", failure?.message)
    }

    @Test
    fun `combined chunks cannot exceed the body limit`() {
        val failure = runCatching { readChunked("1\r\nx\r\n20000\r\n") }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertEquals("http response body too large", failure?.message)
    }

    @Test
    fun `chunk extensions and trailers preserve the decoded body`() {
        assertArrayEquals(
            "hello".toByteArray(),
            readChunked("2;foo=bar\r\nhe\r\n3\r\nllo\r\n0\r\nX-Trace: test\r\n\r\n"),
        )
    }

    private fun readChunked(body: String): ByteArray =
        readHttpResponseBody(body.byteInputStream(Charsets.ISO_8859_1), chunkedResponse)
}
