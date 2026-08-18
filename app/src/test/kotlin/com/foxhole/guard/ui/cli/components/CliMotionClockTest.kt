package com.foxhole.guard.ui.cli.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

internal class CliMotionClockTest {
    @Test
    fun `phase is a position inside the cycle`() {
        val cycle = 1_000
        assertEquals(0f, cliMotionPhaseAt(0L, cycle), TOLERANCE)
        assertEquals(0.5f, cliMotionPhaseAt(500L * NANOS_PER_MS, cycle), TOLERANCE)
        assertEquals(0f, cliMotionPhaseAt(1_000L * NANOS_PER_MS, cycle), TOLERANCE)
        assertEquals(0.25f, cliMotionPhaseAt(9_250L * NANOS_PER_MS, cycle), TOLERANCE)
    }

    @Test
    fun `two callers reading the same frame are in lockstep whenever they started`() {
        val frame = 7_321L * NANOS_PER_MS
        val early = cliMotionPhaseAt(frame, CLI_SHIMMER_CYCLE_MS)
        val late = cliMotionPhaseAt(frame, CLI_SHIMMER_CYCLE_MS)

        assertEquals(early, late, TOLERANCE)
    }

    @Test
    fun `a section appearing mid-cycle resumes rather than restarting at zero`() {
        val mountedMidCycle = (CLI_SHIMMER_CYCLE_MS / 2).toLong() * NANOS_PER_MS

        val phase = cliMotionPhaseAt(mountedMidCycle, CLI_SHIMMER_CYCLE_MS)

        assertTrue("a fresh transition would report 0f here", phase > 0.4f)
    }

    @Test
    fun `the phase never leaves zero to one and survives a negative frame clock`() {
        listOf(-5_000L, -1L, 0L, 1L, 12_345_678L).forEach { millis ->
            val phase = cliMotionPhaseAt(millis * NANOS_PER_MS, CLI_SHIMMER_CYCLE_MS)

            assertTrue("phase out of range for $millis: $phase", phase >= 0f && phase < 1f)
        }
    }

    @Test
    fun `a zero cycle cannot divide by zero`() {
        assertEquals(0f, cliMotionPhaseAt(1_000L, 0), TOLERANCE)
    }

    private companion object {
        const val NANOS_PER_MS = 1_000_000L
        const val TOLERANCE = 0.0001f
    }
}
