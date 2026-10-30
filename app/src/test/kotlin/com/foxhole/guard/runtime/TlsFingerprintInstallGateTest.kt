package com.foxhole.guard.runtime

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.CoroutineContext

class TlsFingerprintInstallGateTest {
    @Test
    fun `installation runs on its dispatcher before native start can continue`() {
        val dispatcher = QueuedDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        var installs = 0
        var nativeStarted = false
        val gate = TlsFingerprintInstallGate(scope, dispatcher) { installs += 1 }
        try {
            gate.start()
            assertEquals(0, installs)
            scope.launch(Dispatchers.Unconfined) {
                gate.awaitReady()
                nativeStarted = true
            }
            assertFalse(nativeStarted)
            dispatcher.drain()
            assertTrue(nativeStarted)
            assertEquals(1, installs)
        } finally {
            scope.cancel()
            dispatcher.drain()
        }
    }

    @Test
    fun `cancelled start caller does not cancel the service owned installation`() {
        val dispatcher = QueuedDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        var installs = 0
        var secondStarted = false
        val gate = TlsFingerprintInstallGate(scope, dispatcher) { installs += 1 }
        try {
            val cancelled = scope.launch(Dispatchers.Unconfined) { gate.awaitReady() }
            cancelled.cancel()
            scope.launch(Dispatchers.Unconfined) {
                gate.awaitReady()
                secondStarted = true
            }
            dispatcher.drain()
            assertTrue(secondStarted)
            assertEquals(1, installs)
        } finally {
            scope.cancel()
            dispatcher.drain()
        }
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val pending = ArrayDeque<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            synchronized(pending) { pending.addLast(block) }
        }

        fun drain() {
            while (true) {
                val next = synchronized(pending) { pending.removeFirstOrNull() } ?: return
                next.run()
            }
        }
    }
}
