package com.foxhole.guard.core.sentinel.anomaly

import com.foxhole.core.model.AnomalySeverity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnomalyEventCoalescingTest {
    @Test
    fun `first sighting is never coalesced`() {
        assertFalse(
            shouldCoalesceAnomalyEvent(
                previousSeverity = null,
                candidateSeverity = AnomalySeverity.ACTIVITY_LOG,
            ),
        )
    }

    @Test
    fun `an ongoing event at the same or lower severity is coalesced`() {
        assertTrue(
            shouldCoalesceAnomalyEvent(
                previousSeverity = AnomalySeverity.NOTIFICATION,
                candidateSeverity = AnomalySeverity.NOTIFICATION,
            ),
        )
        assertTrue(
            shouldCoalesceAnomalyEvent(
                previousSeverity = AnomalySeverity.NOTIFICATION,
                candidateSeverity = AnomalySeverity.ACTIVITY_LOG,
            ),
        )
    }

    @Test
    fun `an escalation is re-logged`() {
        assertFalse(
            shouldCoalesceAnomalyEvent(
                previousSeverity = AnomalySeverity.ACTIVITY_LOG,
                candidateSeverity = AnomalySeverity.HIGH,
            ),
        )
    }
}
