package com.foxhole.guard.ui.cli.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.foxhole.guard.ui.cli.CliTheme
import org.junit.Rule
import org.junit.Test

class CliMetricSpinnerTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun unifiedMetricLoaderIsCircular() {
        composeRule.setContent {
            CliTheme {
                CliMetricSpinner()
            }
        }
        composeRule.onNodeWithTag(CLI_METRIC_SPINNER_TAG).assertIsDisplayed()
    }
}
