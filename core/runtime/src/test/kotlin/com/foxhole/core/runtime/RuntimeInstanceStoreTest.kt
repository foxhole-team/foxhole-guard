package com.foxhole.core.runtime

import com.foxhole.core.model.VpnSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
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
