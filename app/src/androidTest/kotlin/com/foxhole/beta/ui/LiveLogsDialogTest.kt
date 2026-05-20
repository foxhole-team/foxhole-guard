package com.foxhole.beta.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.foxhole.beta.core.diagnostics.DiagnosticEntry
import com.foxhole.beta.core.model.ThemeMode
import com.foxhole.beta.ui.theme.FoxholeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class LiveLogsDialogTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun emptyStateShowsRetentionSummaryAndInvokesClose() {
        var dismissed = 0

        composeRule.setContent {
            FoxholeTheme(themeMode = ThemeMode.LIGHT) {
                LiveLogsDialog(
                    title = "Foxhole journal",
                    entries = emptyList(),
                    onDismiss = { dismissed += 1 },
                )
            }
        }

        composeRule.onNodeWithTag(LIVE_LOGS_DIALOG_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(LIVE_LOGS_EMPTY_STATE_TAG).assertIsDisplayed()
        composeRule.onAllNodesWithTag(LIVE_LOGS_NETWORK_NOTICE_TAG).assertCountEquals(0)

        composeRule.onNodeWithTag(LIVE_LOGS_CLOSE_ACTION_TAG).performClick()

        assertEquals(1, dismissed)
    }

    @Test
    fun nonEmptyStateShowsNetworkNoticeAndLogList() {
        composeRule.setContent {
            FoxholeTheme(themeMode = ThemeMode.LIGHT) {
                LiveLogsDialog(
                    title = "Network activity journal",
                    entries =
                        listOf(
                            DiagnosticEntry(
                                timestamp = 1_700_000_000_000L,
                                tag = "network",
                                message = "App connection: app=Chrome • remote=1.1.1.1:443",
                            ),
                        ),
                    notice = "Network activity is logged only while Foxhole VPN is running.",
                    onDismiss = {},
                )
            }
        }

        composeRule.onNodeWithText("Network activity journal").assertIsDisplayed()
        composeRule.onNodeWithTag(LIVE_LOGS_NETWORK_NOTICE_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(LIVE_LOGS_LIST_TAG).assertIsDisplayed()
        composeRule.onAllNodesWithTag(LIVE_LOGS_EMPTY_STATE_TAG).assertCountEquals(0)
    }

    @Test
    fun sanitizeToggleControlsNetworkLogPreview() {
        composeRule.setContent {
            FoxholeTheme(themeMode = ThemeMode.LIGHT) {
                val sanitize =
                    androidx.compose.runtime.remember {
                        androidx.compose.runtime.mutableStateOf(true)
                    }
                LiveLogsDialog(
                    title = "Network activity journal",
                    entries =
                        listOf(
                            DiagnosticEntry(
                                timestamp = 1_700_000_000_000L,
                                tag = "activity",
                                message = "App connection: app=Chrome • remote=1.1.1.1:443",
                            ),
                        ),
                    sanitizeEntries = sanitize.value,
                    onSanitizeEntriesChanged = { sanitize.value = it },
                    onDismiss = {},
                )
            }
        }

        composeRule.onNodeWithText("Remote: [redacted]").assertIsDisplayed()
        composeRule.onAllNodesWithText("Remote: 1.1.1.1:443").assertCountEquals(0)

        composeRule.onNodeWithText("Hide private data").performClick()

        composeRule.onNodeWithText("Remote: 1.1.1.1:443").assertIsDisplayed()
    }
}
