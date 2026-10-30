package com.foxhole.guard.traffic

import com.foxhole.core.runtime.FoxholeRuntime
import com.foxhole.core.runtime.RuntimeDiagnosticsSink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

private class OwnedObserverFatal(val marker: Any = Any()) : Error("owned observer fatal")

class RuntimeConnectionEventReducerTest {
    @Test fun trackerPreservesEpochAndSkipsOnlyHistoryBoundaryEvents() = runBlocking {
        val sink = object : RuntimeDiagnosticsSink {
            override fun record(tag: String, message: String) = Unit
            override fun recordStructured(tag: String, headline: String, vararg details: String?) = Unit
        }
        val emitted = mutableListOf<Pair<Long, Long>>()
        val first = parseTrafficMap(
            nativeLive(7, 17, 29),
            true
        ).copy(observationEpoch = 11, historyObservationEligible = false)
        val second = parseTrafficMap(
            nativeLive(7, 20, 35),
            true
        ).copy(observationEpoch = 11, historyObservationEligible = true)
        val job = FoxCoreRuntimeStatsTracker(sink).start(
            scope = CoroutineScope(kotlinx.coroutines.currentCoroutineContext()),
            runtimeConnectionSnapshots = listOf(first, second).asFlow(),
            enabled = { true },
            networkActivityEnabled = { true },
            onNetworkActivityEvents = { throw AssertionError("owned epoch callback must be selected") },
            onEpochNetworkActivityEvents = { events, epoch -> emitted += epoch to events.sumOf { it.totalBytes } },
        )
        job.join()
        assertEquals(listOf(11L to 9L), emitted)
    }

    @Test fun firstStableDrainAfterClearCannotBecomeHistoryButNextDrainCan() = runBlocking {
        val runtime = fakeRuntime({ nativeEmpty(7) }, { EMPTY_DRAIN })
        val observer = FoxCoreRuntimeConnectionObserver({ runtime }, pollingIntervalMillis = 1)
        val snapshots = observer.connectionSnapshots(flowOf(true), resetEpoch = { 1 }).take(2).toList()
        assertEquals(listOf(1L, 1L), snapshots.map { it.observationEpoch })
        assertEquals(listOf(false, true), snapshots.map { it.historyObservationEligible })
        assertEquals(listOf(7L, 7L), snapshots.map { it.generation })
    }

    @Test fun observerCancellationStopsWithoutUnavailableSnapshot() = runBlocking {
        val error = CancellationException("owned observer cancel")
        val observer = FoxCoreRuntimeConnectionObserver({ fakeRuntime({ throw error }, { EMPTY_DRAIN }) })
        // flatMapLatest treats its cancelled child as stopped; it must not emit an ordinary error row.
        val snapshots = observer.connectionSnapshots(kotlinx.coroutines.flow.flowOf(true)).take(1).toList()
        assertTrue(snapshots.isEmpty())
    }

    @Test fun observerFatalPropagatesInsteadOfUnavailableSnapshot() = runBlocking {
        val error = OwnedObserverFatal()
        val observer = FoxCoreRuntimeConnectionObserver({ fakeRuntime({ throw error }, { EMPTY_DRAIN }) })
        val thrown = try {
            observer.connectionSnapshots(kotlinx.coroutines.flow.flowOf(true)).take(1).toList()
            null
        } catch (failure: Throwable) { failure }
        assertSame(error, thrown)
    }

    @Test fun observerOrdinaryFailureEmitsUnavailableSnapshot() = runBlocking {
        val observer = FoxCoreRuntimeConnectionObserver(
            { fakeRuntime({ throw IllegalStateException("owned unavailable") }, { EMPTY_DRAIN }) }
        )
        val result = observer.connectionSnapshots(kotlinx.coroutines.flow.flowOf(true)).take(1).toList().single()
        assertEquals(0L, result.generation)
        assertTrue(result.connections.isEmpty())
    }

