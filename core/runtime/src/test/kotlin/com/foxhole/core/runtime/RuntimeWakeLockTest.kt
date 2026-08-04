package com.foxhole.core.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RuntimeWakeLockTest {
    @Test
    fun `release cannot race a concurrent acquire into a held final state`() {
        val acquireEntered = CountDownLatch(1)
        val allowAcquire = CountDownLatch(1)
        val releaseReturned = CountDownLatch(1)
        val handle =
            BlockingWakeLockHandle(
                acquireEntered = acquireEntered,
                allowAcquire = allowAcquire,
            )
        val runtimeWakeLock =
            RuntimeWakeLock(
                diagnosticsLogger = NoOpRuntimeDiagnosticsSink,
                tag = "test",
                wakeLockFactory = { handle },
                elapsedRealtimeMs = { 1L },
            )
        val executor = Executors.newFixedThreadPool(2)

        try {
            executor.execute { runtimeWakeLock.acquire() }
            assertTrue(acquireEntered.await(1, TimeUnit.SECONDS))

            executor.execute {
                runtimeWakeLock.release()
                releaseReturned.countDown()
            }

            assertFalse(
                "release must wait for the in-flight acquire before it publishes the final released state",
                releaseReturned.await(100, TimeUnit.MILLISECONDS),
            )
            allowAcquire.countDown()

            assertTrue(releaseReturned.await(1, TimeUnit.SECONDS))
            assertFalse(handle.isHeld)
        } finally {
            allowAcquire.countDown()
            executor.shutdownNow()
        }
    }

    private class BlockingWakeLockHandle(
        private val acquireEntered: CountDownLatch,
        private val allowAcquire: CountDownLatch,
    ) : RuntimeWakeLockHandle {
        @Volatile
        override var isHeld: Boolean = false
            private set

        override fun acquire(timeoutMs: Long) {
            acquireEntered.countDown()
            check(allowAcquire.await(1, TimeUnit.SECONDS))
            isHeld = true
        }

        override fun release() {
            isHeld = false
        }
    }

    private object NoOpRuntimeDiagnosticsSink : RuntimeDiagnosticsSink {
        override fun record(
            tag: String,
            message: String,
        ) = Unit

        override fun recordStructured(
            tag: String,
            headline: String,
            vararg details: String?,
        ) = Unit
    }
}
