package com.foxhole.guard.ui.cli.settings

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import com.foxhole.core.model.InstalledAppOption
import com.foxhole.guard.R
import com.foxhole.guard.core.sentinel.scanner.SentinelScanControlState
import com.foxhole.guard.core.sentinel.scanner.SentinelScanStage
import com.foxhole.guard.ui.HomeUiState
import com.foxhole.guard.ui.cli.CliTheme
import com.foxhole.guard.ui.cli.logs.CliSentinelJournalContent
import org.junit.Rule
import org.junit.Test

class CliSentinelScanProgressAndroidTest {
    @get:Rule
    val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val app = InstalledAppOption("owned.current", "Owned scan application", false)

    @Test
    fun progressUpdatesFromQuarterToHalfAndResolvesTheCurrentApplication() {
        val scan = mutableStateOf(SentinelScanControlState(SentinelScanStage.RUNNING, app.packageName, 1, 4))
        renderJournal(scan)
        compose.onNodeWithTag(CLI_SENTINEL_SCAN_CURRENT_APP_TAG).assertTextEquals(app.label).assertIsDisplayed()
        assertProgress(.25f)
        compose.onNodeWithText(context.getString(R.string.sentinel_static_progress_percent, 25)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.sentinel_static_progress_count, 1, 4)).assertIsDisplayed()
        compose.runOnIdle { scan.value = scan.value.copy(completedPackages = 2) }
        assertProgress(.5f)
        compose.onNodeWithText(context.getString(R.string.sentinel_static_progress_percent, 50)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.sentinel_static_cancel)).assertIsDisplayed()
    }

    @Test
    fun currentApplicationFallsBackToItsActualPackageName() {
        compose.setContent {
            CliTheme(pixelArtEnabled = false) {
                CliSentinelScanProgress(
                    scan = SentinelScanControlState(SentinelScanStage.RUNNING, "owned.unlisted", 2, 4),
                    installedApps = listOf(app),
                )
            }
        }
        compose.onNodeWithTag(CLI_SENTINEL_SCAN_CURRENT_APP_TAG).assertTextEquals("owned.unlisted").assertIsDisplayed()
        assertProgress(.5f)
    }

    @Test
    fun unknownPackageTotalUsesIndeterminateSemanticsWithoutAnInventedPercent() {
        compose.setContent {
            CliTheme(pixelArtEnabled = false) {
                CliSentinelScanProgress(
                    scan = SentinelScanControlState(stage = SentinelScanStage.RUNNING),
                    installedApps = emptyList(),
                )
            }
        }
        compose.onNodeWithTag(CLI_SENTINEL_SCAN_PROGRESS_TAG).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate),
        )
        compose.onNodeWithText(context.getString(R.string.sentinel_static_progress_unknown)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.sentinel_static_progress_percent, 0)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.sentinel_static_progress_percent, 100)).assertDoesNotExist()
    }

    @Test
    fun partialResultRetainsItsStageEvenWhenEveryRequestedPackageWasProcessed() {
        val scan = mutableStateOf(SentinelScanControlState(SentinelScanStage.RUNNING, app.packageName, 1, 2))
        renderJournal(scan)
        assertProgress(.5f)
        compose.runOnIdle {
            scan.value = scan.value.copy(stage = SentinelScanStage.PARTIAL, completedPackages = 2)
        }
        compose.onNodeWithText(context.getString(R.string.sentinel_static_status_partial)).assertIsDisplayed()
        compose.onNodeWithTag(CLI_SENTINEL_SCAN_PROGRESS_TAG).assertDoesNotExist()
        compose.onNodeWithTag(CLI_SENTINEL_SCAN_CURRENT_APP_TAG).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.sentinel_static_progress_percent, 100)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.sentinel_static_cancel)).assertDoesNotExist()
    }

    private fun assertProgress(fraction: Float) {
        compose.onNodeWithTag(CLI_SENTINEL_SCAN_PROGRESS_TAG).assertIsDisplayed().assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ProgressBarRangeInfo,
                ProgressBarRangeInfo(fraction, 0f..1f),
            ),
        )
    }

    private fun renderJournal(scan: MutableState<SentinelScanControlState>) {
        compose.setContent {
            CliTheme(pixelArtEnabled = false) {
                CliSentinelJournalContent(
                    state = HomeUiState(installedApps = listOf(app), installedAppsLoaded = true),
                    scan = scan.value,
                    scannerAvailable = true,
                    onResolveQuarantine = { _, _ -> },
                    onCancelScan = {},
                )
            }
        }
    }
}
