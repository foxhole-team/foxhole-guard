package com.foxhole.beta.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibboxRuntimePackageSplitTest {
    @Test
    fun `package split fails closed when Android skips selected package`() {
        assertTrue(
            shouldFailClosedVpnPackageSplit(
                requestedCount = 1,
                counts = VpnPackageSplitApplyCounts(appliedCount = 0, skippedCount = 1),
            ),
        )
    }

    @Test
    fun `package split fails closed when only some packages are applied`() {
        assertTrue(
            shouldFailClosedVpnPackageSplit(
                requestedCount = 2,
                counts = VpnPackageSplitApplyCounts(appliedCount = 1, skippedCount = 1),
            ),
        )
    }

    @Test
    fun `package split accepts fully applied selected packages`() {
        assertFalse(
            shouldFailClosedVpnPackageSplit(
                requestedCount = 2,
                counts = VpnPackageSplitApplyCounts(appliedCount = 2, skippedCount = 0),
            ),
        )
    }

    @Test
    fun `full tunnel package split remains accepted`() {
        assertFalse(
            shouldFailClosedVpnPackageSplit(
                requestedCount = 0,
                counts = VpnPackageSplitApplyCounts(appliedCount = 0, skippedCount = 0),
            ),
        )
    }
}
