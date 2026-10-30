package com.foxhole.guard.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal suspend fun <T> consumeAppUpdateResponse(
    call: Call,
    consume: (Response, () -> Boolean) -> T,
): T = suspendCancellableCoroutine { continuation ->
    val responseOwner = AtomicReference<Response?>(null)
    continuation.invokeOnCancellation {
        call.cancel()
        try {
            responseOwner.get()?.close()
        } catch (_: IOException) {
            // The request is already cancelled; cleanup must not replace owner cancellation.
        }
    }
    call.enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            responseOwner.set(response)
            try {
                val value = response.use {
                    if (!continuation.isActive) throw CancellationException()
                    consume(it) { continuation.isActive }
                }
                if (continuation.isActive) continuation.resume(value)
            } catch (error: Exception) {
                if (continuation.isActive) continuation.resumeWithException(error)
            } finally {
                responseOwner.compareAndSet(response, null)
            }
        }
    })
}
