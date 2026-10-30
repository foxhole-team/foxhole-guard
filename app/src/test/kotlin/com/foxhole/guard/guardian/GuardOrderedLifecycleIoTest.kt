package com.foxhole.guard.guardian

import com.foxhole.guard.core.security.FakeGuardCrypto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class GuardOrderedLifecycleIoTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `full ordinary queue retains an admitted final event in FIFO order`() {
        val scope = owner()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = CountDownLatch(4)
        val events = Collections.synchronizedList(mutableListOf<String>())
        val failures = Collections.synchronizedList(mutableListOf<GuardIoFailure>())
        val lane = GuardOrderedLifecycleIo(scope, ordinaryCapacity = 2, terminalCapacity = 1, onFailure = failures::add)
        try {
            assertEquals(
                GuardIoAdmission.ACCEPTED,
                lane.start("host") {
                    entered.countDown()
                    await(release)
                    events += "start"
                    completed.countDown()
                    true
                }
            )
            await(entered)
            assertEquals(
                GuardIoAdmission.ACCEPTED,
                lane.required {
                    events += "task"
                    completed.countDown()
                    true
                }
            )
            assertEquals(
                GuardIoAdmission.ACCEPTED,
                lane.required {
                    events += "heartbeat"
                    completed.countDown()
                    true
                }
            )
            assertEquals(GuardIoAdmission.FULL, lane.required { error("must not be admitted") })
            assertEquals(
                GuardIoAdmission.ACCEPTED,
                lane.terminal("host") {
                    events += "destroyed"
                    completed.countDown()
                    true
                }
            )
            assertEquals(3, lane.health().queued)
            assertEquals(1, lane.health().reservedHosts)
            release.countDown()
            await(completed)
            awaitHealth(lane) { it.queued == 0 && it.reservedHosts == 0 }
            assertEquals(listOf("start", "task", "heartbeat", "destroyed"), events.toList())
            assertEquals(1L, lane.health().rejected)
            assertTrue(failures.contains(GuardIoFailure.CAPACITY))
        } finally {
            release.countDown()
            scope.cancel()
        }
    }

    @Test
    fun `ordinary append failures are counted and callbacks run outside producer and lane locks`() {
        val scope = owner()
        val failed = CountDownLatch(2)
        val firstReported = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val producer = Thread.currentThread()
        val callbacks = Collections.synchronizedList(mutableListOf<Thread>())
        val probe = Executors.newSingleThreadExecutor()
        val callbackFailure = AtomicReference<Throwable>()
        lateinit var lane: GuardOrderedLifecycleIo
        lane = GuardOrderedLifecycleIo(scope, onFailure = {
            try {
                callbacks += Thread.currentThread()
                probe.submit<GuardIoHealth> { lane.health() }.get(2, TimeUnit.SECONDS)
            } catch (failure: Throwable) {
                callbackFailure.set(failure)
            } finally {
                firstReported.countDown()
                failed.countDown()
            }
        })
        try {
            lane.required { false }
            await(firstReported)
            lane.required { throw IllegalStateException("owned append failure") }
            await(failed)
            lane.required {
                completed.countDown()
                true
            }
            await(completed)
            assertEquals(2L, lane.health().failed)
            assertTrue(lane.health().available)
            assertTrue(callbacks.all { it !== producer })
            assertEquals(null, callbackFailure.get())
        } finally {
            scope.cancel()
            probe.shutdownNow()
        }
    }

    @Test
    fun `cancellation stops admission and clears pending work and terminal reservations`() {
        stoppedWorker(CancellationException("owned cancellation"))
    }

    @Test
    fun `fatal worker error retains identity and is not retried`() {
        stoppedWorker(AssertionError("owned fatal"))
    }

    private fun stoppedWorker(failure: Throwable) {
        val thrown = AtomicReference<Throwable>()
        val scope = owner(CoroutineExceptionHandler { _, error -> thrown.set(error) })
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val done = CountDownLatch(1)
        val completion = AtomicReference<Throwable>()
        val lane = GuardOrderedLifecycleIo(scope)
        scope.coroutineContext[Job]!!.children.single().invokeOnCompletion {
            completion.set(it)
            done.countDown()
        }
        try {
            lane.start("host") {
                entered.countDown()
                await(release)
                throw failure
            }
            await(entered)
            lane.required { error("pending must not execute") }
            lane.terminal("host") { error("terminal must not execute") }
            release.countDown()
            await(done)
            assertSame(failure, completion.get())
            if (failure is Error) assertSame(failure, thrown.get())
            assertFalse(lane.health().available)
            assertEquals(0, lane.health().queued)
            assertEquals(0, lane.health().reservedHosts)
            assertEquals(3L, lane.health().failed)
            assertEquals(GuardIoAdmission.UNAVAILABLE, lane.start("successor") { true })
        } finally {
            release.countDown()
            scope.cancel()
        }
    }

    @Test
    fun `reset fences an in-flight old append under the real journal lock`() {
        val scope = owner()
        val root = temporary.newFolder("reset-journal")
        val crypto = FakeGuardCrypto()
        val keys = crypto.sealedBoxKeypair()
        val journal = GuardJournal(File(root, "journal"), crypto, FakeGuardClock(), { keys.publicKey }, { -1L })
        val lane = GuardOrderedLifecycleIo(scope)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val oldDone = CountDownLatch(1)
        val newDone = CountDownLatch(1)
        val predicateHeldLock = AtomicBoolean()
        try {
            val started = CountDownLatch(1)
            lane.start("host") {
                started.countDown()
                true
            }
            await(started)
            lane.terminal("host") { current ->
                entered.countDown()
                await(release)
                journal.appendIfCurrent(GuardEvent(type = GuardEventType.SERVICE_DESTROYED)) {
                    predicateHeldLock.set(Thread.holdsLock(journal))
                    current()
                }.also { oldDone.countDown() }
            }
            await(entered)
            lane.required { error("old queued work must be invalidated") }
            lane.invalidate()
            journal.deleteAll()
            assertEquals(0, lane.health().queued)
            assertEquals(0, lane.health().reservedHosts)
            lane.start("host") { current ->
                journal.appendIfCurrent(
                    GuardEvent(type = GuardEventType.TASK_REMOVED),
                    current
                ).also { newDone.countDown() }
            }
            release.countDown()
            await(oldDone)
            await(newDone)
            assertTrue(predicateHeldLock.get())
            assertEquals(2L, lane.health().invalidated)
            val report = GuardJournalVerifier(
                File(root, "journal"),
                crypto
            ).verify(keys.publicKey, keys.privateKey, null)
            assertEquals(GuardJournalStatus.OK, report.status)
            assertEquals(
                listOf(GuardEventType.GENESIS, GuardEventType.TASK_REMOVED),
                report.entries.map { it.event?.type }
            )
            assertEquals(1, lane.health().reservedHosts)
        } finally {
            release.countDown()
            lane.invalidate()
            scope.cancel()
        }
    }

    @Test
    fun `current privacy gate suppresses old work and permits a later enabled event`() {
        val scope = owner()
        val active = AtomicBoolean(true)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val written = CountDownLatch(1)
        val lane = GuardOrderedLifecycleIo(scope, allowed = active::get)
        try {
            lane.required { current ->
                entered.countDown()
                await(release)
                current()
            }
            await(entered)
            lane.required { error("disabled event must not execute") }
            active.set(false)
            release.countDown()
            awaitHealth(lane) { it.invalidated == 2L }
            assertEquals(0L, lane.health().failed)
            active.set(true)
            lane.required {
                written.countDown()
                true
            }
            await(written)
        } finally {
            release.countDown()
            scope.cancel()
        }
    }

    private fun owner(handler: CoroutineExceptionHandler = CoroutineExceptionHandler { _, _ -> }) =
        CoroutineScope(SupervisorJob() + Dispatchers.IO + handler)

    private fun await(latch: CountDownLatch) {
        assertTrue("owned asynchronous operation completed", latch.await(5, TimeUnit.SECONDS))
    }

    private fun awaitHealth(lane: GuardOrderedLifecycleIo, predicate: (GuardIoHealth) -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!predicate(lane.health()) && System.nanoTime() < deadline) Thread.sleep(1)
        assertTrue("owned lane state reached", predicate(lane.health()))
    }
}
