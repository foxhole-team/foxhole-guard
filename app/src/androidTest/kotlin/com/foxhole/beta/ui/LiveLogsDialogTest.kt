package com.foxhole.beta.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.foxhole.beta.core.diagnostics.DiagnosticEntry
import com.foxhole.beta.core.model.DiagnosticsRetention
import com.foxhole.beta.core.model.ThemeMode
import com.foxhole.beta.ui.theme.FoxholeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class LiveLogsDialogTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun emptyStateShowsRetentionSummaryAndInvokesActions() {
        var saved = 0
        var shared = 0
        var dismissed = 0

        composeRule.setContent {
            FoxholeTheme(themeMode = ThemeMode.LIGHT) {
                LiveLogsDialog(
                    entries = emptyList(),
                    networkActivityLoggingEnabled = false,
                    retention = DiagnosticsRetention.HOURS_24,
                    onDismiss = { dismissed += 1 },
                    onShareArchive = { shared += 1 },
                    onSaveArchive = { saved += 1 },
                )
            }
        }

        composeRule.onNodeWithTag(LIVE_LOGS_DIALOG_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(LIVE_LOGS_RETENTION_SUMMARY_TAG).assertTextContains("24 hours", substring = true)
        composeRule.onNodeWithTag(LIVE_LOGS_EMPTY_STATE_TAG).assertIsDisplayed()
        composeRule.onAllNodesWithTag(LIVE_LOGS_NETWORK_NOTICE_TAG).assertCountEquals(0)

        composeRule.onNodeWithTag(LIVE_LOGS_SAVE_ACTION_TAG).performClick()
        composeRule.onNodeWithTag(LIVE_LOGS_SHARE_ACTION_TAG).performClick()
        composeRule.onNodeWithTag(LIVE_LOGS_CLOSE_ACTION_TAG).performClick()

        assertEquals(1, saved)
        assertEquals(1, shared)
        assertEquals(1, dismissed)
    }

    @Test
    fun nonEmptyStateShowsNetworkNoticeAndLogList() {
        composeRule.setContent {
            FoxholeTheme(themeMode = ThemeMode.LIGHT) {
                LiveLogsDialog(
                    entries =
                        listOf(
                            DiagnosticEntry(
                                timestamp = 1_700_000_000_000L,
                                tag = "network",
                                message = "App connection: app=Chrome • remote=1.1.1.1:443",
                            ),
                        ),
                    networkActivityLoggingEnabled = true,
                    retention = DiagnosticsRetention.HOURS_6,
                    onDismiss = {},
                    onShareArchive = {},
                    onSaveArchive = {},
                )
            }
        }

        composeRule.onNodeWithTag(LIVE_LOGS_RETENTION_SUMMARY_TAG).assertTextContains("6 hours", substring = true)
        composeRule.onNodeWithTag(LIVE_LOGS_NETWORK_NOTICE_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(LIVE_LOGS_LIST_TAG).assertIsDisplayed()
        composeRule.onAllNodesWithTag(LIVE_LOGS_EMPTY_STATE_TAG).assertCountEquals(0)
    }
}
