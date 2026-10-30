package com.foxhole.guard.ui.cli.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.foxhole.guard.R
import com.foxhole.guard.ui.HomeRouteUiState
import com.foxhole.guard.ui.cli.CliTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CliHomeRouterHeaderAndroidTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun routerAndHelpKeepSeparate48dpTargetsAnd28dpGlyphsAcrossHeaderWidthsAndFonts() {
        val width = mutableStateOf(280.dp)
        val pixel = mutableStateOf(true)
        var routerClicks = 0
        var helpClicks = 0
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent {
            CliTheme(pixelArtEnabled = pixel.value) {
                Box(Modifier.width(width.value)) {
                    CliTerminalHeader(
                        home = HomeRouteUiState(profilesLoaded = true, settingsHydrated = true),
                        onHelpRequested = { helpClicks++ },
                        onRouterRequested = { routerClicks++ },
                    )
                }
            }
        }
        for (pixels in listOf(true, false)) for (hostWidth in listOf(280.dp, 400.dp)) {
            compose.runOnIdle { width.value = hostWidth; pixel.value = pixels }
            val router = compose.onNodeWithTag(ROUTER).assertHasClickAction()
                .assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp)
            val help = compose.onNodeWithTag(HELP).assertHasClickAction()
                .assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp)
            assertTrue(router.fetchSemanticsNode().boundsInRoot.right <= help.fetchSemanticsNode().boundsInRoot.left)
            for ((tag, description) in listOf(
                ROUTER to context.getString(R.string.guard_router_settings_title),
                HELP to context.getString(R.string.cli_quick_start_title),
            )) {
                compose.onNode(
                    hasContentDescription(description) and hasAnyAncestor(hasTestTag(tag)),
                    useUnmergedTree = true,
                ).assertWidthIsEqualTo(28.dp).assertHeightIsEqualTo(28.dp)
            }
        }
        compose.onNodeWithTag(ROUTER).performClick()
        compose.runOnIdle { assertEquals(1, routerClicks); assertEquals(0, helpClicks) }
        compose.onNodeWithTag(HELP).performClick()
        compose.runOnIdle { assertEquals(1, routerClicks); assertEquals(1, helpClicks) }
    }

    private companion object {
        const val ROUTER = "guard_home_openwrt_shortcut"
        const val HELP = "guard_home_help_shortcut"
    }
}
