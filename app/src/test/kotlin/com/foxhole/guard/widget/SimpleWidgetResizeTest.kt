package com.foxhole.guard.widget

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

internal class SimpleWidgetResizeTest {
    @Test fun resizingAlignsTheFullWidthButtonWithTheHeaderContent() {
        listOf(110, 200).forEach { height ->
            listOf(240, 241, 280, 320, 400).forEach { width ->
                val metrics = statusWidgetSimpleMetrics(width.dp, height.dp)
                val contentWidth = statusWidgetSimpleContentWidth(width.dp)
                assertEquals(minOf(width.dp, 300.dp), contentWidth)
                val available = contentWidth - metrics.horizontalPadding * 2
                assertEquals(available, statusWidgetSimpleActionWidth(available))
                assertEquals(contentWidth, statusWidgetSimpleActionWidth(available) + metrics.horizontalPadding * 2)
                val button = statusWidgetSimpleActionWidth(available, requested = 120.dp)
                assertEquals(120.dp, button)
                assertTrue(button <= available)
            }
        }
    }

    @Test fun unusuallyNarrowHostsClampWithoutNegativeDimensions() {
        assertEquals(180.dp, statusWidgetSimpleContentWidth(180.dp))
        assertEquals(120.dp, statusWidgetSimpleActionWidth(120.dp))
        assertEquals(0.dp, statusWidgetSimpleActionWidth((-1).dp))
        assertEquals(48.dp, statusWidgetSimpleActionWidth(180.dp, requested = 10.dp))
        assertEquals(32.dp, statusWidgetSimpleActionWidth(32.dp, requested = 120.dp))
    }
}
