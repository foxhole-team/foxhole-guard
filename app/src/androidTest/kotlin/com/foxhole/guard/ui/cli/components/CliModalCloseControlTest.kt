package com.foxhole.guard.ui.cli.components

import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.platform.app.InstrumentationRegistry
import com.foxhole.guard.ui.cli.CliTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CliModalCloseControlTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun sharedBottomSheetCloseIsVisibleAndDismisses() {
        var dismissed = false

        composeRule.setContent {
            CliTheme {
                CliBottomSheet(
                    onDismiss = { dismissed = true },
                    title = "test",
                    closeActionTag = CLOSE_TAG,
                ) {
                    Text("body")
                }
            }
        }
        composeRule.mainClock.advanceTimeBy(SHEET_SETTLE_MS)
        composeRule.waitForIdle()

        val closeNode = composeRule.onNodeWithTag(CLOSE_TAG)
        val closeBounds = closeNode.fetchSemanticsNode().boundsInRoot
        val displayMetrics = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics
        assertTrue(closeBounds.width > 0f && closeBounds.height > 0f)
        assertTrue(closeBounds.left >= 0f && closeBounds.right <= displayMetrics.widthPixels)
        assertTrue(closeBounds.top >= 0f && closeBounds.bottom <= displayMetrics.heightPixels)
        closeNode
            .assertHasClickAction()
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.runOnIdle { assertTrue(dismissed) }
    }

    @Test
    fun leadingActionAndCloseShareOneFooterBaselineBelowScrollableBody() {
        composeRule.setContent {
            CliTheme {
                CliBottomSheet(
                    onDismiss = {},
                    title = "test",
                    closeActionTag = FOOTER_CLOSE_TAG,
                    footerLeading = {
                        CliButton(
                            label = "test",
                            onClick = {},
                            modifier = Modifier
                                .weight(1f)
                                .testTag(FOOTER_ACTION_TAG),
                        )
                    },
                ) {
                    repeat(LONG_BODY_LINE_COUNT) { line -> Text("body $line") }
                }
            }
        }
        composeRule.mainClock.advanceTimeBy(SHEET_SETTLE_MS)
        composeRule.waitForIdle()

        val action = composeRule.onNodeWithTag(FOOTER_ACTION_TAG).fetchSemanticsNode().boundsInRoot
        val close = composeRule.onNodeWithTag(FOOTER_CLOSE_TAG).fetchSemanticsNode().boundsInRoot

        assertEquals(action.top, close.top, FOOTER_ALIGNMENT_TOLERANCE_PX)
        assertEquals(action.bottom, close.bottom, FOOTER_ALIGNMENT_TOLERANCE_PX)
        assertTrue(action.right < close.left)
    }

    @Test
    fun closePrecedesTrailingActionOnTheSharedFooterBaseline() {
        composeRule.setContent {
            CliTheme {
                CliBottomSheet(
                    onDismiss = {},
                    title = "test",
                    closeActionTag = TRAILING_CLOSE_TAG,
                    footerTrailing = {
                        CliButton(
                            label = "test",
                            onClick = {},
                            modifier = Modifier
                                .weight(1f)
                                .testTag(TRAILING_ACTION_TAG),
                        )
                    },
                ) {
                    Text("body")
                }
            }
        }
        composeRule.mainClock.advanceTimeBy(SHEET_SETTLE_MS)
        composeRule.waitForIdle()

        val close = composeRule.onNodeWithTag(TRAILING_CLOSE_TAG).fetchSemanticsNode().boundsInRoot
        val action = composeRule.onNodeWithTag(TRAILING_ACTION_TAG).fetchSemanticsNode().boundsInRoot

        assertEquals(close.top, action.top, FOOTER_ALIGNMENT_TOLERANCE_PX)
        assertEquals(close.bottom, action.bottom, FOOTER_ALIGNMENT_TOLERANCE_PX)
        assertTrue(close.right < action.left)
    }

    private companion object {
        const val CLOSE_TAG = "modal_close"
        const val FOOTER_ACTION_TAG = "modal_footer_action"
        const val FOOTER_CLOSE_TAG = "modal_footer_close"
        const val TRAILING_ACTION_TAG = "modal_trailing_action"
        const val TRAILING_CLOSE_TAG = "modal_trailing_close"
        const val SHEET_SETTLE_MS = 1_000L
        const val LONG_BODY_LINE_COUNT = 40
        const val FOOTER_ALIGNMENT_TOLERANCE_PX = 1f
    }
}
