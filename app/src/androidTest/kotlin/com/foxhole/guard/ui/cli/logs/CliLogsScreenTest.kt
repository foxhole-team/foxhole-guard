package com.foxhole.guard.ui.cli.logs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.foxhole.core.model.DiagnosticEntry
import com.foxhole.guard.ui.cli.CliTheme
import org.junit.Rule
import org.junit.Test

class CliLogsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun emptyAppJournalRendersTheCurrentCliEmptyState() {
        render(entries = emptyList())

        composeRule.onNodeWithTag(CLI_LOGS_EMPTY_TAG).assertIsDisplayed()
        composeRule.onNodeWithText(EMPTY_TEXT).assertIsDisplayed()
    }

    @Test
    fun appJournalAllowsRepeatedRowsWithoutDuplicateLazyListKeys() {
        val duplicate =
            DiagnosticEntry(
                timestamp = 1_700_000_000_000L,
                tag = "runtime",
                message = "bridge write rejected",
            )

        render(entries = listOf(duplicate, duplicate))

        composeRule.onNodeWithTag(CLI_LOGS_LIST_TAG).assertIsDisplayed()
        composeRule.onAllNodesWithText(duplicate.message).assertCountEquals(2)
    }

    @Test
    fun localJournalPreviewStaysRawWhileExportSanitizationRemainsSeparate() {
        val message = "App connection: app=Browser remote=1.1.1.1:443"
        render(
            entries =
                listOf(
                    DiagnosticEntry(
                        timestamp = 1_700_000_000_000L,
                        tag = "runtime",
                        message = message,
                    ),
                ),
        )

        composeRule.onNodeWithText(message).assertIsDisplayed()
    }

    private fun render(entries: List<DiagnosticEntry>) {
        composeRule.setContent {
            CliTheme {
                Column(modifier = Modifier.fillMaxSize()) {
                    CliGroupedDiagnosticList(entries = entries, emptyText = EMPTY_TEXT)
                }
            }
        }
    }

    private companion object {
        const val EMPTY_TEXT = "journal empty"
    }
}
