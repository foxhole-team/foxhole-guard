package com.foxhole.guard.core.backup

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupRestoreTransactionTest {
    @Test
    fun `restore cancellation rolls partial state back in a non cancellable context`() =
        runBlocking {
            var persistedState = "before"
            var rollbackWasActive = false
            val applyStarted = CompletableDeferred<Unit>()
            val restoreJob =
                launch {
                    executeFailAtomicBackupRestore(
                        capture = { persistedState },
                        apply = {
                            persistedState = "partial"
                            applyStarted.complete(Unit)
                            awaitCancellation()
                        },
                        rollback = { checkpoint ->
                            rollbackWasActive = currentCoroutineContext().isActive
                            persistedState = checkpoint
                        },
                    )
                }

            applyStarted.await()
            restoreJob.cancelAndJoin()

            assertEquals("before", persistedState)
            assertTrue(rollbackWasActive)
            assertTrue(restoreJob.isCancelled)
        }

    @Test
    fun `rollback failure is attached to the original cancellation`() {
        val failure =
            runCatching {
                runBlocking {
                    executeFailAtomicBackupRestore(
                        capture = { Unit },
                        apply = { throw CancellationException("cancelled") },
                        rollback = { error("rollback failed") },
                    )
                }
            }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertEquals("rollback failed", failure?.suppressed?.singleOrNull()?.message)
    }
}
