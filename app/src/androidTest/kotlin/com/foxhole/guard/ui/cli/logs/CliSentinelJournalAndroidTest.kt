package com.foxhole.guard.ui.cli.logs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.platform.app.InstrumentationRegistry
import com.foxhole.core.model.InstalledAppOption
import com.foxhole.core.model.PendingQuarantineAppDetails
import com.foxhole.core.model.Settings
import com.foxhole.guard.R
import com.foxhole.guard.core.sentinel.scanner.SentinelScanControlState
import com.foxhole.guard.core.sentinel.scanner.SentinelScanStage
import com.foxhole.guard.ui.HomeUiState
import com.foxhole.guard.ui.cli.CliTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class CliSentinelJournalAndroidTest {
    @get:Rule
    val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val apps = (1..9).map {
        InstalledAppOption("owned.install.$it", "Owned installed $it", false, firstInstallTime = it.toLong())
    }

    @Test
    fun journalKeepsOlderInstallationsBeyondTheStatisticsPreview() {
        render(HomeUiState(installedApps = apps, installedAppsLoaded = true))
        compose.onNodeWithTag(CLI_SENTINEL_JOURNAL_TAG).performScrollToNode(hasText(apps.first().label))
        compose.onNodeWithText(apps.first().label).assertIsDisplayed()
        compose.onNodeWithTag(CLI_SENTINEL_JOURNAL_TAG).performScrollToNode(hasText(apps.last().label))
        compose.onNodeWithText(apps.last().label).assertIsDisplayed()
    }

    @Test
    fun statisticsShowsSixLatestThenOpensTheFullLazyList() {
        compose.setContent {
            CliTheme(pixelArtEnabled = false) {
                Column(Modifier.fillMaxSize()) { CliRecentInstallationsPreview(apps) }
            }
        }
        compose.onNodeWithText(apps.first().label).assertDoesNotExist()
        compose.onNodeWithText(apps.last().label).assertIsDisplayed()
        compose.onNodeWithTag(CLI_SENTINEL_INSTALL_PREVIEW_TAG).performClick()
        compose.onNode(isDialog()).assertIsDisplayed()
        compose.onNodeWithTag(CLI_SENTINEL_INSTALL_FULL_LIST_TAG).performScrollToNode(hasText(apps.first().label))
        compose.onNodeWithText(apps.first().label).assertIsDisplayed()
    }

    @Test
    fun olderPendingReviewsStayActionableWithoutAnImplicitResolutionOrScan() {
        val pending = (1..9).map { "owned.pending.$it" }
        val details = pending.mapIndexed { index, name ->
            PendingQuarantineAppDetails(
                packageName = name,
                label = "Pending review ${index + 1}",
                firstInstallTime = 1L,
                detectedAt = 2L,
                analysisComplete = false,
            )
        }
        val settings = Settings().let {
            it.copy(expert = it.expert.copy(pendingQuarantinePackages = pending, pendingQuarantineAppDetails = details))
        }
        val resolutions = mutableListOf<Pair<String, Boolean>>()
        var scansCancelled = 0
        render(
            state = HomeUiState(settings = settings, installedApps = apps, installedAppsLoaded = true),
            onResolve = { name, blocked -> resolutions += name to blocked },
            onCancelScan = { scansCancelled++ },
        )
        val old = details.last()
        compose.onNodeWithTag(CLI_SENTINEL_JOURNAL_TAG).performScrollToNode(hasText(old.label))
        compose.onNodeWithText(old.label).performClick()
        val pendingRisk = context.getString(
            R.string.cli_quarantine_risk_marker,
            context.getString(R.string.cli_quarantine_analysis_pending),
        )
        compose.onNodeWithText(pendingRisk, substring = true).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.cli_common_close_action), ignoreCase = true).performClick()
        compose.runOnIdle {
            assertEquals(emptyList<Pair<String, Boolean>>(), resolutions)
            assertEquals(0, scansCancelled)
        }
        compose.onNodeWithText(old.label).performClick()
        compose.onNodeWithText(context.getString(R.string.cli_quarantine_allow), ignoreCase = true).performClick()
        compose.runOnIdle {
            assertEquals(listOf(old.packageName to false), resolutions)
            assertEquals(0, scansCancelled)
            assertEquals(pending, settings.expert.pendingQuarantinePackages)
        }
    }

    private fun render(
        state: HomeUiState,
        onResolve: (String, Boolean) -> Unit = { _, _ -> },
        onCancelScan: () -> Unit = {},
    ) {
        compose.setContent {
            CliTheme(pixelArtEnabled = false) {
                CliSentinelJournalContent(
                    state = state,
                    scan = SentinelScanControlState(stage = SentinelScanStage.IDLE),
                    scannerAvailable = true,
                    onResolveQuarantine = onResolve,
                    onCancelScan = onCancelScan,
                )
            }
        }
    }
}
