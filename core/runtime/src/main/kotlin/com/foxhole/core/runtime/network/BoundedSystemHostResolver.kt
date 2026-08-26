package com.foxhole.core.runtime.network

import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

// InetAddress lookup is uninterruptible; the bounded executor prevents stuck DNS calls exhausting connect work.
class BoundedSystemHostResolver(
    private val delegate: (String) -> List<InetAddress>,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val executor: Executor = sharedExecutor,
) : (String) -> List<InetAddress> {
    override fun invoke(hostname: String): List<InetAddress> {
        val task = FutureTask { delegate(hostname) }
        try {
            executor.execute(task)
        } catch (rejected: RejectedExecutionException) {
            throw UnknownHostException("system dns resolver saturated: $hostname").apply {
                initCause(rejected)
            }
        }
        return try {
            task.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (timeout: TimeoutException) {
            task.cancel(false)
            throw UnknownHostException("system dns lookup timed out: $hostname").apply { initCause(timeout) }
        } catch (interrupted: InterruptedException) {
            task.cancel(false)
            Thread.currentThread().interrupt()
            throw UnknownHostException("system dns lookup interrupted: $hostname").apply { initCause(interrupted) }
        } catch (failure: ExecutionException) {
            throw failure.cause?.apply { addSuppressed(failure) }
                ?: UnknownHostException("system dns lookup failed: $hostname").apply { initCause(failure) }
        }
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 3_000L

        private val sharedExecutor: Executor = newBoundedSystemResolverExecutor()
    }
}

internal fun newBoundedSystemResolverExecutor(
    maxThreads: Int = MAX_SYSTEM_RESOLVER_THREADS,
): ThreadPoolExecutor {
    require(maxThreads > 0)
    return ThreadPoolExecutor(
        maxThreads,
        maxThreads,
        30L,
        TimeUnit.SECONDS,

        SynchronousQueue(),
        { runnable ->
            Thread(
                runnable,
                "foxhole-dns-resolve-${BoundedSystemResolverThreadSequence.next()}",
            ).apply { isDaemon = true }
        },
        ThreadPoolExecutor.AbortPolicy(),
    ).apply { allowCoreThreadTimeOut(true) }
}

internal const val MAX_SYSTEM_RESOLVER_THREADS = 4

private object BoundedSystemResolverThreadSequence {
    private val sequence = AtomicInteger(0)

    fun next(): Int = sequence.incrementAndGet()
}
