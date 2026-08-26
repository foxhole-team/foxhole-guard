package com.foxhole.guard.ui.cli.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.foxhole.guard.R
import com.foxhole.guard.ui.cli.CliTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CliTopBarControlLayoutTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun screenHeaderKeepsOneTitleBaselineForEveryTrailingSlot() {
        composeRule.setContent {
            CliTheme {
                Column {
                    CliScreenHeader(
                        label = headerLabel(NO_TRAILING_KIND),
                        icon = R.drawable.lin_profiles,
                        modifier = Modifier.testTag(headerTag(NO_TRAILING_KIND)),
                    )
                    CliScreenHeader(
                        label = headerLabel(ONE_TRAILING_KIND),
                        icon = R.drawable.lin_profiles,
                        modifier = Modifier.testTag(headerTag(ONE_TRAILING_KIND)),
                        trailing = {
                            CliTopBarIconButton(
                                icon = R.drawable.lin_add,
                                contentDescription = "add",
                                onClick = {},
                            )
                        },
                    )
                    CliScreenHeader(
                        label = headerLabel(TWO_TRAILING_KIND),
                        icon = R.drawable.lin_profiles,
                        modifier = Modifier.testTag(headerTag(TWO_TRAILING_KIND)),
                        trailing = {
                            Row {
                                CliTopBarIconButton(
                                    icon = R.drawable.lin_add,
                                    contentDescription = "add second",
                                    onClick = {},
                                )
                                CliTopBarIconButton(
                                    icon = R.drawable.lin_settings,
                                    contentDescription = "settings",
                                    onClick = {},
                                )
                            }
                        },
                    )
                }
            }
        }

        val relativeTitleTops = HEADER_TRAILING_KINDS.map { kind ->
            val header = composeRule
                .onNodeWithTag(headerTag(kind))
                .assertHeightIsEqualTo(CliScreenHeaderContentHeight + CliScreenHeaderBottomGap)
                .fetchSemanticsNode()
                .boundsInRoot
            val title = composeRule
                .onNodeWithText(headerLabel(kind), useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
            title.top - header.top
        }

        relativeTitleTops.drop(1).forEach { titleTop ->
            assertEquals(relativeTitleTops.first(), titleTop, POSITION_TOLERANCE_PX)
        }
    }

    @Test
    fun russianTwoLineHeaderGrowsWithoutMovingTheTopBarAction() {
        composeRule.setContent {
            CliTheme {
                Box(modifier = Modifier.width(280.dp)) {
                    CliScreenHeader(
                        label = LONG_RUSSIAN_HEADER,
                        icon = R.drawable.lin_apps,
                        modifier = Modifier.testTag(LONG_HEADER_TAG),
                        trailing = {
                            CliHeaderHelpButton(
                                contentDescription = LONG_HEADER_HELP,
                                topBar = true,
                                alignIconToFirstLine = true,
                                firstLineText = LONG_RUSSIAN_HEADER,
                                onClick = {},
                                modifier = Modifier.testTag(LONG_HEADER_HELP_TAG),
                            )
                        },
                    )
                }
            }
        }

        val density = composeRule.density
        val header = composeRule.onNodeWithTag(LONG_HEADER_TAG).fetchSemanticsNode().boundsInRoot
        val help = composeRule.onNodeWithTag(LONG_HEADER_HELP_TAG).fetchSemanticsNode().boundsInRoot
        val oneLineHeight = with(density) {
            (CliScreenHeaderContentHeight + CliScreenHeaderBottomGap).toPx()
        }

        assertTrue(header.height > oneLineHeight)
        assertEquals(header.top, help.top, POSITION_TOLERANCE_PX)
        assertTrue(help.bottom <= header.top + with(density) { CliTopBarControlSize.toPx() })
    }

    @Test
    fun topBarActionsShareOneControlAndRightEdgeContract() {
        composeRule.setContent {
            CliTheme {
                Row {
                    CliTopBarIconButton(
                        icon = R.drawable.lin_settings,
                        contentDescription = SETTINGS_KIND,
                        onClick = {},
                        modifier = Modifier.testTag(topBarTag(SETTINGS_KIND)),
                    )
                    CliTopBarIconButton(
                        icon = R.drawable.lin_add,
                        contentDescription = NEW_PROFILE_KIND,
                        onClick = {},
                        modifier = Modifier.testTag(topBarTag(NEW_PROFILE_KIND)),
                    )
                    CliHeaderHelpButton(
                        contentDescription = HELP_KIND,
                        topBar = true,
                        alignIconToFirstLine = true,
                        onClick = {},
                        modifier = Modifier.testTag(topBarTag(HELP_KIND)),
                    )
                }
            }
        }

        TOP_BAR_KINDS.forEach { kind ->
            val controlNode = composeRule.onNodeWithTag(topBarTag(kind))
            controlNode
                .assertWidthIsEqualTo(CliTopBarControlSize)
                .assertHeightIsEqualTo(CliTopBarControlSize)
            val control = controlNode.fetchSemanticsNode().boundsInRoot
            val glyph = composeRule
                .onNodeWithContentDescription(kind, useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot

            assertEquals(control.right, glyph.right, POSITION_TOLERANCE_PX)
            if (kind == HELP_KIND) {
                assertEquals(control.top, glyph.top, POSITION_TOLERANCE_PX)
            }
        }
    }

    @Test
    fun sectionTitleAndHelpGlyphsShareExactFirstLinePlacement() {
        composeRule.setContent {
            CliTheme {
                Row(verticalAlignment = Alignment.Top) {
                    CliSectionHeaderIcon(
                        icon = R.drawable.lin_status,
                        tint = Color.Unspecified,
                        contentDescription = SECTION_TITLE_ICON,
                    )
                    CliHeaderHelpButton(
                        contentDescription = SECTION_HELP_ICON,
                        alignIconToFirstLine = true,
                        onClick = {},
                    )
                }
            }
        }

        val titleGlyph = composeRule
            .onNodeWithContentDescription(SECTION_TITLE_ICON, useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot
        val helpGlyph = composeRule
            .onNodeWithContentDescription(SECTION_HELP_ICON, useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot

        assertEquals(titleGlyph.width, helpGlyph.width, POSITION_TOLERANCE_PX)
        assertEquals(titleGlyph.height, helpGlyph.height, POSITION_TOLERANCE_PX)
        assertEquals(titleGlyph.top, helpGlyph.top, POSITION_TOLERANCE_PX)
        assertEquals(titleGlyph.bottom, helpGlyph.bottom, POSITION_TOLERANCE_PX)
    }

    @Test
    fun panelHelpGlyphFollowsTheFirstTitleLineWithinOneDp() {
        composeRule.setContent {
            CliTheme {
                CliPanel(
                    title = PANEL_TITLE,
                    infoText = "help",
                    modifier = Modifier.testTag(PANEL_TAG),
                ) {}
            }
        }

        val description = InstrumentationRegistry.getInstrumentation().targetContext
            .getString(R.string.cli_common_information)
        val insidePanel = hasAnyAncestor(hasTestTag(PANEL_TAG))
        val title = composeRule
            .onNode(hasText(PANEL_TITLE).and(insidePanel), useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot
        val glyph = composeRule
            .onNode(
                hasContentDescription(description).and(IMAGE_GLYPH).and(insidePanel),
                useUnmergedTree = true,
            )
            .fetchSemanticsNode()
            .boundsInRoot

        val oneDp = with(composeRule.density) { 1.dp.toPx() }
        assertEquals(title.top, glyph.top, oneDp + POSITION_TOLERANCE_PX)
    }

    @Test
    fun panelHelpGlyphDoesNotJumpWhenPixelArtChanges() {
        composeRule.setContent {
            Column {
                CliTheme(pixelArtEnabled = true) {
                    CliPanel(
                        title = PANEL_TITLE,
                        infoText = "help",
                        modifier = Modifier.testTag(PIXEL_PANEL_TAG),
                    ) {}
                }
                CliTheme(pixelArtEnabled = false) {
                    CliPanel(
                        title = PANEL_TITLE,
                        infoText = "help",
                        modifier = Modifier.testTag(MONO_PANEL_TAG),
                    ) {}
                }
            }
        }

        val description = InstrumentationRegistry.getInstrumentation().targetContext
            .getString(R.string.cli_common_information)

        fun relativeGlyphTop(panelTag: String): Float {
            val insidePanel = hasAnyAncestor(hasTestTag(panelTag))
            val panel = composeRule
                .onNodeWithTag(panelTag)
                .fetchSemanticsNode()
                .boundsInRoot
            val glyph = composeRule
                .onNode(
                    hasContentDescription(description).and(IMAGE_GLYPH).and(insidePanel),
                    useUnmergedTree = true,
                )
                .fetchSemanticsNode()
                .boundsInRoot
            return glyph.top - panel.top
        }

        assertEquals(
            relativeGlyphTop(PIXEL_PANEL_TAG),
            relativeGlyphTop(MONO_PANEL_TAG),
            POSITION_TOLERANCE_PX,
        )
    }

    @Test
    fun sharedIconPrimitiveStaysVerticallyCentered() {
        composeRule.setContent {
            CliTheme {
                Row(
                    modifier = Modifier
                        .height(ICON_ROW_HEIGHT)
                        .testTag(ICON_ROW_TAG),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CliIcon(
                        id = R.drawable.lin_terminal,
                        contentDescription = ICON_DESCRIPTION,
                        size = 16.dp,
                    )
                    Text("A")
                }
            }
        }

        val row = composeRule.onNodeWithTag(ICON_ROW_TAG).fetchSemanticsNode().boundsInRoot
        val icon = composeRule
            .onNodeWithContentDescription(ICON_DESCRIPTION, useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot

        assertEquals(row.center.y, icon.center.y, POSITION_TOLERANCE_PX)
    }

    private companion object {
        const val SETTINGS_KIND = "settings"
        const val NEW_PROFILE_KIND = "new_profile"
        const val HELP_KIND = "help"
        const val NO_TRAILING_KIND = "none"
        const val ONE_TRAILING_KIND = "one"
        const val TWO_TRAILING_KIND = "two"
        const val PANEL_TITLE = "CURRENT INFORMATION"
        const val PANEL_TAG = "panel"
        const val PIXEL_PANEL_TAG = "pixel_panel"
        const val MONO_PANEL_TAG = "mono_panel"
        const val LONG_RUSSIAN_HEADER = "Исключения по приложениям"
        const val LONG_HEADER_TAG = "long_header"
        const val LONG_HEADER_HELP_TAG = "long_header_help"
        const val LONG_HEADER_HELP = "Справка"
        const val SECTION_TITLE_ICON = "section title"
        const val SECTION_HELP_ICON = "section help"
        const val ICON_ROW_TAG = "icon_row"
        const val ICON_DESCRIPTION = "icon"
        val ICON_ROW_HEIGHT = 48.dp
        val TOP_BAR_KINDS = listOf(SETTINGS_KIND, NEW_PROFILE_KIND, HELP_KIND)
        val HEADER_TRAILING_KINDS = listOf(NO_TRAILING_KIND, ONE_TRAILING_KIND, TWO_TRAILING_KIND)
        val IMAGE_GLYPH = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Image)
        const val POSITION_TOLERANCE_PX = 0.5f
    }
}

private fun topBarTag(kind: String): String = "topbar_$kind"

private fun headerTag(kind: String): String = "header_$kind"

private fun headerLabel(kind: String): String = "HEADER ${kind.uppercase()}"
