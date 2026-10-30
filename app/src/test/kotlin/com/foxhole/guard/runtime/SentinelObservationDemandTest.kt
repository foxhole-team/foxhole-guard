package com.foxhole.guard.runtime

import com.foxhole.core.model.AnomalySensitivity
import com.foxhole.core.model.AnomalySettings
import com.foxhole.core.model.Settings
import com.foxhole.guard.traffic.RuntimeConnectionSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SentinelObservationDemandTest {
    @Test fun flagsSwitchOneObservationSourceWithoutRestartOrBacklog() = runBlocking {
        val settings = MutableStateFlow(Settings())
        val observed = Channel<RuntimeConnectionSnapshot>(4)
        val stopped = CompletableDeferred<Unit>()
        var active = 0
        var sourceStarts = 0
        val source = flow {
            sourceStarts++
            active++
            try {
                emit(RuntimeConnectionSnapshot(generation = 7))
                awaitCancellation()
            } finally {
                active--
                stopped.complete(Unit)
            }
        }
        val job = launch {
            runtimeConnectionStatsSnapshots(settings, ::networkActivityStatsRuntimeEnabled) { source }
                .collect { observed.send(it) }
        }
        try {
            assertEquals(0L, withTimeout(3_000) { observed.receive() }.generation)
            assertEquals(0, active)
            settings.value = Settings(anomaly = AnomalySettings(enabled = true))
            assertEquals(7L, withTimeout(3_000) { observed.receive() }.generation)
            assertEquals(1, active)
            settings.value = settings.value.copy(anomaly = settings.value.anomaly.copy(sensitivity = AnomalySensitivity.STRICT))
            settings.value = Settings()
            assertEquals(0L, withTimeout(3_000) { observed.receive() }.generation)
            withTimeout(3_000) { stopped.await() }
            assertEquals(0, active)
            assertEquals(1, sourceStarts)
            assertTrue(job.isActive)
        } finally {
            job.cancelAndJoin()
            observed.close()
        }
    }
}
