package com.foxhole.guard.core.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingLicenseConsentTest {
    @Test
    fun skippedSetupWithoutAgreementCannotPersistConsentOrBypassTheGate() {
        var completed = true // An installation that skipped setup before consent was recorded.
        var acceptedVersion = 0
        var noticeVersion = 0
        var writes = 0
        fun required() = requiresOnboarding(true, completed, acceptedVersion)

        assertTrue(required())
        assertFalse(completeOnboardingWithConsent(licenseAccepted = false) { version ->
            writes += 1
            completed = true
            acceptedVersion = version
            noticeVersion = 1
        })
        assertEquals(0, writes)
        assertEquals(0, acceptedVersion)
        assertEquals(0, noticeVersion)
        assertTrue(required())
        assertFalse(requiresOnboarding(false, completed, acceptedVersion))
        assertTrue(requiresOnboarding(true, true, ONBOARDING_LICENSE_AGREEMENT_VERSION + 1))

        assertTrue(completeOnboardingWithConsent(licenseAccepted = true) { version ->
            writes += 1
            completed = true
            acceptedVersion = version
            noticeVersion = 1
        })
        assertEquals(1, writes)
        assertEquals(ONBOARDING_LICENSE_AGREEMENT_VERSION, acceptedVersion)
        assertFalse(required())
        assertTrue(requiresOnboarding(true, false, acceptedVersion))
    }
}