    @Test fun generationGuardUsesTwoMapReadsAndOneDrainOfEachKind() = runBlocking {
        var maps = 0
        var traffic = 0
        var audits = 0
        val runtime = fakeRuntime(
            map = {
                maps++
                nativeEmpty(7)
            },
            traffic = {
                traffic++
                EMPTY_DRAIN
            },
            audit = {
                audits++
                EMPTY_DRAIN
            }
        )
        val observer = FoxCoreRuntimeConnectionObserver({ runtime })
        observer.connectionSnapshots(kotlinx.coroutines.flow.flowOf(true)).take(1).toList()
        assertEquals(2, maps)
        assertEquals(1, traffic)
        assertEquals(1, audits)
    }

    @Test fun sharedUidOpeningNeverBecomesOneExactPackage() {
        val drain = parseRuntimeTrafficDrain(
            NATIVE_OPENED_CLOSED.replace(
                "[\"com.example.safe\"]",
                "[\"com.example.one\",\"com.example.two\"]"
            )
        )
        val row = RuntimeConnectionEventReducer().reduce(
            RuntimeConnectionSnapshot(generation = 7),
            drain
        ).connections.single()
        assertTrue(row.packageNames.isEmpty())
        assertEquals(listOf("com.example.one", "com.example.two"), row.sharedUidCandidates)
    }

    @Test fun consentGateAlsoRemovesOwnersFromFreshLiveOutput() {
        val live = parseTrafficMap(nativeLive(7, 17, 29), includeProcessInfo = true)
        val result = RuntimeConnectionEventReducer().reduce(live, RuntimeTrafficDrain(), includeProcessInfo = false)
        assertTrue(result.connections.single().packageNames.isEmpty())
        assertNull(result.connections.single().uid)
    }

    @Test fun demandOffDestroysOpeningCacheAndStopsPollingBeforeResume() = runBlocking {
        val available = MutableStateFlow(true)
        val drain = AtomicReference(OPENING_ONLY_JSON)
        val reads = AtomicLong()
        val runtime = fakeRuntime(map = {
            reads.incrementAndGet()
            nativeEmpty(7)
        }, traffic = {
            drain.getAndSet(EMPTY_DRAIN)
        })
        val snapshots = mutableListOf<RuntimeConnectionSnapshot>()
        val observer = FoxCoreRuntimeConnectionObserver({ runtime }, pollingIntervalMillis = 1L)
        withTimeout(5_000) {
            observer.connectionSnapshots(available, includeProcessInfo = { true }).take(3).collect { snapshot ->
                snapshots += snapshot
                when (snapshots.size) {
                    1 -> available.value = false
                    2 -> {
                        val pausedReads = reads.get()
                        kotlinx.coroutines.delay(10)
                        assertEquals(pausedReads, reads.get())
                        drain.set(CLOSED_ONLY_JSON)
                        available.value = true
                    }
                }
            }
        }
        assertEquals(0L, snapshots[1].generation)
        assertTrue(snapshots[2].connections.single().packageNames.isEmpty())
        assertTrue(snapshots[2].truncated)
    }

    @Test fun drainCrossingNativeGenerationIsDiscarded() = runBlocking {
        val generation = AtomicLong(7)
        val runtime = fakeRuntime(map = { nativeEmpty(generation.get()) }, traffic = {
            generation.set(8)
            NATIVE_OPENED_CLOSED
        })
        val observer = FoxCoreRuntimeConnectionObserver({ runtime }, pollingIntervalMillis = 1L)
        val result = withTimeout(5_000) {
            observer.connectionSnapshots(kotlinx.coroutines.flow.flowOf(true), { true }).take(1).toList().single()
        }
        assertEquals(0L, result.generation)
        assertTrue(result.connections.isEmpty())
    }

    @Test fun duplicateCloseCannotReplayFinalBytesOrIdentity() {
        val reducer = RuntimeConnectionEventReducer()
        val drain = parseRuntimeTrafficDrain(NATIVE_OPENED_CLOSED)
        assertEquals(1, reducer.reduce(RuntimeConnectionSnapshot(generation = 7), drain).connections.size)
        assertTrue(reducer.reduce(RuntimeConnectionSnapshot(generation = 7), drain).connections.isEmpty())
    }

    @Test fun missingOpenedRemainsPartialAndUnknown() {
        val result = RuntimeConnectionEventReducer().reduce(RuntimeConnectionSnapshot(generation = 7), closedOnly())
        assertTrue(result.truncated)
        assertNull(result.connections.single().destination)
        assertTrue(result.connections.single().packageNames.isEmpty())
        assertEquals(17L, result.connections.single().bytesTx)
    }

