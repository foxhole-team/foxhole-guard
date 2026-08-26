package com.foxhole.guard.ui.cli

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CliHelpAndTopBarGlyphContractTest {
    @Test
    fun `help screen routes every topic glyph through the info colour`() {
        val help = cli("settings/CliHelpSubScreen.kt")
        val topicIcon = help
            .substringAfter("private fun CliHelpTopicIcon(")
            .substringBefore("private fun helpGlossaryTerm(")
        val helpCard = help
            .substringAfter("private fun CliHelpCard(")
            .substringBefore("private fun CliHelpBody(")
        val disclosure = helpCard
            .substringAfter("text = if (expanded) \"▾\" else \"▸\",")
            .substringBefore("            }")
        val killSwitchAction = helpCard
            .substringAfter("if (section.key == HELP_KEY_KILLSWITCH)")
            .substringBefore("onTap =")
        val contextHelp = cli("components/CliContextHelp.kt")
        val headerHelp = contextHelp
            .substringAfter("internal fun CliHeaderHelpButton(")
            .substringBefore("internal fun CliTopBarIconButton(")

        assertTrue(help.contains("iconColor = colors.info"))
        assertTrue(help.contains("CliQuickStartItems("))
        assertTrue(help.contains("smartBody = stringResource(R.string.cli_help_smart_body)"))
        assertTrue(help.contains("detailsBody = stringResource(R.string.cli_help_start_details)"))
        assertTrue(topicIcon.contains("tint = LocalCliColors.current.info"))
        assertTrue(help.contains("CliHelpTopicIcon(section.icon)"))
        assertTrue(help.contains("CliHelpTopicIcon(icon)"))
        assertFalse(topicIcon.contains("colors.accent"))
        assertTrue(disclosure.contains("color = colors.info"))
        assertTrue(killSwitchAction.contains("actionColor = colors.info"))
        assertTrue(killSwitchAction.contains("labelColor = colors.fg"))
        assertTrue(headerHelp.contains("tint = colors.info"))
    }

    @Test
    fun `top bar settings help and create actions use larger glyphs without changing controls`() {
        val header = cli("components/CliScreenHeader.kt")
        val contextHelp = cli("components/CliContextHelp.kt")
        val sizeToken = header
            .substringAfter("internal fun cliScreenHeaderGlyphSize()")
            .substringBefore("internal fun cliTopBarGlyphSizeFor(")
        val helpButton = contextHelp
            .substringAfter("internal fun CliHeaderHelpButton(")
            .substringBefore("internal fun CliTopBarIconButton(")
        val settingsButton = contextHelp
            .substringAfter("internal fun CliTopBarSettingsButton(")
            .substringBefore("private fun CliHeaderIconButton(")
        val createButton = contextHelp
            .substringAfter("internal fun CliTopBarIconButton(")
            .substringBefore("internal fun CliTopBarSettingsButton(")
        val backRow = header.substringAfter("internal fun CliBackRow(")

        assertTrue(header.contains("val iconSize = cliScreenHeaderGlyphSize()"))
        assertTrue(sizeToken.contains("LocalCliIconMetricOverrides.current.screenHeaderIconSize"))
        assertTrue(sizeToken.contains("cliTopBarGlyphSizeFor(pixelArtEnabled)"))
        assertTrue(helpButton.contains("val topBarIconSize = CLI_TOP_BAR_ACTION_ICON_SIZE"))
        assertTrue(settingsButton.contains("icon = R.drawable.lin_settings"))
        assertTrue(settingsButton.contains("iconSize = CLI_TOP_BAR_ACTION_ICON_SIZE"))
        assertTrue(settingsButton.contains("controlSize = CliTopBarControlSize"))
        assertTrue(createButton.contains("iconSize = CLI_TOP_BAR_ACTION_ICON_SIZE"))
        assertTrue(createButton.contains("controlSize = CliTopBarControlSize"))
        assertTrue(contextHelp.contains(".requiredSize(controlSize)"))
        assertFalse(backRow.contains("cliScreenHeaderGlyphSize()"))
        assertTrue(backRow.contains("Modifier.offset(y = CLI_HEADER_ICON_LIFT)"))
        assertTrue(cli("logs/CliLogsScreen.kt").contains("CliTopBarSettingsButton("))
        assertTrue(cli("stats/CliStatsScreen.kt").contains("CliTopBarSettingsButton("))
        assertTrue(cli("settings/CliUpdateSourcesSheet.kt").contains("CliTopBarSettingsButton("))
        assertTrue(cli("profiles/CliProfilesScreen.kt").contains("CliTopBarIconButton("))
    }

    private fun cli(relative: String): String =
        listOf(
            File("src/main/kotlin/com/foxhole/guard/ui/cli", relative),
            File("app/src/main/kotlin/com/foxhole/guard/ui/cli", relative),
            File("../app/src/main/kotlin/com/foxhole/guard/ui/cli", relative),
        ).first(File::isFile).readText()
}
