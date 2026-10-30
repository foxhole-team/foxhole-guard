package com.foxhole.guard.ui.cli

import com.foxhole.core.model.InterfaceMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CliInterfaceModeDockTest {
    @Test
    fun `user hides only map while preserving optional dock choices`() {
        val screens = cliDockScreens(
            webAppsVisible = true,
            statisticsDockIconEnabled = true,
            interfaceMode = InterfaceMode.USER,
        )

        assertFalse(CliScreen.MAP in screens)
        assertTrue(CliScreen.WEBAPPS in screens)
        assertTrue(CliScreen.STATS in screens)
        assertEquals(CliScreen.entries.filter { it != CliScreen.MAP }, screens)
    }

    @Test
    fun `expert keeps map in the dock`() {
        val screens = cliDockScreens(
            webAppsVisible = false,
            statisticsDockIconEnabled = false,
            interfaceMode = InterfaceMode.EXPERT,
        )

        assertTrue(CliScreen.MAP in screens)
    }

    @Test
    fun `user map details keep home selected without changing the active route`() {
        assertEquals(CliScreen.HOME, cliDockSelectedScreen(CliScreen.MAP, InterfaceMode.USER))
        assertEquals(CliScreen.MAP, cliDockSelectedScreen(CliScreen.MAP, InterfaceMode.EXPERT))
        CliScreen.entries.filter { it != CliScreen.MAP }.forEach { screen ->
            assertEquals(screen, cliDockSelectedScreen(screen, InterfaceMode.USER))
        }
    }
}
