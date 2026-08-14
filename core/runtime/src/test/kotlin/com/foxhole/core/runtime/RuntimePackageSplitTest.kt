package com.foxhole.core.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimePackageSplitTest {
    @Test
    fun `include split fails closed when nothing was applied`() {
        // Zero allowed apps would turn the per-app tun into a full-device capture.
        assertTrue(
            shouldFailClosedVpnPackageSplit(
                includeMode = true,
                requestedCount = 1,
                counts = VpnPackageSplitApplyCounts(appliedCount = 0, skippedCount = 1),
            ),
        )
    }

    @Test
    fun `include split tolerates an uninstalled package when others applied`() {
        // The uninstalled app has no traffic; the remaining apps still route through the tunnel.
        assertFalse(
            shouldFailClosedVpnPackageSplit(
                includeMode = true,
                requestedCount = 2,
                counts = VpnPackageSplitApplyCounts(appliedCount = 1, skippedCount = 1),
            ),
        )
    }

    @Test
    fun `exclude split tolerates uninstalled packages entirely`() {
        assertFalse(
            shouldFailClosedVpnPackageSplit(
                includeMode = false,
                requestedCount = 2,
                counts = VpnPackageSplitApplyCounts(appliedCount = 0, skippedCount = 2),
            ),
        )
    }

    @Test
    fun `split fails closed when a package is neither applied nor accounted as skipped`() {
        assertTrue(
            shouldFailClosedVpnPackageSplit(
                includeMode = false,
                requestedCount = 2,
                counts = VpnPackageSplitApplyCounts(appliedCount = 1, skippedCount = 0),
            ),
        )
    }

    @Test
    fun `package split accepts fully applied selected packages`() {
        assertFalse(
            shouldFailClosedVpnPackageSplit(
                includeMode = true,
                requestedCount = 2,
                counts = VpnPackageSplitApplyCounts(appliedCount = 2, skippedCount = 0),
            ),
        )
    }

    @Test
    fun `full tunnel package split remains accepted`() {
        assertFalse(
            shouldFailClosedVpnPackageSplit(
                includeMode = false,
                requestedCount = 0,
                counts = VpnPackageSplitApplyCounts(appliedCount = 0, skippedCount = 0),
            ),
        )
    }
}
