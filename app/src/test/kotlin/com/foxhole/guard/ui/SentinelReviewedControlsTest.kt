package com.foxhole.guard.ui

import com.foxhole.core.model.InstalledAppOption
import com.foxhole.guard.ui.cli.sentinel.sentinelAppVisibleLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SentinelReviewedControlsTest {
    @Test fun visibleAppNamesNeverFallBackToTechnicalPackageNames() {
        val app = InstalledAppOption("org.example.android", "Example", false)
        assertEquals("Example", sentinelAppVisibleLabel(app, "Unknown app"))
        assertEquals("Unknown app", sentinelAppVisibleLabel(app.copy(label = app.packageName), "Unknown app"))
        assertEquals("Unknown app", sentinelAppVisibleLabel(null, "Unknown app"))
    }
    @Test fun aVerifiedRepeatCanEnableTheModuleButOlderOrUnverifiedDataCannot() {
        val installed = "2026-11-12T00:00:00Z"
        assertTrue(threatIntelVerifiedSuccess(FoxholeUpdatePhase.DONE, installed, installed))
        assertFalse(threatIntelVerifiedSuccess(FoxholeUpdatePhase.FAILED, installed, installed))
        assertFalse(threatIntelVerifiedSuccess(FoxholeUpdatePhase.DONE, "2026-11-11T00:00:00Z", installed))
        assertFalse(threatIntelVerifiedSuccess(FoxholeUpdatePhase.DONE, null, installed))
    }
}
