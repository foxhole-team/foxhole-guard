package com.foxhole.guard.core.sentinel

import com.foxhole.core.model.ExpertSettings
import com.foxhole.core.model.Settings
import com.foxhole.core.model.StatisticsSettings
import com.foxhole.core.model.TunnelAppTrafficStats
import com.foxhole.core.runtime.RuntimeDiagnosticsSink
import com.foxhole.guard.runtime.runtimeConnectionStatsSnapshots
import com.foxhole.guard.traffic.FoxCoreRuntimeStatsTracker
import com.foxhole.guard.traffic.RuntimeConnectionRecord
import com.foxhole.guard.traffic.RuntimeConnectionSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

class SentinelObservationPrivacyTest {
    @Test fun resumedJournalAndAppStatsCannotCountBytesTransferredWhileDisabled() = runBlocking {
        TunnelAppTrafficStats.reset()
        val on = Settings(
            statistics = StatisticsSettings(enabled = true, appTrafficEnabled = true),
            expert = ExpertSettings(networkActivityLogging = true),
            appTrafficStatsEnabled = true,
            appTrafficUsageAccessConsent = true,
        )
        val state = MutableStateFlow(on)
        val native = MutableSharedFlow<RuntimeConnectionSnapshot>(replay = 1)
        val observed = Channel<Long>(4)
        val disabled = CompletableDeferred<Unit>()
        val sink = object : RuntimeDiagnosticsSink {
            override fun record(tag: String, message: String) = Unit
            override fun recordStructured(tag: String, headline: String, vararg details: String?) = Unit
        }
        native.emit(snapshot(8, 16))
        val snapshots = runtimeConnectionStatsSnapshots(
            state,
            enabled = { it.expert.networkActivityLogging || it.appTrafficStatsEnabled },
            snapshots = { native },
        ).onEach { if (it.generation == 0L)disabled.complete(Unit) }
        val job = FoxCoreRuntimeStatsTracker(sink).start(
            CoroutineScope(currentCoroutineContext()),
            snapshots,
            enabled = { state.value.expert.networkActivityLogging || state.value.appTrafficStatsEnabled },
            networkActivityEnabled = { state.value.expert.networkActivityLogging },
            tunnelAppTrafficEnabled = { state.value.appTrafficStatsEnabled && state.value.appTrafficUsageAccessConsent },
            onNetworkActivityEvents = { events -> observed.trySend(events.sumOf { it.bytesTx + it.bytesRx }) },
        )
        try {
            assertEquals(24L, withTimeout(3_000) { observed.receive() })
            TunnelAppTrafficStats.drain()
            state.value = Settings()
            withTimeout(3_000) {
                disabled.await()
                native.subscriptionCount.first { it == 0 }
            }
            native.emit(snapshot(10_000, 10_000))
            state.value = on
            withTimeout(3_000) { native.subscriptionCount.first { it > 0 } }
            native.emit(snapshot(10_000, 10_000))
            assertEquals(
                "resume establishes a baseline without OFF-interval bytes",
                0L,
                withTimeout(3_000) { observed.receive() }
            )
            assertEquals(0L, TunnelAppTrafficStats.drain().values.sumOf { it.rxBytes + it.txBytes })
            native.emit(snapshot(10_010, 10_020))
            assertEquals(30L, withTimeout(3_000) { observed.receive() })
            assertEquals(30L, TunnelAppTrafficStats.drain().values.sumOf { it.rxBytes + it.txBytes })
        } finally {
            job.cancelAndJoin()
            observed.close()
            TunnelAppTrafficStats.reset()
        }
    }
    private fun snapshot(tx: Long, rx: Long) = RuntimeConnectionSnapshot(
        generation = 1,
        connections = listOf(
            RuntimeConnectionRecord(
                "owned-privacy-flow", "vpn", "vless", "owned", "owned.example:443", "owned.example", "tcp", "vless",
                tx, rx, listOf("com.owned.privacy"), uid = 11001,
            )
        ),
    )
}
