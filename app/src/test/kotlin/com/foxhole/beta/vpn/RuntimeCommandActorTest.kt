package com.foxhole.beta.vpn

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

class RuntimeCommandActorTest {
    @Test
    fun `normal commands run sequentially`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val actor = actor(scope)
            val events = Collections.synchronizedList(mutableListOf<String>())
            val firstStarted = CompletableDeferred<Unit>()
            val releaseFirst = CompletableDeferred<Unit>()
            val secondStarted = CompletableDeferred<Unit>()

            actor.launch(RuntimeCommandPriority.NORMAL, reason = "connect") {
                events += "first-start"
                firstStarted.complete(Unit)
                releaseFirst.await()
                events += "first-end"
            }
            actor.launch(RuntimeCommandPriority.NORMAL, reason = "reload") {
                events += "second-start"
                secondStarted.complete(Unit)
            }

            withTimeout(1_000L) { firstStarted.await() }
            delay(50L)
            assertFalse(secondStarted.isCompleted)

            releaseFirst.complete(Unit)
            withTimeout(1_000L) { secondStarted.await() }

            assertEquals(listOf("first-start", "first-end", "second-start"), events.toList())
            actor.close()
        }

    @Test
    fun `normal command overload stays bounded while command is active`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val actor = actor(scope)
            val currentStarted = CompletableDeferred<Unit>()
            val releaseCurrent = CompletableDeferred<Unit>()
            val started = Collections.synchronizedList(mutableListOf<Int>())

            actor.launch(RuntimeCommandPriority.NORMAL, reason = "connect") {
                currentStarted.complete(Unit)
                releaseCurrent.await()
            }
            withTimeout(1_000L) { currentStarted.await() }

            repeat(250) { index ->
                actor.launch(RuntimeCommandPriority.NORMAL, reason = "reload-$index") {
                    started += index
                }
            }
            delay(250L)

            releaseCurrent.complete(Unit)
            delay(1_000L)

            assertTrue("normal overload should stay within the actor's bounded queues", started.size <= 80)
            actor.close()
        }

    @Test
    fun `stop still preempts after normal command overload`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val killReasons = Collections.synchronizedList(mutableListOf<String>())
            val actor = actor(scope, killReasons)
            val currentStarted = CompletableDeferred<Unit>()
            val currentCancelled = CompletableDeferred<Unit>()
            val stopCompleted = CompletableDeferred<Unit>()
            val normalStarted = Collections.synchronizedList(mutableListOf<Int>())

            actor.launch(RuntimeCommandPriority.NORMAL, reason = "connect") {
                try {
                    currentStarted.complete(Unit)
                    awaitCancellation()
                } finally {
                    currentCancelled.complete(Unit)
                }
            }
            withTimeout(1_000L) { currentStarted.await() }

            repeat(250) { index ->
                actor.launch(RuntimeCommandPriority.NORMAL, reason = "reload-$index") {
                    normalStarted += index
                }
            }
            actor.launch(RuntimeCommandPriority.STOP, reason = "disconnect") {
                stopCompleted.complete(Unit)
            }

            withTimeout(1_000L) { currentCancelled.await() }
            withTimeout(1_000L) { stopCompleted.await() }
            delay(50L)

            assertTrue(normalStarted.isEmpty())
            assertEquals(listOf("priority_command_preempt:disconnect"), killReasons.toList())
            actor.close()
        }

    @Test
    fun `stop preempts current command and clears queued normal commands`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val killReasons = Collections.synchronizedList(mutableListOf<String>())
            val actor = actor(scope, killReasons)
            val currentStarted = CompletableDeferred<Unit>()
            val currentCancelled = CompletableDeferred<Unit>()
            val queuedNormalStarted = CompletableDeferred<Unit>()
            val stopCompleted = CompletableDeferred<Unit>()

            actor.launch(RuntimeCommandPriority.NORMAL, reason = "connect") {
                try {
                    currentStarted.complete(Unit)
                    awaitCancellation()
                } finally {
                    currentCancelled.complete(Unit)
                }
            }
            withTimeout(1_000L) { currentStarted.await() }
            actor.launch(RuntimeCommandPriority.NORMAL, reason = "reload") {
                queuedNormalStarted.complete(Unit)
            }
            actor.launch(RuntimeCommandPriority.STOP, reason = "disconnect") {
                stopCompleted.complete(Unit)
            }

            withTimeout(1_000L) { currentCancelled.await() }
            withTimeout(1_000L) { stopCompleted.await() }
            delay(50L)

            assertFalse(queuedNormalStarted.isCompleted)
            assertEquals(listOf("priority_command_preempt:disconnect"), killReasons.toList())
            actor.close()
        }

    @Test
    fun `connect does not run concurrently with stop`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val actor = actor(scope)
            val stopStarted = CompletableDeferred<Unit>()
            val releaseStop = CompletableDeferred<Unit>()
            val connectStarted = CompletableDeferred<Unit>()

            actor.launch(RuntimeCommandPriority.STOP, reason = "disconnect") {
                stopStarted.complete(Unit)
                releaseStop.await()
            }
            withTimeout(1_000L) { stopStarted.await() }
            actor.launch(RuntimeCommandPriority.NORMAL, reason = "connect") {
                connectStarted.complete(Unit)
            }

            delay(50L)
            assertFalse(connectStarted.isCompleted)

            releaseStop.complete(Unit)
            withTimeout(1_000L) { connectStarted.await() }
            actor.close()
        }

    @Test
    fun `switch preempts stop so latest user transition wins`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val killReasons = Collections.synchronizedList(mutableListOf<String>())
            val actor = actor(scope, killReasons)
            val stopStarted = CompletableDeferred<Unit>()
            val stopCancelled = CompletableDeferred<Unit>()
            val switchCompleted = CompletableDeferred<Unit>()

            actor.launch(RuntimeCommandPriority.STOP, reason = "disconnect") {
                try {
                    stopStarted.complete(Unit)
                    awaitCancellation()
                } finally {
                    stopCancelled.complete(Unit)
                }
            }
            withTimeout(1_000L) { stopStarted.await() }

            actor.launch(RuntimeCommandPriority.SWITCH, reason = "connect:1:default") {
                switchCompleted.complete(Unit)
            }

            withTimeout(1_000L) { stopCancelled.await() }
            withTimeout(1_000L) { switchCompleted.await() }
            assertEquals(listOf("priority_command_preempt:connect:1:default"), killReasons.toList())
            actor.close()
        }

    @Test
    fun `new switch preempts running switch with different target`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val killReasons = Collections.synchronizedList(mutableListOf<String>())
            val actor = actor(scope, killReasons)
            val firstStarted = CompletableDeferred<Unit>()
            val firstCancelled = CompletableDeferred<Unit>()
            val secondCompleted = CompletableDeferred<Unit>()

            actor.launch(RuntimeCommandPriority.SWITCH, reason = "connect:1:default") {
                try {
                    firstStarted.complete(Unit)
                    awaitCancellation()
                } finally {
                    firstCancelled.complete(Unit)
                }
            }
            withTimeout(1_000L) { firstStarted.await() }

            actor.launch(RuntimeCommandPriority.SWITCH, reason = "local_guard:firewall") {
                secondCompleted.complete(Unit)
            }

            withTimeout(1_000L) { firstCancelled.await() }
            withTimeout(1_000L) { secondCompleted.await() }
            assertEquals(listOf("priority_command_preempt:local_guard:firewall"), killReasons.toList())
            actor.close()
        }

    @Test
    fun `second stop while stop is running is coalesced without emergency kill`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val killReasons = Collections.synchronizedList(mutableListOf<String>())
            val actor = actor(scope, killReasons)
            val events = Collections.synchronizedList(mutableListOf<String>())
            val stopStarted = CompletableDeferred<Unit>()
            val releaseStop = CompletableDeferred<Unit>()

            actor.launch(RuntimeCommandPriority.STOP, reason = "disconnect") {
                events += "first-stop-start"
                stopStarted.complete(Unit)
                releaseStop.await()
                events += "first-stop-end"
            }
            withTimeout(1_000L) { stopStarted.await() }

            actor.launch(RuntimeCommandPriority.STOP, reason = "disconnect") {
                events += "second-stop"
            }
            delay(100L)

            assertEquals(listOf("first-stop-start"), events.toList())
            assertTrue(killReasons.isEmpty())

            releaseStop.complete(Unit)
            delay(100L)

            assertEquals(listOf("first-stop-start", "first-stop-end"), events.toList())
            assertTrue(killReasons.isEmpty())
            actor.close()
        }

    @Test
    fun `kill preempts running stop once`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val killReasons = Collections.synchronizedList(mutableListOf<String>())
            val actor = actor(scope, killReasons)
            val stopStarted = CompletableDeferred<Unit>()
            val stopCancelled = CompletableDeferred<Unit>()
            val killCompleted = CompletableDeferred<Unit>()

            actor.launch(RuntimeCommandPriority.STOP, reason = "disconnect") {
                try {
                    stopStarted.complete(Unit)
                    awaitCancellation()
                } finally {
                    stopCancelled.complete(Unit)
                }
            }
            withTimeout(1_000L) { stopStarted.await() }

            actor.launch(RuntimeCommandPriority.KILL, reason = "kill") {
                killCompleted.complete(Unit)
            }

            withTimeout(1_000L) { stopCancelled.await() }
            withTimeout(1_000L) { killCompleted.await() }

            assertEquals(listOf("priority_command_preempt:kill"), killReasons.toList())
            actor.close()
        }

    @Test
    fun `stop runs immediately while current command is still cancelling`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val killReasons = Collections.synchronizedList(mutableListOf<String>())
            val actor = actor(scope, killReasons)
            val currentStarted = CompletableDeferred<Unit>()
            val releaseCurrentCleanup = CompletableDeferred<Unit>()
            val currentCleanupFinished = CompletableDeferred<Unit>()
            val stopCompleted = CompletableDeferred<Unit>()

            actor.launch(RuntimeCommandPriority.NORMAL, reason = "connect") {
                try {
                    currentStarted.complete(Unit)
                    delay(5_000L)
                } finally {
                    withContext(NonCancellable) {
                        releaseCurrentCleanup.await()
                        currentCleanupFinished.complete(Unit)
                    }
                }
            }
            withTimeout(1_000L) { currentStarted.await() }

            actor.launch(RuntimeCommandPriority.STOP, reason = "disconnect") {
                stopCompleted.complete(Unit)
            }

            withTimeout(1_000L) { stopCompleted.await() }
            assertEquals(listOf("priority_command_preempt:disconnect"), killReasons.toList())

            releaseCurrentCleanup.complete(Unit)
            withTimeout(1_000L) { currentCleanupFinished.await() }
            actor.close()
        }

    @Test
    fun `normal command after stop waits for preempted cleanup`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val actor = actor(scope)
            val currentStarted = CompletableDeferred<Unit>()
            val releaseCurrentCleanup = CompletableDeferred<Unit>()
            val stopCompleted = CompletableDeferred<Unit>()
            val nextStarted = CompletableDeferred<Unit>()

            actor.launch(RuntimeCommandPriority.NORMAL, reason = "connect") {
                try {
                    currentStarted.complete(Unit)
                    delay(5_000L)
                } finally {
                    withContext(NonCancellable) {
                        releaseCurrentCleanup.await()
                    }
                }
            }
            withTimeout(1_000L) { currentStarted.await() }

            actor.launch(RuntimeCommandPriority.STOP, reason = "disconnect") {
                stopCompleted.complete(Unit)
            }
            withTimeout(1_000L) { stopCompleted.await() }

            actor.launch(RuntimeCommandPriority.NORMAL, reason = "connect-next") {
                nextStarted.complete(Unit)
            }
            delay(50L)
            assertFalse(nextStarted.isCompleted)

            releaseCurrentCleanup.complete(Unit)
            withTimeout(1_000L) { nextStarted.await() }
            actor.close()
        }

    @Test
    fun `normal command after preempted stuck cleanup starts after bounded drain`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val actor = actor(scope)
            val currentStarted = CompletableDeferred<Unit>()
            val releaseCurrentCleanup = CompletableDeferred<Unit>()
            val currentCleanupFinished = CompletableDeferred<Unit>()
            val stopCompleted = CompletableDeferred<Unit>()
            val nextStarted = CompletableDeferred<Unit>()

            actor.launch(RuntimeCommandPriority.NORMAL, reason = "connect") {
                try {
                    currentStarted.complete(Unit)
                    delay(5_000L)
                } finally {
                    withContext(NonCancellable) {
                        releaseCurrentCleanup.await()
                        currentCleanupFinished.complete(Unit)
                    }
                }
            }
            withTimeout(1_000L) { currentStarted.await() }

            actor.launch(RuntimeCommandPriority.STOP, reason = "disconnect") {
                stopCompleted.complete(Unit)
            }
            withTimeout(1_000L) { stopCompleted.await() }

            actor.launch(RuntimeCommandPriority.NORMAL, reason = "restore") {
                nextStarted.complete(Unit)
            }

            withTimeout(2_500L) { nextStarted.await() }
            releaseCurrentCleanup.complete(Unit)
            withTimeout(1_000L) { currentCleanupFinished.await() }
            actor.close()
        }

    @Test
    fun `duplicate normal command coalesces into running command`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val actor = actor(scope)
            val events = Collections.synchronizedList(mutableListOf<String>())
            val currentStarted = CompletableDeferred<Unit>()
            val releaseCurrent = CompletableDeferred<Unit>()

            actor.launch(RuntimeCommandPriority.NORMAL, reason = "connect:1:wireguard") {
                events += "first-start"
                currentStarted.complete(Unit)
                releaseCurrent.await()
                events += "first-end"
            }
            withTimeout(1_000L) { currentStarted.await() }

            actor.launch(RuntimeCommandPriority.NORMAL, reason = "connect:1:wireguard") {
                events += "duplicate-start"
            }
            delay(100L)
            releaseCurrent.complete(Unit)
            delay(100L)

            assertEquals(listOf("first-start", "first-end"), events.toList())
            actor.close()
        }

    @Test
    fun `reload during disconnect is serialized`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val actor = actor(scope)
            val events = Collections.synchronizedList(mutableListOf<String>())
            val disconnectStarted = CompletableDeferred<Unit>()
            val releaseDisconnect = CompletableDeferred<Unit>()
            val reloadDone = CompletableDeferred<Unit>()

            actor.launch(RuntimeCommandPriority.STOP, reason = "disconnect") {
                events += "disconnect-start"
                disconnectStarted.complete(Unit)
                releaseDisconnect.await()
                events += "disconnect-end"
            }
            withTimeout(1_000L) { disconnectStarted.await() }
            actor.launch(RuntimeCommandPriority.NORMAL, reason = "reload") {
                events += "reload"
                reloadDone.complete(Unit)
            }

            delay(50L)
            assertEquals(listOf("disconnect-start"), events.toList())

            releaseDisconnect.complete(Unit)
            withTimeout(1_000L) { reloadDone.await() }
            assertEquals(listOf("disconnect-start", "disconnect-end", "reload"), events.toList())
            actor.close()
        }

    @Test
    fun `close cancels current worker and pending commands`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val actor = actor(scope)
            val currentStarted = CompletableDeferred<Unit>()
            val currentCancelled = CompletableDeferred<Unit>()
            val pendingStarted = CompletableDeferred<Unit>()

            actor.launch(RuntimeCommandPriority.NORMAL, reason = "connect") {
                try {
                    currentStarted.complete(Unit)
                    awaitCancellation()
                } finally {
                    currentCancelled.complete(Unit)
                }
            }
            withTimeout(1_000L) { currentStarted.await() }
            actor.launch(RuntimeCommandPriority.NORMAL, reason = "reload") {
                pendingStarted.complete(Unit)
            }

            actor.close()

            withTimeout(1_000L) { currentCancelled.await() }
            delay(50L)
            assertFalse(pendingStarted.isCompleted)
        }

    @Test
    fun `repeated stop commands coalesce while force kill is draining`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val currentStarted = CompletableDeferred<Unit>()
            val killStarted = CompletableDeferred<Unit>()
            val releaseKill = CompletableDeferred<Unit>()
            val diagnostics = Collections.synchronizedList(mutableListOf<RuntimeCommandDiagnosticEvent>())
            val actor =
                actor(
                    scope = scope,
                    diagnostics = diagnostics,
                    emergencyKill = { reason ->
                        killStarted.complete(Unit)
                        releaseKill.await()
                        RuntimeKillResult(
                            reason = reason,
                            tunClosed = true,
                            serverDetached = true,
                        )
                    },
                )

            actor.launch(RuntimeCommandPriority.NORMAL, reason = "connect") {
                currentStarted.complete(Unit)
                awaitCancellation()
            }
            withTimeout(1_000L) { currentStarted.await() }
            actor.launch(RuntimeCommandPriority.STOP, reason = "disconnect") {
            }
            withTimeout(1_000L) { killStarted.await() }

            repeat(100) {
                actor.launch(RuntimeCommandPriority.STOP, reason = "disconnect") {
                }
            }
            delay(100L)

            assertFalse(
                diagnostics.any { event ->
                    event.headline == "runtime command rejected" &&
                        event.details.contains("reason=disconnect")
                },
            )
            assertTrue(
                diagnostics.any { event ->
                    event.headline == "runtime priority command coalesced" &&
                        event.details.contains("reason=disconnect") &&
                        event.details.contains("buffered=true")
                },
            )

            releaseKill.complete(Unit)
            actor.close()
        }

    @Test
    fun `priority command reject logs queue full instead of closed when buffer is full`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val currentStarted = CompletableDeferred<Unit>()
            val killStarted = CompletableDeferred<Unit>()
            val releaseKill = CompletableDeferred<Unit>()
            val diagnostics = Collections.synchronizedList(mutableListOf<RuntimeCommandDiagnosticEvent>())
            val actor =
                actor(
                    scope = scope,
                    diagnostics = diagnostics,
                    emergencyKill = { reason ->
                        killStarted.complete(Unit)
                        releaseKill.await()
                        RuntimeKillResult(
                            reason = reason,
                            tunClosed = true,
                            serverDetached = true,
                        )
                    },
                )

            actor.launch(RuntimeCommandPriority.NORMAL, reason = "connect") {
                currentStarted.complete(Unit)
                awaitCancellation()
            }
            withTimeout(1_000L) { currentStarted.await() }
            actor.launch(RuntimeCommandPriority.STOP, reason = "disconnect") {
            }
            withTimeout(1_000L) { killStarted.await() }

            repeat(17) { index ->
                actor.launch(RuntimeCommandPriority.KILL, reason = "kill-$index") {
                }
            }

            val rejected =
                diagnostics.first { event ->
                    event.headline == "runtime command rejected" &&
                        event.details.contains("reason=kill-16")
                }
            assertTrue(rejected.details.contains("closed=false"))
            assertTrue(rejected.details.contains("queue_full=true"))
            assertTrue(rejected.details.contains("buffer_rejected=true"))

            releaseKill.complete(Unit)
            actor.close()
        }

    private fun actor(
        scope: CoroutineScope,
        killReasons: MutableList<String> = mutableListOf(),
        diagnostics: MutableList<RuntimeCommandDiagnosticEvent> = mutableListOf(),
        emergencyKill: (suspend (String) -> RuntimeKillResult)? = null,
    ): RuntimeCommandActor {
        val fallbackKill: suspend (String) -> RuntimeKillResult = { reason ->
            killReasons += reason
            RuntimeKillResult(
                reason = reason,
                tunClosed = true,
                serverDetached = true,
            )
        }
        val recorder =
            RuntimeCommandDiagnosticsRecorder { tag, headline, details ->
                diagnostics +=
                    RuntimeCommandDiagnosticEvent(
                        tag = tag,
                        headline = headline,
                        details = details.filterNotNull(),
                    )
            }
        return RuntimeCommandActor(
            scope = scope,
            diagnosticsLogger = null,
            emergencyKill = emergencyKill ?: fallbackKill,
            diagnosticsRecorder = recorder,
        )
    }

    private data class RuntimeCommandDiagnosticEvent(
        val tag: String,
        val headline: String,
        val details: List<String>,
    )
}
