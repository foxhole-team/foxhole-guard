package com.foxhole.guard.core.diagnostics

import com.foxhole.core.runtime.FoxholeRuntime
import com.foxhole.core.runtime.RuntimeNativeStatsSource
import com.foxhole.core.runtime.RuntimeStatsReadLease
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.lang.reflect.Proxy

class NativeAggregateCounterExportTest {
    @Test
    fun `all exact u64 values including zero and max are retained`() {
        val values = NativeAggregateCounterKey.entries.associateWith { 0uL }.toMutableMap()
        values[NativeAggregateCounterKey.DNS_QUERIES] = ULong.MAX_VALUE
        val result = parseNativeAggregateCounters(document(values))
        assertEquals(NativeAggregateReadStatus.AVAILABLE, result.status)
        assertEquals(values, result.values)
    }

    @Test
    fun `relaxed counter relationships do not invalidate real scalar values`() {
        val values = NativeAggregateCounterKey.entries.associateWith { 0uL }.toMutableMap()
        values[NativeAggregateCounterKey.DNS_BLOCKED] = 3uL
        val result = parseNativeAggregateCounters(document(values))
        assertEquals(NativeAggregateReadStatus.AVAILABLE, result.status)
        assertEquals(3uL, result.values[NativeAggregateCounterKey.DNS_BLOCKED])
        assertEquals(0uL, result.values[NativeAggregateCounterKey.DNS_ALLOWED])
    }

    @Test
    fun `empty null malformed and overcap payloads cannot fabricate zeros`() {
        listOf(null, "{}", "[]", "not-json", " ".repeat(MAX_NATIVE_AGGREGATE_CHARS + 1)).forEach { raw ->
            val result = parseNativeAggregateCounters(raw)
            assertEquals(NativeAggregateReadStatus.UNAVAILABLE, result.status)
            assertTrue(result.values.isEmpty())
        }
    }

    @Test
    fun `missing or wrong typed counters are partial without coercion`() {
        listOf("-1", "1.0", "1e2", "true", "null", "\"17\"", "18446744073709551616").forEach { bad ->
            val result = parseNativeAggregateCounters("{\"dns_queries\":9,\"dns_blocked\":$bad}")
            assertEquals(NativeAggregateReadStatus.PARTIAL, result.status)
            assertEquals(9uL, result.values[NativeAggregateCounterKey.DNS_QUERIES])
            assertFalse(result.values.containsKey(NativeAggregateCounterKey.DNS_BLOCKED))
        }
    }

    @Test
    fun `unknown private data never enters a safe snapshot or rendering`() {
        val raw = document(NativeAggregateCounterKey.entries.associateWith { 2uL }).dropLast(1) +
            ",\"uid\":\"SAFE_PRIVATE_UID\",\"package\":\"SAFE_PRIVATE_PACKAGE\",\"domain\":\"SAFE_PRIVATE_DOMAIN\"," +
            "\"generation\":42,\"handle\":99,\"config\":{\"secret\":\"SAFE_PRIVATE_CONFIG\"}," +
            "\"selectors\":[\"SAFE_PRIVATE_SELECTOR\"],\"last_error\":\"SAFE_PRIVATE_ERROR\"}"
        val snapshot = parseNativeAggregateCounters(raw)
        val rendered = nativeAggregateCounterLines(snapshot).joinToString("\n")
        assertEquals(NativeAggregateReadStatus.AVAILABLE, snapshot.status)
        assertFalse(rendered.contains("SAFE_PRIVATE"))
        listOf("uid", "package", "domain", "generation", "handle", "config", "selectors", "last_error")
            .forEach { assertFalse(rendered.contains(it)) }
    }

    @Test
    fun `unsupported or missing runtime is explicitly unavailable`() = runBlocking {
        assertEquals(NativeAggregateUnavailableReason.NO_RUNTIME, collectNativeAggregateCounters { null }.reason)
        assertEquals(
            NativeAggregateUnavailableReason.UNSUPPORTED,
            collectNativeAggregateCounters { fakeRuntime(supported = false) }.reason,
        )
    }

