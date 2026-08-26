package com.foxhole.guard.ui.cli

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CliStatisticsPlacementTest {
    @Test
    fun `statistics is absent from the dock by default`() {
        val screens = cliDockScreens(webAppsVisible = false, statisticsDockIconEnabled = false)

        assertFalse(CliScreen.STATS in screens)
        assertFalse(CliScreen.WEBAPPS in screens)
        assertTrue(CliScreen.SETTINGS in screens)
    }

    @Test
    fun `statistics moves into the dock only when explicitly enabled`() {
        val screens = cliDockScreens(webAppsVisible = true, statisticsDockIconEnabled = true)

        assertTrue(CliScreen.STATS in screens)
        assertTrue(CliScreen.WEBAPPS in screens)
    }
}
