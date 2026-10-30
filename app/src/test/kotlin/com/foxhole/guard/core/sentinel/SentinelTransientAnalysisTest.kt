package com.foxhole.guard.core.sentinel

import com.foxhole.core.model.AnomalySettings
import com.foxhole.core.model.AnomalyType
import com.foxhole.core.model.ExpertSettings
import com.foxhole.core.model.ProtocolMetricEvent
import com.foxhole.core.model.ProtocolMetricEventKind
import com.foxhole.core.model.Settings
import com.foxhole.core.model.StatisticsSettings
import com.foxhole.core.model.ThreatIntelDocument
import com.foxhole.core.runtime.RuntimeDiagnosticsSink
import com.foxhole.core.sentinel.detection.NetworkIocMatcher
import com.foxhole.guard.core.data.AnomalyDao
import com.foxhole.guard.core.data.AnomalyEventEntity
import com.foxhole.guard.core.data.NetworkActivityEventEntity
import com.foxhole.guard.core.sentinel.anomaly.AnomalyRepository
import com.foxhole.guard.core.sentinel.anomaly.SentinelSettingsReader
import com.foxhole.guard.runtime.networkActivityStatsRuntimeEnabled
import com.foxhole.guard.traffic.FoxCoreRuntimeStatsTracker
import com.foxhole.guard.traffic.RuntimeConnectionRecord
import com.foxhole.guard.traffic.RuntimeConnectionSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList

class SentinelTransientAnalysisTest {
    @Test fun sentinelDoesNotRequireFullNetworkJournalToFindOwnedIoc() = runBlocking {
        exerciseSnapshot(sentinel = true, journal = false, findings = 1, journalRows = 0)
    }

    @Test fun sentinelAndJournalHaveIndependentFindingsAndHistory() = runBlocking {
        exerciseSnapshot(sentinel = true, journal = true, findings = 1, journalRows = 1)
    }

    @Test fun disabledSentinelAndJournalRetainNothing() = runBlocking {
        exerciseSnapshot(sentinel = false, journal = false, findings = 0, journalRows = 0)
    }

    @Test fun journalWithoutSentinelRecordsWithoutSecurityFinding() = runBlocking {
        exerciseSnapshot(sentinel = false, journal = true, findings = 0, journalRows = 1)
    }

    @Test fun cancelledMetricPersistenceCannotReturnOrdinaryFailure() = runBlocking {
        val dao = DaoBoundary()
        val cancelled = CancellationException("owned normal-classpath cancellation")
        dao.metricFailure = cancelled
        val sink = RecordingSink()
        assertSame(cancelled, thrownBy { repository(metricSettings(), dao, sink).recordProtocolMetricEvent(metric()) })
        assertTrue(sink.failures.isEmpty())
    }

    @Test fun ordinaryMetricIOExceptionRetainsExistingFailureHandling() = runBlocking {
        val dao = DaoBoundary()
        dao.metricFailure = IOException("owned normal-classpath storage failure")
        val sink = RecordingSink()
        repository(metricSettings(), dao, sink).recordProtocolMetricEvent(metric())
        assertEquals(1, sink.failures.size)
        assertEquals(0, dao.metricDeletes)
    }

    @Test fun fatalMetricErrorPropagates() = runBlocking {
        val dao = DaoBoundary()
        val fatal = AssertionError("owned normal-classpath fatal marker")
        dao.metricFailure = fatal
        assertSame(
            fatal,
            thrownBy { repository(metricSettings(), dao, RecordingSink()).recordProtocolMetricEvent(metric()) }
        )
    }