    @Test
    fun `runtime replacement before read does not query the successor`() = runBlocking {
        var reads = 0
        val old = fakeRuntime {
            reads += 1
            document(NativeAggregateCounterKey.entries.associateWith { 1uL })
        }
        val next = fakeRuntime { error("successor must not be queried") }
        var access = 0
        val result = collectNativeAggregateCounters { if (access++ == 0) old else next }
        assertEquals(NativeAggregateUnavailableReason.OWNER_CHANGED, result.reason)
        assertEquals(0, reads)
    }

    @Test
    fun `runtime replacement during read discards output`() = runBlocking {
        var current: FoxholeRuntime? = null
        val next = fakeRuntime { error("successor must not be queried") }
        current = fakeRuntime {
            current = next
            document(NativeAggregateCounterKey.entries.associateWith { 1uL })
        }
        assertEquals(NativeAggregateUnavailableReason.OWNER_CHANGED, collectNativeAggregateCounters { current }.reason)
    }

    @Test
    fun `read uses owned IO rather than caller thread`() = runBlocking {
        val caller = Thread.currentThread()
        val runtime = fakeRuntime {
            assertFalse(Thread.currentThread() === caller)
            document(NativeAggregateCounterKey.entries.associateWith { 1uL })
        }
        assertEquals(NativeAggregateReadStatus.AVAILABLE, collectNativeAggregateCounters { runtime }.status)
    }

    @Test
    fun `ordinary read failure does not leak its message`() = runBlocking {
        val runtime = fakeRuntime { throw IOException("SAFE_PRIVATE_READ_FAILURE") }
        val result = collectNativeAggregateCounters { runtime }
        assertEquals(NativeAggregateReadStatus.UNAVAILABLE, result.status)
        assertFalse(nativeAggregateCounterLines(result).joinToString().contains("SAFE_PRIVATE_READ_FAILURE"))
    }

    @Test
    fun `cancelled capture cannot proceed to writer`() = runBlocking(Dispatchers.IO) {
        var wrote = false
        val cancelled = IdentityCancellation("cancel capture")
        val runtime = fakeRuntime { throw cancelled }
        val failure = runCatching {
            collectNativeAggregateCounters { runtime }
            wrote = true
        }.exceptionOrNull()
        assertSame(cancelled, failure)
        assertSame(cancelled.identity, (failure as IdentityCancellation).identity)
        assertFalse(wrote)
    }

    @Test
    fun `cancel and fatal errors escape outer export boundary with finally settlement`() = runBlocking {
        listOf<Throwable>(CancellationException("cancelled"), LinkageError("fatal")).forEach { failure ->
            var settled = false
            val actual = runCatching {
                prepareDiagnosticsAboutExport(onSettled = { settled = true }) { throw failure }
            }.exceptionOrNull()
            assertSame(failure, actual)
            assertTrue(settled)
        }
    }

    @Test
    fun `ordinary export failure settles flag and remains a typed failure`() = runBlocking {
        var settled = false
        val failure = IOException("untrusted message")
        val result = prepareDiagnosticsAboutExport(onSettled = { settled = true }) { throw failure }
        assertSame(failure, result.exceptionOrNull())
        assertTrue(settled)
    }

    @Test
    fun `manual snapshot providers propagate cancellation and fatal identity`() {
        listOf("session", "settings", "retention").forEach { provider ->
            listOf<Throwable>(CancellationException(provider), LinkageError(provider)).forEach { failure ->
                val actual = runCatching { ordinaryDiagnosticsSnapshotAttempt { throw failure } }.exceptionOrNull()
                assertSame(failure, actual)
            }
        }
    }

    @Test
    fun `runtime provider propagates original cancellation and fatal identity`() = runBlocking(Dispatchers.IO) {
        listOf<Throwable>(IdentityCancellation("provider"), IdentityFatal("provider")).forEach { failure ->
            val actual = runCatching { collectNativeAggregateCounters { throw failure } }.exceptionOrNull()
            assertSame(failure, actual)
            if (failure is IdentityCancellation) assertSame(failure.identity, (actual as IdentityCancellation).identity)
            if (failure is IdentityFatal) assertSame(failure.identity, (actual as IdentityFatal).identity)
        }
    }

    @Test
    fun `cancellation before archive write permits neither archive nor chooser`() {
        var wrote = false
        var shared = false
        val failure = runCatching {
            runBlocking {
                prepareNativeDiagnosticsArchive(
                    capture = {
                        currentCoroutineContext().job.cancel()
                        parseNativeAggregateCounters("{}")
                    },
                    write = { wrote = true },
                )
                shared = true
            }
        }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertFalse(wrote)
        assertFalse(shared)
    }

