package com.foxhole.guard.widget

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

internal class SimpleWidgetResizeTest {
    @Test fun horizontalResizeKeepsTheMinimumFullWidthButtonAtBothHeights() {
        listOf(110 to 230, 200 to 220).forEach { (height, expectedWidth) ->
            listOf(240, 241, 280, 320, 400).forEach { width ->
                val metrics = statusWidgetSimpleMetrics(width.dp, height.dp)
                val contentWidth = statusWidgetSimpleContentWidth(width.dp)
                assertEquals(240.dp, contentWidth)
                assertEquals(expectedWidth.dp,
                    statusWidgetSimpleActionWidth(contentWidth - metrics.horizontalPadding * 2))
            }
        }
    }

    @Test fun unusuallyNarrowHostsClampWithoutNegativeDimensions() {
        assertEquals(180.dp, statusWidgetSimpleContentWidth(180.dp))
        assertEquals(120.dp, statusWidgetSimpleActionWidth(120.dp))
        assertEquals(0.dp, statusWidgetSimpleActionWidth((-1).dp))
    }
}