    private suspend fun exerciseSnapshot(sentinel: Boolean, journal: Boolean, findings: Int, journalRows: Int) {
        val settings = Settings(
            anomaly = AnomalySettings(enabled = sentinel),
            expert = ExpertSettings(networkActivityLogging = journal),
            statistics = StatisticsSettings(enabled = false),
            appTrafficStatsEnabled = false,
            appTrafficUsageAccessConsent = false,
        )
        val dao = DaoBoundary()
        val sink = RecordingSink()
        val repo = repository(settings, dao, sink)
        val snapshot = RuntimeConnectionSnapshot(
            generation = 1,
            connections = listOf(
                RuntimeConnectionRecord(
                    connectionId = "owned-normal-connection",
                    lane = "vpn",
                    outboundType = "vless",
                    outboundTag = "owned-outbound",
                    destination = "owned-ioc.example:443",
                    domain = "owned-ioc.example",
                    network = "tcp",
                    protocol = "vless",
                    bytesTx = 8,
                    bytesRx = 16,
                    packageNames = listOf("com.owned.fixture"),
                    uid = 11001,
                )
            ),
        )
        var observed = 0
        val job = FoxCoreRuntimeStatsTracker(sink).start(
            scope = CoroutineScope(currentCoroutineContext()),
            runtimeConnectionSnapshots = flowOf(snapshot),
            enabled = { true },
            networkActivityEnabled = { networkActivityStatsRuntimeEnabled(settings) },
            tunnelAppTrafficEnabled = { false },
            onNetworkActivityEvents = { events ->
                observed += events.size
                runBlocking { repo.recordNetworkActivityEvents(events) }
            },
        )
        try {
            withTimeout(3_000) { job.join() }
            assertEquals("owned IOC finding", findings, dao.findings.size)
            assertEquals("complete history must honor its own toggle", journalRows, dao.networkRows.size)
            assertEquals(if (sentinel || journal) 1 else 0, observed)
            dao.findings.singleOrNull()?.let { finding ->
                assertEquals("com.owned.fixture", finding.packageName)
                assertEquals(AnomalyType.KNOWN_THREAT_DESTINATION.name, finding.type)
            }
            assertEquals("Sentinel must not widen app-baseline consent", 0, dao.appWrites)
        } finally {
            job.cancelAndJoin()
        }
    }

    private fun repository(settings: Settings, dao: DaoBoundary, sink: RecordingSink): AnomalyRepository {
        val state = MutableStateFlow(settings)
        val reader = object : SentinelSettingsReader {
            override val settings = state
            override suspend fun current() = state.value
        }
        return AnomalyRepository(
            daoProvider = { dao.value },
            settingsRepository = reader,
            diagnosticsLogger = sink,
            notify = { false },
            networkIocMatcher = { NetworkIocMatcher(ThreatIntelDocument(domains = listOf("owned-ioc.example"))) },
            nowProvider = { 1_000_000L },
        )
    }

    private fun metricSettings() = Settings(statistics = StatisticsSettings(enabled = true))
    private fun metric() = ProtocolMetricEvent(1_000L, 1L, null, "vless", ProtocolMetricEventKind.PROBE_SUCCESS, 10L)
    private suspend fun thrownBy(
        block: suspend () -> Unit
    ): Throwable? = try {
        block()
        null
    } catch (e: Throwable) { e }

    private class RecordingSink : RuntimeDiagnosticsSink {
        val failures = CopyOnWriteArrayList<String>()
        override fun record(tag: String, message: String) { failures.add("$tag:$message") }
        override fun recordStructured(tag: String, headline: String, vararg details: String?) = Unit
    }

    private class DaoBoundary {
        val networkRows = mutableListOf<NetworkActivityEventEntity>()
        val findings = mutableListOf<AnomalyEventEntity>()
        var metricFailure: Throwable? = null
        var metricDeletes = 0
        var appWrites = 0
        val value = Proxy.newProxyInstance(
            AnomalyDao::class.java.classLoader,
            arrayOf(AnomalyDao::class.java)
        ) { _, method, args ->
            when (method.name) {
                "insertNetworkActivityEvents" -> {
                    @Suppress("UNCHECKED_CAST")
                    networkRows.addAll(
                        args!![0] as List<NetworkActivityEventEntity>
                    )
                    Unit
                }
                "deleteNetworkActivityEventsBefore" -> Unit
                "latestAnomalyEventSince" -> null
                "insertAnomalyEvent" -> {
                    findings.add(args!![0] as AnomalyEventEntity)
                    1L
                }
                "insertProtocolMetricEvent" -> {
                    metricFailure?.let { throw it }
                    1L
                }
                "deleteProtocolMetricEventsBefore" -> {
                    metricDeletes++
                    Unit
                }
                "insertAppTrafficWindows", "upsertAppBaseline", "upsertTrafficBaseline" -> {
                    appWrites++
                    Unit
                }
                else -> throw AssertionError("unexpected DAO method: ${method.name}")
            }
        } as AnomalyDao
    }
}
