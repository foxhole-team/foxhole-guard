package com.foxhole.guard.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AppUpdateNotificationRuleTest {
    private var lastNotified = 0L

    private fun announce(
        availableVersionCode: Long,
        installedVersionCode: Long = 89L,
    ): Boolean {
        val required =
            AppUpdatePolicy.updateNotificationRequired(
                availableVersionCode = availableVersionCode,
                installedVersionCode = installedVersionCode,
                lastNotifiedVersionCode = lastNotified,
            )
        if (required) {
            lastNotified = availableVersionCode
        }
        return required
    }

    @Test
    fun `a daily check announces a release exactly once`() {
        assertTrue(announce(90))
        repeat(30) { assertEquals("re-announced on a later daily run", false, announce(90)) }
        assertEquals(90L, lastNotified)
    }

    @Test
    fun `each new release gets its own announcement`() {
        assertTrue(announce(90))
        assertTrue(announce(91))
        assertTrue(announce(92))
        assertEquals(false, announce(92))
    }

    @Test
    fun `installing the announced version silences it`() {
        assertTrue(announce(90))
        assertEquals("the installed version is not an update", false, announce(90, installedVersionCode = 90))
    }

    @Test
    fun `an upgrade past the announced release stays silent until something newer ships`() {
        assertTrue(announce(90))
        assertEquals(false, announce(90, installedVersionCode = 90))
        assertTrue(announce(91, installedVersionCode = 90))
    }

    @Test
    fun `the notifier persists the announced version and honours the notification permission`() {
        val notifier = source("AppUpdateNotifier.kt")

        assertTrue(notifier.contains("AppUpdatePolicy.updateNotificationRequired"))
        assertTrue(notifier.contains("KEY_LAST_NOTIFIED_VERSION_CODE"))
        assertTrue(notifier.contains("Manifest.permission.POST_NOTIFICATIONS"))
        assertTrue(
            "the announcement must be recorded even when the permission is denied",
            notifier.indexOf("rememberNotifiedVersionCode(versionCode)") <
                notifier.indexOf("Manifest.permission.POST_NOTIFICATIONS"),
        )
        assertTrue(notifier.contains("NotificationManager.IMPORTANCE_DEFAULT"))
        assertTrue(notifier.contains("CHANNEL_ID = \"foxhole_app_updates\""))
    }

    @Test
    fun `the update channel is re-registered when the app language changes`() {
        val channels = source("../AppNotificationChannels.kt")

        assertTrue(channels.contains("AppUpdateNotifier.CHANNEL_ID in registered"))
        assertTrue(channels.contains("AppUpdateNotifier(this).ensureChannel()"))
    }

    @Test
    fun `reaching the newest build clears the announcement record`() {
        val worker = source("AppUpdateWorker.kt")

        assertTrue(worker.contains("clearAnnouncedVersion()"))
        assertTrue(worker.contains("AppUpdateCheck.UpToDate ->"))
    }

    private fun source(name: String): String =
        listOf(
            File("app/src/main/kotlin/com/foxhole/guard/runtime/$name"),
            File("src/main/kotlin/com/foxhole/guard/runtime/$name"),
            File("../app/src/main/kotlin/com/foxhole/guard/runtime/$name"),
        ).first(File::isFile).readText()
}
