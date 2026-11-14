package com.foxhole.guard.ui

import com.foxhole.core.model.InstalledAppOption
import com.foxhole.guard.ui.cli.sentinel.sentinelCurrentAppLabel
import org.junit.Assert.assertEquals
import org.junit.Test

class SentinelCurrentAppLabelTest {
    @Test fun noPackageDuringScanPreparationIsNotAnUnknownApplication() {
        assertEquals("Preparing", sentinelCurrentAppLabel(null, null, null, "Preparing"))
    }
    @Test fun aTemporarilyMissingListEntryUsesTheResolvedApplicationName() {
        assertEquals("Example", sentinelCurrentAppLabel(null, "org.example", "Example", "Preparing"))
    }
    @Test fun aKnownPackageIsStillIdentifiableWhenNoReadableLabelIsAvailable() {
        assertEquals("org.example", sentinelCurrentAppLabel(null, "org.example", null, "Preparing"))
    }
    @Test fun theCapturedApplicationLabelHasPriority() {
        assertEquals("Saved", sentinelCurrentAppLabel(InstalledAppOption("org.example", "Saved", false),
            "org.example", "Other", "Preparing"))
    }
}
