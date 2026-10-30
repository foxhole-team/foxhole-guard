package com.foxhole.guard.ui.cli.settings

import com.foxhole.guard.core.sentinel.scanner.SentinelScanControlState
import com.foxhole.guard.core.sentinel.scanner.SentinelScanStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SentinelScanProgressTest {
    @Test
    fun `global percentage counts completed packages rather than the current package`() {
        val scan = SentinelScanControlState(SentinelScanStage.RUNNING, "owned.current", 1, 4)
        assertEquals(25, sentinelScanProgressPercent(scan))
        assertEquals(50, sentinelScanProgressPercent(scan.copy(completedPackages = 2)))
        assertEquals(33, sentinelScanProgressPercent(scan.copy(completedPackages = 1, requestedPackages = 3)))
    }

    @Test
    fun `unknown totals stay unknown and inconsistent counts cannot overflow or exceed bounds`() {
        val scan = SentinelScanControlState(stage = SentinelScanStage.RUNNING)
        assertNull(sentinelScanProgressPercent(scan))
        assertNull(sentinelScanProgressPercent(scan.copy(requestedPackages = -1)))
        assertEquals(0, sentinelScanProgressPercent(scan.copy(completedPackages = -2, requestedPackages = 4)))
        assertEquals(
            100,
            sentinelScanProgressPercent(scan.copy(completedPackages = Int.MAX_VALUE, requestedPackages = 1)),
        )
        assertEquals(
            99,
            sentinelScanProgressPercent(
                scan.copy(completedPackages = Int.MAX_VALUE - 1, requestedPackages = Int.MAX_VALUE),
            ),
        )
    }

    @Test
    fun `terminal and waiting stages never expose a completed safety percentage`() {
        SentinelScanStage.entries.filterNot { it == SentinelScanStage.RUNNING }.forEach { stage ->
            assertNull(
                sentinelScanProgressPercent(
                    SentinelScanControlState(stage = stage, completedPackages = 4, requestedPackages = 4),
                ),
            )
        }
    }
}
