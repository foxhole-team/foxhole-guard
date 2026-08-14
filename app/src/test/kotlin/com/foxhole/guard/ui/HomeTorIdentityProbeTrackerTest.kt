package com.foxhole.guard.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeTorIdentityProbeTrackerTest {
    @Test
    fun `failure and cancellation fence late results by generation`() {
        val tracker = TorIdentityProbeTracker()
        val failedGeneration = tracker.restart()

        assertEquals(failedGeneration, tracker.begin())
        assertEquals(TorIdentityProbePhase.LOOKING_UP, tracker.state.value.phase)
        tracker.fail(failedGeneration)
        tracker.confirm(failedGeneration)
        assertEquals(TorIdentityProbePhase.FAILED, tracker.state.value.phase)

        val cancelledGeneration = tracker.restart()
        tracker.begin()
        tracker.cancel()
        tracker.confirm(cancelledGeneration)
        tracker.fail(cancelledGeneration)
        assertEquals(TorIdentityProbePhase.CANCELLED, tracker.state.value.phase)
    }

    @Test
    fun `a fresh generation can confirm exactly its own lookup`() {
        val tracker = TorIdentityProbeTracker()
        val staleGeneration = tracker.restart()
        tracker.begin()
        val currentGeneration = tracker.restart()
        tracker.begin()

        tracker.confirm(staleGeneration)
        assertEquals(TorIdentityProbePhase.LOOKING_UP, tracker.state.value.phase)
        tracker.confirm(currentGeneration)
        assertEquals(TorIdentityProbePhase.CONFIRMED, tracker.state.value.phase)
    }

    @Test
    fun `background retry reopens a failed probe with a new generation`() {
        val tracker = TorIdentityProbeTracker()
        val failedGeneration = tracker.restart()
        tracker.begin()
        tracker.fail(failedGeneration)

        val retryGeneration = tracker.begin(retryAfterFailure = true)

        assertEquals(failedGeneration + 1L, retryGeneration)
        assertEquals(TorIdentityProbePhase.LOOKING_UP, tracker.state.value.phase)
        tracker.confirm(failedGeneration)
        assertEquals(TorIdentityProbePhase.LOOKING_UP, tracker.state.value.phase)
        tracker.confirm(retryGeneration)
        assertEquals(TorIdentityProbePhase.CONFIRMED, tracker.state.value.phase)
    }
}
