package com.foxhole.guard.router

import java.io.Closeable
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RouterRetainedIoTest {
    @Test fun deliveredTransactionRetainsItsTransportUntilTheOwnerClosesIt() = runTest {
        val closed = AtomicInteger()
        val transport = ownedTransport(closed)
        val result = async { retainedRouterIo(StandardTestDispatcher(testScheduler)) { register -> register(transport); transport } }
        runCurrent()
        assertEquals(0, closed.get())
        result.await().close()
        assertEquals(1, closed.get())
    }

    @Test fun failureClosesTheRegisteredTransport() = runTest {
        val closed = AtomicInteger()
        val failure = async {
            runCatching { retainedRouterIo<Closeable>(StandardTestDispatcher(testScheduler)) { register ->
                register(ownedTransport(closed))
                throw IllegalArgumentException("fixture")
            } }
        }
        runCurrent()
        assertEquals(1, closed.get())
        assertThrows(IllegalArgumentException::class.java) { failure.getCompleted().getOrThrow() }
    }

    @Test fun cancellationBeforeDeliveryClosesAnUnclaimedTransaction() = runTest {
        val closed = AtomicInteger()
        val owner = async {
            retainedRouterIo(StandardTestDispatcher(testScheduler)) { register ->
                val transport = ownedTransport(closed)
                register(transport)
                coroutineContext[kotlinx.coroutines.Job]?.cancel()
                transport
            }
        }
        runCurrent()
        assertEquals(true, owner.isCancelled)
        assertEquals(1, closed.get())
    }

    private fun ownedTransport(counter: AtomicInteger): Closeable {
        val closed = AtomicBoolean()
        return Closeable { if (closed.compareAndSet(false, true)) counter.incrementAndGet() }
    }
}
