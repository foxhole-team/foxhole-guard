package com.foxhole.guard.core.data

import com.foxhole.core.model.Settings
import com.foxhole.guard.core.settings.SettingsRestoreSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class ProfileMutationCoordinatorTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `durable commit settles effects outside locks even when block throws cancellation`() = runBlocking {
        val coordinator = ProfileMutationCoordinator()
        val settingsLock = Mutex()
        val committed = temporary.newFile("committed-state")
        val effects = AtomicInteger()
        val primary = CancellationException("owned post-commit cancellation")
        val failure = runCatching {
            coordinator.write({ error -> throw error }) { owner ->
                owner.secrets(coordinator.secretMutex) {
                    settingsLock.withLock {
                        committed.writeText("owned durable data")
                        owner.markLocalCommit()
                        owner.afterCommit {
                            assertFalse(coordinator.mutex.isLocked)
                            assertFalse(coordinator.secretMutex.isLocked)
                            assertFalse(settingsLock.isLocked)
                            effects.incrementAndGet()
                        }
                        throw primary
                    }
                }
            }
        }.exceptionOrNull()
        assertSame(primary, failure)
        assertEquals("owned durable data", committed.readText())
        assertEquals(1, effects.get())
    }

    @Test
    fun `rollback discards provisional applied-state effects`() = runBlocking {
        val coordinator = ProfileMutationCoordinator()
        val effects = AtomicInteger()
        val primary = AssertionError("owned applied failure")
        val failure = runCatching {
            coordinator.write({ error -> throw error }) { owner ->
                val settings = SettingsRestoreSession(Settings(), { owner.requireOwner(coordinator) }, {}, {})
                owner.bindSettings(settings)
                owner.markLocalCommit()
                owner.afterCommit { effects.incrementAndGet() }
                owner.markRestoreCommitted()
                settings.rollback()
                owner.markRestoreRolledBack()
                throw primary
            }
        }.exceptionOrNull()
        assertSame(primary, failure)
        assertEquals(0, effects.get())
    }

    @Test
    fun `ordinary callback failure does not skip later effects and idempotent retry is bounded and settled`() = runBlocking {
        val coordinator = ProfileMutationCoordinator()
        val reports = mutableListOf<Throwable>()
        val firstCalls = AtomicInteger()
        val laterCalls = AtomicInteger()
        val firstFailure = IllegalStateException("owned policy dispatch failure")
        val result = coordinator.write(reports::add) { owner ->
            owner.markLocalCommit()
            owner.afterCommit(retryKey = "quarantine-policy") {
                if (firstCalls.incrementAndGet() == 1) throw firstFailure
            }
            owner.afterCommit { laterCalls.incrementAndGet() }
            "committed"
        }
        assertEquals("committed", result)
        assertEquals(listOf(firstFailure), reports)
        assertEquals(1, laterCalls.get())
        coordinator.write(reports::add) { "next operation" }
        assertEquals(2, firstCalls.get())
        assertEquals(1, laterCalls.get())
    }

    @Test
    fun `primary operation error retains identity while every committed effect is attempted`() = runBlocking {
        val coordinator = ProfileMutationCoordinator()
        val primary = AssertionError("owned primary failure")
        val secondary = AssertionError("owned effect failure")
        val effects = AtomicInteger()
        val failure = runCatching {
            coordinator.write({}) { owner ->
                owner.markLocalCommit()
                owner.afterCommit { throw secondary }
                owner.afterCommit { effects.incrementAndGet() }
                throw primary
            }
        }.exceptionOrNull()
        assertSame(primary, failure)
        assertTrue(primary.suppressed.any { it === secondary })
        assertEquals(1, effects.get())
    }

    @Test
    fun `cancellation during settled-effect handoff does not return a successful ack`() {
        effectHandoff(null)
    }

    @Test
    fun `fatal effect observed before canceled handoff retains identity`() {
        effectHandoff(AssertionError("owned fatal effect"))
    }

    private fun effectHandoff(fatal: Error?) {
        val coordinator = ProfileMutationCoordinator()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val thrown = AtomicReference<Throwable>()
        val laterEffects = AtomicInteger()
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val job = scope.launch {
            try {
                coordinator.write({}) { owner ->
                    owner.markLocalCommit()
                    owner.afterCommit {
                        entered.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                        fatal?.let { throw it }
                    }
                    owner.afterCommit { laterEffects.incrementAndGet() }
                    "must not be acknowledged after cancellation"
                }
            } catch (error: Throwable) {
                thrown.set(error)
            } finally {
                finished.countDown()
            }
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            job.cancel(CancellationException("owned handoff cancellation"))
            release.countDown()
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            if (fatal != null) assertSame(fatal, thrown.get()) else assertTrue(thrown.get() is CancellationException)
            assertEquals(1, laterEffects.get())
        } finally {
            release.countDown()
            scope.cancel()
            dispatcher.close()
        }
    }

    @Test
    fun `cancellation after successful durable block still settles effects before rejecting ack`() = runBlocking {
        val coordinator = ProfileMutationCoordinator()
        val calls = AtomicInteger()
        val thrown = AtomicReference<Throwable>()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            scope.launch {
                val caller = coroutineContext[kotlinx.coroutines.Job]!!
                try {
                    coordinator.write({}) { owner ->
                        owner.markLocalCommit()
                        owner.afterCommit { calls.incrementAndGet() }
                        caller.cancel(CancellationException("owned before settlement cancellation"))
                        "durable"
                    }
                    thrown.set(AssertionError("cancelled write returned a successful acknowledgement"))
                } catch (error: Throwable) { thrown.set(error) }
            }.join()
            assertTrue(thrown.get() is CancellationException)
            assertEquals(1, calls.get())
        } finally { scope.cancel() }
    }

    @Test
    fun `ordinary failed effect is suppressed on existing primary and later effects still run`() = runBlocking {
        val coordinator = ProfileMutationCoordinator()
        val primary = AssertionError("owned operation failure")
        val secondary = IllegalStateException("owned ordinary callback failure")
        val calls = AtomicInteger()
        val failure = runCatching {
            coordinator.write({}) { owner ->
                owner.markLocalCommit()
                owner.afterCommit { throw secondary }
                owner.afterCommit { calls.incrementAndGet() }
                throw primary
            }
        }.exceptionOrNull()
        assertSame(primary, failure)
        assertTrue(primary.suppressed.any { it === secondary })
        assertEquals(1, calls.get())
    }

    @Test
    fun `later writer can finish while earlier local result callback waits without mixing caller revisions`() = runBlocking {
        val coordinator = ProfileMutationCoordinator()
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val firstRevision = AtomicReference<String>()
        val secondRevision = AtomicReference<String>()
        val committed = AtomicReference<String>()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val first = scope.launch {
                coordinator.write({}) { owner ->
                    committed.set("A")
                    owner.markLocalCommit()
                    owner.afterCommit {
                        firstEntered.countDown()
                        check(releaseFirst.await(5, TimeUnit.SECONDS))
                        firstRevision.set("A")
                    }
                }
            }
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS))
            val second = scope.launch {
                coordinator.write({}) { owner ->
                    committed.set("B")
                    owner.markLocalCommit()
                    owner.afterCommit { secondRevision.set("B") }
                }
            }
            second.join()
            assertEquals("B", committed.get())
            assertEquals("B", secondRevision.get())
            assertEquals(null, firstRevision.get())
            releaseFirst.countDown()
            first.join()
            assertEquals("A", firstRevision.get())
            assertEquals("B", committed.get())
        } finally {
            releaseFirst.countDown()
            scope.cancel()
        }
    }

    @Test
    fun `explicit same owner can use nested secret facade but escaped owner cannot write`() = runBlocking {
        val coordinator = ProfileMutationCoordinator()
        lateinit var escaped: ProfileMutationLease
        coordinator.write({}) { owner ->
            escaped = owner
            owner.secrets(coordinator.secretMutex) {
                owner.secrets(coordinator.secretMutex) { owner.markLocalCommit() }
            }
        }
        assertTrue(runCatching { escaped.markLocalCommit() }.exceptionOrNull() is IllegalStateException)
    }
}
