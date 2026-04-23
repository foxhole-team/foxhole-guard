package com.foxhole.beta.vpn

import com.foxhole.beta.core.model.ConnectionState
import com.foxhole.beta.core.model.NotificationSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StealthNotificationFormatterTest {
    @Test
    fun `uses country flag as subtext`() {
        val snapshot =
            NotificationSnapshot(
                profileName = "edge",
                state = ConnectionState.CONNECTED,
                ipAddress = "185.62.57.44",
                countryCode = "nl",
                countryName = "netherlands",
                trafficAvailable = true,
                txRate = 2_048,
                rxRate = 4_096,
            )

        assertEquals("🇳🇱", StealthNotificationFormatter.subtext(snapshot))
    }

    @Test
    fun `hides subtext for redacted notifications`() {
        val snapshot = NotificationSnapshot(isRedacted = true)

        assertNull(StealthNotificationFormatter.subtext(snapshot))
    }

    @Test
    fun `keeps notification quiet when country is missing`() {
        val snapshot =
            NotificationSnapshot(
                profileName = "edge",
                state = ConnectionState.CONNECTED,
                ipAddress = "185.62.57.44",
            )

        assertNull(StealthNotificationFormatter.subtext(snapshot))
    }
}
