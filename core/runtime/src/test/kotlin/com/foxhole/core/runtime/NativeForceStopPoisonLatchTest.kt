package com.foxhole.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeForceStopPoisonLatchTest {
    @Test
    fun `healthy outcomes pass through without poisoning the latch`() {
        val latch = NativeForceStopPoisonLatch()

        assertEquals(NativeForceStopOutcome.RELEASED, latch.remember(NativeForceStopOutcome.RELEASED))
        assertEquals(NativeForceStopOutcome.NOT_ATTEMPTED, latch.current)
        assertFalse(latch.processPoisoned)
    }

    @Test
    fun `poisoned outcome remains sticky across later healthy stops`() {
        val latch = NativeForceStopPoisonLatch()

        assertEquals(
            NativeForceStopOutcome.QUARANTINED,
            latch.remember(NativeForceStopOutcome.QUARANTINED),
        )
        assertEquals(
            NativeForceStopOutcome.QUARANTINED,
            latch.remember(NativeForceStopOutcome.RELEASED),
        )
        assertEquals(NativeForceStopOutcome.QUARANTINED, latch.current)
        assertTrue(latch.processPoisoned)
    }

    @Test
    fun `later poisoned evidence replaces the retained outcome`() {
        val latch = NativeForceStopPoisonLatch()

        latch.remember(NativeForceStopOutcome.QUARANTINED)

        assertEquals(
            NativeForceStopOutcome.CALL_TIMED_OUT,
            latch.remember(NativeForceStopOutcome.CALL_TIMED_OUT),
        )
        assertEquals(NativeForceStopOutcome.CALL_TIMED_OUT, latch.current)
    }

    @Test
    fun `only the first healthy to poisoned transition notifies the process observer`() {
        val latch = NativeForceStopPoisonLatch()
        val notifications = mutableListOf<NativeForceStopOutcome>()

        latch.remember(NativeForceStopOutcome.RELEASED, notifications::add)
        latch.remember(NativeForceStopOutcome.QUARANTINED, notifications::add)
        latch.remember(NativeForceStopOutcome.FAILED, notifications::add)
        latch.remember(NativeForceStopOutcome.CALL_TIMED_OUT, notifications::add)

        assertEquals(listOf(NativeForceStopOutcome.QUARANTINED), notifications)
        assertEquals(NativeForceStopOutcome.CALL_TIMED_OUT, latch.current)
    }
}
