package com.foxhole.guard.traffic

import com.foxhole.core.model.NetworkActivityEvent
import com.foxhole.core.runtime.FoxholeRuntime
import com.foxhole.core.runtime.RuntimeDiagnosticsSink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Proxy

class NetworkJournalObservationOwnerTest {
    @Test fun queuedObservedTrafficNeverBorrowsTheReplacementProfile() = runBlocking {
        assertObservation(RuntimeNetworkActivityContext(11L, "owned-old-session"), 11L, "owned-old-session")
    }

    @Test fun explicitlyUnknownObservedContextNeverBorrowsAProfile() = runBlocking {
        assertObservation(RuntimeNetworkActivityContext(), null, null)
    }

    @Test fun legacyUncapturedCallerRetainsItsExplicitContext() = runBlocking {
        assertObservation(null, 22L, "owned-replacement-session")
    }

    private suspend fun assertObservation(captured: RuntimeNetworkActivityContext?, profileId: Long?, sessionId: String?) {
        var current = RuntimeNetworkActivityContext(11L, "owned-old-session")
        val snapshots = flow {
            val observed = RuntimeConnectionSnapshot(
                generation = 1,
                capturedActivityContext = captured,
                connections = listOf(
                    RuntimeConnectionRecord(
                        "owned", "vpn", "trojan", "default", "192.0.2.1:443", null,
                        "tcp", null, 1, 2, listOf("org.owned.fixture"),
                    )
                ),
            )
            current = RuntimeNetworkActivityContext(22L, "owned-replacement-session")
            emit(observed)
        }
        val result = CompletableDeferred<NetworkActivityEvent>()
        val sink = object : RuntimeDiagnosticsSink {
            override fun record(tag: String, message: String) = Unit
            override fun recordFailure(tag: String, message: String) = Unit
            override fun recordStructured(tag: String, headline: String, vararg details: String?) = Unit
        }
        val job = FoxCoreRuntimeStatsTracker(sink).start(
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.currentCoroutineContext()),
            snapshots,
            enabled = {
                true
            },
            networkActivityEnabled = { true },
            networkActivityContext = { current },
            onNetworkActivityEvents = { result.complete(it.single()) },
        )
        job.join()
        val event = result.await()
        assertEquals(profileId, event.profileId)
        assertEquals(sessionId, event.sessionId)
    }

    @Test fun ownerChangeDuringNativeObservationMakesHistoryIneligible() = runBlocking {
        var context = RuntimeNetworkActivityContext(11L, "owned-old-session")
        val runtime = Proxy.newProxyInstance(
            FoxholeRuntime::class.java.classLoader,
            arrayOf(FoxholeRuntime::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "runtimeTrafficMapJson" -> {
                    context = RuntimeNetworkActivityContext(22L, "owned-replacement-session")
                    "{\"generation\":1,\"connections\":[],\"omitted_rows\":0}"
                }
                "drainRuntimeAuditEventsJson", "drainRuntimeTrafficEventsJson" -> "{\"events\":[],\"dropped\":0}"
                else -> error("unexpected owned runtime method ${method.name}")
            }
        } as FoxholeRuntime
        val observed = FoxCoreRuntimeConnectionObserver({ runtime }).connectionSnapshots(
            flowOf(true),
            activityContext = { context },
        ).first()
        assertEquals(RuntimeNetworkActivityContext(), observed.capturedActivityContext)
        assertEquals(false, observed.historyObservationEligible)
    }
}
