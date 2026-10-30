package com.foxhole.guard.core.settings

import com.foxhole.core.model.CoreTuningSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CoreTuningParameterTest {
    @Test
    fun workerMenuOffersOnlyAutoAndSupportedManualPresets() {
        assertEquals(listOf(0, 1, 2, 4), CoreTuningParameter.WORKERS.presets)
    }

    @Test
    fun workerAutoChangesOnlyWorkersAndDoesNotPermitEight() {
        val current = CoreTuningSettings(workerThreads = 4)
        assertEquals(CoreTuningSettings(), CoreTuningParameter.WORKERS.withValue(current, 0))
        assertThrows(IllegalArgumentException::class.java) {
            CoreTuningParameter.WORKERS.withValue(current, 8)
        }
    }

    @Test
    fun autoSentinelDoesNotRelaxOtherParameterRanges() {
        CoreTuningParameter.entries.filter { it != CoreTuningParameter.WORKERS }.forEach { parameter ->
            assertThrows(IllegalArgumentException::class.java) {
                parameter.withValue(CoreTuningSettings(), 0)
            }
        }
    }
}
