package com.foxhole.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

internal class I2pdSocksCredentialGateLifecycleTest {
    @Test
    fun `invalid username does not acquire a listening socket`() {
        assertInvalidCredentialsDoNotListen("", PASSWORD)
    }

    @Test
    fun `oversized password does not acquire a listening socket`() {
        assertInvalidCredentialsDoNotListen(USER, "a".repeat(256))
    }

    @Test
    fun `connection capacity rejects clients before creating another handshake worker`() {
        val clients = mutableListOf<Socket>()
        I2pdSocksCredentialGate(1, 1, USER, PASSWORD).use { gate ->
            gate.start()
            try {
                repeat(128) {
                    val client = Socket()
                    clients.add(client)
                    client.soTimeout = 2_000
                    client.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), gate.port))
                    client.getOutputStream().write(byteArrayOf(5, 1, 2))
                    assertEquals(5, client.getInputStream().read())
                    assertEquals(2, client.getInputStream().read())
                }
                Socket().use { overflow ->
                    overflow.soTimeout = 1_000
                    overflow.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), gate.port))
                    assertEquals(-1, overflow.getInputStream().read())
                }
            } finally {
                gate.close()
                clients.forEach(Socket::close)
            }
        }
    }

    @Test
    fun `stop closes an accepted client waiting for credentials`() {
        I2pdSocksCredentialGate(1, 1, USER, PASSWORD).use { gate ->
            gate.start()
            Socket().use { client ->
                client.soTimeout = 1_000
                client.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), gate.port))
                client.getOutputStream().write(byteArrayOf(5, 1, 2))
                assertEquals(5, client.getInputStream().read())
                assertEquals(2, client.getInputStream().read())
                gate.close()
                assertEquals(-1, client.getInputStream().read())
            }
        }
    }

    @Test
    fun `failed retry closes the replacement upstream socket`() {
        assertReplacementClosed(REFUSED)
    }

    @Test
    fun `truncated retry reply closes the replacement upstream socket`() {
        assertReplacementClosed(byteArrayOf(5, 0))
    }

    @Test
    fun `stop during retry does not open another upstream connection`() {
        val upstream = ScriptedSocket(byteArrayOf(5, 0) + REFUSED)
        val client = ScriptedSocket(CLIENT_REQUEST)
        var attempts = 0
        lateinit var gate: I2pdSocksCredentialGate
        gate = I2pdSocksCredentialGate(
            1, 1, USER, PASSWORD,
            upstreamFactory = {
                attempts++
                upstream
            },
            clock = { 0L },
            sleeper = { gate.close() },
        )
        gate.use { it.serve(client) }
        assertEquals(1, attempts)
        assertTrue(client.closed.get())
        assertTrue(upstream.closed.get())
    }

    private fun assertReplacementClosed(secondReply: ByteArray) {
        val first = ScriptedSocket(byteArrayOf(5, 0) + REFUSED)
        val second = ScriptedSocket(byteArrayOf(5, 0) + secondReply)
        val sockets = ArrayDeque(listOf(first, second))
        var now = 0L
        I2pdSocksCredentialGate(
            1, 1, USER, PASSWORD,
            upstreamFactory = { sockets.removeFirst() },
            clock = { now },
            sleeper = { now = 11L },
            retryBudgetMs = 10L,
            retryDelayMs = 1L,
        ).use { it.serve(ScriptedSocket(CLIENT_REQUEST)) }
        assertTrue(first.closed.get())
        assertTrue(second.closed.get())
    }

    private fun assertInvalidCredentialsDoNotListen(username: String, password: String) {
        val opened = mutableListOf<ServerSocket>()
        try {
            assertThrows(IllegalArgumentException::class.java) {
                I2pdSocksCredentialGate(
                    1,
                    1,
                    username,
                    password,
                    listenerFactory = {
                        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).also(opened::add)
                    },
                )
            }
            assertTrue(opened.isEmpty())
        } finally {
            opened.forEach(ServerSocket::close)
        }
    }

    private class ScriptedSocket(bytes: ByteArray) : Socket() {
        private val input = ByteArrayInputStream(bytes)
        private val output = ByteArrayOutputStream()
        val closed = AtomicBoolean(false)

        override fun getInputStream(): InputStream = input
        override fun getOutputStream(): OutputStream = output
        override fun setSoTimeout(timeout: Int) = Unit
        override fun close() { closed.set(true) }
    }

    private companion object {
        const val USER = "foxhole"
        const val PASSWORD = "fixture-password"
        val REFUSED = byteArrayOf(5, 4, 0, 1, 0, 0, 0, 0, 0, 0)
        val CLIENT_REQUEST = byteArrayOf(5, 1, 2, 1, USER.length.toByte()) + USER.toByteArray() +
            byteArrayOf(PASSWORD.length.toByte()) + PASSWORD.toByteArray() +
            byteArrayOf(5, 1, 0, 3, 8) + "site.i2p".toByteArray() + byteArrayOf(1, 0xbb.toByte())
    }
}
