package com.foxhole.guard.guardian

import com.foxhole.guard.core.security.FakeGuardCrypto
import com.foxhole.guard.core.security.GuardCrypto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class GuardJournalLifecycleFailureTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `real guarded journal crypto cancellation stops its IO owner with original cause`() {
        realJournalWorkerFailure(CancellationException("owned crypto cancellation"))
    }

    @Test
    fun `real guarded journal crypto fatal error stops its IO owner with original identity`() {
        realJournalWorkerFailure(AssertionError("owned crypto fatal"))
    }

    private fun realJournalWorkerFailure(failure: Throwable) {
        val crypto = FakeGuardCrypto()
        val keys = crypto.sealedBoxKeypair()
        val failingCrypto = object : GuardCrypto by crypto {
            override fun seal(payload: ByteArray, publicKey: ByteArray): ByteArray = throw failure
        }
        val journal = GuardJournal(temporary.newFolder("fatal-journal"), failingCrypto, FakeGuardClock(), {
            keys.publicKey
        }, { -1L })
        assertSame(
            failure,
            runCatching { journal.append(GuardEvent(GuardEventType.HEARTBEAT)) }.exceptionOrNull(),
        )
        val handlerFailure = AtomicReference<Throwable>()
        val scope =
            CoroutineScope(
                SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error -> handlerFailure.set(error) }
            )
        val lane = GuardOrderedLifecycleIo(scope)
        val completion = AtomicReference<Throwable>()
        val done = CountDownLatch(1)
        scope.coroutineContext[Job]!!.children.single().invokeOnCompletion {
            completion.set(it)
            done.countDown()
        }
        try {
            lane.start(
                "host"
            ) { current -> journal.appendIfCurrent(GuardEvent(GuardEventType.SERVICE_STARTED), current) }
            assertTrue(done.await(5, TimeUnit.SECONDS))
            assertSame(failure, completion.get())
            if (failure is Error) assertSame(failure, handlerFailure.get())
            assertFalse(lane.health().available)
            assertEquals(1L, lane.health().failed)
            assertEquals(0, lane.health().reservedHosts)
            assertNull(GuardJournal::class.java.getDeclaredField("head").apply { isAccessible = true }.get(journal))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `ordinary crypto failure returns false and later real journal append recovers`() {
        val crypto = FakeGuardCrypto()
        val keys = crypto.sealedBoxKeypair()
        val fault = AtomicReference<Exception?>(IllegalStateException("owned crypto failure"))
        val failingCrypto = object : GuardCrypto by crypto {
            override fun seal(payload: ByteArray, publicKey: ByteArray): ByteArray {
                fault.get()?.let { throw it }
                return crypto.seal(payload, publicKey)
            }
        }
        val directory = temporary.newFolder("ordinary-journal")
        val journal = GuardJournal(directory, failingCrypto, FakeGuardClock(), { keys.publicKey }, { -1L })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val reported = CountDownLatch(1)
        val recorded = CountDownLatch(1)
        val lane =
            GuardOrderedLifecycleIo(scope, onFailure = { if (it == GuardIoFailure.APPEND_FAILED) reported.countDown() })
        try {
            lane.required { current -> journal.appendIfCurrent(GuardEvent(GuardEventType.HEARTBEAT), current) }
            assertTrue(reported.await(5, TimeUnit.SECONDS))
            assertNull(GuardJournal::class.java.getDeclaredField("head").apply { isAccessible = true }.get(journal))
            assertEquals(1L, lane.health().failed)
            assertTrue(lane.health().available)
            fault.set(null)
            lane.required { current ->
                journal.appendIfCurrent(GuardEvent(GuardEventType.TASK_REMOVED), current).also {
                    if (it) recorded.countDown()
                }
            }
            assertTrue(recorded.await(5, TimeUnit.SECONDS))
            val report = GuardJournalVerifier(directory, crypto).verify(keys.publicKey, keys.privateKey, null)
            assertEquals(GuardJournalStatus.OK, report.status)
            assertEquals(
                listOf(GuardEventType.GENESIS, GuardEventType.TASK_REMOVED),
                report.entries.map { it.event?.type }
            )
        } finally {
            scope.cancel()
        }
    }
}
