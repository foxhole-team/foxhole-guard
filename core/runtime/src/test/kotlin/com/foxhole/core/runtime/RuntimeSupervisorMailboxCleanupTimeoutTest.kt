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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

class RuntimeSupervisorMailboxCleanupTimeoutTest {
    @Test
    fun `switch still runs when preempted cleanup hangs past the timeout`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val events = Collections.synchronizedList(mutableListOf<String>())
            val mailbox =
                RuntimeSupervisorMailbox(
                    scope = scope,
                    diagnosticsLogger = null,
                    emergencyKill = { reason ->
                        RuntimeKillResult(reason = reason, tunClosed = true, serverDetached = true)
                    },
                    diagnosticsRecorder = { _, headline, _ -> events += headline },
                    switchCleanupTimeoutMs = 200L,
                )
            val normalStarted = CompletableDeferred<Unit>()
            val cleanupHang = CompletableDeferred<Unit>()
            val switchRan = CompletableDeferred<Unit>()

            mailbox.launch(RuntimeCommandPriority.NORMAL, reason = "connect") {
                normalStarted.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        cleanupHang.await()
                    }
                }
            }
            withTimeout(2_000L) { normalStarted.await() }

            mailbox.launch(RuntimeCommandPriority.SWITCH, reason = "switch_vpn") {
                switchRan.complete(Unit)
            }

            withTimeout(5_000L) { switchRan.await() }
            assertTrue(events.any { it == "runtime switch proceeding after cleanup timeout" })

            cleanupHang.complete(Unit)
            mailbox.close()
            scope.cancel()
        }
}
