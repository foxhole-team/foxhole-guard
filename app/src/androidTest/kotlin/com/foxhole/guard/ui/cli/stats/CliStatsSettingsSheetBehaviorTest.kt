package com.foxhole.guard.ui.cli.stats

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import com.foxhole.guard.ui.cli.CliTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

class CliStatsSettingsSheetBehaviorTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun bodyScrollDoesNotDragTheSheet() {
        var dismissed = false
        composeRule.setContent {
            CliTheme {
                CliStatsSettingsSheetFrame(onDismiss = { dismissed = true }) {
                    repeat(ITEM_COUNT) { index ->
                        Text(
                            text = "statistics item $index",
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(ITEM_HEIGHT)
                                .testTag(itemTag(index)),
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithTag(itemTag(ITEM_COUNT - 1)).performScrollTo().assertIsDisplayed()
        val firstItem = composeRule.onNodeWithTag(itemTag(0)).performScrollTo().assertIsDisplayed()
        val topBeforeDrag = firstItem.fetchSemanticsNode().boundsInRoot.top

        composeRule.onNode(hasScrollAction()).performTouchInput { swipeDown() }
        composeRule.waitForIdle()

        assertFalse(dismissed)
        assertEquals(
            topBeforeDrag,
            composeRule.onNodeWithTag(itemTag(0)).fetchSemanticsNode().boundsInRoot.top,
            POSITION_TOLERANCE_PX,
        )
    }

    private fun itemTag(index: Int): String = "stats_settings_item_$index"

    private companion object {
        const val ITEM_COUNT = 32
        val ITEM_HEIGHT = 48.dp
        const val POSITION_TOLERANCE_PX = 0.5f
    }
}
