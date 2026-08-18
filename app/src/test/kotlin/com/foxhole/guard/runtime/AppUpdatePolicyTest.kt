package com.foxhole.guard.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdatePolicyTest {
    @Test
    fun `only the github channel may self-update`() {
        assertTrue(AppUpdatePolicy.selfUpdateAllowed("github"))
        assertFalse(AppUpdatePolicy.selfUpdateAllowed("fdroid"))
        assertFalse(AppUpdatePolicy.selfUpdateAllowed("play"))
        assertFalse(AppUpdatePolicy.selfUpdateAllowed("GitHub"))
        assertFalse(AppUpdatePolicy.selfUpdateAllowed(""))
    }

    @Test
    fun `the background check needs both the github channel and the updates master switch`() {
        assertTrue(
            AppUpdatePolicy.backgroundCheckAllowed(channel = "github", componentUpdateCheckEnabled = true),
        )
        assertFalse(
            AppUpdatePolicy.backgroundCheckAllowed(channel = "github", componentUpdateCheckEnabled = false),
        )
        assertFalse(
            AppUpdatePolicy.backgroundCheckAllowed(channel = "fdroid", componentUpdateCheckEnabled = true),
        )
        assertFalse(
            AppUpdatePolicy.backgroundCheckAllowed(channel = "fdroid", componentUpdateCheckEnabled = false),
        )
    }

    @Test
    fun `a version is announced once and never re-announced`() {
        val installed = 89L
        assertTrue(
            AppUpdatePolicy.updateNotificationRequired(
                availableVersionCode = 90,
                installedVersionCode = installed,
                lastNotifiedVersionCode = 0,
            ),
        )
        assertFalse(
            "the same version must not notify on the next daily run",
            AppUpdatePolicy.updateNotificationRequired(
                availableVersionCode = 90,
                installedVersionCode = installed,
                lastNotifiedVersionCode = 90,
            ),
        )
        assertTrue(
            "a newer release than the announced one still notifies",
            AppUpdatePolicy.updateNotificationRequired(
                availableVersionCode = 91,
                installedVersionCode = installed,
                lastNotifiedVersionCode = 90,
            ),
        )
    }

    @Test
    fun `a version the user already runs is never announced`() {
        assertFalse(
            AppUpdatePolicy.updateNotificationRequired(
                availableVersionCode = 90,
                installedVersionCode = 90,
                lastNotifiedVersionCode = 0,
            ),
        )
        assertFalse(
            "a downgrade is not an update",
            AppUpdatePolicy.updateNotificationRequired(
                availableVersionCode = 88,
                installedVersionCode = 90,
                lastNotifiedVersionCode = 0,
            ),
        )
    }

    @Test
    fun `the cold-start notice belongs to the fdroid channel only`() {
        assertFalse(
            AppUpdatePolicy.fdroidUpdateNoticeRequired(
                channel = "github",
                installedVersionCode = 89,
                floorVersionCode = 95,
                supportedUntilEpochDay = 100,
                todayEpochDay = 999,
            ),
        )
    }

    @Test
    fun `a stamped floor above the installed version means a newer version is known`() {
        assertTrue(
            AppUpdatePolicy.fdroidUpdateNoticeRequired(
                channel = "fdroid",
                installedVersionCode = 89,
                floorVersionCode = 90,
                supportedUntilEpochDay = 0,
                todayEpochDay = 20_000,
            ),
        )
        assertFalse(
            AppUpdatePolicy.fdroidUpdateNoticeRequired(
                channel = "fdroid",
                installedVersionCode = 90,
                floorVersionCode = 90,
                supportedUntilEpochDay = 0,
                todayEpochDay = 20_000,
            ),
        )
    }

    @Test
    fun `the support horizon fires only after it has passed`() {
        assertFalse(
            AppUpdatePolicy.fdroidUpdateNoticeRequired(
                channel = "fdroid",
                installedVersionCode = 89,
                floorVersionCode = 0,
                supportedUntilEpochDay = 20_000,
                todayEpochDay = 20_000,
            ),
        )
        assertTrue(
            AppUpdatePolicy.fdroidUpdateNoticeRequired(
                channel = "fdroid",
                installedVersionCode = 89,
                floorVersionCode = 0,
                supportedUntilEpochDay = 20_000,
                todayEpochDay = 20_001,
            ),
        )
    }

    @Test
    fun `an unstamped fdroid build never nags`() {
        assertFalse(
            AppUpdatePolicy.fdroidUpdateNoticeRequired(
                channel = "fdroid",
                installedVersionCode = 89,
                floorVersionCode = 0,
                supportedUntilEpochDay = 0,
                todayEpochDay = 99_999,
            ),
        )
    }

    @Test
    fun `signer comparison is fail-closed`() {
        assertTrue(appUpdateSignersMatch(setOf("aa"), setOf("aa")))
        assertFalse("an unknown installed signer must never match", appUpdateSignersMatch(emptySet(), emptySet()))
        assertFalse(appUpdateSignersMatch(setOf("aa"), emptySet()))
        assertFalse(appUpdateSignersMatch(setOf("aa"), setOf("bb")))
        assertFalse("an extra signer is still a mismatch", appUpdateSignersMatch(setOf("aa"), setOf("aa", "bb")))
    }
}