    @Test fun droppedBatchInvalidatesCachedIdentity() {
        val reducer = seeded()
        val result = reducer.reduce(RuntimeConnectionSnapshot(generation = 7), closedOnly().copy(dropped = 1))
        assertTrue(result.truncated)
        assertTrue(result.connections.single().packageNames.isEmpty())
    }

    @Test fun explicitResetCannotReusePreviousIdentity() {
        val reducer = seeded()
        reducer.reset()
        assertTrue(
            reducer.reduce(
                RuntimeConnectionSnapshot(generation = 7),
                closedOnly()
            ).connections.single().packageNames.isEmpty()
        )
    }

    @Test fun resetEpochCannotReusePreviousIdentity() {
        val reducer = seeded()
        assertTrue(
            reducer.reduce(
                RuntimeConnectionSnapshot(generation = 7),
                closedOnly(),
                resetEpoch = 1
            ).connections.single().packageNames.isEmpty()
        )
    }

    @Test fun replacementGenerationCannotReusePreviousIdentity() {
        val reducer = seeded()
        assertTrue(
            reducer.reduce(
                RuntimeConnectionSnapshot(generation = 8),
                closedOnly()
            ).connections.single().packageNames.isEmpty()
        )
    }

    @Test fun disabledProcessInfoDropsOwnedAndSharedMetadata() {
        val reducer = seeded()
        val result = reducer.reduce(RuntimeConnectionSnapshot(generation = 7), closedOnly(), includeProcessInfo = false)
        assertTrue(result.connections.single().packageNames.isEmpty())
        assertTrue(result.connections.single().sharedUidCandidates.isEmpty())
        assertNull(result.connections.single().uid)
    }

    @Test fun boundedEvictionRemainsUnknownWithoutRecoveringOldOwner() {
        val drain = parseRuntimeTrafficDrain(NATIVE_OPENED_CLOSED)
        val reducer = RuntimeConnectionEventReducer(capacity = 1)
        reducer.reduce(
            RuntimeConnectionSnapshot(generation = 7),
            RuntimeTrafficDrain(
                openedConnections = listOf(
                    drain.openedConnections.single(),
                    drain.openedConnections.single().copy(connectionId = "8")
                )
            )
        )
        val result = reducer.reduce(RuntimeConnectionSnapshot(generation = 7), closedOnly())
        assertTrue(result.connections.single().packageNames.isEmpty())
        assertTrue(result.truncated)
    }

    @Test fun liveMetadataCanSupplyAClosingRowAndFinalTotalsReplaceLiveTotals() {
        val live = parseTrafficMap(nativeLive(7, 10, 20), includeProcessInfo = true)
        val result = RuntimeConnectionEventReducer().reduce(
            RuntimeConnectionSnapshot(generation = 7),
            closedOnly(),
            live.connections
        )
        assertEquals(listOf("com.example.safe"), result.connections.single().packageNames)
        assertEquals(17L, result.connections.single().bytesTx)
        assertEquals(29L, result.connections.single().bytesRx)
    }
    private fun seeded(): RuntimeConnectionEventReducer = RuntimeConnectionEventReducer().also { reducer ->
        val drain = parseRuntimeTrafficDrain(NATIVE_OPENED_CLOSED)
        reducer.reduce(
            RuntimeConnectionSnapshot(generation = 7),
            RuntimeTrafficDrain(openedConnections = drain.openedConnections)
        )
    }
    private fun closedOnly() = parseRuntimeTrafficDrain(
        """{"events":[{"type":"closed","id":7,"bytes_up":17,"bytes_down":29}],"dropped":0}"""
    )

