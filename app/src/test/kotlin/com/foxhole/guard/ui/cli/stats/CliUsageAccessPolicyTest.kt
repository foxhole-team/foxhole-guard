package com.foxhole.guard.ui.cli.stats

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CliUsageAccessPolicyTest {
    @Test
    fun `one enable tap completes after returning with usage access`() {
        assertFalse(shouldCompleteAppTrafficEnable(usageAccessGranted = false, enablePending = true))
        assertFalse(shouldCompleteAppTrafficEnable(usageAccessGranted = true, enablePending = false))
        assertTrue(shouldCompleteAppTrafficEnable(usageAccessGranted = true, enablePending = true))
    }
}
