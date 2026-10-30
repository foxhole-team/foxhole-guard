package com.foxhole.guard.core.sentinel.scanner

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.IOException

class ScannerCleanupSettlementTest {
    @Test fun shutdownAndUnbindFailuresDoNotSkipDescriptorCleanup() = runBlocking {
        val attempts = mutableListOf<String>()
        val errors = mutableListOf<Throwable>()
        settleScannerCleanup(errors) {
            attempts += "shutdown"
            throw IOException("owned shutdown")
        }
        settleScannerCleanup(errors) {
            attempts += "unbind"
            throw IOException("owned unbind")
        }
        settleScannerCleanup(errors) { attempts += "descriptors" }
        assertEquals(listOf("shutdown", "unbind", "descriptors"), attempts)
        assertEquals(2, errors.size)
    }

    @Test fun primaryCancellationRetainsIdentityAndCleanupFailures() {
        val primary = CancellationException("owned cancellation")
        val secondary = Error("owned cleanup fatal")
        settleScannerFailures(primary, listOf(secondary))
        assertSame(secondary, primary.suppressed.single())
    }

    @Test fun fatalCleanupIsNeverReducedToOrdinaryRefusal() {
        val primary = IOException("owned bind failure")
        val fatal = Error("owned cleanup fatal")
        assertSame(fatal, runCatching { settleScannerFailures(primary, listOf(fatal)) }.exceptionOrNull())
        assertSame(primary, fatal.suppressed.single())
    }

    @Test fun ordinaryPrimaryRetainsIdentityWhileAllCleanupFailuresRemainVisible() {
        val primary = IOException("owned input failure")
        val cleanup = IOException("owned cleanup")
        settleScannerFailures(primary, listOf(cleanup, primary))
        assertSame(cleanup, primary.suppressed.single())
    }
}
