package com.foxhole.guard.ui.cli.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.CliTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class CliPanelPressFeedbackTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun edgeToEdgeRowsKeepContentInsetAndAcceptTouchesAtBothPanelEdges() {
        var clickCount = 0
        composeRule.setContent {
            CliTheme {
                Column {
                    CliPanel(
                        modifier = Modifier.width(PANEL_WIDTH).testTag(EDGE_PANEL_TAG),
                        contentPadding = CliPanelEdgeToEdgeContentPadding,
                    ) {
                        CliActionRow(
                            label = "edge",
                            onTap = { clickCount += 1 },
                            modifier = Modifier.testTag(EDGE_ROW_TAG),
                        )
                    }
                    Spacer(modifier = Modifier.height(CliSpacing.sm))
                    CliPanel(
                        modifier = Modifier.width(PANEL_WIDTH).testTag(DEFAULT_PANEL_TAG),
                    ) {
                        CliActionRow(
                            label = "default",
                            onTap = {},
                            modifier = Modifier.testTag(DEFAULT_ROW_TAG),
                        )
                    }
                }
            }
        }

        val edgePanel = composeRule.onNodeWithTag(EDGE_PANEL_TAG).fetchSemanticsNode().boundsInRoot
        val edgeRowNode = composeRule.onNodeWithTag(EDGE_ROW_TAG)
        val edgeRow = edgeRowNode.fetchSemanticsNode().boundsInRoot
        val defaultPanel = composeRule.onNodeWithTag(DEFAULT_PANEL_TAG).fetchSemanticsNode().boundsInRoot
        val defaultRow = composeRule.onNodeWithTag(DEFAULT_ROW_TAG).fetchSemanticsNode().boundsInRoot

        assertEquals(edgePanel.left, edgeRow.left, POSITION_TOLERANCE_PX)
        assertEquals(edgePanel.right, edgeRow.right, POSITION_TOLERANCE_PX)
        val expectedDefaultInsetPx = with(composeRule.density) { CliSpacing.md.toPx() }
        assertEquals(defaultPanel.left + expectedDefaultInsetPx, defaultRow.left, POSITION_TOLERANCE_PX)
        assertEquals(defaultPanel.right - expectedDefaultInsetPx, defaultRow.right, POSITION_TOLERANCE_PX)

        edgeRowNode.performTouchInput { click(Offset(1f, center.y)) }
        edgeRowNode.performTouchInput { click(Offset(edgeRow.width - 1f, center.y)) }
        composeRule.runOnIdle { assertEquals(2, clickCount) }
    }

    private companion object {
        val PANEL_WIDTH = 300.dp
        const val EDGE_PANEL_TAG = "edge-panel"
        const val EDGE_ROW_TAG = "edge-row"
        const val DEFAULT_PANEL_TAG = "default-panel"
        const val DEFAULT_ROW_TAG = "default-row"
        const val POSITION_TOLERANCE_PX = 1f
    }
}
