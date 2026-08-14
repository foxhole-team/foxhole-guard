package com.foxhole.core.runtime

import android.content.Context
import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.VpnSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

/** The native worker stops before replacement start while the old master TUN stays held. */
class RuntimeInterfaceHandoverTest {
    @Test
    fun `the incoming interface is established before the retired one is closed`() =
        runBlocking {
            val operations = Collections.synchronizedList(mutableListOf<String>())
            val outgoing = RecordingRuntime("guard", operations)
            val store = storeOf(outgoing)

            assertSame(outgoing, store.get())
            assertTrue(store.quiesceAndRetireCurrent())

            store.establishNextInterfaceThenStopRetired(
                stopRetired = { retired -> retired.stop() },
            ) {
                store.get().start(SESSION, StubHost)
            }

            assertEquals(listOf("guard:quiesce", "tunnel:establish", "guard:close"), operations.toList())
        }

    @Test
    fun `post-start validation begins only after the retired interface is closed`() =
        runBlocking {
            val operations = Collections.synchronizedList(mutableListOf<String>())
            val outgoing = RecordingRuntime("guard", operations)
            val store = storeOf(outgoing)

            store.get()
            assertTrue(store.quiesceAndRetireCurrent())
            val validate =
                store.establishNextInterfaceThenStopRetired(
                    stopRetired = { retired -> retired.stop() },
                ) {
                    store.get().start(SESSION, StubHost)
                    suspend { operations += "tunnel:validate" }
                }

            validate()

            assertEquals(
                listOf("guard:quiesce", "tunnel:establish", "guard:close", "tunnel:validate"),
                operations.toList(),
            )
        }

    @Test
    fun `a worker that cannot quiesce is not retired`() =
        runBlocking {
            val operations = Collections.synchronizedList(mutableListOf<String>())
            val outgoing = RecordingRuntime("guard", operations, quiesceSucceeds = false)
            val store = storeOf(outgoing)

            assertSame(outgoing, store.get())
            assertFalse(store.quiesceAndRetireCurrent())

            assertSame(outgoing, store.current())
            assertFalse(store.hasRetired())
            assertEquals(listOf("guard:quiesce"), operations.toList())
        }

    @Test
    fun `the retired interface is closed after a failed incoming start`() =
        runBlocking {
            val operations = Collections.synchronizedList(mutableListOf<String>())
            val outgoing = RecordingRuntime("guard", operations)
            val store = storeOf(outgoing, incomingStartSucceeds = false)

            assertSame(outgoing, store.get())
            assertTrue(store.quiesceAndRetireCurrent())

            val result =
                store.establishNextInterfaceThenStopRetired(
                    stopRetired = { retired -> retired.stop() },
                ) {
                    store.get().start(SESSION, StubHost)
                }

            assertTrue(result.isFailure)
            // Still last, and still present: a switch that failed must not leave the device bare,
            // and must not leave the retired runtime holding a TUN nobody owns either.
            assertEquals(listOf("guard:quiesce", "tunnel:establish", "guard:close"), operations.toList())
            assertFalse(store.hasRetired())
        }

    @Test
    fun `the retired interface is closed when the incoming start throws`() =
        runBlocking {
            val operations = Collections.synchronizedList(mutableListOf<String>())
            val outgoing = RecordingRuntime("guard", operations)
            val store = storeOf(outgoing)

            assertSame(outgoing, store.get())
            assertTrue(store.quiesceAndRetireCurrent())

            val thrown =
                runCatching {
                    store.establishNextInterfaceThenStopRetired(
                        stopRetired = { retired -> retired.stop() },
                    ) {
                        error("session build rejected the profile")
                    }
                }

            assertTrue(thrown.isFailure)
            assertEquals(listOf("guard:quiesce", "guard:close"), operations.toList())
            assertFalse(store.hasRetired())
        }

