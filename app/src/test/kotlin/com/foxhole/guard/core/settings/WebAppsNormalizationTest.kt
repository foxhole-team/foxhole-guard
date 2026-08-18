package com.foxhole.guard.core.settings

import com.foxhole.core.model.ExpertSettings
import com.foxhole.core.model.Settings
import com.foxhole.core.model.WebAppsSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

internal class WebAppsNormalizationTest {
    @Test
    fun `push is forced off when firewall is off`() {
        val normalized =
            Settings(
                webApps = WebAppsSettings(enabled = true, pushServiceEnabled = true),
                expert = ExpertSettings(firewallEnabled = false),
            ).normalized()

        assertFalse(normalized.webApps.pushServiceEnabled)
        assertTrue(normalized.webApps.enabled)
    }

    @Test
    fun `push survives when firewall is on`() {
        val normalized =
            Settings(
                webApps = WebAppsSettings(enabled = true, pushServiceEnabled = true),
                expert = ExpertSettings(firewallEnabled = true),
            ).normalized()

        assertTrue(normalized.webApps.pushServiceEnabled)
    }

    @Test
    fun `poll interval snaps to the nearest allowed option`() {
        fun normalizedInterval(minutes: Int): Int =
            Settings(webApps = WebAppsSettings(pollIntervalMinutes = minutes))
                .normalized()
                .webApps
                .pollIntervalMinutes

        assertEquals(5, normalizedInterval(7))
        assertEquals(1, normalizedInterval(-3))
        assertEquals(30, normalizedInterval(300))
        assertEquals(15, normalizedInterval(15))
    }

    @Test
    fun `defaults stay untouched by normalization`() {
        assertEquals(WebAppsSettings(), Settings().normalized().webApps)
    }
}
