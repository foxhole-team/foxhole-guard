package com.foxhole.core.runtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal class RuntimeSupervisorCooperativePreemptionTest {
    @Test
    fun `user stop cooperatively cancels a healthy policy reload without native force kill`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val kills = Collections.synchronizedList(mutableListOf<String>())
        val supervisor = supervisor(scope, kills)
        val started = CompletableDeferred<Unit>()
        val cleaned = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val stoppedAfterCleanup = AtomicBoolean(false)
        try {
            supervisor.launch(RuntimeCommandPriority.NORMAL, "enforce_quarantine") {
                started.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    cleaned.complete(Unit)
                }
            }
            withTimeout(2_000L) { started.await() }
            supervisor.launch(RuntimeCommandPriority.USER_STOP, "disconnect") {
                stoppedAfterCleanup.set(cleaned.isCompleted)
                stopped.complete(Unit)
            }
            withTimeout(3_000L) { stopped.await() }
            assertTrue("Stop overtook normal cleanup", stoppedAfterCleanup.get())
            assertTrue("Healthy cancellation poisoned the native runtime: $kills", kills.isEmpty())
        } finally {
            supervisor.close()
            scope.cancel()
        }
    }

    @Test
    fun `stop does not complete until cancellable normal cleanup is quiescent`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val kills = Collections.synchronizedList(mutableListOf<String>())
        val supervisor = supervisor(scope, kills)
        val started = CompletableDeferred<Unit>()
        val cleanupStarted = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val cleaned = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        try {
            supervisor.launch(RuntimeCommandPriority.NORMAL, "reload") {
                started.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        cleanupStarted.complete(Unit)
                        releaseCleanup.await()
                        cleaned.complete(Unit)
                    }
                }
            }
            withTimeout(2_000L) { started.await() }
            supervisor.launch(RuntimeCommandPriority.USER_STOP, "disconnect") {
                stopped.complete(Unit)
            }
            withTimeout(2_000L) { cleanupStarted.await() }
            assertFalse(
                withTimeoutOrNull(100L) {
                    stopped.await()
                    true
                } == true,
            )
            releaseCleanup.complete(Unit)
            withTimeout(3_000L) { stopped.await() }
            assertTrue(cleaned.isCompleted)
            assertTrue(kills.isEmpty())
        } finally {
            releaseCleanup.complete(Unit)
            supervisor.close()
            scope.cancel()
        }
    }

    @Test
    fun `non cooperative normal cleanup still escalates and fences late publication after stop`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val kills = Collections.synchronizedList(mutableListOf<String>())
        val supervisor = supervisor(scope, kills)
        val started = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val cleaned = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val stalePublication = AtomicBoolean(false)
        try {
            supervisor.launch(RuntimeCommandPriority.NORMAL, "reload") {
                val generation = supervisor.beginTransition("normal_reload")
                started.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        releaseCleanup.await()
                        stalePublication.set(supervisor.isCurrentTransition(generation, "late_reload"))
                        cleaned.complete(Unit)
                    }
                }
            }
            withTimeout(2_000L) { started.await() }
            supervisor.launch(RuntimeCommandPriority.USER_STOP, "disconnect") {
                supervisor.beginTransition("user_stop")
                stopped.complete(Unit)
            }
            withTimeout(3_000L) { stopped.await() }
            assertEquals(listOf("priority_command_preempt:disconnect"), kills.toList())
            assertFalse(cleaned.isCompleted)
            releaseCleanup.complete(Unit)
            withTimeout(2_000L) { cleaned.await() }
            assertFalse("Old reload retained publication ownership after Stop", stalePublication.get())
        } finally {
            releaseCleanup.complete(Unit)
            supervisor.close()
            scope.cancel()
        }
    }

    @Test
    fun `explicit kill interrupts the cooperative grace without entering the stopped body`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val kills = Collections.synchronizedList(mutableListOf<String>())
        val started = CompletableDeferred<Unit>()
        val cleanupStarted = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val stopEntered = AtomicBoolean(false)
        val killed = CompletableDeferred<Unit>()
        val graceStarted = CompletableDeferred<Unit>()
        val cleaned = CompletableDeferred<Unit>()
        val stalePublication = AtomicBoolean(false)
        val supervisor = supervisor(
            scope,
            kills,
            RuntimeCommandDiagnosticsRecorder { _, headline, _ ->
                if (headline == "runtime normal preemption waiting for cleanup") graceStarted.complete(Unit)
            }
        )
        try {
            supervisor.launch(RuntimeCommandPriority.NORMAL, "reload") {
                val generation = supervisor.beginTransition("normal_reload")
                started.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        cleanupStarted.complete(Unit)
                        releaseCleanup.await()
                        stalePublication.set(supervisor.isCurrentTransition(generation, "late_reload"))
                        cleaned.complete(Unit)
                    }
                }
            }
            withTimeout(2_000L) { started.await() }
            supervisor.launch(RuntimeCommandPriority.USER_STOP, "disconnect") {
                stopEntered.set(true)
                awaitCancellation()
            }
            withTimeout(2_000L) { cleanupStarted.await() }
            withTimeout(2_000L) { graceStarted.await() }
            supervisor.launch(RuntimeCommandPriority.KILL, "kill") {
                supervisor.beginTransition("explicit_kill")
                killed.complete(Unit)
            }
            withTimeout(2_000L) { killed.await() }
            assertFalse("Stop ran before the preempted normal job settled", stopEntered.get())
            assertEquals(listOf("priority_command_preempt:kill"), kills.toList())
            releaseCleanup.complete(Unit)
            withTimeout(2_000L) { cleaned.await() }
            assertFalse("Old reload retained publication ownership after Kill", stalePublication.get())
        } finally {
            releaseCleanup.complete(Unit)
            supervisor.close()
            scope.cancel()
        }
    }

    @Test
    fun `replacement user stop retains normal cleanup ownership before the first stop starts`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val kills = Collections.synchronizedList(mutableListOf<String>())
        val supervisorRef = AtomicReference<RuntimeSupervisor>()
        val started = CompletableDeferred<Unit>()
        val cleanupStarted = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val supervisor = supervisor(
            scope,
            kills,
            RuntimeCommandDiagnosticsRecorder { _, headline, _ ->
                if (headline == "runtime command preempted") {
                    supervisorRef.get().launch(RuntimeCommandPriority.USER_STOP, "replacement_disconnect") {
                        stopped.complete(Unit)
                    }
                }
            }
        )
        supervisorRef.set(supervisor)
        try {
            supervisor.launch(RuntimeCommandPriority.NORMAL, "reload") {
                started.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        cleanupStarted.complete(Unit)
                        releaseCleanup.await()
                    }
                }
            }
            withTimeout(2_000L) { started.await() }
            supervisor.launch(RuntimeCommandPriority.USER_STOP, "disconnect") {}
            withTimeout(2_000L) { cleanupStarted.await() }
            assertFalse(
                "Replacement Stop overtook normal cleanup",
                withTimeoutOrNull(100L) {
                    stopped.await()
                    true
                } == true
            )
            releaseCleanup.complete(Unit)
            withTimeout(3_000L) { stopped.await() }
            assertTrue(kills.isEmpty())
        } finally {
            releaseCleanup.complete(Unit)
            supervisor.close()
            scope.cancel()
        }
    }

    @Test
    fun `explicit kill before the first stop starts still escalates blocked normal cleanup`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val kills = Collections.synchronizedList(mutableListOf<String>())
        val supervisorRef = AtomicReference<RuntimeSupervisor>()
        val started = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val killed = CompletableDeferred<Unit>()
        val supervisor = supervisor(
            scope,
            kills,
            RuntimeCommandDiagnosticsRecorder { _, headline, _ ->
                if (headline == "runtime command preempted") {
                    supervisorRef.get().launch(RuntimeCommandPriority.KILL, "kill") { killed.complete(Unit) }
                }
            }
        )
        supervisorRef.set(supervisor)
        try {
            supervisor.launch(RuntimeCommandPriority.NORMAL, "reload") {
                started.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) { releaseCleanup.await() }
                }
            }
            withTimeout(2_000L) { started.await() }
            supervisor.launch(RuntimeCommandPriority.USER_STOP, "disconnect") {}
            withTimeout(2_000L) { killed.await() }
            assertEquals(listOf("priority_command_preempt:kill"), kills.toList())
        } finally {
            releaseCleanup.complete(Unit)
            supervisor.close()
            scope.cancel()
        }
    }

    @Test
    fun `raw stop retains immediate escalation and user stop preempts its executing body`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val kills = Collections.synchronizedList(mutableListOf<String>())
        val supervisor = supervisor(scope, kills)
        val started = CompletableDeferred<Unit>()
        val rawStopEntered = CompletableDeferred<Unit>()
        val rawStopCancelled = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        try {
            supervisor.launch(RuntimeCommandPriority.NORMAL, "reload") {
                started.complete(Unit)
                awaitCancellation()
            }
            withTimeout(2_000L) { started.await() }
            supervisor.launch(RuntimeCommandPriority.STOP, "raw_stop") {
                rawStopEntered.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    rawStopCancelled.complete(Unit)
                }
            }
            withTimeout(2_000L) { rawStopEntered.await() }
            assertEquals(listOf("priority_command_preempt:raw_stop"), kills.toList())
            supervisor.launch(RuntimeCommandPriority.USER_STOP, "disconnect") { stopped.complete(Unit) }
            withTimeout(2_000L) { stopped.await() }
            withTimeout(2_000L) { rawStopCancelled.await() }
            assertEquals(
                listOf("priority_command_preempt:raw_stop", "priority_command_preempt:disconnect"),
                kills.toList(),
            )
        } finally {
            supervisor.close()
            scope.cancel()
        }
    }

    @Test
    fun `repeated user stop during grace preserves the cleanup wait without native force kill`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val kills = Collections.synchronizedList(mutableListOf<String>())
        val started = CompletableDeferred<Unit>()
        val graceStarted = CompletableDeferred<Unit>()
        val coalesced = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val replacementEntered = AtomicBoolean(false)
        val supervisor = supervisor(
            scope,
            kills,
            RuntimeCommandDiagnosticsRecorder { _, headline, details ->
                if (headline == "runtime normal preemption waiting for cleanup") graceStarted.complete(Unit)
                if (headline == "runtime priority command coalesced" && details.contains("reason=repeated_disconnect")) {
                    coalesced.complete(Unit)
                }
            }
        )
        try {
            supervisor.launch(RuntimeCommandPriority.NORMAL, "reload") {
                started.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) { releaseCleanup.await() }
                }
            }
            withTimeout(2_000L) { started.await() }
            supervisor.launch(RuntimeCommandPriority.USER_STOP, "disconnect") { stopped.complete(Unit) }
            withTimeout(2_000L) { graceStarted.await() }
            supervisor.launch(RuntimeCommandPriority.USER_STOP, "repeated_disconnect") {
                replacementEntered.set(true)
            }
            withTimeout(2_000L) { coalesced.await() }
            assertFalse(stopped.isCompleted)
            assertFalse(replacementEntered.get())
            assertTrue(kills.isEmpty())
            releaseCleanup.complete(Unit)
            withTimeout(3_000L) { stopped.await() }
            assertTrue(kills.isEmpty())
        } finally {
            releaseCleanup.complete(Unit)
            supervisor.close()
            scope.cancel()
        }
    }

    private fun supervisor(
        scope: CoroutineScope,
        kills: MutableList<String>,
        recorder: RuntimeCommandDiagnosticsRecorder? = null,
    ): RuntimeSupervisor =
        RuntimeSupervisor(scope, diagnosticsLogger = null, diagnosticsRecorder = recorder, emergencyKill = { reason ->
            kills += reason
            RuntimeKillResult(
                reason,
                tunClosed = true,
                serverDetached = true,
                forceStopOutcome = NativeForceStopOutcome.QUARANTINED
            )
        })
}
