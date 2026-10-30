package com.foxhole.core.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class DnsBootstrapReaderSettlementTest {
    @Test
    fun successfulOperationSettlesReaderAndReportsOrdinaryCleanupWithoutLosingSuccess() = runBlocking {
        val order = mutableListOf<String>()
        val reader = reader {
            order += "release"
            throw IOException("owned cleanup")
        }
        val result = runWithDnsBootstrapReader(reader, { order += "pending" }) {
            order += "operation"
            Result.success(Unit)
        }
        assertTrue(result.isSuccess)
        assertEquals(listOf("operation", "release", "pending"), order)
    }

    @Test
    fun cancellationRemainsPrimaryAndFatalCleanupIsSuppressed() = runBlocking {
        val primary = OwnedDnsCancellation()
        val secondary = OwnedDnsFatal()
        val thrown = failureOf { runWithDnsBootstrapReader(reader { throw secondary }, {}) { throw primary } }
        assertSame(primary, thrown)
        assertEquals(listOf(secondary), primary.suppressed.toList())
    }

    @Test
    fun fatalCleanupCannotBeHiddenByAnOrdinaryOperationFailure() = runBlocking {
        val primary = IOException("owned operation")
        val fatal = OwnedDnsFatal()
        val thrown = failureOf { runWithDnsBootstrapReader(reader { throw fatal }, {}) { throw primary } }
        assertSame(fatal, thrown)
        assertEquals(listOf(primary), fatal.suppressed.toList())
    }

    @Test
    fun returnedFailureRetainsItsPrimaryAndOrdinaryCleanup() = runBlocking {
        val primary = IllegalStateException("owned returned failure")
        val cleanup = IOException("owned cleanup")
        val result = runWithDnsBootstrapReader(reader { throw cleanup }, {}) { Result.failure(primary) }
        assertSame(primary, result.exceptionOrNull())
        assertEquals(listOf(cleanup), primary.suppressed.toList())
    }

    private fun reader(onRelease: suspend () -> Unit) = object : RuntimeDnsBootstrapReadLease {
        override suspend fun release() = onRelease()
    }

    private suspend fun failureOf(action: suspend () -> Unit): Throwable? =
        try {
            action()
            null
        } catch (failure: Throwable) { failure }
}

private class OwnedDnsCancellation(val marker: Any = Any()) : CancellationException("owned cancellation")
private class OwnedDnsFatal(val marker: Any = Any()) : Error("owned fatal")
