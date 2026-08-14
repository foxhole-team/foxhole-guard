package com.foxhole.guard.core.settings

import com.foxhole.core.model.AppLockMode
import com.foxhole.core.model.AppLockTimeout
import com.foxhole.core.model.GuardHostingMode
import com.foxhole.core.model.SETTINGS_SCHEMA_VERSION
import com.foxhole.core.model.Settings
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class AppLockSettingsCompatTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            coerceInputValues = true
        }

    @Test
    fun `schema v17 settings file without appLock decodes to safe defaults`() {
        val storedV17 = """{"schemaVersion":17,"ui":{"themeMode":"DARK"}}"""

        val decoded = json.decodeFromString<Settings>(storedV17).normalized()

        assertEquals(SETTINGS_SCHEMA_VERSION, decoded.schemaVersion)
        assertEquals(AppLockMode.OFF, decoded.appLock.mode)
        assertEquals(AppLockTimeout.AFTER_REBOOT, decoded.appLock.lockTimeout)
        assertEquals(GuardHostingMode.ECONOMY, decoded.appLock.guardHosting)
        assertEquals(null, decoded.appLock.passwordSetAt)
    }

    @Test
    fun `unknown stored appLock enum values coerce to field defaults`() {
        val futureFile =
            """{"schemaVersion":18,"appLock":{"mode":"RETINA_SCAN","lockTimeout":"MIN_45","guardHosting":"ORBITAL"}}"""

        val decoded = json.decodeFromString<Settings>(futureFile).normalized()

        assertEquals(AppLockMode.OFF, decoded.appLock.mode)
        assertEquals(AppLockTimeout.AFTER_REBOOT, decoded.appLock.lockTimeout)
        assertEquals(GuardHostingMode.ECONOMY, decoded.appLock.guardHosting)
    }

    @Test
    fun `guard hosting collapses to economy whenever the password mode is off`() {
        val stored =
            """{"schemaVersion":18,"appLock":{"mode":"SYSTEM","guardHosting":"REINFORCED"}}"""

        val decoded = json.decodeFromString<Settings>(stored).normalized()

        assertEquals(AppLockMode.SYSTEM, decoded.appLock.mode)
        assertEquals(GuardHostingMode.ECONOMY, decoded.appLock.guardHosting)
    }

    @Test
    fun `password mode keeps the reinforced hosting choice`() {
        val stored =
            """{"schemaVersion":18,"appLock":{"mode":"PASSWORD","guardHosting":"REINFORCED","lockTimeout":"HOUR_1"}}"""

        val decoded = json.decodeFromString<Settings>(stored).normalized()

        assertEquals(AppLockMode.PASSWORD, decoded.appLock.mode)
        assertEquals(GuardHostingMode.REINFORCED, decoded.appLock.guardHosting)
        assertEquals(AppLockTimeout.HOUR_1, decoded.appLock.lockTimeout)
    }

    @Test
    fun `timeouts expose the minutes used by the relock policy`() {
        assertEquals(15, AppLockTimeout.MIN_15.minutes)
        assertEquals(30, AppLockTimeout.MIN_30.minutes)
        assertEquals(60, AppLockTimeout.HOUR_1.minutes)
    }

    @Test
    fun `stored explicit timeout survives and biometric flag drops with the lock off`() {
        val stored =
            """{"schemaVersion":18,"appLock":{"mode":"OFF","lockTimeout":"MIN_30","biometricEnabled":true}}"""

        val decoded = json.decodeFromString<Settings>(stored).normalized()

        assertEquals(AppLockTimeout.MIN_30, decoded.appLock.lockTimeout)
        assertEquals(false, decoded.appLock.biometricEnabled)
        assertEquals(true, decoded.appLock.authAttemptNoticeEnabled)
    }
}
