package com.foxhole.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val HEX_DIGITS = "0123456789abcdef"

class I2pdSocksCredentialGateTest {
    private val user = "foxhole"

    private val secret = HEX_DIGITS.repeat(2)

    private class FakeRouter : AutoCloseable {
        private val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = server.localPort
        val connections = CountDownLatch(1)

        init {
            Thread({
                while (true) {
                    val socket = runCatching { server.accept() }.getOrNull() ?: return@Thread
                    connections.countDown()
                    Thread({
                        runCatching {
                            socket.use {
                                val input = it.getInputStream()
                                val output = it.getOutputStream()
                                check(input.read() == 0x05)
                                val count = input.read()
                                repeat(count) { input.read() }
                                output.write(byteArrayOf(0x05, 0x00))
                                output.flush()
                                input.copyTo(output)
                            }
                        }
                    }).apply { isDaemon = true }.start()
                }
            }).apply { isDaemon = true }.start()
        }

        override fun close() = server.close()
    }

    private fun gate(routerPort: Int): I2pdSocksCredentialGate =
        I2pdSocksCredentialGate(routerPort, routerPort, user, secret).also { it.start() }

    private fun connect(port: Int): Socket =
        Socket().apply {
            soTimeout = 4_000
            connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 4_000)
        }

    private fun greet(
        output: OutputStream,
        vararg methods: Byte,
    ) {
        output.write(byteArrayOf(0x05, methods.size.toByte()) + methods)
        output.flush()
    }

    private fun authenticate(
        output: OutputStream,
        username: String,
        password: String,
    ) {
        val u = username.toByteArray()
        val p = password.toByteArray()
        output.write(byteArrayOf(0x01, u.size.toByte()) + u + byteArrayOf(p.size.toByte()) + p)
        output.flush()
    }

    @Test
    fun `an unauthenticated client is refused and never reaches the router`() {
        FakeRouter().use { router ->
            gate(router.port).use { gate ->
                connect(gate.port).use { client ->
                    val input = client.getInputStream()
                    greet(client.getOutputStream(), 0x00)
                    assertEquals("the gate answers SOCKS5", 0x05, input.read())
                    assertEquals(
                        "0xFF is NO ACCEPTABLE METHODS; i2pd itself would have answered 0x00",
                        0xFF,
                        input.read(),
                    )
                    assertEquals("and the connection is closed, not relayed", -1, input.read())
                }
                assertFalse(
                    "a refused client must not cause an upstream connection to the router",
                    router.connections.await(300, TimeUnit.MILLISECONDS),
                )
            }
        }
    }

    @Test
    fun `a wrong credential is refused after the subnegotiation`() {
        FakeRouter().use { router ->
            gate(router.port).use { gate ->
                connect(gate.port).use { client ->
                    val input = client.getInputStream()
                    val output = client.getOutputStream()
                    greet(output, 0x02)
                    assertEquals(0x05, input.read())
                    assertEquals(0x02, input.read())
                    authenticate(output, user, "not-the-per-start-secret")
                    assertEquals("RFC 1929 reply version", 0x01, input.read())
                    assertEquals("non-zero status is a refusal", 0x01, input.read())
                    assertEquals(-1, input.read())
                }
                assertFalse(
                    "a wrong credential must not reach the router either",
                    router.connections.await(300, TimeUnit.MILLISECONDS),
                )
            }
        }
    }

    @Test
    fun `an authenticated client is served and relayed to the router`() {
        FakeRouter().use { router ->
            gate(router.port).use { gate ->
                connect(gate.port).use { client ->
                    val input = client.getInputStream()
                    val output = client.getOutputStream()
                    greet(output, 0x00, 0x02)
                    assertEquals(0x05, input.read())
                    assertEquals("the gate selects username/password", 0x02, input.read())
                    authenticate(output, user, secret)
                    assertEquals(0x01, input.read())
                    assertEquals("zero status is success", 0x00, input.read())

                    val request =
                        byteArrayOf(
                            0x05, 0x01, 0x00, 0x03, 0x04, 0x74, 0x65, 0x73, 0x74,
                            0x01, 0xBB.toByte(),
                        )
                    output.write(request)
                    output.flush()
                    val echoed = ByteArray(request.size)
                    assertTrue("the relay must carry the request", input.readFullyOrFalse(echoed))
                    assertTrue("and carry it byte for byte, unmodified", request.contentEquals(echoed))
                }
                assertTrue(
                    "an authenticated client is the only one that opens an upstream connection",
                    router.connections.await(2, TimeUnit.SECONDS),
                )
            }
        }
    }

    @Test
    fun `authentication does not succeed while the router is down`() {
        val deadPort = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        gate(deadPort).use { gate ->
            connect(gate.port).use { client ->
                val input = client.getInputStream()
                val output = client.getOutputStream()
                greet(output, 0x02)
                assertEquals(0x05, input.read())
                assertEquals(0x02, input.read())
                authenticate(output, user, secret)
                assertEquals("with no router behind it the gate must not report success", -1, input.read())
            }
        }
    }
}

private fun InputStream.readFullyOrFalse(target: ByteArray): Boolean {
    var read = 0
    while (read < target.size) {
        val count = read(target, read, target.size - read)
        if (count < 0) return false
        read += count
    }
    return true
}
