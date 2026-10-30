package com.foxhole.guard.core.sentinel.anomaly

import com.foxhole.core.model.AnomalyEvent
import com.foxhole.core.model.AnomalySettings
import com.foxhole.core.model.ApkScanSettings
import com.foxhole.core.model.ExpertSettings
import com.foxhole.core.model.NetworkActivityEvent
import com.foxhole.core.model.Settings
import com.foxhole.core.runtime.RuntimeDiagnosticsSink
import com.foxhole.guard.core.data.AnomalyDao
import com.foxhole.guard.core.data.AnomalyEventEntity
import com.foxhole.guard.core.data.NetworkActivityEventEntity
import com.foxhole.guard.core.sentinel.scanner.SentinelScanSource
import com.foxhole.guard.core.sentinel.scanner.SentinelScanSummary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume

class NetworkJournalClearBoundaryTest {
    @Test
    fun queuedObservationCannotRepopulateClearedJournal() = runBlocking {
        val fixture = Fixture()
        val epoch = fixture.repository.captureNetworkActivityEpoch()
        val gate = CompletableDeferred<Unit>()
        val queued = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.await()
            fixture.repository.recordNetworkActivityEvents(listOf(event), epoch)
        }
        fixture.repository.clearNetworkActivityEvents()
        gate.complete(Unit)
        queued.join()
        assertTrue(fixture.rows.isEmpty())
        fixture.repository.recordNetworkActivityEvents(listOf(event), fixture.repository.captureNetworkActivityEpoch())
        assertEquals(1, fixture.rows.size)
    }

    @Test
    fun clearWaitsForAdmittedWriterThenDeletesItsRows() = runBlocking {
        val fixture = Fixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fixture.insert = { rows, continuation ->
            entered.complete(Unit)
            launch {
                release.await()

                fixture.rows.addAll(rows)

                continuation.resume(Unit)
            }
            COROUTINE_SUSPENDED
        }
        val writer = async { fixture.repository.recordNetworkActivityEvents(listOf(event), 0) }
        entered.await()
        val clear = async(start = CoroutineStart.UNDISPATCHED) { fixture.repository.clearNetworkActivityEvents() }
        assertFalse(clear.isCompleted)
        release.complete(Unit)
        writer.await()
        clear.await()
        assertTrue(fixture.rows.isEmpty())
        assertEquals(1, fixture.repository.captureNetworkActivityEpoch())
    }

    @Test
    fun failedDeleteKeepsRowsAndStillFencesQueuedOldObservation() = runBlocking {
        val fixture = Fixture()
        fixture.repository.recordNetworkActivityEvents(listOf(event), 0)
        val primary = IllegalStateException("owned delete failure")
        fixture.deleteFailure = primary
        val thrown = try {
            fixture.repository.clearNetworkActivityEvents()

            null
        } catch (failure: Throwable) { failure }
        assertSame(primary, thrown)
        fixture.repository.recordNetworkActivityEvents(listOf(event), 0)
        assertEquals(1, fixture.rows.size)
        assertEquals(1, fixture.repository.captureNetworkActivityEpoch())
    }

    @Test
    fun staticResultCapturedBeforeStatisticsClearCannotRestoreRowsOrNotify() = runBlocking {
        val fixture = Fixture(staticNotices = true)
        val old = staticSummary(fixture.repository.captureStaticScanJournalEpoch())
        val release = CompletableDeferred<Unit>()
        val queued = async(start = CoroutineStart.UNDISPATCHED) {
            release.await()
            fixture.repository.recordStaticScan(old) { true }
        }
        fixture.repository.clearTrafficStatistics()
        release.complete(Unit)
        assertFalse(queued.await())
        assertTrue(fixture.staticRows.isEmpty())
        assertTrue(fixture.notices.isEmpty())

        val fresh = staticSummary(fixture.repository.captureStaticScanJournalEpoch())
        assertTrue(fixture.repository.recordStaticScan(fresh) { true })
        assertEquals(1, fixture.staticRows.size)
        assertEquals(1, fixture.notices.size)
    }

    @Test
    fun failedStatisticsDeleteStillRejectsOldStaticResults() = runBlocking {
        val fixture = Fixture(staticNotices = true)
        val old = staticSummary(fixture.repository.captureStaticScanJournalEpoch())
        val failure = IllegalStateException("owned statistics delete failure")
        fixture.deleteFailure = failure
        val thrown = try {
            fixture.repository.clearTrafficStatistics()
            null
        } catch (error: Throwable) { error }
        assertSame(failure, thrown)
        assertFalse(fixture.repository.recordStaticScan(old) { true })
        assertTrue(fixture.staticRows.isEmpty())
        assertTrue(fixture.notices.isEmpty())
    }

    private fun staticSummary(epoch: Long) = SentinelScanSummary(
        source = SentinelScanSource("com.owned.fixture", "owned:1", epoch),
        complete = true,
        observations = setOf("owned.reference"),
        ruleIds = emptySet(),
    )

    private class Fixture(staticNotices: Boolean = false) {
        val rows = mutableListOf<NetworkActivityEventEntity>()
        val staticRows = mutableListOf<AnomalyEventEntity>()
        val notices = mutableListOf<AnomalyEvent>()
        var deleteFailure: Throwable? = null
        var insert: ((List<NetworkActivityEventEntity>, Continuation<Unit>) -> Any?)? = null
        private val dao = Proxy.newProxyInstance(
            AnomalyDao::class.java.classLoader,
            arrayOf(AnomalyDao::class.java)
        ) { _, method, args ->
            when (method.name) {
                "insertNetworkActivityEvents" -> {
                    @Suppress("UNCHECKED_CAST")
                    val batch = args!![0] as List<NetworkActivityEventEntity>

                    @Suppress("UNCHECKED_CAST")
                    val continuation = args.last() as Continuation<Unit>
                    insert?.invoke(batch, continuation) ?: run {
                        rows.addAll(batch)

                        Unit
                    }
                }
                "deleteNetworkActivityEvents" -> {
                    deleteFailure?.let { throw it }

                    rows.clear()

                    Unit
                }
                "deleteNetworkActivityEventsBefore" -> Unit
                "deleteTrafficWindowsBefore" -> {
                    deleteFailure?.let { throw it }
                    Unit
                }
                "deleteAnomalyEventsBefore" -> {
                    staticRows.clear()
                    Unit
                }
                "deleteAppTrafficWindowsBefore", "deleteTrafficBaselines", "deleteAppBaselines",
                "deleteAllProtocolMetricEvents", "deleteSeenDestinationCountries", "deleteAppNetworkPresence",
                "deleteAnomalyCounters", "markAnomalyNotificationShown" -> Unit
                "insertAnomalyEvent" -> {
                    staticRows.add(args!![0] as AnomalyEventEntity)
                    staticRows.size.toLong()
                }
                "globalNotificationCountSince" -> 0
                else -> throw AssertionError("unexpected DAO call: ${method.name}")
            }
        } as AnomalyDao
        private val state = MutableStateFlow(
            Settings(
                anomaly = AnomalySettings(
                    enabled = staticNotices,
                    apkScan = ApkScanSettings(notifyFindings = staticNotices)
                ),
                expert = ExpertSettings(networkActivityLogging = true),
            )
        )
        private val reader = object : SentinelSettingsReader {
            override val settings = state
            override suspend fun current() = state.value
        }
        private val sink = object : RuntimeDiagnosticsSink {
            override fun record(tag: String, message: String) = Unit
            override fun recordStructured(tag: String, headline: String, vararg details: String?) = Unit
        }
        val repository = AnomalyRepository(
            { dao },
            reader,
            sink,
            {
                notices.add(it)
                true
            },
            nowProvider = { 1_000_000L },
            staticScanTransaction = { operation -> operation() },
        )
    }

    private companion object {
        val event = NetworkActivityEvent(
            timestampMs = 1_000_000L,
            packageNames = listOf(
                "com.owned.fixture"
            ),
            protocol = "tcp", remoteHost = "owned.invalid", remotePort = 443, countryCode = null, bytesRx = 29, bytesTx = 17, profileId = null, sessionId = null
        )
    }
}
