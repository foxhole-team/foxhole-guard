package com.foxhole.guard.guardian

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PendingQuarantineAnalysisWorkerContractTest {
    @Test
    fun `new install cannot lose its durable analysis wakeup behind a running worker`() {
        val source =
            File(
                "src/main/kotlin/com/foxhole/guard/guardian/" +
                    "PendingQuarantineAnalysisWorker.kt",
            ).readText()

        assertTrue(source.contains("ExistingWorkPolicy.APPEND_OR_REPLACE"))
        assertFalse(source.contains("ExistingWorkPolicy.KEEP"))
    }
}
