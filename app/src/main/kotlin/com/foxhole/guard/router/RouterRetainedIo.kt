package com.foxhole.guard.router

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.Closeable
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resumeWithException

// The result owns the registered transport until installation or cancellation closes it.
internal suspend fun <T : AutoCloseable> retainedRouterIo(
    io: CoroutineDispatcher,
    block: (register: (Closeable) -> Unit) -> T,
): T = suspendCancellableCoroutine { continuation ->
    val resource = AtomicReference<Closeable?>()
    val cleanups = IdentityHashMap<AutoCloseable, RouterTransportCleanup>()
    fun closeOwned(value: AutoCloseable) {
        val cleanup = synchronized(cleanups) {
            cleanups[value] ?: RouterTransportCleanup(cleanup = value::close).also { cleanups[value] = it }
        }
        cleanup.close()
    }
    val worker = CoroutineScope(continuation.context).launch(io) {
        try {
            val result = block { value ->
                check(resource.compareAndSet(null, value))
                if (!continuation.isActive) {
                    resource.getAndSet(null)?.let(::closeOwned)
                    throw CancellationException("router_cancelled")
                }
            }
            continuation.resume(result) { _, unclaimed, _ -> closeOwned(unclaimed) }
        } catch (failure: Exception) {
            resource.getAndSet(null)?.let(::closeOwned)
            if (continuation.isActive) continuation.resumeWithException(failure)
        }
    }
    continuation.invokeOnCancellation {
        worker.cancel()
        resource.getAndSet(null)?.let(::closeOwned)
    }
}
