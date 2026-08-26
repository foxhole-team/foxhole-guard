package com.foxhole.core.runtime

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

internal class I2pdSocksCredentialGate(
    private val routerSocksPort: Int,
    private val routerHttpProxyPort: Int,
    private val username: String,
    private val password: String,
    listenerFactory: () -> ServerSocket = { ServerSocket(0, ACCEPT_BACKLOG, loopback()) },
    private val upstreamFactory: (Int) -> Socket = { port ->
        Socket().apply {
            connect(InetSocketAddress(loopback(), port), UPSTREAM_CONNECT_TIMEOUT_MS)
        }
    },
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleeper: (Long) -> Unit = { millis -> Thread.sleep(millis) },
    private val retryBudgetMs: Long = B32_RETRY_BUDGET_MS,
    private val retryDelayMs: Long = B32_RETRY_DELAY_MS,
) : I2pdSocksGate {
    private val listener: ServerSocket = listenerFactory()
    private val closed = AtomicBoolean(false)

    override val port: Int get() = listener.localPort

    init {
        require(username.isNotEmpty() && username.toByteArray(Charsets.UTF_8).size <= MAX_FIELD) {
            "SOCKS username must be 1..255 bytes"
        }
        require(password.isNotEmpty() && password.toByteArray(Charsets.UTF_8).size <= MAX_FIELD) {
            "SOCKS password must be 1..255 bytes"
        }
    }

    fun start() {
        Thread({ acceptLoop() }, "FoxHoleI2pdSocksGate").apply {
            isDaemon = true
            start()
        }
    }

    private fun acceptLoop() {
        while (!closed.get()) {
            val client =
                runCatching { listener.accept() }.getOrElse {
                    return
                }
            Thread({ serve(client) }, "FoxHoleI2pdSocksGateConn").apply {
                isDaemon = true
                start()
            }
        }
    }

    @Suppress("ReturnCount")
    internal fun serve(client: Socket) {
        var router: Socket? = null
        try {
            client.soTimeout = HANDSHAKE_TIMEOUT_MS
            val input = client.getInputStream()
            val output = client.getOutputStream()
            if (!negotiateMethod(input, output)) {
                return
            }
            if (!verifyCredentials(input, output)) {
                return
            }
            val first =
                runCatching { upstreamFactory(routerSocksPort) }.getOrElse {
                    return
                }
            router = first
            first.soTimeout = HANDSHAKE_TIMEOUT_MS
            if (!negotiateUpstream(first)) {
                return
            }

            output.write(byteArrayOf(AUTH_VERSION, STATUS_SUCCESS))
            output.flush()
            val request = readSocksRequest(input) ?: return
            val established =
                if (request.browsable) {
                    connectHttpProxy(request, first, output)
                } else {
                    connectWithRetry(request, first, output)
                } ?: return
            router = established
            client.soTimeout = 0
            established.soTimeout = 0
            pump(client, established)
        } catch (_: IOException) {
        } finally {
            runCatching { router?.close() }
            runCatching { client.close() }
        }
    }

    private fun connectHttpProxy(
        request: SocksRequest,
        socksUpstream: Socket,
        clientOut: OutputStream,
    ): Socket? {
        runCatching { socksUpstream.close() }
        val proxy = runCatching { upstreamFactory(routerHttpProxyPort) }.getOrElse { return null }
        return runCatching {
            clientOut.write(request.successReply())
            clientOut.flush()
            proxy
        }.getOrElse {
            runCatching { proxy.close() }
            null
        }
    }

    private fun connectWithRetry(
        request: SocksRequest,
        initial: Socket,
        clientOut: OutputStream,
    ): Socket? {
        var router = initial
        val deadline = clock() + retryBudgetMs
        while (true) {
            router.soTimeout = REQUEST_TIMEOUT_MS
            val reply = exchangeRequest(router, request)

            val retryable = reply?.succeeded == false && request.retryable && clock() < deadline
            if (!retryable) {
                reply ?: return null
                clientOut.write(reply.raw)
                clientOut.flush()
                return if (reply.succeeded) router else null
            }
            runCatching { router.close() }
            sleeper(retryDelayMs)
            val next = runCatching { upstreamFactory(routerSocksPort) }.getOrElse { return null }
            next.soTimeout = HANDSHAKE_TIMEOUT_MS
            if (!negotiateUpstream(next)) {
                runCatching { next.close() }
                return null
            }
            router = next
        }
    }

    private fun exchangeRequest(
        router: Socket,
        request: SocksRequest,
    ): SocksReply? =
        runCatching {
            val out = router.getOutputStream()
            out.write(request.raw)
            out.flush()
            readSocksReply(router.getInputStream())
        }.getOrNull()

    private fun negotiateMethod(
        input: InputStream,
        output: OutputStream,
    ): Boolean {
        if (input.read() != SOCKS_VERSION) {
            return false
        }
        val count = input.read()
        if (count <= 0) {
            return false
        }
        val methods = ByteArray(count)
        if (!input.readFully(methods)) {
            return false
        }
        if (methods.none { method -> method == METHOD_USERNAME_PASSWORD }) {
            // Never fall through to i2pd's unauthenticated listener.
            output.write(byteArrayOf(SOCKS_VERSION.toByte(), METHOD_NONE_ACCEPTABLE))
            output.flush()
            return false
        }
        output.write(byteArrayOf(SOCKS_VERSION.toByte(), METHOD_USERNAME_PASSWORD))
        output.flush()
        return true
    }

    private fun verifyCredentials(
        input: InputStream,
        output: OutputStream,
    ): Boolean {
        if (input.read() != AUTH_VERSION.toInt()) {
            return false
        }
        val offeredUser = input.readLengthPrefixed() ?: return false
        val offeredPassword = input.readLengthPrefixed() ?: return false

        val matches =
            constantTimeEquals(offeredUser, username.toByteArray(Charsets.UTF_8)) and
                constantTimeEquals(offeredPassword, password.toByteArray(Charsets.UTF_8))
        if (!matches) {
            output.write(byteArrayOf(AUTH_VERSION, STATUS_FAILURE))
            output.flush()
            return false
        }
        return true
    }

    private fun negotiateUpstream(router: Socket): Boolean {
        val out = router.getOutputStream()
        val input = router.getInputStream()
        out.write(byteArrayOf(SOCKS_VERSION.toByte(), 0x01, METHOD_NO_AUTHENTICATION))
        out.flush()
        return input.read() == SOCKS_VERSION && input.read() == METHOD_NO_AUTHENTICATION.toInt()
    }

    private fun pump(
        client: Socket,
        router: Socket,
    ) {
        val upward =
            Thread({
                runCatching { client.getInputStream().copyTo(router.getOutputStream()) }
                runCatching { router.shutdownOutput() }
            }, "FoxHoleI2pdSocksGateUp")
        upward.isDaemon = true
        upward.start()
        runCatching { router.getInputStream().copyTo(client.getOutputStream()) }
        runCatching { client.shutdownOutput() }
        runCatching { upward.join(PUMP_JOIN_MS) }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            runCatching { listener.close() }
        }
    }

    private companion object {
        const val SOCKS_VERSION = 0x05
        const val AUTH_VERSION: Byte = 0x01
        const val METHOD_NO_AUTHENTICATION: Byte = 0x00
        const val METHOD_USERNAME_PASSWORD: Byte = 0x02
        const val METHOD_NONE_ACCEPTABLE: Byte = 0xFF.toByte()
        const val STATUS_SUCCESS: Byte = 0x00
        const val STATUS_FAILURE: Byte = 0x01
        const val MAX_FIELD = 255
        const val ACCEPT_BACKLOG = 16
        const val HANDSHAKE_TIMEOUT_MS = 5_000
        const val UPSTREAM_CONNECT_TIMEOUT_MS = 2_000
        const val PUMP_JOIN_MS = 1_000L

        const val REQUEST_TIMEOUT_MS = 20_000

        fun loopback(): InetAddress = InetAddress.getByName("127.0.0.1")
    }
}

