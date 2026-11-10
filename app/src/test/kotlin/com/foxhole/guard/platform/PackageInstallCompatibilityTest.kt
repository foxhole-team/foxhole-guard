package com.foxhole.guard.platform

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PackageInstallCompatibilityTest {
    @Test
    fun legacyAndroidLeavesUnknownSourcesConsentToTheSystemInstaller() {
        for (sdk in listOf(24, 25)) {
            assertFalse(requiresPerAppInstallSettings(sdk) { error("Per-app install permission is unavailable") })
        }
    }

    @Test
    fun modernAndroidStillRequiresItsPerAppInstallGrant() {
        for (sdk in listOf(26, 33, 37)) {
            assertTrue(requiresPerAppInstallSettings(sdk) { false })
            assertFalse(requiresPerAppInstallSettings(sdk) { true })
        }
    }
}
