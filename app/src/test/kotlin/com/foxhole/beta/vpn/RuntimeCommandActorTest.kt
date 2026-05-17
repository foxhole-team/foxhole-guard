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

    private fun actor(
        scope: CoroutineScope,
        killReasons: MutableList<String> = mutableListOf(),
    ): RuntimeCommandActor =
        RuntimeCommandActor(
            scope = scope,
            diagnosticsLogger = null,
            emergencyKill = { reason ->
                killReasons += reason
                RuntimeKillResult(
                    reason = reason,
                    tunClosed = true,
                    serverDetached = true,
                )
            },
        )
}