internal const val B32_RETRY_BUDGET_MS = 25_000L
internal const val B32_RETRY_DELAY_MS = 2_000L

internal data class SocksRequest(
    val raw: ByteArray,
    val host: String?,
) {
    val retryable: Boolean get() = host?.endsWith(I2P_DOMAIN_SUFFIX) == true

    val browsable: Boolean get() = port == HTTP_PORT

    val port: Int
        get() {
            val high = raw[raw.size - 2].toInt() and 0xFF
            val low = raw[raw.size - 1].toInt() and 0xFF
            return (high shl 8) or low
        }

    fun successReply(): ByteArray =
        byteArrayOf(SOCKS5_VERSION.toByte(), 0x00, 0x00, SOCKS5_ATYP_IPV4.toByte(), 0, 0, 0, 0, 0, 0)

    override fun equals(other: Any?): Boolean =
        this === other || (other is SocksRequest && raw.contentEquals(other.raw) && host == other.host)

    override fun hashCode(): Int = raw.contentHashCode() * 31 + host.hashCode()
}

internal data class SocksReply(
    val raw: ByteArray,
    val succeeded: Boolean,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is SocksReply && raw.contentEquals(other.raw) && succeeded == other.succeeded)

    override fun hashCode(): Int = raw.contentHashCode() * 31 + succeeded.hashCode()
}

