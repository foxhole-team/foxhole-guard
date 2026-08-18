package com.foxhole.guard

import com.foxhole.guard.core.webapps.WebAppsNotifier
import com.foxhole.guard.guardian.FoxholeGuardService
import com.foxhole.guard.guardian.GuardHeartbeatWorker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class WatchdogRenameMigrationTest {

    @Test
    fun `the cleanup knows the identifiers the previous build registered`() {
        assertEquals(listOf("webapps-watchdog", "guard-heartbeat"), legacyWatchdogWorkNamesForTest())
        assertEquals(listOf("foxhole_webapps", "foxhole-guard"), legacyWatchdogChannelIdsForTest())
    }

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
            assertFalse("\"$legacy\" is both a legacy and a live identifier", legacy in current)
        }
    }

    @Test
    fun `the readable name and the identifier are not the same string`() {
        assertFalse(WatchdogNames.WEB == WatchdogNames.WEB_ID)
        assertFalse(WatchdogNames.GUARD == WatchdogNames.GUARD_ID)
        assertFalse("an identifier must not contain a space", WatchdogNames.WEB_ID.contains(' '))
        assertFalse("an identifier must not contain a space", WatchdogNames.GUARD_ID.contains(' '))
    }
}