    @Test
    fun `a cancelled switch still closes the retired interface`() =
        runBlocking {
            val operations = Collections.synchronizedList(mutableListOf<String>())
            val outgoing = RecordingRuntime("guard", operations)
            val store = storeOf(outgoing)
            assertSame(outgoing, store.get())
            assertTrue(store.quiesceAndRetireCurrent())

            val entered = CompletableDeferred<Unit>()
            val closed = CompletableDeferred<Unit>()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val switch =
                scope.launch {
                    store.establishNextInterfaceThenStopRetired(
                        stopRetired = { retired ->
                            retired.stop()
                            closed.complete(Unit)
                        },
                    ) {
                        entered.complete(Unit)
                        // Never completes; the command is cancelled underneath it.
                        CompletableDeferred<Unit>().await()
                    }
                }

            withTimeout(TIMEOUT_MS) { entered.await() }
            switch.cancel()
            withTimeout(TIMEOUT_MS) { closed.await() }
            scope.cancel()

            assertEquals(listOf("guard:quiesce", "guard:close"), operations.toList())
            assertFalse(store.hasRetired())
        }

    @Test
    fun `retiring hands the incoming session a fresh owner while the old one stays alive`() {
        val operations = Collections.synchronizedList(mutableListOf<String>())
        val outgoing = RecordingRuntime("guard", operations)
        val store = storeOf(outgoing)

        val previous = store.get()
        runBlocking { assertTrue(store.quiesceAndRetireCurrent()) }
        val next = store.get()

        assertNotSame(previous, next)
        assertTrue(store.hasRetired())
        assertEquals(listOf("guard:quiesce"), operations.toList())
    }

    @Test
    fun `an abandoned handover is reaped by the next drain instead of being orphaned`() =
        runBlocking {
            val operations = Collections.synchronizedList(mutableListOf<String>())
            val store = storeOf(RecordingRuntime("guard", operations))

            store.get()
            assertTrue(store.quiesceAndRetireCurrent())
            // A second switch begins on top of an abandoned one: neither runtime may be dropped.
            store.get()
            assertTrue(store.quiesceAndRetireCurrent())

            store.stopEveryRetiredRuntime { retired -> retired.stop() }

            assertEquals(
                listOf("guard:quiesce", "tunnel:quiesce", "guard:close", "tunnel:close"),
                operations.toList(),
            )
            assertFalse(store.hasRetired())
        }

    private fun storeOf(
        first: RecordingRuntime,
        incomingStartSucceeds: Boolean = true,
    ): RuntimeInstanceStore {
        val operations = first.operations
        var created = 0
        return RuntimeInstanceStore {
            created += 1
            if (created == 1) {
                first
            } else {
                RecordingRuntime(
                    name = "tunnel",
                    operations = operations,
                    startSucceeds = incomingStartSucceeds,
                )
            }
        }
    }

    private companion object {
        const val TIMEOUT_MS = 2_000L
        val SESSION =
            VpnSession(
                profileId = 1L,
                profileName = "handover",
                protocolHint = ProtocolHint.LOCAL_GUARD,
                configJson = "{}",
                correlationId = "handover-test",
            )
    }
}

/**
 * Records the two operations that matter for the ordering: `start` is where a runtime establishes
 * its TUN, `stop` is where it closes one.
 */
private class RecordingRuntime(
    private val name: String,
    val operations: MutableList<String>,
    private val startSucceeds: Boolean = true,
    private val quiesceSucceeds: Boolean = true,
) : FoxholeRuntime {
    override suspend fun start(
        session: VpnSession,
        host: RuntimeServiceHost,
    ): Result<Unit> {
        operations += "$name:establish"
        return if (startSucceeds) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException("native start failed"))
        }
    }

    override suspend fun reload(
        session: VpnSession,
        host: RuntimeServiceHost,
    ): Result<Unit> = Result.success(Unit)

    override suspend fun quiesceForInterfaceHandover(): Boolean {
        operations += "$name:quiesce"
        return quiesceSucceeds
    }

    override suspend fun stop(policy: RuntimeStopPolicy): RuntimeStopResult {
        operations += "$name:close"
        return RuntimeStopResult(
            closeServiceOk = true,
            closeServerOk = true,
            tunClosed = true,
            escalatedToKill = false,
            elapsedMs = 0L,
        )
    }
}

/** Never touched: the fake runtimes above never reach the Android host. */
private object StubHost : RuntimeServiceHost {
    override val runtimeContext: Context
        get() = throw UnsupportedOperationException("no android context in a jvm handover test")

    override fun stopRuntimeService() = throw UnsupportedOperationException()

    override fun protectSocket(socket: Int): Boolean = throw UnsupportedOperationException()
}
