package com.foxhole.guard.core.webapps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

internal class WebAppProfilesTest {
    @Test
    fun `profile name is stable and derived from the app id`() {
        assertEquals("webapp-7", webAppProfileName(7L))
        assertEquals("webapp-7", webAppProfileName(7L))
    }

    @Test
    fun `different apps never share a profile name`() {
        assertNotEquals(webAppProfileName(1L), webAppProfileName(2L))
    }

    @Test
    fun `profile name never collides with the reserved default`() {
        assertNotEquals("Default", webAppProfileName(0L))
    }

    @Test
    fun `multi profile wins regardless of per site support`() {
        assertEquals(
            WebAppClearMode.PER_APP_PROFILE,
            webAppClearMode(multiProfile = true, perSiteDelete = true),
        )
        assertEquals(
            WebAppClearMode.PER_APP_PROFILE,
            webAppClearMode(multiProfile = true, perSiteDelete = false),
        )
    }

    @Test
    fun `per site clearing rides without profiles`() {
        assertEquals(
            WebAppClearMode.PER_SITE,
            webAppClearMode(multiProfile = false, perSiteDelete = true),
        )
    }

    @Test
    fun `no capability leaves only the consented full wipe`() {
        assertEquals(
            WebAppClearMode.FULL_WIPE_ONLY,
            webAppClearMode(multiProfile = false, perSiteDelete = false),
        )
    }
}
