package com.foxhole.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeHealthMetricsTest {
    @Test
    fun `main-thread resource snapshot skips expensive memory fields`() {
        val snapshot =
            RuntimeHealthMetrics.captureResourceSnapshot(
                runtimeGeneration = 7L,
                commandQueueDepth = 3,
                activeNetworkCallbacks = 2,
                nativeSnapshot = NativeRuntimeSnapshot.NONE,
                skipExpensiveFields = true,
            )

        assertTrue(snapshot.skippedExpensiveFields)
        assertNull(snapshot.rssKb)
        assertNull(snapshot.pssKb)
        assertNull(snapshot.nativeHeapKb)
        assertEquals(-1, snapshot.threadCount)
        assertEquals(7L, snapshot.runtimeGeneration)
        assertEquals(3, snapshot.commandQueueDepth)
        assertEquals(2, snapshot.activeNetworkCallbacks)
        assertTrue(snapshot.javaHeapKb >= 0L)
    }
}
