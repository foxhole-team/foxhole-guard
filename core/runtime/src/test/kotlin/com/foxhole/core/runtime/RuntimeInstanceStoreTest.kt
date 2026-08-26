package com.foxhole.core.runtime

import com.foxhole.core.model.VpnSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class RuntimeInstanceStoreTest {
    @Test
    fun `runtime owner is created once under concurrent callbacks`() {
        val createCount = AtomicInteger(0)
        val store =
            RuntimeInstanceStore {
                Thread.sleep(10L)
                FakeStoreRuntime(createCount.incrementAndGet())
            }
        val ready = CountDownLatch(CONCURRENT_CALLS)
        val start = CountDownLatch(1)
        val done = CountDownLatch(CONCURRENT_CALLS)
        val results = Collections.synchronizedList(mutableListOf<FoxholeRuntime>())

        repeat(CONCURRENT_CALLS) {
            Thread {
                ready.countDown()
                start.await(1, TimeUnit.SECONDS)
                results += store.get()
                done.countDown()
            }.start()
        }
        assertEquals(true, ready.await(1, TimeUnit.SECONDS))
        start.countDown()
        assertEquals(true, done.await(2, TimeUnit.SECONDS))

        assertEquals(1, createCount.get())
        val owner = store.current()
        results.forEach { runtime -> assertSame(owner, runtime) }
    }

    @Test
    fun `clear drops stopped owner before handoff creates replacement`() {
        val createCount = AtomicInteger(0)
        val store = RuntimeInstanceStore { FakeStoreRuntime(createCount.incrementAndGet()) }

        val first = store.get()
        store.clear()
        val second = store.get()

        assertEquals(2, createCount.get())
        assertNotSame(first, second)
    }

    @Test
    fun `current and snapshot do not create runtime owner`() {
        val createCount = AtomicInteger(0)
        val store = RuntimeInstanceStore { FakeStoreRuntime(createCount.incrementAndGet()) }

        assertEquals(null, store.current())
        assertEquals(NativeRuntimeSnapshot.NONE, store.nativeSnapshot())
        assertEquals(0, createCount.get())
    }

    @Test
    fun `stale service destroy cannot detach a runtime claimed by its successor`() {
        val createCount = AtomicInteger(0)
        val store = RuntimeInstanceStore { FakeStoreRuntime(createCount.incrementAndGet()) }
        val oldOwner = store.claimServiceOwner()
        val runtime = store.get()
        val replacementOwner = store.claimServiceOwner()

        assertEquals(oldOwner.generation + 1L, replacementOwner.generation)
        assertEquals(false, store.beginServiceDestroy(oldOwner))
        assertNull(store.detachCurrentForServiceDestroy(oldOwner))
        assertSame(runtime, store.current())
        assertEquals(true, store.beginServiceDestroy(replacementOwner))
        assertSame(runtime, store.detachCurrentForServiceDestroy(replacementOwner))
        store.sealServiceDestroy(replacementOwner) {}
        assertNull(store.current())
        val successorOwner = store.claimServiceOwner()
        assertThrows(IllegalStateException::class.java) { store.get() }
        assertEquals(
            false,
            runBlocking {
                store.prepareServiceOwnerForRuntime(successorOwner, timeoutMs = 1L) {}
            },
        )
        store.completeServiceDestroyRuntime(replacementOwner)
        var cleanupRan = false
        assertEquals(
            true,
            runBlocking {
                store.prepareServiceOwnerForRuntime(successorOwner, timeoutMs = 1_000L) {
                    cleanupRan = true
                }
            },
        )
        assertEquals(true, cleanupRan)
        assertNotSame(runtime, store.get())
        assertEquals(2, createCount.get())
    }

    @Test
    fun `orphan cleanup waits for every live and retired runtime then transfers to successor`() {
        val createCount = AtomicInteger(0)
        val store = RuntimeInstanceStore { FakeStoreRuntime(createCount.incrementAndGet()) }
        val owner = store.claimServiceOwner()
        val retired = store.get()
        assertSame(retired, store.retireCurrent())
        val live = store.get()
        var cleanupCount = 0

        assertEquals(true, store.beginServiceDestroy(owner))
        assertSame(live, store.detachCurrentForServiceDestroy(owner))
        assertSame(retired, store.takeRetiredForServiceDestroy(owner))
        store.sealServiceDestroy(owner) { cleanupCount += 1 }

        store.completeServiceDestroyRuntime(owner)
        assertEquals(0, cleanupCount)
        assertThrows(IllegalStateException::class.java) { store.get() }

        val successorOwner = store.claimServiceOwner()
        store.completeServiceDestroyRuntime(owner)
        assertEquals(0, cleanupCount)
        assertEquals(
            true,
            runBlocking {
                store.prepareServiceOwnerForRuntime(successorOwner, timeoutMs = 1_000L) {
                    cleanupCount += 1
                }
            },
        )
        assertEquals(1, cleanupCount)
        assertNotSame(live, store.get())
    }

    @Test
    fun `destroying a waiting successor preserves the predecessor drain for the next owner`() {
        val store = RuntimeInstanceStore { FakeStoreRuntime(1) }
        val firstOwner = store.claimServiceOwner()
        store.get()
        var cleanupCount = 0
        assertEquals(true, store.beginServiceDestroy(firstOwner))
        assertEquals(true, store.detachCurrentForServiceDestroy(firstOwner) != null)
        store.sealServiceDestroy(firstOwner) { cleanupCount += 1 }

        val waitingOwner = store.claimServiceOwner()
        assertEquals(true, store.beginServiceDestroy(waitingOwner))
        store.sealServiceDestroy(waitingOwner) { cleanupCount += 1 }
        val finalOwner = store.claimServiceOwner()

        assertEquals(
            false,
            runBlocking {
                store.prepareServiceOwnerForRuntime(finalOwner, timeoutMs = 1L) {
                    cleanupCount += 1
                }
            },
        )
        store.completeServiceDestroyRuntime(firstOwner)
        assertEquals(0, cleanupCount)
        assertEquals(
            true,
            runBlocking {
                store.prepareServiceOwnerForRuntime(finalOwner, timeoutMs = 1_000L) {
                    cleanupCount += 1
                }
            },
        )
        assertEquals(1, cleanupCount)
    }

    private companion object {
        const val CONCURRENT_CALLS = 32
    }
}

private class FakeStoreRuntime(
    private val generation: Int,
) : FoxholeRuntime {
    override suspend fun start(
        session: VpnSession,
        host: RuntimeServiceHost,
    ): Result<Unit> = Result.success(Unit)

    override suspend fun reload(
        session: VpnSession,
        host: RuntimeServiceHost,
    ): Result<Unit> = Result.success(Unit)

    override suspend fun quiesceForInterfaceHandover(): Boolean = true

    override suspend fun stop(policy: RuntimeStopPolicy): RuntimeStopResult =
        RuntimeStopResult(
            closeServiceOk = true,
            closeServerOk = true,
            tunClosed = true,
            escalatedToKill = false,
            elapsedMs = 0L,
        )

    override fun nativeSnapshot(): NativeRuntimeSnapshot =
        NativeRuntimeSnapshot.NONE.copy(nativeGeneration = generation.toLong())
}