@Suppress("ReturnCount")
internal fun readSocksRequest(input: InputStream): SocksRequest? {
    val header = ByteArray(SOCKS_HEADER_BYTES)
    if (!input.readFully(header)) return null
    if (header[0].toInt() and 0xFF != SOCKS5_VERSION) return null
    val addressType = header[SOCKS_ADDRESS_TYPE_INDEX].toInt() and 0xFF
    val address = input.readSocksAddress(addressType) ?: return null
    val port = ByteArray(SOCKS_PORT_BYTES)
    if (!input.readFully(port)) return null
    return SocksRequest(
        raw = header + address.raw + port,
        host = address.host,
    )
}

@Suppress("ReturnCount")
internal fun readSocksReply(input: InputStream): SocksReply? {
    val header = ByteArray(SOCKS_HEADER_BYTES)
    if (!input.readFully(header)) return null
    if (header[0].toInt() and 0xFF != SOCKS5_VERSION) return null
    val addressType = header[SOCKS_ADDRESS_TYPE_INDEX].toInt() and 0xFF
    val address = input.readSocksAddress(addressType) ?: return null
    val port = ByteArray(SOCKS_PORT_BYTES)
    if (!input.readFully(port)) return null
    return SocksReply(
        raw = header + address.raw + port,
        succeeded = header[SOCKS_REPLY_CODE_INDEX].toInt() == SOCKS5_REPLY_SUCCEEDED,
    )
}

private class SocksAddress(val raw: ByteArray, val host: String?)

private fun InputStream.readSocksAddress(addressType: Int): SocksAddress? =
    when (addressType) {
        SOCKS5_ATYP_IPV4 -> readFixedAddress(SOCKS_IPV4_BYTES)
        SOCKS5_ATYP_IPV6 -> readFixedAddress(SOCKS_IPV6_BYTES)
        SOCKS5_ATYP_DOMAIN -> readDomainAddress()
        else -> null
    }

private fun InputStream.readDomainAddress(): SocksAddress? {
    val length = read()
    if (length <= 0) {
        return null
    }
    val name = ByteArray(length)
    if (!readFully(name)) {
        return null
    }
    return SocksAddress(
        raw = byteArrayOf(length.toByte()) + name,
        host = String(name, Charsets.US_ASCII).lowercase(),
    )
}

private fun InputStream.readFixedAddress(size: Int): SocksAddress? {
    val value = ByteArray(size)
    return if (readFully(value)) SocksAddress(raw = value, host = null) else null
}

private const val SOCKS5_VERSION = 0x05
private const val SOCKS5_REPLY_SUCCEEDED = 0x00
private const val SOCKS5_ATYP_IPV4 = 0x01
private const val SOCKS5_ATYP_DOMAIN = 0x03
private const val SOCKS5_ATYP_IPV6 = 0x04

private const val SOCKS_HEADER_BYTES = 4
private const val SOCKS_REPLY_CODE_INDEX = 1
private const val SOCKS_ADDRESS_TYPE_INDEX = 3
private const val SOCKS_PORT_BYTES = 2
private const val SOCKS_IPV4_BYTES = 4
private const val SOCKS_IPV6_BYTES = 16
private const val HTTP_PORT = 80

private fun InputStream.readFully(target: ByteArray): Boolean {
    var read = 0
    while (read < target.size) {
        val count = read(target, read, target.size - read)
        if (count < 0) {
            return false
        }
        read += count
    }
    return true
}

private fun InputStream.readLengthPrefixed(): ByteArray? {
    val length = read()
    if (length < 0) {
        return null
    }
    val value = ByteArray(length)
    return if (readFully(value)) value else null
}

private fun constantTimeEquals(
    left: ByteArray,
    right: ByteArray,
): Boolean {
    var difference = left.size xor right.size
    for (index in left.indices) {
        difference = difference or (left[index].toInt() xor right[index % right.size.coerceAtLeast(1)].toInt())
    }
    return difference == 0
}

internal interface I2pdSocksGate : Closeable {
    val port: Int
}

internal fun openI2pdSocksGate(
    routerSocksPort: Int,
    routerHttpProxyPort: Int,
    username: String,
    password: String,
): I2pdSocksGate {
    val gate = I2pdSocksCredentialGate(routerSocksPort, routerHttpProxyPort, username, password)
    gate.start()
    return gate
}
