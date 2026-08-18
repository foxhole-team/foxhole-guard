package com.foxhole.guard.ui.cli

import com.foxhole.guard.ui.cli.components.cliTopBarHelpBody
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CliHelpAndStatsUiContractTest {
    @Test
    fun `screen header puts section icon first and owns right-edge actions`() {
        val header = cli("components/CliScreenHeader.kt")
        val icon = header.indexOf("if (icon != null)")
        val brand = header.indexOf("text = brandText")
        val title = header.indexOf("text = label", startIndex = brand)

        assertTrue(icon >= 0)
        assertTrue(brand > icon)
        assertTrue(title > brand)
        assertTrue(header.contains(".fillMaxWidth()"))
        assertTrue(header.contains("horizontalArrangement = Arrangement.SpaceBetween"))
        assertTrue(header.contains("contentAlignment = Alignment.CenterEnd"))
        assertTrue(header.contains("CliHeaderIconSize = 18.dp"))
    }

    @Test
    fun `scenario uses the compact apps glyph in its header and dock`() {
        val routing = cli("settings/CliRoutingScreen.kt")
        val dock = cli("components/CliHintBar.kt")
        val help = cli("components/CliContextHelp.kt")

        assertTrue(routing.contains("icon = R.drawable.pix_apps"))
        assertTrue(routing.contains("trailing = { CliTopBarHelpButton("))
        assertTrue(dock.contains("CliScreen.APPS -> R.drawable.pix_apps"))
        assertTrue(help.contains("CliContextHelpButtonSize = CliHeaderControlSlotHeight"))
    }

    @Test
    fun `profile header carries create beside help and the help button keeps its proportions`() {
        val profiles = cli("profiles/CliProfilesScreen.kt")
            .substringAfter("trailing = {")
            .substringBefore("if (templateAddOpen)")
        val help = cli("components/CliContextHelp.kt")

        assertTrue(profiles.contains("CliProfileAddButton("))
        assertTrue(profiles.contains("CliTopBarHelpButton("))
        assertTrue(!profiles.contains("iconOffsetX"))
        assertTrue(!profiles.contains("PROFILE_HEADER_ICON_NUDGE"))
        assertTrue(help.contains("id = R.drawable.pix_info"))
        assertTrue(help.contains("CliModernTopBarHelpIconSize = 20.dp"))
        assertTrue(help.contains("topBar && LocalCliVisualStyle.current == VisualStyle.PLAIN"))
        assertTrue(help.contains("size = iconSize"))
        assertTrue(help.contains("CliContextHelpButtonSize = CliHeaderControlSlotHeight"))
    }

    @Test
    fun `section help buttons reuse the header icon size and first line alignment`() {
        val panel = cli("components/CliPanel.kt")
        val rows = cli("components/CliRows.kt")

        assertTrue(panel.contains("Row(modifier = modifier, verticalAlignment = Alignment.Top)"))
        assertTrue(panel.substringAfter("private fun CliPanelInfoGlyph").contains("CliHeaderHelpButton("))
        assertTrue(rows.substringAfter("internal fun CliRowInfoGlyph").contains("CliHeaderHelpButton("))
    }

    @Test
    fun `top bar help is left aligned with default body colour while section sheets stay centered`() {
        val help = cli("components/CliContextHelp.kt")
        val topBar = help
            .substringAfter("internal fun CliTopBarHelpButton")
            .substringBefore("internal fun CliHeaderHelpButton")
        val section = help
            .substringAfter("internal fun CliContextHelpButton")
            .substringBefore("internal fun CliTopBarHelpButton")
        val infoSheet = cli("components/CliInfoSheet.kt")
        val manual = cli("profiles/CliManualProfileEditor.kt")

        assertTrue(topBar.contains("appendInlineContent(TOP_BAR_HELP_ICON_ID"))
        assertTrue(topBar.contains("textAlign = TextAlign.Start"))
        assertTrue(topBar.contains("color = colors.fg"))
        assertTrue(topBar.contains("joinToString(\"\\n\\n\")"))
        assertTrue(section.contains("centered = true"))
        assertTrue(section.contains("color = colors.accent"))
        assertTrue(infoSheet.contains("centered = true"))
        assertTrue(infoSheet.contains("color = colors.accent"))
        assertTrue(manual.contains("trailing = { CliTopBarHelpButton("))
        assertTrue(
            cliTopBarHelpBody("first section\nsecond section") ==
                "first section\n\nsecond section",
        )
    }

    @Test
    fun `home profile value and disclosure finish on the shared right edge`() {
        val facts = cli("home/CliHomeFacts.kt")
        val keyValue = cli("components/CliText.kt")
        val profileRow = facts
            .substringAfter("key = stringResource(R.string.cli_home_key_profile)")
            .substringBefore("CliRowDivider()")

        assertTrue(keyValue.contains("textAlign = TextAlign.End"))
        assertTrue(profileRow.contains("modifier = Modifier.offset(x = CliSpacing.xs)"))
    }

    @Test
    fun `home help opens the existing quick start sheet from the right edge`() {
        val home = cli("home/CliHomeScreen.kt")
        val terminal = cli("home/CliHomeTerminal.kt")

        assertTrue(home.contains("CliQuickStartSheetContent(onDismiss = { quickStartOpen = false })"))
        assertTrue(home.contains("onHelpRequested = { quickStartOpen = true }"))
        assertTrue(terminal.contains("CliHeaderHelpButton("))
        assertTrue(terminal.contains("contentDescription = stringResource(R.string.cli_help_start_title)"))
        assertTrue(terminal.contains("BoxWithConstraints(modifier = Modifier.weight(1f))"))
    }

    @Test
    fun `statistics module actions follow state and disabled screen explains collection`() {
        val screen = cli("stats/CliStatsScreen.kt")
        val settings = cli("stats/CliStatsSettingsSheet.kt")
        val english = resource("values/strings.xml")
        val russian = resource("values-ru/strings.xml")

        assertTrue(
            settings.contains(
                "if (enabled) R.string.cli_stats_disable_module else " +
                    "R.string.cli_stats_enable_module",
            ),
        )
        assertTrue(screen.contains("CliDashedInfoNote("))
        assertTrue(screen.contains("R.string.cli_stats_consent_note"))
        assertTrue(screen.contains("R.string.cli_stats_enable_module"))
        assertTrue(screen.contains("contentAlignment = Alignment.Center"))
        assertTrue(screen.contains("Column(horizontalAlignment = Alignment.CenterHorizontally)"))
        assertTrue(english.contains(">Enable statistics module</string>"))
        assertTrue(english.contains(">Disable statistics module</string>"))
        assertTrue(english.contains(">Collects statistics about this device’s network activity</string>"))
        assertTrue(russian.contains(">Включить модуль статистики</string>"))
        assertTrue(russian.contains(">Выключить модуль статистики</string>"))
        assertTrue(
            russian.contains(
                ">Собирает статистику сетевой " +
                    "активности устройства</string>",
            ),
        )
    }

    private fun cli(relative: String): String = source("main/kotlin/com/foxhole/guard/ui/cli/$relative")

    private fun resource(relative: String): String = source("main/res/$relative")

    private fun source(relative: String): String =
        listOf(
            File("src/$relative"),
            File("app/src/$relative"),
            File("../app/src/$relative"),
        ).first(File::isFile).readText()
}
