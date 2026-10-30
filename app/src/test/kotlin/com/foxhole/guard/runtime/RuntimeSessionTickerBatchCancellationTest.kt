package com.foxhole.guard.runtime

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.coroutines.CoroutineContext

class RuntimeSessionTickerBatchCancellationTest {
    @Test
    fun `unregistered inline task never executes from an already claimed batch`() = verifyClaimedTaskIsSkipped(null)

    @Test
    fun `unregistered dispatched task never executes from an already claimed batch`() =
        verifyClaimedTaskIsSkipped(Dispatchers.Unconfined)

    private fun verifyClaimedTaskIsSkipped(runOn: CoroutineDispatcher?) {
        val dispatcher = QueuedDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        var now = 0L
        val ticker = RuntimeSessionTicker(scope, loopDispatcher = dispatcher, nowMs = { now })
        var removedTaskCalls = 0
        ticker.register("remove-next", fireImmediately = false, intervalMs = { 10L }) {
            ticker.unregister("removed")
        }
        ticker.register("removed", fireImmediately = false, intervalMs = { 10L }, runOn = runOn) {
            removedTaskCalls += 1
        }
        now = 10L
        try {
            dispatcher.runNext()
            assertEquals(
                "a claimed task must still belong to the current registration before execution",
                0,
                removedTaskCalls
            )
        } finally {
            ticker.stop()
            scope.cancel()
            dispatcher.drain()
        }
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val pending = ArrayDeque<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            synchronized(pending) { pending.addLast(block) }
        }

        fun runNext() {
            synchronized(pending) { pending.removeFirst() }.run()
        }

        fun drain() {
            while (true) {
                val next = synchronized(pending) { pending.removeFirstOrNull() } ?: return
                next.run()
            }
        }
    }
}
