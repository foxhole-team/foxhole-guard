package com.foxhole.guard.core.sentinel.anomaly

import android.app.AppOpsManager
import org.junit.Assert.assertEquals
import org.junit.Test

class UsageStatsAccessTest {
    @Test
    fun `explicit AppOps decisions remain authoritative`() {
        assertEquals(
            UsageAccessState.GRANTED,
            usageAccessStateForMode(AppOpsManager.MODE_ALLOWED, hasQueryableUsage = false),
        )
        assertEquals(
            UsageAccessState.DENIED,
            usageAccessStateForMode(AppOpsManager.MODE_IGNORED, hasQueryableUsage = true),
        )
        assertEquals(
            UsageAccessState.DENIED,
            usageAccessStateForMode(AppOpsManager.MODE_ERRORED, hasQueryableUsage = true),
        )
    }

    @Test
    fun `default with empty history never becomes a persisted revocation`() {
        assertEquals(
            UsageAccessState.INDETERMINATE,
            usageAccessStateForMode(AppOpsManager.MODE_DEFAULT, hasQueryableUsage = false),
        )
        assertEquals(
            UsageAccessState.GRANTED,
            usageAccessStateForMode(AppOpsManager.MODE_DEFAULT, hasQueryableUsage = true),
        )
    }
}
