package com.foxhole.beta.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InsecureTlsBadgePresentationTest {
    @Test
    fun `dashboard can suppress insecure tls badge even when profile requires it`() {
        assertFalse(
            shouldShowInsecureTlsProfileBadge(
                showInsecureTlsBadge = false,
                profileRequiresInsecureTls = true,
                selectedOptionRequiresInsecureTls = true,
            ),
        )
    }

    @Test
    fun `profile surfaces show insecure tls badge for profile or selected option markers`() {
        assertTrue(
            shouldShowInsecureTlsProfileBadge(
                showInsecureTlsBadge = true,
                profileRequiresInsecureTls = true,
                selectedOptionRequiresInsecureTls = false,
                hasMultipleProtocolOptions = false,
            ),
        )
        assertTrue(
            shouldShowInsecureTlsProfileBadge(
                showInsecureTlsBadge = true,
                profileRequiresInsecureTls = false,
                selectedOptionRequiresInsecureTls = true,
                hasMultipleProtocolOptions = true,
            ),
        )
    }

    @Test
    fun `smart profile badge follows the selected protocol marker instead of the aggregate profile flag`() {
        assertFalse(
            shouldShowInsecureTlsProfileBadge(
                showInsecureTlsBadge = true,
                profileRequiresInsecureTls = true,
                selectedOptionRequiresInsecureTls = false,
                hasMultipleProtocolOptions = true,
            ),
        )
    }
}
