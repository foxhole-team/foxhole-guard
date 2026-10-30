package com.foxhole.guard.core.sentinel

import com.foxhole.core.model.AnomalySettings
import com.foxhole.core.model.Settings
import com.foxhole.core.model.StatisticsSettings
import com.foxhole.core.model.ThreatIntelDocument
import com.foxhole.core.runtime.RuntimeDiagnosticsSink
import com.foxhole.core.sentinel.detection.NetworkIocMatcher
import com.foxhole.guard.core.data.AnomalyDao
import com.foxhole.guard.core.data.AnomalyEventEntity
import com.foxhole.guard.core.sentinel.anomaly.AnomalyRepository
import com.foxhole.guard.core.sentinel.anomaly.SentinelSettingsReader
import com.foxhole.guard.runtime.networkActivityStatsRuntimeEnabled
import com.foxhole.guard.runtime.shouldIncludeRuntimeProcessInfo
import com.foxhole.guard.traffic.FoxCoreRuntimeStatsTracker
import com.foxhole.guard.traffic.parseTrafficMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class SentinelNativeSchemaTest {
    @Test fun sentinelOnlyDomainRowRetainsExactOwnerWithoutUsageConsent() = runBlocking {
        exercise("owned-ioc.example", ThreatIntelDocument(domains = listOf("owned-ioc.example")))
    }

    @Test fun sentinelOnlyIpv4RowRetainsExactOwnerWithoutUsageConsent() = runBlocking {
        exercise("203.0.113.7", ThreatIntelDocument(ips = listOf("203.0.113.7")))
    }

    @Test fun sentinelOnlyIpv6RowKeepsBareIndicatorAndSeparatePort() = runBlocking {
        exercise("2001:db8::7", ThreatIntelDocument(ips = listOf("2001:db8::7")))
    }

    @Test fun lateExactOwnershipWithoutNewBytesStillProducesOwnedIoc() = runBlocking {
        exercise(
            "owned-ioc.example",
            ThreatIntelDocument(domains = listOf("owned-ioc.example")),
            documents = listOf(
                nativeDocument("owned-ioc.example", "[]"),
                nativeDocument("owned-ioc.example", "[\"com.owned.fixture\"]"),
                nativeDocument("owned-ioc.example", "[\"com.owned.fixture\"]"),
            ),
        )
    }

    @Test fun unknownOwnerNeverBecomesAnInventedPackageOrGlobalIoc() = runBlocking {
        exercise(
            "owned-ioc.example",
            ThreatIntelDocument(domains = listOf("owned-ioc.example")),
            documents = listOf(nativeDocument("owned-ioc.example", "[]")),
            expectedFindings = 0
        )
    }

    @Test fun sharedUidCandidatesNeverBecomeAnArbitraryExactPackage() = runBlocking {
        exercise(
            "owned-ioc.example",
            ThreatIntelDocument(domains = listOf("owned-ioc.example")),
            documents = listOf(nativeDocument("owned-ioc.example", "[\"com.owned.first\",\"com.owned.second\"]")),
            expectedFindings = 0
        )
    }

    private suspend fun exercise(
        host: String,
        intel: ThreatIntelDocument,
        documents: List<String> = listOf(nativeDocument(host, "[\"com.owned.fixture\"]")),
        expectedFindings: Int = 1,
    ) {
        val settings = Settings(
            anomaly = AnomalySettings(enabled = true),
            statistics = StatisticsSettings(enabled = false),
            appTrafficStatsEnabled = false,
            appTrafficUsageAccessConsent = false,
        )
        val include = shouldIncludeRuntimeProcessInfo(
            networkActivityEnabled = networkActivityStatsRuntimeEnabled(settings),
            appTrafficEnabled = false,
        )
        assertTrue("Sentinel opt-in must permit transient exact ownership", include)
        val snapshots = documents.map { parseTrafficMap(it, include) }
        if (expectedFindings > 0) {
            val last = snapshots.last().connections.single()
            assertEquals(listOf("com.owned.fixture"), last.packageNames)
            assertEquals(if (host.contains(':')) null else host.takeIf { it == "owned-ioc.example" }, last.domain)
        }
        val findings = mutableListOf<AnomalyEventEntity>()
        var fullJournalWrites = 0
        var baselineWrites = 0
        val dao = Proxy.newProxyInstance(
            AnomalyDao::class.java.classLoader,
            arrayOf(AnomalyDao::class.java)
        ) { _, method, args ->
            when (method.name) {
                "latestAnomalyEventSince" -> null
                "insertAnomalyEvent" -> {
                    findings.add(args!![0] as AnomalyEventEntity)
                    1L
                }
                "insertNetworkActivityEvents" -> {
                    fullJournalWrites++
                    Unit
                }
                "deleteNetworkActivityEventsBefore" -> Unit
                "insertAppTrafficWindows", "upsertAppBaseline", "upsertTrafficBaseline" -> {
                    baselineWrites++
                    Unit
                }
                else -> throw AssertionError("unexpected DAO call: ${method.name}")
            }
        } as AnomalyDao
        val state = MutableStateFlow(settings)
        val reader = object : SentinelSettingsReader {
            override val settings = state
            override suspend fun current() = state.value
        }
        val sink = object : RuntimeDiagnosticsSink {
            override fun record(tag: String, message: String) = Unit
            override fun recordStructured(tag: String, headline: String, vararg details: String?) = Unit
        }
        val repo = AnomalyRepository(
            daoProvider = { dao },
            settingsRepository = reader,
            diagnosticsLogger = sink,
            notify = { false },
            networkIocMatcher = { NetworkIocMatcher(intel) },
            nowProvider = { 1_000_000L },
        )
        val job = FoxCoreRuntimeStatsTracker(sink).start(
            scope = CoroutineScope(currentCoroutineContext()),
            runtimeConnectionSnapshots = snapshots.asFlow(),
            enabled = { true },
            networkActivityEnabled = { networkActivityStatsRuntimeEnabled(state.value) },
            tunnelAppTrafficEnabled = { false },
            onNetworkActivityEvents = { events -> runBlocking { repo.recordNetworkActivityEvents(events) } },
        )
        try {
            withTimeout(3_000) { job.join() }
            assertEquals("native-schema IOC findings", expectedFindings, findings.size)
            assertEquals(0, fullJournalWrites)
            assertEquals(0, baselineWrites)
            findings.singleOrNull()?.let { assertEquals("com.owned.fixture", it.packageName) }
        } finally { job.cancelAndJoin() }
    }

    private fun nativeDocument(host: String, packages: String): String =
        """{
          "generation":1,"omitted_rows":0,"packages":[],"lanes":[],"selectors":[],
          "dns":{"queries":0,"blocked":0,"allowed":0},
          "connections":[{"id":1,"transport":"tcp","host":"$host","port":443,
            "outbound":"vless","route":{"lane":"vpn","outbound":"vless","outbound_id":"owned","member":"vless"},
            "packages":$packages,"uid":11001,"age_ms":10,"bytes_up":8,"bytes_down":16}]
        }"""
}
