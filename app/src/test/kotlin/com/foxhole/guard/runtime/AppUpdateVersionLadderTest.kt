package com.foxhole.guard.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateVersionLadderTest {
    private fun version(raw: String) = requireNotNull(AppUpdateVersion.parseOrNull(raw)) { "unparsed: $raw" }

    private fun behind(
        installed: String,
        latest: String,
    ) = appUpdateVersionsBehind(version(installed), version(latest))

    @Test
    fun `a v prefix and a ref prefix are not part of the version`() {
        assertEquals(version("0.0.3"), version("v0.0.3"))
        assertEquals(version("0.0.3"), version("V0.0.3"))
        assertEquals(version("0.0.3"), version("refs/tags/v0.0.3"))
        assertEquals("0.0.3", appUpdateDisplayVersionName("refs/tags/v0.0.3"))
        assertEquals("nightly", appUpdateDisplayVersionName("nightly"))
        assertEquals("a v that is not a prefix must survive", "voice1.0", appUpdateDisplayVersionName("voice1.0"))
    }

    @Test
    fun `build metadata orders nothing and a pre-release orders below its release`() {
        assertEquals(version("1.2.3"), version("1.2.3+ci77"))
        assertTrue(version("1.2.3") > version("1.2.3-rc1"))
        assertTrue(version("1.2.3-rc2") > version("1.2.3-rc1"))
        assertTrue(version("1.2.3-beta10") > version("1.2.3-beta5"))
        assertTrue("a numeric identifier ranks below an alphanumeric one", version("1.2.3-beta") > version("1.2.3-1"))
    }

    @Test
    fun `unequal component counts are compared as if zero-padded`() {
        assertEquals("1.2 and 1.2.0 are the same version", 0, version("1.2").compareTo(version("1.2.0")))
        assertTrue(version("1.2.1") > version("1.2"))
        assertTrue(version("1.3") > version("1.2.9"))
        assertEquals(0, behind(installed = "1.2", latest = "1.2.0"))
    }

    @Test
    fun `a tag that carries no orderable number is refused rather than guessed`() {
        assertNull(AppUpdateVersion.parseOrNull("latest"))
        assertNull(AppUpdateVersion.parseOrNull("nightly-build"))
        assertNull(AppUpdateVersion.parseOrNull(""))
        assertNull(AppUpdateVersion.parseOrNull(null))
    }

    @Test
    fun `one two and three releases behind are counted exactly`() {
        assertEquals(1, behind(installed = "0.0.2", latest = "0.0.3"))
        assertEquals(2, behind(installed = "0.0.2", latest = "0.0.4"))
        assertEquals(3, behind(installed = "0.0.2", latest = "0.0.5"))
        assertEquals(7, behind(installed = "0.0.2", latest = "0.0.9"))
    }

    @Test
    fun `an equal or newer installed build is never behind`() {
        assertEquals(0, behind(installed = "0.0.2", latest = "0.0.2"))
        assertEquals(0, behind(installed = "0.0.2", latest = "v0.0.2"))
        assertEquals(0, behind(installed = "0.0.3", latest = "0.0.2"))
        assertEquals(0, behind(installed = "1.0.0", latest = "0.9.9"))
        assertEquals(0, behind(installed = "1.2.3", latest = "1.2.3-rc9"))
    }

    @Test
    fun `a bump above the patch component retires the whole older line`() {
        assertEquals("0.0.2 against 0.1.0 is not one release", 3, behind(installed = "0.0.2", latest = "0.1.0"))
        assertEquals(3, behind(installed = "0.9.0", latest = "1.0.0"))
        assertEquals(5, behind(installed = "0.0.2", latest = "5.0.0"))
    }

    @Test
    fun `a pre-release is exactly one release behind the version it leads to`() {
        assertEquals(1, behind(installed = "0.0.3-rc1", latest = "0.0.3"))
    }

    @Test
    fun `the ladder maps a delta onto one rung and saturates at three`() {
        assertEquals(AppUpdateSeverity.NONE, AppUpdateSeverity.forVersionsBehind(0))
        assertEquals(AppUpdateSeverity.NONE, AppUpdateSeverity.forVersionsBehind(-1))
        assertEquals(AppUpdateSeverity.BEHIND_ONE, AppUpdateSeverity.forVersionsBehind(1))
        assertEquals(AppUpdateSeverity.BEHIND_TWO, AppUpdateSeverity.forVersionsBehind(2))
        assertEquals(AppUpdateSeverity.BEHIND_MANY, AppUpdateSeverity.forVersionsBehind(3))
        assertEquals(AppUpdateSeverity.BEHIND_MANY, AppUpdateSeverity.forVersionsBehind(42))
    }

    @Test
    fun `only the worst rung is allowed to interrupt`() {
        assertFalse(AppUpdateSeverity.NONE.notifies)
        assertFalse(AppUpdateSeverity.BEHIND_ONE.notifies)
        assertFalse(AppUpdateSeverity.BEHIND_TWO.notifies)
        assertTrue(AppUpdateSeverity.BEHIND_MANY.notifies)
    }

    @Test
    fun `a version behind three or more is announced once, not once per check`() {
        assertTrue(
            AppUpdatePolicy.updateNotificationRequired(
                severity = AppUpdateSeverity.BEHIND_MANY,
                availableVersionName = "0.0.5",
                lastNotifiedVersionName = "",
            ),
        )
        assertFalse(
            "the same version must stay silent on the next run",
            AppUpdatePolicy.updateNotificationRequired(
                severity = AppUpdateSeverity.BEHIND_MANY,
                availableVersionName = "0.0.5",
                lastNotifiedVersionName = "0.0.5",
            ),
        )
        assertFalse(
            "a re-tagged older release must not announce itself again",
            AppUpdatePolicy.updateNotificationRequired(
                severity = AppUpdateSeverity.BEHIND_MANY,
                availableVersionName = "v0.0.4",
                lastNotifiedVersionName = "0.0.5",
            ),
        )
        assertTrue(
            "a release newer than the announced one announces again",
            AppUpdatePolicy.updateNotificationRequired(
                severity = AppUpdateSeverity.BEHIND_MANY,
                availableVersionName = "v0.0.6",
                lastNotifiedVersionName = "0.0.5",
            ),
        )
    }

    @Test
    fun `being one or two releases behind changes the colour and nothing else`() {
        listOf(AppUpdateSeverity.BEHIND_ONE, AppUpdateSeverity.BEHIND_TWO, AppUpdateSeverity.NONE)
            .forEach { severity ->
                assertFalse(
                    "$severity must not post a notification",
                    AppUpdatePolicy.updateNotificationRequired(
                        severity = severity,
                        availableVersionName = "0.0.4",
                        lastNotifiedVersionName = "",
                    ),
                )
            }
    }

    @Test
    fun `an unorderable version name is never announced`() {
        assertFalse(
            AppUpdatePolicy.updateNotificationRequired(
                severity = AppUpdateSeverity.BEHIND_MANY,
                availableVersionName = "nightly",
                lastNotifiedVersionName = "",
            ),
        )
    }
}
