package com.foxhole.guard.ui.cli.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.foxhole.guard.R
import com.foxhole.guard.ui.cli.CliTheme
import com.foxhole.guard.ui.cli.components.CliContextHelpButton
import com.foxhole.guard.ui.cli.help.GuardHelpBlocks
import com.foxhole.guard.ui.cli.help.guardHelpTopic
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CliHelpAndroidTest {
    @get:Rule val compose = createComposeRule()

    @Test fun smartKeepsTwoColumnTablesWithLargeTextAt320dp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent { CliTheme(pixelArtEnabled = false) {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                Column(Modifier.width(320.dp).verticalScroll(rememberScrollState())) {
                    GuardHelpBlocks(guardHelpTopic("smart").blocks)
                }
            }
        } }
        val label = compose.onNodeWithText("T").fetchSemanticsNode().boundsInRoot
        val detail = compose.onNodeWithText(context.getString(R.string.help_smart_connection)).fetchSemanticsNode().boundsInRoot
        assertTrue(label.right <= detail.left || detail.right <= label.left)
        assertTrue(minOf(label.bottom, detail.bottom) > maxOf(label.top, detail.top))
        compose.onNodeWithText(context.getString(R.string.help_smart_very_high)).performScrollTo().assertIsDisplayed()
    }

    @Test fun contextHelpHasAnExplicit48dpControlAndUsesTheSmartTopic() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent { CliTheme(pixelArtEnabled = false) { CliContextHelpButton(R.string.cli_help_smart_body) } }
        compose.onNodeWithContentDescription(context.getString(R.string.cli_context_help_title))
            .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithText(context.getString(R.string.help_smart_metrics)).assertIsDisplayed()
    }

    @Test fun topicDisclosureAnnouncesItsState() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent { CliTheme(pixelArtEnabled = false) { CliHelpSubScreen() } }
        val heading = compose.onNodeWithText(context.getString(R.string.cli_help_start_title), ignoreCase = true)
        heading.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, context.getString(R.string.help_collapsed)))
        heading.performClick()
        heading.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, context.getString(R.string.help_expanded)))
    }
}
