package com.foxhole.beta.vpn

import com.foxhole.beta.core.model.ProtocolHint
import com.foxhole.beta.core.model.VpnSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

class RuntimeSupervisorTest {
    @Test
    fun `transition generation rejects stale owner`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val supervisor = supervisor(scope)

            val first = supervisor.beginTransition("connect", RuntimePhase.StartingNative)
            val second = supervisor.beginTransition("disconnect", RuntimePhase.Stopping)

            assertFalse(supervisor.isCurrentTransition(first, owner = "connect_result"))
            assertTrue(supervisor.isCurrentTransition(second, owner = "disconnect_result"))
            assertEquals(second, supervisor.state.value.generation)
            assertEquals(RuntimePhase.Stopping, supervisor.state.value.phase)

            supervisor.close()
            scope.cancel()
        }

    @Test
    fun `queue snapshot exposes delegated actor state`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val supervisor = supervisor(scope)
            val currentStarted = CompletableDeferred<Unit>()
            val releaseCurrent = CompletableDeferred<Unit>()

            supervisor.launch(RuntimeCommandPriority.NORMAL, reason = "connect") {
                currentStarted.complete(Unit)
                releaseCurrent.await()
            }
            withTimeout(1_000L) { currentStarted.await() }
            supervisor.launch(RuntimeCommandPriority.NORMAL, reason = "reload") {
            }
            delay(100L)

            val snapshot = supervisor.queueSnapshot()

            assertTrue(snapshot.running)
            assertEquals("normal", snapshot.runningPriority)
            assertEquals("connect", snapshot.runningReason)
            assertTrue(snapshot.commandQueueDepth >= 2)

            releaseCurrent.complete(Unit)
            supervisor.close()
            scope.cancel()
        }

    @Test
    fun `switch preemption delegates emergency kill`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val killReasons = Collections.synchronizedList(mutableListOf<String>())
            val supervisor =
                supervisor(
                    scope = scope,
                    emergencyKill = { reason ->
                        killReasons += reason
                        RuntimeKillResult(
                            reason = reason,
                            tunClosed = true,
                            serverDetached = true,
                        )
                    },
                )
            val currentStarted = CompletableDeferred<Unit>()
            val currentCancelled = CompletableDeferred<Unit>()
            val switchCompleted = CompletableDeferred<Unit>()

            supervisor.launch(RuntimeCommandPriority.NORMAL, reason = "connect") {
                try {
                    currentStarted.complete(Unit)
                    awaitCancellation()
                } finally {
                    currentCancelled.complete(Unit)
                }
            }
            withTimeout(1_000L) { currentStarted.await() }
            supervisor.launch(RuntimeCommandPriority.SWITCH, reason = "connect:2:default") {
                switchCompleted.complete(Unit)
            }

            withTimeout(1_000L) { currentCancelled.await() }
            withTimeout(1_000L) { switchCompleted.await() }

            assertEquals(listOf("priority_command_preempt:connect:2:default"), killReasons.toList())

            supervisor.close()
            scope.cancel()
        }

    @Test
    fun `ownership store makes supervisor active runtime owner`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val supervisor = supervisor(scope)
            val session =
                VpnSession(
                    profileId = 7L,
                    profileName = "test",
                    protocolHint = ProtocolHint.VLESS,
                    configJson = "{}",
                    correlationId = "session-7",
                )

            supervisor.setActiveSession(session)
            supervisor.setValidationActive(true)
            supervisor.setNetworkCallbackRegistered(RuntimeNetworkCallbackKind.VPN, true)
            supervisor.setActiveVpnNetworkHandle(100L)

            assertEquals(session, supervisor.ownership.value.activeSession)
            assertEquals(null, supervisor.ownership.value.activeLocalGuardMode)
            assertTrue(supervisor.ownership.value.validationActive)
            assertTrue(supervisor.ownership.value.vpnNetworkCallbackRegistered)
            assertEquals(100L, supervisor.ownership.value.activeVpnNetworkHandle)

            supervisor.setActiveLocalGuardMode(LocalGuardMode.FIREWALL)

            assertEquals(null, supervisor.ownership.value.activeSession)
            assertEquals(LocalGuardMode.FIREWALL, supervisor.ownership.value.activeLocalGuardMode)

            supervisor.clearRuntimeOwnership()

            assertEquals(RuntimeControlPlaneOwnershipState(), supervisor.ownership.value)

            supervisor.close()
            scope.cancel()
        }

    @Test
    fun `explicit runtime command dispatch uses command priority and reason`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val supervisor = supervisor(scope)
            val executed = CompletableDeferred<RuntimeCommand>()
            val command =
                RuntimeCommand.Reload(
                    reason = "settings_changed",
                    source = RuntimeCommandSource.USER,
                )

            supervisor.dispatch(command) { runtimeCommand ->
                executed.complete(runtimeCommand)
            }

            assertEquals(command, withTimeout(1_000L) { executed.await() })

            supervisor.close()
            scope.cancel()
        }

    private fun supervisor(
        scope: CoroutineScope,
        emergencyKill: suspend (String) -> RuntimeKillResult = { reason ->
            RuntimeKillResult(
                reason = reason,
                tunClosed = true,
                serverDetached = true,
            )
        },
    ): RuntimeSupervisor =
        RuntimeSupervisor(
            scope = scope,
            diagnosticsLogger = null,
            emergencyKill = emergencyKill,
        )
}