    @Test
    fun `chooser preserves cancellation and fatal identity with no fallback share`() = runBlocking {
        listOf<Throwable>(CancellationException("chooser"), LinkageError("chooser")).forEach { failure ->
            var fallbackShared = false
            val actual = runCatching {
                ordinaryDiagnosticsExportAttempt { throw failure }
                    .onFailure { fallbackShared = true }
            }.exceptionOrNull()
            assertSame(failure, actual)
            assertFalse(fallbackShared)
        }
    }

    @Test
    fun `caller cancellation before collection invokes no native reader`() {
        var reads = 0
        val failure = runCatching {
            runBlocking {
                currentCoroutineContext().job.cancel()
                collectNativeAggregateCounters {
                    fakeRuntime {
                        reads += 1
                        "{}"
                    }
                }
            }
        }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertEquals(0, reads)
    }

    @Test
    fun `cancel during capture or second provider check invokes zero native reads`() {
        listOf(true, false).forEach { cancelInCapture ->
            var reads = 0
            var writes = 0
            val actual = runCatching {
                runBlocking {
                    val caller = currentCoroutineContext().job
                    val runtime = fakeRuntime {
                        reads += 1
                        "{}"
                    }
                    var lookups = 0
                    val wrapped = Proxy.newProxyInstance(
                        FoxholeRuntime::class.java.classLoader,
                        arrayOf(FoxholeRuntime::class.java, RuntimeNativeStatsSource::class.java),
                    ) { _, method, _ ->
                        if (method.name == "captureStatsReadLease") {
                            if (cancelInCapture) caller.cancel()
                            (runtime as RuntimeNativeStatsSource).captureStatsReadLease()
                        } else {
                            null
                        }
                    } as FoxholeRuntime
                    prepareNativeDiagnosticsArchive(
                        capture = {
                            collectNativeAggregateCounters {
                                lookups += 1
                                if (!cancelInCapture && lookups == 2) caller.cancel()
                                wrapped
                            }
                        },
                        write = { writes += 1 },
                    )
                }
            }.exceptionOrNull()
            assertTrue(actual is CancellationException)
            assertEquals(0, reads)
            assertEquals(0, writes)
        }
    }

    private fun document(values: Map<NativeAggregateCounterKey, ULong>): String =
        values.entries.joinToString(prefix = "{", postfix = "}") { (key, value) -> "\"${key.jsonKey}\":$value" }

    @Test
    fun `standard cancellation retains its original cause and never writes or shares`() = runBlocking {
        val cancelled = CancellationException("standard cancel")
        var writes = 0
        var shares = 0
        val runtime = fakeRuntime { throw cancelled }
        val actual = runCatching {
            val archive = prepareNativeDiagnosticsArchive(
                capture = { collectNativeAggregateCounters { runtime } },
                write = { writes += 1 },
            )
            ordinaryDiagnosticsExportAttempt {
                shares += 1
                archive
            }
        }.exceptionOrNull()
        assertTrue(actual is CancellationException)
        assertSame(cancelled, actual?.cause ?: actual)
        assertEquals(0, writes)
        assertEquals(0, shares)
    }

    private class IdentityFatal(message: String) : LinkageError(message) {
        val identity = Any()
    }

    private class IdentityCancellation(message: String) : CancellationException(message) {
        val identity = Any()
    }

    private fun fakeRuntime(supported: Boolean = true, reader: (() -> String?)? = null): FoxholeRuntime {
        val interfaces = if (supported) {
            arrayOf(FoxholeRuntime::class.java, RuntimeNativeStatsSource::class.java)
        } else {
            arrayOf(FoxholeRuntime::class.java)
        }
        return Proxy.newProxyInstance(FoxholeRuntime::class.java.classLoader, interfaces) { _, method, _ ->
            if (method.name == "captureStatsReadLease" && reader != null) {
                RuntimeStatsReadLease::class.java.declaredConstructors.single()
                    .apply { isAccessible = true }.newInstance(reader)
            } else {
                null
            }
        } as FoxholeRuntime
    }
}
