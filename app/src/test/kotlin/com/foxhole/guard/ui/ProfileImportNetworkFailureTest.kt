package com.foxhole.guard.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class ProfileImportNetworkFailureTest {
    @Test
    fun `a connect timeout is a network failure, not a bad link`() {
        val wrapped =
            IllegalStateException(
                "failed to connect to host/10.0.0.1 (port 443) from /10.0.0.2 (port 34556) after 10000ms",
            )
        assertTrue(isNetworkFailure(wrapped, wrapped.message.orEmpty()))
    }

    @Test
    fun `the usual transport failures are recognised through the cause chain`() {
        listOf(
            SocketTimeoutException("timeout"),
            ConnectException("Connection refused"),
            UnknownHostException("Unable to resolve host"),
            IOException("unexpected end of stream"),
        ).forEach { cause ->
            val wrapped = IllegalStateException("import failed", cause)
            assertTrue(cause::class.simpleName, isNetworkFailure(wrapped, "import failed"))
        }
    }

    @Test
    fun `a genuinely malformed payload is still reported as a bad configuration`() {
        val parseFailure = IllegalArgumentException("unexpected token at offset 12")
        assertFalse(isNetworkFailure(parseFailure, parseFailure.message.orEmpty()))
    }

    @Test
    fun `a circular cause chain does not hang the error message`() {
        val first = IllegalStateException("first")
        val second = IllegalStateException("second", first)
        first.initCause(second)
        assertFalse(isNetworkFailure(first, "first"))
    }
}
