package com.foxhole.guard.runtime

import com.foxhole.core.model.ConnectionSnapshot
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.TrafficMode
import com.foxhole.core.runtime.RuntimeCommand
import com.foxhole.core.runtime.RuntimeCommandPriority
import com.foxhole.core.runtime.priority
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouterOwnedDisconnectTest {
    private val owner = RouterOwnedDisconnect(7L, "hysteria2", 11L, 42L, 3L, 55L, "192.168.1.1", 8443, 10_000L, 9L)
    private val current = ConnectionSnapshot(state = ConnectionState.CONNECTED, trafficMode = TrafficMode.TUNNEL,
        profileId = 7L, protocolOptionId = "hysteria2")
    private fun accepts(snapshot: ConnectionSnapshot = current, active: Long? = 7L, option: String? = "hysteria2",
        native: Long? = 11L, network: Long? = 42L, request: Long = 3L, wifi: Long? = 55L, now: Long = 10_100L, authority: Long = 9L) =
        owner.matches(snapshot, active, option, native, network, request, wifi, now, authority)

    @Test fun exactSessionCanConsumeHomeReturn() { assertTrue(accepts()) }
    @Test fun aNewConnectRequestBeforeServiceConsumptionInvalidatesOldStop() {
        assertFalse(accepts(request = 4L))
        assertFalse(accepts(snapshot = current.copy(state = ConnectionState.CONNECTING)))
        assertFalse(accepts(snapshot = current.copy(profileId = 8L)))
        assertFalse(accepts(active = 8L))
    }
    @Test fun reusedProfileWithDifferentProtocolOrNativeSessionCannotBeStopped() {
        assertFalse(accepts(snapshot = current.copy(protocolOptionId = "vless")))
        assertFalse(accepts(option = "vless"))
        assertFalse(accepts(native = 12L))
        assertFalse(accepts(network = 43L))
        assertFalse(accepts(native = null))
        assertFalse(accepts(network = null))
    }
    @Test fun stoppedOrOtherRuntimeDoesNotConsumeAnOldReturn() {
        assertFalse(accepts(snapshot = current.copy(state = ConnectionState.DISCONNECTING)))
        assertFalse(accepts(snapshot = current.copy(state = ConnectionState.IDLE)))
        assertFalse(accepts(active = null))
    }
    @Test fun lostOrChangedHomeWifiAndExpiredProofNeverStopTheVpn() {
        assertFalse(accepts(wifi = null))
        assertFalse(accepts(wifi = 56L))
        assertFalse(accepts(now = 9_999L))
        assertFalse(accepts(now = 15_001L))
    }
    @Test fun conditionalHomeReturnNeverUsesThePreemptiveUserStopQueue() {
        val command = routerOwnedDisconnectCommand()
        assertTrue(command is RuntimeCommand.Reload)
        assertTrue(command.priority.value < RuntimeCommandPriority.STOP.value)
    }
    @Test fun pairingOrPreferenceChangesAfterProofInvalidateQueuedReturn() {
        assertFalse(accepts(authority = 10L))
        assertFalse(accepts(authority = 8L))
        assertTrue(accepts(authority = 9L))
    }
    @Test fun pendingNewStartIsNotCancelledByAnOlderHomeReturn() = kotlinx.coroutines.runBlocking {
        // The production mailbox explicitly uses IO/Default; a virtual test clock cannot drain it.
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        val supervisor = com.foxhole.core.runtime.RuntimeSupervisor(scope, diagnosticsLogger = null,
            emergencyKill = { error("Conditional home return must not kill runtime") })
        val started = kotlinx.coroutines.CompletableDeferred<Unit>()
        val releaseStart = kotlinx.coroutines.CompletableDeferred<Unit>()
        val completed = kotlinx.coroutines.CompletableDeferred<Unit>()
        val stopConsumed = kotlinx.coroutines.CompletableDeferred<Boolean>()
        val request = java.util.concurrent.atomic.AtomicLong(3L)
        try {
            supervisor.dispatch(RuntimeCommand.StartTunnel(8L, source = com.foxhole.core.runtime.RuntimeCommandSource.USER)) {
                started.complete(Unit)
                releaseStart.await()
                request.set(4L)
                completed.complete(Unit)
            }
            kotlinx.coroutines.withTimeout(3_000L) { started.await() }
            supervisor.dispatch(routerOwnedDisconnectCommand()) {
                stopConsumed.complete(accepts(request = request.get()))
            }
            assertFalse(stopConsumed.isCompleted)
            releaseStart.complete(Unit)
            kotlinx.coroutines.withTimeout(3_000L) { completed.await() }
            assertFalse(kotlinx.coroutines.withTimeout(3_000L) { stopConsumed.await() })
        } finally {
            supervisor.close()
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }
}
