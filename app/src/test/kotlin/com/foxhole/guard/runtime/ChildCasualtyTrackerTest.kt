package com.foxhole.guard.runtime

import com.foxhole.core.runtime.I2pdState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChildCasualtyTrackerTest {
    @Test
    fun `killed child that never ran this session is ignored`() {
        val tracker = ChildCasualtyTracker()
        repeat(5) {
            assertNull(tracker.observe(I2pdState.KILLED))
        }
    }

    @Test
    fun `unexpected death is confirmed only after consecutive killed ticks`() {
        val tracker = ChildCasualtyTracker()
        assertNull(tracker.observe(I2pdState.RUNNING))
        assertNull(tracker.observe(I2pdState.KILLED))
        assertEquals("i2pd", tracker.observe(I2pdState.KILLED))
    }

    @Test
    fun `deliberate restart bouncing through killed does not trip the watchdog`() {
        val tracker = ChildCasualtyTracker()
        assertNull(tracker.observe(I2pdState.RUNNING))
        assertNull(tracker.observe(I2pdState.KILLED))
        assertNull(tracker.observe(I2pdState.STARTING))
        assertNull(tracker.observe(I2pdState.RUNNING))
    }

    @Test
    fun `confirmation counter resets after a casualty is reported`() {
        val tracker = ChildCasualtyTracker()
        tracker.observe(I2pdState.RUNNING)
        tracker.observe(I2pdState.KILLED)
        assertEquals("i2pd", tracker.observe(I2pdState.KILLED))
        assertNull(tracker.observe(I2pdState.KILLED))
        assertEquals("i2pd", tracker.observe(I2pdState.KILLED))
    }

    @Test
    fun `disconnected ticks reset the confirmation window`() {
        val tracker = ChildCasualtyTracker()
        tracker.observe(I2pdState.RUNNING)
        tracker.observe(I2pdState.KILLED)
        tracker.resetTicks()
        assertNull(tracker.observe(I2pdState.KILLED))
    }
}
