package com.foxhole.guard.ui.cli.settings

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CliSettingsStructureContractTest {

    @Test
    fun `expanded settings rows share one small upward optical offset in both font modes`() {
        val settings = cli("settings/CliSettingsScreen.kt")
        val rows = cli("components/CliRows.kt")

        assertEquals((-1).dp, CLI_SETTINGS_ROW_CONTENT_LIFT)
        assertTrue(
            settings.contains(
                "LocalCliPanelRowContentOffset provides CLI_SETTINGS_ROW_CONTENT_LIFT",
            ),
        )
        assertTrue(rows.contains(".offset(y = LocalCliPanelRowContentOffset.current)"))
    }

    @Test
    fun `settings more icons keep pixel placement while only the updates row drops`() {
        val settings = cli("settings/CliSettingsScreen.kt")
        val more = settings
            .substringAfter("private fun CliMoreSection(")
            .substringBefore("private fun CliTerminalClearRows()")

        assertTrue(settings.contains("panelHeaderContentDrop = 3.dp"))
        assertEquals(1.dp, CLI_SETTINGS_UPDATE_ROW_DROP)
        assertEquals(0.dp, cliSettingsMoreIconLiftFor(pixelArtEnabled = true))
        assertEquals(0.dp, cliSettingsMoreIconLiftFor(pixelArtEnabled = false))
        assertEquals(
            5,
            Regex("iconOffsetY = iconLift")
                .findAll(more)
                .count(),
        )
        assertEquals(
            1,
            Regex("modifier = Modifier\\.offset\\(y = CLI_SETTINGS_UPDATE_ROW_DROP\\)")
                .findAll(more)
                .count(),
        )
        assertTrue(
            more
                .substringAfter("R.string.cli_cfg_more_updates")
                .substringBefore("R.string.cli_cfg_more_journals")
                .contains("modifier = Modifier.offset(y = CLI_SETTINGS_UPDATE_ROW_DROP)"),
        )
        val statistics = more
            .substringAfter("if (!statisticsDockIconEnabled)")
            .substringBefore("R.string.cli_cfg_more_help")
        assertTrue(statistics.contains("R.string.cli_dock_stats"))
        assertTrue(statistics.contains("onOpenSub(SUB_STATS)"))
        assertTrue(settings.contains("viewModel.onStatisticsUiVisibilityChanged(true)"))
        assertTrue(settings.contains("viewModel.onStatisticsUiVisibilityChanged(false)"))
    }

    @Test
    fun `application language is first inside the application section and is not duplicated`() {
        val source = cli("settings/CliSettingsScreen.kt")
        val root = source
            .substringAfter("private fun CliSettingsRootColumn")
            .substringBefore("private fun CliCfgSubScreen")
        val application = source
            .substringAfter("private fun CliApplicationSection")
            .substringBefore("private fun CliLanguageRow")

        assertFalse(root.contains("CliLanguageRow("))
        assertTrue(application.indexOf("CliLanguageRow(") < application.indexOf("val systemAppearanceLabel"))
        assertEquals(1, Regex("CliLanguageRow\\(").findAll(application).count())
        assertTrue(source.substringAfter("private fun CliLanguageRow").contains("R.string.cli_cfg_language"))
        assertTrue(root.indexOf("CliExtrasSection(") < root.indexOf("CliApplicationSection("))
        assertTrue(root.indexOf("CliApplicationSection(") < root.indexOf("CliMoreSection("))
    }

    @Test
    fun `missing app rejection remains visible for six seconds`() {
        assertEquals(6_000L, MISSING_APPS_WARNING_VISIBLE_MS)
    }

    @Test
    fun `widget defaults live on their own sub-screen`() {
        val root = cli("settings/CliSettingsScreen.kt")

        assertTrue(root.contains("""private const val SUB_WIDGETS = "widgets""""))
        assertTrue(root.contains("SUB_WIDGETS -> CliWidgetsSubScreen(viewModel, modifier)"))
        assertTrue(root.contains("onTap = { onOpenSub(SUB_WIDGETS) }"))
        assertFalse(root.contains("R.string.cli_cfg_widget_bg"))
        assertFalse(root.contains("R.string.cli_cfg_widget_alpha"))
    }

    @Test
    fun `the widgets sub-screen carries per-kind panels with honest previews`() {
        val screen = cli("settings/CliWidgetsSubScreen.kt")

        assertTrue(screen.contains("CliScreenHeader("))
        assertTrue(screen.contains("CliPanel("))
        assertTrue(screen.contains("viewModel.onWidgetBlackBackgroundChanged(kind"))
        assertTrue(screen.contains("viewModel.onWidgetAlphaPercentChanged(kind"))
        assertTrue(screen.contains("viewModel.onWidgetOutlineChanged(kind"))
        assertTrue(screen.contains("onFoxWidgetAnimationChanged"))
        assertTrue(screen.contains("HomeWidgetKind.CONNECTION"))
        assertTrue(screen.contains("HomeWidgetKind.WEB_APPS"))
        assertTrue(screen.contains("HomeWidgetKind.STATUS"))
        assertEquals(2, Regex("viewModel\\.onAddHomeWidget\\(").findAll(screen).count())
        assertTrue(screen.contains("CliStatusWidgetPreview"))
        assertTrue(screen.contains("CliWebAppsWidgetPreview"))
        assertTrue(screen.contains("connected = connected"))
        assertTrue(screen.contains("activeStatusWidgetLayoutMode(widgets.statusLayoutMode)"))
        assertTrue(screen.contains("CliStatusWidgetLayoutRow("))
        assertTrue(screen.contains("viewModel::onStatusWidgetLayoutModeChanged"))
        assertTrue(screen.contains("R.string.cli_widget_status_layout_simple"))
        assertTrue(screen.contains("SELECTABLE_STATUS_WIDGET_LAYOUT_MODES"))
        val layoutSelector = screen
            .substringAfter("private fun CliStatusWidgetLayoutRow(")
            .substringBefore("private fun CliFoxWidgetPanel(")
        assertFalse(layoutSelector.contains("StatusWidgetLayoutMode.EXPANDED"))
    }

    @Test
    fun `a module block cannot be declared without a leading icon`() {
        val block = cli("settings/CliModuleBlock.kt")

        assertTrue(block.contains("@DrawableRes icon: Int,"))
        assertTrue(block.contains("icon = icon,"))
        assertTrue(block.contains("icon = R.drawable.lin_settings,"))
    }

    @Test
    fun `every module block passes an icon`() {
        val callSites =
            listOf("settings/CliExtrasSection.kt", "settings/CliModulesSection.kt")
                .flatMap { relative ->
                    cli(relative).split("CliModuleBlock(").drop(1).map { relative to it }
                }

        assertEquals(6, callSites.size)
        callSites.forEach { (relative, call) ->
            val head = call.lineSequence().take(MODULE_BLOCK_HEAD_LINES).joinToString("\n")
            assertTrue("$relative: CliModuleBlock has no icon", head.contains("icon = R.drawable.lin_"))
        }
    }

    @Test
    fun `module and extra explanations open through contextual help`() {
        val block = cli("settings/CliModuleBlock.kt")
        val modules = cli("settings/CliModulesSection.kt")
        val extras = cli("settings/CliExtrasSection.kt")

        assertTrue(block.contains("infoText: String,"))
        assertTrue(block.contains("infoText = infoText,"))
        listOf(
            "cli_help_tor_body",
            "cli_i2p_start_body",
            "cli_i2p_runtime_note",
            "cli_i2p_allow_outside_tunnel_note",
            "cli_help_firewall_body",
            "cli_help_sentinel_body",
        ).forEach { key -> assertTrue("modules: missing $key", modules.contains(key)) }
        listOf("cli_help_webapps_body", "cli_help_lan_proxy_body").forEach { key ->
            assertTrue("extras: missing $key", extras.contains(key))
        }
    }

    @Test
    fun `routing explanation notes use the section glyph`() {
        val apps = cli("settings/CliRoutingAppLanesSection.kt")
        val sites = cli("settings/CliRoutingSiteRulesSection.kt")

        assertTrue(
            apps.contains("missingAppsTarget == CliMissingAppsTarget.VPN -> R.drawable.lin_shield"),
        )
        assertTrue(
            apps.contains(
                "missingAppsTarget == CliMissingAppsTarget.TOR || torAppsMissing -> R.drawable.lin_tor",
            ),
        )
        assertTrue(apps.contains("else -> R.drawable.lin_info"))
        assertTrue(apps.contains("icon = emptyNoteIcon"))
        assertTrue(sites.contains("icon = R.drawable.lin_info"))
    }

    @Test
    fun `every help body line keeps its section glyph`() {
        val help = cli("settings/CliHelpSubScreen.kt")
        val body = help
            .substringAfter("private fun CliHelpBody(")
            .substringBefore("private fun helpGlossaryTerm")

        assertTrue(
            body.contains("paragraph.lineSequence().filter(String::isNotBlank).forEach { line ->"),
        )
        assertTrue(body.contains("CliHelpTextRow(text = line, icon = icon)"))
        assertTrue(body.contains("CliHelpTopicIcon(icon)"))
        assertTrue(body.contains("helpGlossaryTerm(text)"))
    }

    @Test
    fun `the extras rows name their own glyphs`() {
        val extras = cli("settings/CliExtrasSection.kt")

        assertTrue(extras.contains("icon = R.drawable.lin_webapps"))
        assertTrue(extras.contains("icon = R.drawable.lin_device"))
    }

    private fun cli(relative: String): String =
        listOf(
            File("src/main/kotlin/com/foxhole/guard/ui/cli/$relative"),
            File("app/src/main/kotlin/com/foxhole/guard/ui/cli/$relative"),
            File("../app/src/main/kotlin/com/foxhole/guard/ui/cli/$relative"),
        ).first(File::isFile).readText()
}

private const val MODULE_BLOCK_HEAD_LINES = 4
