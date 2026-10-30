package com.foxhole.guard.router

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.Closeable
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resumeWithException

// The result owns the registered transport until installation or cancellation closes it.
internal suspend fun <T : AutoCloseable> retainedRouterIo(
    io: CoroutineDispatcher,
    block: (register: (Closeable) -> Unit) -> T,
): T = suspendCancellableCoroutine { continuation ->
    val resource = AtomicReference<Closeable?>()
    val worker = CoroutineScope(continuation.context).launch(io) {
        try {
            val result = block { value ->
                check(resource.compareAndSet(null, value))
                if (!continuation.isActive) {
                    resource.getAndSet(null)?.close()
                    throw CancellationException("router_cancelled")
                }
            }
            continuation.resume(result) { _, unclaimed, _ -> unclaimed.close() }
        } catch (failure: Exception) {
            resource.getAndSet(null)?.close()
            if (continuation.isActive) continuation.resumeWithException(failure)
        }
    }
    continuation.invokeOnCancellation {
        worker.cancel()
        resource.getAndSet(null)?.close()
    }
}
