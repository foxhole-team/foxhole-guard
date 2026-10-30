package com.foxhole.guard.runtime

import com.foxhole.core.runtime.RuntimeNetworkChangeLease
import com.foxhole.core.runtime.RuntimeNetworkChangeOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

class UpstreamNetworkChangeMailboxTest {
    @Test
    fun finiteBurstKeepsOneWorkerAndOnlyLatestPendingTarget() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val attempted = Collections.synchronizedList(mutableListOf<Int>())
        val abandoned = Collections.synchronizedList(mutableListOf<Int>())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val mailbox = UpstreamNetworkChangeMailbox(scope, { true }, { error("not a failure") }, {})
        try {
            mailbox.offer(
                lease {
                    attempted += 1
                    entered.complete(Unit)
                    release.await()
                    RuntimeNetworkChangeOutcome.SUPERSEDED
                }
            )
            withTimeout(1000) { entered.await() }
            (2..24).forEach { id ->
                mailbox.offer(
                    lease(abandon = { abandoned += id }) {
                        attempted += id
                        RuntimeNetworkChangeOutcome.SUBMITTED
                    }
                )
            }
            assertEquals(1, scope.coroutineContext[Job]!!.children.count())
            assertEquals((2..23).toList(), abandoned.toList())
            release.complete(Unit)
            withTimeout(1000) { scope.coroutineContext[Job]!!.children.toList().joinAll() }
            assertEquals(listOf(1, 24), attempted.toList())
        } finally {
            mailbox.close()
            scope.cancel()
        }
    }

    @Test
    fun failedPredecessorNeverRetriesOverNewerPendingTarget() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val attempted = Collections.synchronizedList(mutableListOf<Int>())
        val diagnostics = AtomicInteger()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val mailbox = UpstreamNetworkChangeMailbox(scope, { true }, { diagnostics.incrementAndGet() }, {})
        try {
            mailbox.offer(
                lease {
                    attempted += 2
                    entered.complete(Unit)
                    release.await()
                    RuntimeNetworkChangeOutcome.FAILED
                }
            )
            withTimeout(1000) { entered.await() }
            mailbox.offer(
                lease {
                    attempted += 3
                    RuntimeNetworkChangeOutcome.SUBMITTED
                }
            )
            release.complete(Unit)
            withTimeout(1000) { scope.coroutineContext[Job]!!.children.toList().joinAll() }
            assertEquals(listOf(2, 3), attempted.toList())
            assertEquals(1, diagnostics.get())
        } finally {
            mailbox.close()
            scope.cancel()
        }
    }

    @Test
    fun ordinaryFailureHasOnlyOneBoundedRetryAndDiagnostics() = runBlocking {
        val calls = AtomicInteger()
        val diagnostics = AtomicInteger()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val mailbox = UpstreamNetworkChangeMailbox(scope, { true }, { diagnostics.incrementAndGet() }, {})
        try {
            mailbox.offer(
                lease {
                    calls.incrementAndGet()
                    RuntimeNetworkChangeOutcome.FAILED
                }
            )
            withTimeout(1000) { scope.coroutineContext[Job]!!.children.toList().joinAll() }
            assertEquals(2, calls.get())
            assertEquals(2, diagnostics.get())
        } finally {
            mailbox.close()
            scope.cancel()
        }
    }

    @Test
    fun nonFailureOutcomesAreNotRetriedOrLoggedAsNativeFailures() = runBlocking {
        val calls = AtomicInteger()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val mailbox = UpstreamNetworkChangeMailbox(scope, { true }, { error("not ordinary failure") }, {})
        try {
            listOf(RuntimeNetworkChangeOutcome.SUPERSEDED, RuntimeNetworkChangeOutcome.UNAVAILABLE).forEach { outcome ->
                mailbox.offer(
                    lease {
                        calls.incrementAndGet()
                        outcome
                    }
                )
                withTimeout(1000) { scope.coroutineContext[Job]!!.children.toList().joinAll() }
            }
            assertEquals(2, calls.get())
        } finally {
            mailbox.close()
            scope.cancel()
        }
    }

    @Test
    fun closeCancelsBlockedWorkerAndAbandonsPendingWithoutMainWait() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val never = CompletableDeferred<Unit>()
        val abandoned = AtomicInteger()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val mailbox = UpstreamNetworkChangeMailbox(scope, { true }, { error("not failure") }, {})
        try {
            mailbox.offer(
                lease {
                    entered.complete(Unit)
                    never.await()
                    RuntimeNetworkChangeOutcome.SUBMITTED
                }
            )
            withTimeout(1000) { entered.await() }
            mailbox.offer(lease(abandon = { abandoned.incrementAndGet() }) { error("pending must not run") })
            val children = scope.coroutineContext[Job]!!.children.toList()
            mailbox.close()
            withTimeout(1000) { children.joinAll() }
            assertTrue(children.all { it.isCancelled })
            assertEquals(1, abandoned.get())
            assertFalse(
                mailbox.offer(
                    lease(abandon = { abandoned.incrementAndGet() }) { error("closed offer cannot run") }
                ),
            )
            assertEquals(2, abandoned.get())
        } finally {
            mailbox.close()
            scope.cancel()
        }
    }

    @Test
    fun cancelledWorkerAbandonsNewerPendingAndAllowsNextSignal() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val abandoned = AtomicInteger()
        val completed = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val mailbox = UpstreamNetworkChangeMailbox(scope, { true }, { error("not failure") }, {})
        try {
            mailbox.offer(
                lease {
                    entered.complete(Unit)
                    release.await()
                    throw CancellationException("owned cancellation")
                }
            )
            withTimeout(1000) { entered.await() }
            mailbox.offer(lease(abandon = { abandoned.incrementAndGet() }) { error("discarded pending must not run") })
            val children = scope.coroutineContext[Job]!!.children.toList()
            release.complete(Unit)
            withTimeout(1000) { children.joinAll() }
            assertTrue(children.all { it.isCancelled })
            assertEquals(1, abandoned.get())
            mailbox.offer(
                lease {
                    completed.complete(Unit)
                    RuntimeNetworkChangeOutcome.SUBMITTED
                }
            )
            withTimeout(1000) { completed.await() }
        } finally {
            mailbox.close()
            scope.cancel()
        }
    }

    @Test
    fun fatalWorkerFailurePropagatesAndClearsPendingQueue() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val observed = CompletableDeferred<Throwable>()
        val failure = AssertionError("owned fatal")
        val abandoned = AtomicInteger()
        val handler = CoroutineExceptionHandler { _, error -> observed.complete(error) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + handler)
        val mailbox = UpstreamNetworkChangeMailbox(scope, { true }, { error("fatal is not ordinary") }, {})
        try {
            mailbox.offer(
                lease {
                    entered.complete(Unit)
                    release.await()
                    throw failure
                }
            )
            withTimeout(1000) { entered.await() }
            mailbox.offer(lease(abandon = { abandoned.incrementAndGet() }) { error("pending must not run") })
            release.complete(Unit)
            assertSame(failure, withTimeout(1000) { observed.await() })
            withTimeout(1000) { scope.coroutineContext[Job]!!.children.toList().joinAll() }
            assertEquals(1, abandoned.get())
        } finally {
            mailbox.close()
            scope.cancel()
        }
    }

    @Test
    fun ordinaryDiagnosticFailureCannotDropNewerPendingTarget() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val applied = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val mailbox =
            UpstreamNetworkChangeMailbox(scope, { true }, { throw java.io.IOException("owned optional log") }, {})
        try {
            mailbox.offer(
                lease {
                    entered.complete(Unit)
                    release.await()
                    RuntimeNetworkChangeOutcome.FAILED
                }
            )
            withTimeout(1000) { entered.await() }
            mailbox.offer(
                lease {
                    applied.complete(Unit)
                    RuntimeNetworkChangeOutcome.SUBMITTED
                }
            )
            release.complete(Unit)
            withTimeout(1000) { applied.await() }
        } finally {
            mailbox.close()
            scope.cancel()
        }
    }

    private fun lease(
        abandon: () -> Unit = {},
        submit: suspend () -> RuntimeNetworkChangeOutcome,
    ): RuntimeNetworkChangeLease = object : RuntimeNetworkChangeLease {
        override suspend fun submit() = submit.invoke()
        override fun abandon() = abandon.invoke()
    }
}
