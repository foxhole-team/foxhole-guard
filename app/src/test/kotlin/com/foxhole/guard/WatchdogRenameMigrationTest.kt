package com.foxhole.guard

import com.foxhole.guard.core.webapps.WebAppsNotifier
import com.foxhole.guard.guardian.FoxholeGuardService
import com.foxhole.guard.guardian.GuardHeartbeatWorker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The rename is a migration, and a migration is only correct while it still knows what it is
 * migrating FROM.
 *
 * Nothing in the app reads the legacy ids any more, so a later edit could quietly drop them from
 * the cleanup list — or, worse, someone renames a watchdog again and leaves the list pointing at
 * the generation before last. These assertions are the only place the two generations are held
 * side by side.
 */
class WatchdogRenameMigrationTest {

    @Test
    fun `the cleanup knows the identifiers the previous build registered`() {
        assertEquals(listOf("webapps-watchdog", "guard-heartbeat"), legacyWatchdogWorkNamesForTest())
        assertEquals(listOf("foxhole_webapps", "foxhole-guard"), legacyWatchdogChannelIdsForTest())
    }

    /**
     * And that they are genuinely the previous generation: a legacy id that still matched a live
     * one would make the migration cancel the work it had just scheduled.
     */
    @Test
    fun `no legacy identifier collides with a current one`() {
        val current = setOf(
            WatchdogNames.WEB_ID,
            WatchdogNames.GUARD_ID,
            GuardHeartbeatWorker.WORK_NAME,
            WebAppsNotifier.CHANNEL_ID,
            FoxholeGuardService.CHANNEL_ID,
        )

        (legacyWatchdogWorkNamesForTest() + legacyWatchdogChannelIdsForTest()).forEach { legacy ->
            assertFalse("«$legacy» одновременно старый и живой идентификатор", legacy in current)
        }
    }

    /** The two spellings are different on purpose; a name with a space is not an id. */
    @Test
    fun `the readable name and the identifier are not the same string`() {
        assertFalse(WatchdogNames.WEB == WatchdogNames.WEB_ID)
        assertFalse(WatchdogNames.GUARD == WatchdogNames.GUARD_ID)
        assertFalse("идентификатор не может содержать пробел", WatchdogNames.WEB_ID.contains(' '))
        assertFalse("идентификатор не может содержать пробел", WatchdogNames.GUARD_ID.contains(' '))
    }
}
