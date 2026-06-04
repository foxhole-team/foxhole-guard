package com.foxhole.beta.ui

import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsHomeHotPathTest {
    @Test
    fun `cold settings home starts with all groups ready`() {
        val coldStage = initialSettingsHomeStartupStage(settingsAlreadyWarm = false)

        assertTrue(shouldComposeSettingsHomeSecurityGroup(coldStage))
        assertTrue(shouldComposeSettingsHomeAppGroup(coldStage))
    }

    @Test
    fun `warm settings home return skips staged group delay`() {
        val warmStage = initialSettingsHomeStartupStage(settingsAlreadyWarm = true)

        assertTrue(shouldComposeSettingsHomeSecurityGroup(warmStage))
        assertTrue(shouldComposeSettingsHomeAppGroup(warmStage))
    }
}
