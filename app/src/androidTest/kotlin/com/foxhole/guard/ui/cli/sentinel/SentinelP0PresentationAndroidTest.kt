package com.foxhole.guard.ui.cli.sentinel

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.foxhole.core.model.AnomalyEvent
import com.foxhole.core.model.AnomalySeverity
import com.foxhole.core.model.AnomalyType
import com.foxhole.core.model.InstalledAppOption
import com.foxhole.guard.R
import com.foxhole.guard.core.sentinel.scanner.ScanReportStatus
import com.foxhole.guard.core.sentinel.scanner.SentinelScanControlState
import com.foxhole.guard.core.sentinel.scanner.SentinelScanReport
import com.foxhole.guard.core.sentinel.scanner.SentinelScanStage
import com.foxhole.guard.ui.cli.CliTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SentinelP0PresentationAndroidTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun duplicateLabelsSelectTheActualPackageAndInvokeTheSharedRequest() {
        val requests = mutableListOf<String>()
        val apps = listOf(InstalledAppOption("org.owned.first", "Owned app", false), InstalledAppOption("org.owned.second", "Owned app", false))
        compose.setContent { CliTheme(pixelArtEnabled = false) { Column {
            SentinelScanActions(apps, SentinelScanControlState(stage = SentinelScanStage.IDLE), true, true, { requests += it }, {}, {})
        } } }
        compose.onNodeWithText(context.getString(R.string.sentinel_static_select_app), ignoreCase = true).performClick()
        compose.onNodeWithText("Owned app · org.owned.second", ignoreCase = true).performClick()
        compose.onNodeWithText(context.getString(R.string.sentinel_static_run), ignoreCase = true).performClick()
        compose.runOnIdle { assertEquals(listOf("org.owned.second"), requests) }
    }

    @Test fun observationBeyondOldTenRowPreviewRemainsReachableWithoutOpeningItsIndicator() {
        val apps = (1..12).map { InstalledAppOption("org.owned.app$it", "Owned app $it", false) }
        val events = apps.mapIndexed { index, app -> AnomalyEvent(
            createdAtMs = index.toLong(), type = AnomalyType.APP_STATIC_REFERENCE,
            severity = AnomalySeverity.ACTIVITY_LOG, score = 1, reason = "owned test",
            evidence = mapOf("indicator" to "https://owned.invalid/static-reference"), packageName = app.packageName,
        ) }
        compose.setContent { CliTheme(pixelArtEnabled = false) { SentinelObservationCards(events, apps) } }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(apps.last().label))
        compose.onNodeWithText(apps.last().label).assertIsDisplayed().performClick()
        compose.onNodeWithText("https://owned.invalid/static-reference").assertHasNoClickAction()
    }

    @Test fun cachedPartialReportHasTextualCoverageAndOpensItsExactIdentity() {
        val report = SentinelScanReport(
            id = "owned-report", processOwner = "owned-process", packageName = "org.owned.app",
            startedAtMs = 1, finishedAtMs = 2, requestType = "PACKAGE", status = ScanReportStatus.PARTIAL,
            appVersion = "test", ruleEpoch = "dex-only", cached = true,
        )
        var opened: String? = null
        compose.setContent { CliTheme(pixelArtEnabled = false) { SentinelReportSummary(report, true) { opened = it } } }
        compose.onNodeWithText(context.getString(R.string.sentinel_static_status_partial)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.sentinel_report_cached)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.sentinel_report_unsaved)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.sentinel_report_coverage, 0, 0, 0, 0)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.sentinel_report_open), ignoreCase = true).performClick()
        compose.runOnIdle { assertEquals("owned-report", opened) }
    }
}
