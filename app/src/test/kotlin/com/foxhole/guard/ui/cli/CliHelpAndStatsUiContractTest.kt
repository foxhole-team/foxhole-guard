package com.foxhole.guard.ui.cli

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
        assertTrue(header.contains("CliHeaderIconSize = 12.dp"))
    }

    @Test
    fun `scenario uses the compact apps glyph in its header and dock`() {
        val routing = cli("settings/CliRoutingScreen.kt")
        val dock = cli("components/CliHintBar.kt")

        assertTrue(routing.contains("icon = R.drawable.pix_apps"))
        assertTrue(dock.contains("CliScreen.APPS -> R.drawable.pix_apps"))
    }

    @Test
    fun `profile header puts create before compact circular help`() {
        val profiles = cli("profiles/CliProfilesScreen.kt")
            .substringAfter("trailing = {")
            .substringBefore("if (templateAddOpen)")
        val help = cli("components/CliContextHelp.kt")

        assertTrue(profiles.indexOf("CliProfileAddButton(") < profiles.indexOf("CliContextHelpButton("))
        assertTrue(!profiles.contains("iconOffsetX"))
        assertTrue(!profiles.contains("PROFILE_HEADER_ICON_NUDGE"))
        assertTrue(help.contains(".border(HELP_CIRCLE_STROKE, colors.accent, CircleShape)"))
        assertTrue(help.contains("HELP_CIRCLE_SIZE = 16.dp"))
        assertTrue(help.contains("HELP_GLYPH_SIZE = 10.sp"))
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
        assertTrue(screen.contains("Modifier.align(Alignment.CenterHorizontally)"))
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
