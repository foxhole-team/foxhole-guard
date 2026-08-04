package com.foxhole.guard.ui.cli.home

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.platform.app.InstrumentationRegistry
import com.foxhole.guard.R
import com.foxhole.guard.ui.cli.CliTheme
import org.junit.Rule
import org.junit.Test

class CliProfileQuickSelectorTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun loadingStateUsesTheCurrentCliSelectorContract() {
        composeRule.setContent {
            CliTheme {
                CliProfileSelectorLoadingState()
            }
        }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule
            .onNodeWithTag(CLI_PROFILE_SELECTOR_LOADING_TAG)
            .assertIsDisplayed()
            .assertTextEquals(context.getString(R.string.cli_common_loading))
    }
}