    @Test
    fun nativeGenerationReplacementDoesNotSubtractPreviousGenerationSameIdBytes() = runBlocking {
        val snapshots = listOf(
            parseTrafficMap(nativeLive(7, 17, 29), includeProcessInfo = true),
            parseTrafficMap(nativeLive(8, 50, 60), includeProcessInfo = true),
        )
        val observed = mutableListOf<com.foxhole.core.model.NetworkActivityEvent>()
        val scope = CoroutineScope(SupervisorJob())
        try {
            val job = FoxCoreRuntimeStatsTracker(QuietSink).start(
                scope,
                runtimeConnectionSnapshots = snapshots.asFlow(),
                enabled = { true },
                networkActivityEnabled = { true },
                onNetworkActivityEvents = { observed.addAll(it) }
            )
            withTimeout(5_000) { job.join() }
            assertEquals(2, observed.size)
            assertEquals(50L, observed[1].bytesTx)
            assertEquals(60L, observed[1].bytesRx)
        } finally { scope.cancel() }
    }

    @Test
    fun openedThenMinimalClosedNativeSchemaRetainsOwnedMetadataForFinalTotals() = runBlocking {
        val drain = parseRuntimeTrafficDrain(NATIVE_OPENED_CLOSED)
        val snapshot = RuntimeConnectionEventReducer().reduce(RuntimeConnectionSnapshot(generation = 7L), drain)
        val observed = mutableListOf<com.foxhole.core.model.NetworkActivityEvent>()
        val scope = CoroutineScope(SupervisorJob())
        try {
            val job = FoxCoreRuntimeStatsTracker(QuietSink).start(
                scope,
                runtimeConnectionSnapshots = flowOf(snapshot),
                enabled = { true },
                networkActivityEnabled = { true },
                onNetworkActivityEvents = { observed.addAll(it) }
            )
            withTimeout(5_000) { job.join() }
            assertEquals("source-faithful opened metadata must reach the closed final observation", 1, observed.size)
            val event = observed.single()
            assertEquals(listOf("com.example.safe"), event.packageNames)
            assertEquals("short.example", event.remoteHost)
            assertEquals(443, event.remotePort)
            assertEquals("TCP", event.protocol)
            assertEquals(17L, event.bytesTx)
            assertEquals(29L, event.bytesRx)
        } finally { scope.cancel() }
    }
}

private fun nativeLive(generation: Long, up: Long, down: Long): String =
    """{"generation":$generation,"connections":[{"id":7,"transport":"tcp","host":"short.example","port":443,
    "route":{"lane":"vpn","outbound":"vless"},"packages":["com.example.safe"],"uid":10123,
    "bytes_up":$up,"bytes_down":$down}],"lanes":[],"dns":{}}"""

private object QuietSink : RuntimeDiagnosticsSink {
    override fun record(tag: String, message: String) = Unit
    override fun recordFailure(tag: String, message: String) = Unit
    override fun recordStructured(tag: String, headline: String, vararg details: String?) = Unit
}

internal const val NATIVE_OPENED_CLOSED = """{"events":[
{"type":"opened","id":7,"transport":"tcp","host":"short.example","port":443,
"route":{"lane":"vpn","outbound":"vless"},"packages":["com.example.safe"],"uid":10123},
{"type":"closed","id":7,"bytes_up":17,"bytes_down":29}],"dropped":0}"""

private const val EMPTY_DRAIN = """{"events":[],"dropped":0}"""
private const val CLOSED_ONLY_JSON = """{"events":[{"type":"closed","id":7,"bytes_up":17,"bytes_down":29}],"dropped":0}"""
private fun nativeEmpty(generation: Long) = """{"generation":$generation,"connections":[],"lanes":[],"dns":{}}"""
private const val OPENING_ONLY_JSON = """{"events":[{"type":"opened","id":7,"transport":"tcp","host":"short.example","port":443,
"route":{"lane":"vpn","outbound":"vless"},"packages":["com.example.safe"],"uid":10123}],"dropped":0}"""
private fun fakeRuntime(
    map: () -> String,
    traffic: () -> String,
    audit: () -> String = {
        EMPTY_DRAIN
    }
): FoxholeRuntime =
    Proxy.newProxyInstance(
        FoxholeRuntime::class.java.classLoader,
        arrayOf(FoxholeRuntime::class.java)
    ) { _, method, _ ->
        when (method.name) {
            "runtimeTrafficMapJson" -> map()
            "drainRuntimeTrafficEventsJson" -> traffic()
            "drainRuntimeAuditEventsJson" -> audit()
            else -> throw AssertionError("unexpected runtime call: ${method.name}")
        }
    } as FoxholeRuntime
