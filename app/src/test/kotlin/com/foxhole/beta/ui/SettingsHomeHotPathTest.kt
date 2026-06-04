package com.foxhole.beta.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsHomeHotPathTest {
    @Test
    fun `cold settings home starts with first group only`() {
        val coldStage = initialSettingsHomeStartupStage(settingsAlreadyWarm = false)

        assertFalse(shouldComposeSettingsHomeSecurityGroup(coldStage))
        assertFalse(shouldComposeSettingsHomeAppGroup(coldStage))
    }

    @Test
    fun `warm settings home return starts with security group before app group`() {
        val warmStage = initialSettingsHomeStartupStage(settingsAlreadyWarm = true)

        assertTrue(shouldComposeSettingsHomeSecurityGroup(warmStage))
        assertFalse(shouldComposeSettingsHomeAppGroup(warmStage))
    }
}
