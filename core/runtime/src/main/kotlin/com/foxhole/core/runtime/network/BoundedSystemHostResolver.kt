package com.foxhole.core.runtime.network

import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * A time budget around system host resolution.
 *
 * `InetAddress.getAllByName` blocks with no budget and cannot be interrupted — neither by
 * `interrupt()` nor by coroutine cancellation. It is called synchronously on the connect path, and
 * with a jammed system resolver — exactly the network state right after a tunnel teardown — the
 * connect command hung in the supervisor forever: observed live, "connecting" for over four
 * minutes without a line of progress, the stack stuck in `Linux.android_getaddrinfo`.
 *
 * The blocking call cannot be interrupted, so it is abandoned instead: the work goes to its own
 * thread, the wait is bounded, and the stuck thread finishes on its own and dies. A timeout
 * surfaces as [UnknownHostException], which is what makes [PublicRemoteDns] fall back to DoH, so
 * the whole resolve chain stays finite.
 */
class BoundedSystemHostResolver(
    private val delegate: (String) -> List<InetAddress>,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) : (String) -> List<InetAddress> {
    override fun invoke(hostname: String): List<InetAddress> {
        val task = FutureTask { delegate(hostname) }
        executor.execute(task)
        return try {
            task.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (timeout: TimeoutException) {
            // Cancels only our wait: getaddrinfo itself keeps running in the background.
            task.cancel(false)
            throw UnknownHostException("system dns lookup timed out: $hostname").apply { initCause(timeout) }
        } catch (interrupted: InterruptedException) {
            task.cancel(false)
            Thread.currentThread().interrupt()
            throw UnknownHostException("system dns lookup interrupted: $hostname").apply { initCause(interrupted) }
        } catch (failure: ExecutionException) {
            // ExecutionException is just FutureTask's wrapper around the delegate's failure:
            // unwrap it so callers see the same type as without this bound — PublicRemoteDns
            // branches on UnknownHostException and RuntimeException specifically.
            throw failure.cause?.apply { addSuppressed(failure) }
                ?: UnknownHostException("system dns lookup failed: $hostname").apply { initCause(failure) }
        }
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 3_000L

        // A cached pool: a thread stuck in getaddrinfo is occupied until it expires and never
        // returns, so a fixed size would eventually jam completely.
        val executor =
            Executors.newCachedThreadPool { runnable ->
                Thread(runnable, "foxhole-dns-resolve").apply { isDaemon = true }
            }
    }
}
