package com.foxhole.guard.guardian

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardSentinelPolicyTest {
    @Test
    fun `activation baseline treats present apps as known and reports only later installs`() {
        val present = GuardInventoryApp(packageName = "app.present", versionCode = 1L)
        val baseline =
            guardInventoryActivationBaseline(
                installedApps = listOf(present),
                capturedAt = 10L,
            )

        assertTrue(
            GuardInventoryDiff.diff(
                previous = baseline,
                current = GuardInventorySnapshot(capturedAt = 11L, apps = listOf(present)),
            ).isEmpty(),
        )

        val events =
            GuardInventoryDiff.diff(
                previous = baseline,
                current =
                GuardInventorySnapshot(
                    capturedAt = 12L,
                    apps =
                    listOf(
                        present,
                        GuardInventoryApp(packageName = "app.after-enable", versionCode = 1L),
                    ),
                ),
            )

        assertEquals(listOf("app.after-enable"), events.mapNotNull(GuardEvent::packageName))
        assertEquals(listOf(GuardEventType.PACKAGE_ADDED), events.map(GuardEvent::type))
    }

    @Test
    fun `direct package broadcast is excluded from the following inventory reconciliation`() {
        val previous =
            GuardInventorySnapshot(
                capturedAt = 1L,
                apps = listOf(GuardInventoryApp(packageName = "app.old", versionCode = 1L)),
            )
        val current =
            GuardInventorySnapshot(
                capturedAt = 2L,
                apps = listOf(
                    GuardInventoryApp(packageName = "app.direct", versionCode = 1L),
                    GuardInventoryApp(packageName = "app.missed", versionCode = 1L),
                ),
            )

        val events = guardInventoryEventsToJournal(previous, current, excludedPackageName = "app.direct")

        assertEquals(setOf("app.old", "app.missed"), events.mapNotNull(GuardEvent::packageName).toSet())
    }

    @Test
    fun `blackout policy uses the last journal record and ignores ordinary cadence`() {
        val threshold = 30L * 60L * 1_000L

        assertNull(
            guardBlackoutGapMs(
                lastRecordAt = 1_000L,
                nowWall = 1_000L + threshold,
                thresholdMs = threshold,
            ),
        )
        assertEquals(
            threshold + 1L,
            guardBlackoutGapMs(
                lastRecordAt = 1_000L,
                nowWall = 1_001L + threshold,
                thresholdMs = threshold,
            ),
        )
        assertNull(guardBlackoutGapMs(lastRecordAt = 0L, nowWall = Long.MAX_VALUE, thresholdMs = threshold))
    }
}
