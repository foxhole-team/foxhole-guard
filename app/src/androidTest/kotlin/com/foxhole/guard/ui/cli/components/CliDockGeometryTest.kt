package com.foxhole.guard.ui.cli.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.foxhole.guard.R
import com.foxhole.guard.ui.cli.CliScreen
import com.foxhole.guard.ui.cli.CliTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CliDockGeometryTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun visualDropKeepsLayoutAndTouchBoundsAboveGestureNavigation() {
        assertDockGeometry(navigationInset = 24.dp)
    }

    @Test
    fun visualDropPreservesLayoutAndTouchBoundsAboveThreeButtonNavigation() {
        assertDockGeometry(navigationInset = 48.dp)
    }

    @Test
    fun sixScreenDockShowsCalibratedLabels() {
        assertNarrowDockLabelPresentation(
            screens = CliScreen.entries.filter { it != CliScreen.WEBAPPS },
        )
    }

    @Test
    fun sevenScreenDockAutoSizesInsteadOfHidingLabels() {
        assertNarrowDockLabelPresentation(
            screens = CliScreen.entries.toList(),
        )
    }

    private fun assertDockGeometry(navigationInset: Dp) {
        var selected: CliScreen? = null
        composeRule.setContent {
            CliTheme {
                Box(
                    modifier = Modifier
                        .width(ROOT_WIDTH)
                        .height(ROOT_HEIGHT)
                        .clipToBounds()
                        .testTag(ROOT_TAG),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(bottom = navigationInset),
                    ) {
                        CliHintBar(
                            current = CliScreen.HOME,
                            onSelect = { selected = it },
                            screens = listOf(CliScreen.HOME, CliScreen.SETTINGS),
                            modifier = Modifier.align(Alignment.BottomCenter),
                        )
                    }
                }
            }
        }

        val root = composeRule.onNodeWithTag(ROOT_TAG).fetchSemanticsNode().boundsInRoot
        val dock = composeRule.onNodeWithTag(CLI_DOCK_TAG).fetchSemanticsNode().boundsInRoot
        val settings = composeRule
            .onNodeWithTag(cliDockItemTag(CliScreen.SETTINGS))
            .fetchSemanticsNode()
            .boundsInRoot
        val density = composeRule.density
        val safeBottom = root.bottom - with(density) { navigationInset.toPx() }
        val touchFloor = with(density) { MIN_TOUCH_TARGET.toPx() }

        assertEquals(safeBottom, dock.bottom, POSITION_TOLERANCE_PX)
        assertTrue(dock.bottom <= root.bottom)
        assertTrue(dock.top >= root.top)
        assertTrue(settings.height >= touchFloor)
        assertTrue(settings.bottom <= safeBottom + POSITION_TOLERANCE_PX)

        composeRule.onNodeWithTag(cliDockItemTag(CliScreen.SETTINGS)).performClick()
        composeRule.runOnIdle { assertEquals(CliScreen.SETTINGS, selected) }
    }

    private fun assertNarrowDockLabelPresentation(
        screens: List<CliScreen>,
    ) {
        composeRule.setContent {
            CliTheme {
                Box(
                    modifier = Modifier
                        .width(ROOT_WIDTH)
                        .height(ROOT_HEIGHT),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    CliHintBar(
                        current = CliScreen.HOME,
                        onSelect = {},
                        screens = screens,
                    )
                }
            }
        }

        val rawLabel = InstrumentationRegistry.getInstrumentation().targetContext
            .getString(R.string.cli_dock_apps)
        val semanticLabel = rawLabel.replaceFirstChar { char -> char.uppercaseChar() }
        val shownLabel = semanticLabel.uppercase()
        composeRule.onNodeWithText(shownLabel).fetchSemanticsNode()
        composeRule.onNodeWithContentDescription(semanticLabel).fetchSemanticsNode()
    }

    private companion object {
        val ROOT_WIDTH = 360.dp
        val ROOT_HEIGHT = 180.dp
        val MIN_TOUCH_TARGET = 48.dp
        const val ROOT_TAG = "dock_geometry_root"
        const val POSITION_TOLERANCE_PX = 1f
    }
}
