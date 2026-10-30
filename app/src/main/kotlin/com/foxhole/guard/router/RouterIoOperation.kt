package com.foxhole.guard.router

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class RouterIoOperation : Closeable {
    private val closed = AtomicBoolean(false)
    private val resource = AtomicReference<Closeable?>()
    fun register(value: Closeable) {
        check(resource.compareAndSet(null, value))
        if (closed.get()) { value.close(); throw IOException("router_cancelled") }
    }
    override fun close() {
        closed.set(true)
        try { resource.getAndSet(null)?.close() } catch (_: IOException) {}
    }
}

internal suspend fun <T> routerIo(io: CoroutineDispatcher = Dispatchers.IO, block: (RouterIoOperation) -> T): T = withContext(io) {
    suspendCancellableCoroutine { continuation ->
        val operation = RouterIoOperation()
        continuation.invokeOnCancellation { operation.close() }
        try {
            val result = block(operation)
            if (continuation.isActive) continuation.resume(result)
        } catch (error: Exception) {
            if (continuation.isActive) continuation.resumeWithException(error)
        } finally { operation.close() }
    }
}
