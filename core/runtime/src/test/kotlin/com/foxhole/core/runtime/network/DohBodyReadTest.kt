package com.foxhole.core.runtime.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

class DohBodyReadTest {
    @Test
    fun `body deadline stops a continuing stream without sleeping`() {
        var time = 0L
        var reads = 0
        val input = object : InputStream() {
            override fun read(): Int = error("unexpected scalar read")
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                reads++
                time += TimeUnit.MILLISECONDS.toNanos(750)
                bytes[offset] = ' '.code.toByte()
                return 1
            }
        }
        val error = runCatching { input.readDohBodyCapped { time } }.exceptionOrNull()
        assertTrue(error is SocketTimeoutException)
        assertEquals(2, reads)
    }

    @Test
    fun `small valid DNS JSON is preserved exactly`() {
        val body = "{\"Status\":0,\"Answer\":[{\"type\":28,\"data\":\"2001:4860:4860::8888\"}]}"
        assertEquals(body, ByteArrayInputStream(body.toByteArray()).readDohBodyCapped { 0L })
        assertEquals(
            "2001:4860:4860:0:0:0:0:8888",
            PublicDohDnsFallback.parseDohAddresses(body, 28).single().hostAddress,
        )
    }

    @Test
    fun `TXT decoding remains intact`() {
        val body = "{\"Status\":0,\"Answer\":[{\"type\":16,\"data\":\"\\\"owned text\\\"\"}]}"
        val bounded = ByteArrayInputStream(body.toByteArray()).readDohBodyCapped { 0L }
        assertEquals(listOf("owned text"), PublicDohDnsFallback.parseDohTxtRecords(bounded))
    }
}
