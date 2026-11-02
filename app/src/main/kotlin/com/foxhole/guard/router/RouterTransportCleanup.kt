package com.foxhole.guard.router

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.SocketFactory

private val routerCleanupDispatcher = Dispatchers.IO.limitedParallelism(4)

internal class RouterTransportCleanup(
    private val io: CoroutineDispatcher = routerCleanupDispatcher,
    private val invalidate: () -> Unit = {},
    private val cleanup: () -> Unit,
) : AutoCloseable {
    private val started = AtomicBoolean()
    private val completed = CompletableDeferred<Unit>()

    override fun close() {
        if (!started.compareAndSet(false, true)) return
        invalidate()
        io.dispatch(NonCancellable) {
            try { cleanup(); completed.complete(Unit) }
            catch (failure: Throwable) {
                completed.completeExceptionally(failure)
                if (failure !is IOException) throw failure
            }
        }
    }

    suspend fun closeAndAwait(timeoutMillis: Long = 5000) = withContext(NonCancellable) {
        require(timeoutMillis in 1L..5000L)
        close()
        if (withTimeoutOrNull(timeoutMillis) { completed.await(); true } != true) throw IOException("router_cleanup_timeout")
    }
}

internal fun closeRouterTransport(resource: AutoCloseable, io: CoroutineDispatcher = routerCleanupDispatcher) {
    RouterTransportCleanup(io = io, cleanup = resource::close).close()
}

internal class RouterOwnedSocketFactory(private val delegate: SocketFactory) : SocketFactory() {
    private val invalidated = AtomicBoolean()
    private val owned = ConcurrentHashMap<Socket, Boolean>()

    fun invalidate() { invalidated.set(true) }

    // Abort owned raw sockets before pool eviction can attempt TLS close_notify.
    fun closeOwnedOnIo() {
        owned.keys.forEach { socket ->
            if (owned.remove(socket) != null) try { socket.close() } catch (_: IOException) { }
        }
    }

    override fun createSocket(): Socket = create { delegate.createSocket() }
    override fun createSocket(host: String, port: Int): Socket = create { delegate.createSocket(host, port) }
    override fun createSocket(host: InetAddress, port: Int): Socket = create { delegate.createSocket(host, port) }
    override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int): Socket =
        create { delegate.createSocket(host, port, local, localPort) }
    override fun createSocket(host: InetAddress, port: Int, local: InetAddress, localPort: Int): Socket =
        create { delegate.createSocket(host, port, local, localPort) }

    private fun create(open: () -> Socket): Socket {
        if (invalidated.get()) throw IOException("router_transport_closed")
        val socket = open()
        owned.keys.removeAll { it.isClosed }
        if (owned.size >= 128) { closeRouterTransport(socket); throw IOException("router_socket_limit") }
        owned[socket] = true
        if (invalidated.get()) {
            if (owned.remove(socket) != null) closeRouterTransport(socket)
            throw IOException("router_transport_closed")
        }
        return socket
    }
}
