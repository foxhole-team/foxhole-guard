package com.foxhole.guard.core.sentinel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.IOException

class SentinelAttemptTest {
    @Test fun cancellationIsNeverAnOrdinaryResultFailure() {
        val cancellation = CancellationException("owned cancellation")
        assertSame(cancellation, thrownBy { sentinelAttempt { throw cancellation } })
    }

    @Test fun fatalErrorIsNeverAnOrdinaryResultFailure() {
        val fatal = AssertionError("owned fatal marker")
        assertSame(fatal, thrownBy { sentinelAttempt { throw fatal } })
    }

    @Test fun ordinaryIOExceptionRemainsAvailableForExistingRetryMapping() {
        val ordinary = IOException("owned storage failure")
        val result = sentinelAttempt { throw ordinary }
        assertSame(ordinary, result.exceptionOrNull())
    }

    @Test fun completedValueStillReturnsNormally() {
        assertEquals(17, sentinelAttempt { 17 }.getOrThrow())
    }

    @Test fun cancellingARealOwnedJobCannotContinueAfterTheAttempt() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        var normalReturn = false
        val job = launch {
            sentinelAttempt {
                entered.complete(Unit)
                awaitCancellation()
            }
            normalReturn = true
        }
        try {
            withTimeout(3_000) { entered.await() }
            job.cancelAndJoin()
            assertFalse("cancelled work resumed after the failure boundary", normalReturn)
        } finally {
            job.cancelAndJoin()
        }
    }

    private fun thrownBy(block: () -> Unit): Throwable? = try {
        block()
        null
    } catch (e: Throwable) { e }
}
