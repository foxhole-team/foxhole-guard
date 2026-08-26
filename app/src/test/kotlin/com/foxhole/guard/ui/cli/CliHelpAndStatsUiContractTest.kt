package com.foxhole.guard.ui.cli

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foxhole.guard.R
import com.foxhole.guard.ui.cli.components.CLI_HEADER_ICON_LIFT
import com.foxhole.guard.ui.cli.components.CLI_PANEL_HEADER_LEADING_CONTROL_WIDTH
import com.foxhole.guard.ui.cli.components.CLI_PANEL_HEADER_LEADING_GAP
import com.foxhole.guard.ui.cli.components.CLI_PANEL_HEADER_LEADING_ICON_SIZE
import com.foxhole.guard.ui.cli.components.CLI_SCREEN_HEADER_ICON_LIFT
import com.foxhole.guard.ui.cli.components.CLI_SCREEN_HEADER_ICON_SIZE
import com.foxhole.guard.ui.cli.components.CLI_SECTION_HEADER_ICON_SIZE
import com.foxhole.guard.ui.cli.components.CLI_TOP_BAR_ACTION_ICON_SIZE
import com.foxhole.guard.ui.cli.components.CLI_TOP_BAR_ICON_LIFT
import com.foxhole.guard.ui.cli.components.CliSheetHeaderIconRole
import com.foxhole.guard.ui.cli.components.cliHeaderHelpIconOffsetFor
import com.foxhole.guard.ui.cli.components.cliHeaderIconFirstLineOffsetFor
import com.foxhole.guard.ui.cli.components.cliIconTextItems
import com.foxhole.guard.ui.cli.components.cliModalHeaderIconSizeFor
import com.foxhole.guard.ui.cli.components.cliPanelHeaderGlyphLiftFor
import com.foxhole.guard.ui.cli.components.cliPanelHeaderLeadingIconLiftFor
import com.foxhole.guard.ui.cli.components.cliScreenHeaderIconOffsetFor
import com.foxhole.guard.ui.cli.components.cliScreenHeaderRowOffsetFor
import com.foxhole.guard.ui.cli.components.cliTopBarGlyphSizeFor
import com.foxhole.guard.ui.cli.components.cliTopBarHelpBody
import com.foxhole.guard.ui.cli.components.cliTopBarIconLiftFor
import com.foxhole.guard.ui.cli.settings.cliSettingsIconMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CliHelpAndStatsUiContractTest {
    @Test
    fun `screen header puts section icon first and owns right-edge actions`() {
        val header = cli("components/CliScreenHeader.kt")
        val glass = cli("components/CliGlass.kt")
        val icon = header.indexOf("if (icon != null)")
        val title = header.indexOf("text = shownLabel", startIndex = icon)

        assertTrue(icon >= 0)
        assertTrue(title > icon)
        assertFalse(header.contains("brandText"))
        assertTrue(header.contains(".fillMaxWidth()"))
        assertTrue(header.contains(".heightIn(min = CliScreenHeaderContentHeight)"))
        assertTrue(header.contains("CliScreenHeaderContentHeight = CliTopBarControlSize"))
        assertTrue(header.contains("CliScreenHeaderBottomGap = 2.dp"))
        assertEquals(0.dp, CliTopContentGap)
        assertEquals(6.dp, CliTopBarLift)
        assertTrue(glass.contains(".statusBarsPadding()"))
        assertTrue(glass.contains(".cliTopBarLifted()"))
        assertTrue(glass.contains("top = CliTopContentGap"))
        assertTrue(header.contains("style = cliScreenTitleStyle(shownLabel)"))
        assertTrue(header.contains("cliScreenHeadingOpticalOffsetFor(shownLabel, pixelArtEnabled)"))
        assertTrue(header.contains("contentAlignment = Alignment.CenterEnd"))
        assertTrue(header.contains("cliScreenHeaderRowOffsetFor(pixelArtEnabled)"))
        assertEquals(18.dp, CLI_SCREEN_HEADER_ICON_SIZE)
        assertEquals(18.dp, cliTopBarGlyphSizeFor(pixelArtEnabled = true))
        assertEquals(18.dp, cliTopBarGlyphSizeFor(pixelArtEnabled = false))
        assertEquals((-1).dp, CLI_HEADER_ICON_LIFT)
        val settings = cliSettingsIconMetrics(pixelArtEnabled = true)
        assertEquals(null, settings.screenHeaderIconSize)
        assertEquals(18.dp, settings.panelHeaderIconSize)
        assertEquals((-2).dp, settings.panelHeaderGlyphLift)
        assertEquals(0.dp, settings.panelHeaderLeadingIconLiftAdjustment)
        assertEquals(3.dp, settings.panelHeaderContentDrop)
        assertEquals(cliPixelFontSizeForMonoSp(15f), settings.panelHeaderFontSize)
        assertEquals(21.sp, settings.panelHeaderLineHeight)
        assertEquals(16.dp, settings.rowLeadingIconSize)
        val monoSettings = cliSettingsIconMetrics(pixelArtEnabled = false)
        assertEquals((-2).dp, monoSettings.panelHeaderGlyphLift)
        assertEquals(0.dp, monoSettings.panelHeaderLeadingIconLiftAdjustment)
        assertEquals(3.dp, monoSettings.panelHeaderContentDrop)
        assertEquals(15.sp, monoSettings.panelHeaderFontSize)
        assertEquals(21.sp, monoSettings.panelHeaderLineHeight)
        assertEquals((-6).dp, CLI_SCREEN_HEADER_ICON_LIFT)
        assertEquals((-7).dp, CLI_TOP_BAR_ICON_LIFT)
        assertEquals(0.dp, cliScreenHeaderRowOffsetFor(pixelArtEnabled = true))
        assertEquals(0.dp, cliScreenHeaderRowOffsetFor(pixelArtEnabled = false))
        assertEquals(20.dp, CLI_TOP_BAR_ACTION_ICON_SIZE)
        assertEquals((-9).dp, cliTopBarIconLiftFor(pixelArtEnabled = true))
        assertEquals((-9).dp, cliTopBarIconLiftFor(pixelArtEnabled = false))
        assertEquals((-8).dp, cliScreenHeaderIconOffsetFor("Настройки"))
        assertEquals((-8).dp, cliScreenHeaderIconOffsetFor("Settings"))
        assertEquals((-8).dp, cliScreenHeaderIconOffsetFor("Settings", pixelArtEnabled = false))
        assertEquals((-1).dp, cliHeaderIconFirstLineOffsetFor("FOXHOLE GUARD"))
        assertEquals(
            (-2).dp,
            cliHeaderHelpIconOffsetFor(
                topBar = true,
                alignIconToFirstLine = true,
                firstLineText = "FOXHOLE GUARD",
            ),
        )
        assertEquals(
            (-1).dp,
            cliHeaderHelpIconOffsetFor(
                topBar = false,
                alignIconToFirstLine = true,
                firstLineText = "Information",
            ),
        )
    }

    @Test
    fun `scenario uses the compact apps glyph in its header and dock`() {
        val routing = cli("settings/CliRoutingScreen.kt")
        val dock = cli("components/CliHintBar.kt")
        val help = cli("components/CliContextHelp.kt")

        assertTrue(routing.contains("icon = R.drawable.lin_apps"))
        assertTrue(routing.contains("trailing = {"))
        assertTrue(routing.contains("CliTopBarHelpButton("))
        assertTrue(routing.contains("titleRes = R.string.cli_help_routing_title"))
        assertTrue(routing.contains("itemIcons = ROUTING_HELP_ICONS"))
        assertTrue(routing.contains("R.drawable.lin_link"))
        assertTrue(routing.contains("R.drawable.lin_device"))
        assertTrue(routing.contains("R.drawable.lin_forbidden"))
        assertTrue(routing.contains("R.drawable.lin_incognito"))
        assertTrue(dock.contains("CliScreen.APPS -> R.drawable.lin_apps"))
        assertTrue(help.contains("CliSectionHeaderControlSize = 21.dp"))
    }

    @Test
    fun `profile create settings and help share the top bar control geometry`() {
        val profileSource = cli("profiles/CliProfilesScreen.kt")
        val app = cli("CliApp.kt")
        val profileRoute = app
            .substringAfter("CliScreen.PROFILES ->")
            .substringBefore("CliScreen.APPS ->")
        val profiles = profileSource
            .substringAfter("trailing = {")
            .substringBefore("if (templateAddOpen)")
        val help = cli("components/CliContextHelp.kt")
        val profileAdd = cli("profiles/CliProfilesScreen.kt")
            .substringAfter("private fun CliProfileAddButton")
            .substringBefore("internal data class CliProfilesActions")
        val statistics = cli("stats/CliStatsScreen.kt")
            .substringAfter("private fun CliStatsSettingsButton")
            .substringBefore("private fun CliStatsBody")

        assertTrue(profiles.contains("CliProfileAddButton("))
        assertTrue(profiles.contains("CliTopBarHelpButton("))
        assertTrue(profiles.contains("titleRes = R.string.cli_help_editor_title"))
        assertTrue(profiles.contains("itemIcons = PROFILES_HELP_ICONS"))
        assertTrue(profileSource.contains("R.drawable.lin_edit"))
        assertTrue(profileSource.contains("R.drawable.lin_star"))
        assertTrue(profileSource.contains("R.drawable.lin_lock"))
        assertTrue(profileSource.contains("CliGlassHeaderScreen("))
        assertTrue(profileSource.contains("Spacer(modifier = Modifier.height(topInset))"))
        assertFalse(profileRoute.contains("statusBarsPadding"))
        assertFalse(profileRoute.contains("CliTopContentGap"))
        assertTrue(!profiles.contains("iconOffsetX"))
        assertTrue(!profiles.contains("PROFILE_HEADER_ICON_NUDGE"))
        assertTrue(profileAdd.contains("CliTopBarIconButton("))
        assertTrue(profileAdd.contains("tint = colors.accent"))
        assertTrue(statistics.contains("CliTopBarSettingsButton("))
        assertTrue(help.contains("CliTopBarControlSize = 48.dp"))
        assertTrue(help.contains("CliTopBarIconSize = CLI_TOP_BAR_ACTION_ICON_SIZE"))
        assertTrue(help.contains("CLI_TOP_BAR_ACTION_ICON_SIZE = 20.dp"))
        assertTrue(help.contains("CLI_TOP_BAR_ICON_LIFT = (-7).dp"))
        assertTrue(help.contains("iconOffsetY = cliTopBarIconLiftFor(pixelArtEnabled)"))
        assertTrue(help.contains("iconOffsetY = iconOffsetY ?: cliHeaderHelpIconOffsetFor("))
        assertTrue(help.contains("iconSize ?: CLI_SECTION_HEADER_ICON_SIZE"))
        val headerHelp = help
            .substringAfter("internal fun CliHeaderHelpButton")
            .substringBefore("internal fun CliTopBarIconButton")
        assertTrue(headerHelp.contains("tint = colors.info"))
        assertTrue(help.contains("contentAlignment = Alignment.CenterEnd"))
        assertTrue(help.contains("topBar && alignIconToFirstLine -> Alignment.TopEnd"))
        assertFalse(help.contains("iconOffsetX"))
        assertTrue(help.contains("icon = R.drawable.lin_help"))
    }

    @Test
    fun `section help buttons reuse the header icon size and first line alignment`() {
        val panel = cli("components/CliPanel.kt")
        val sheet = cli("components/CliBottomSheet.kt")
        val rows = cli("components/CliRows.kt")

        assertTrue(panel.contains("modifier = modifier.offset(y = headerContentDrop)"))
        assertTrue(panel.contains("verticalAlignment = Alignment.Top"))
        assertFalse(panel.contains("cliPanelHeaderOffsetFor"))
        assertTrue(panel.substringAfter("private fun CliPanelInfoGlyph").contains("CliHeaderHelpButton("))
        assertTrue(panel.substringAfter("private fun CliPanelInfoGlyph").contains("alignIconToFirstLine = true"))
        assertTrue(
            panel.substringAfter("private fun CliPanelInfoGlyph")
                .contains("Modifier.offset(y = controlOffsetY)"),
        )
        assertTrue(panel.substringAfter("private fun CliPanelInfoGlyph").contains("iconOffsetY = 0.dp"))
        assertFalse(panel.substringAfter("private fun CliPanelInfoGlyph").contains("headerGlyphLift"))
        val titleGroup = panel
            .substringAfter("private fun CliPanelTitleGroup")
            .substringBefore("private fun CliPanelInfoGlyph")
        val infoBranch = titleGroup.substringAfter("if (hasInfo)")
        assertTrue(titleGroup.contains("CliSectionHeaderIcon("))
        assertTrue(
            titleGroup.contains(
                "?: cliPanelHeaderLeadingIconLiftFor(pixelArtEnabled)",
            ),
        )
        assertTrue(titleGroup.contains("y = headerIconLift"))
        assertTrue(infoBranch.contains("controlOffsetY = headerIconLift"))
        assertTrue(infoBranch.contains("Spacer(modifier = Modifier.width(CliSpacing.xs))"))
        assertTrue(
            infoBranch.indexOf("Spacer(modifier = Modifier.width(CliSpacing.xs))") <
                infoBranch.indexOf("CliPanelInfoGlyph("),
        )
        assertEquals((-1).dp, cliPanelHeaderGlyphLiftFor("Информация"))
        assertEquals((-1).dp, cliPanelHeaderGlyphLiftFor("Information"))
        assertEquals((-1).dp, cliPanelHeaderGlyphLiftFor("Information", pixelArtEnabled = false))
        assertEquals((-1).dp, cliPanelHeaderLeadingIconLiftFor(pixelArtEnabled = true))
        assertEquals((-2).dp, cliPanelHeaderLeadingIconLiftFor(pixelArtEnabled = false))
        assertEquals(16.dp, CLI_PANEL_HEADER_LEADING_CONTROL_WIDTH)
        assertEquals(18.dp, CLI_PANEL_HEADER_LEADING_ICON_SIZE)
        assertEquals(
            19.dp,
            CLI_PANEL_HEADER_LEADING_CONTROL_WIDTH + CLI_PANEL_HEADER_LEADING_GAP,
        )
        assertEquals(18.dp, CLI_SECTION_HEADER_ICON_SIZE)
        assertTrue(panel.contains("val baseStyle = cliPanelTitleStyle(shownTitle)"))
        assertTrue(titleGroup.contains("controlWidth = CLI_PANEL_HEADER_LEADING_CONTROL_WIDTH"))
        assertTrue(titleGroup.contains("iconMetricOverrides.panelHeaderIconSize"))
        assertTrue(titleGroup.contains("?: CLI_PANEL_HEADER_LEADING_ICON_SIZE"))
        assertTrue(panel.contains("cliPanelHeadingOpticalOffsetFor(shownTitle, pixelArtEnabled)"))
        assertTrue(sheet.contains("style = cliTitleStyle(shownTitle)"))
        assertTrue(sheet.contains("cliHeadingOpticalOffsetFor(shownTitle, pixelArtEnabled)"))
        assertEquals(
            18.dp,
            cliModalHeaderIconSizeFor(CliSheetHeaderIconRole.INFORMATION),
        )
        assertEquals(
            18.dp,
            cliModalHeaderIconSizeFor(CliSheetHeaderIconRole.DEFAULT),
        )
        assertTrue(rows.substringAfter("internal fun CliRowInfoGlyph").contains("CliHeaderHelpButton("))
        val sharedButton = cli("components/CliContextHelp.kt")
            .substringAfter("internal fun CliHeaderHelpButton")
        assertTrue(sharedButton.contains("icon = R.drawable.lin_help"))
        assertTrue(sharedButton.contains("CLI_SECTION_HEADER_ICON_SIZE"))
        assertTrue(sharedButton.contains("contentAlignment = Alignment.TopCenter"))
        assertTrue(sharedButton.contains("CLI_SECTION_HEADER_ICON_OFFSET"))
        assertFalse(panel.contains("iconOffsetX"))
    }

    @Test
    fun `all help sheets share an aligned question and default body colour`() {
        val help = cli("components/CliContextHelp.kt")
        val topBar = help
            .substringAfter("internal fun CliTopBarHelpButton")
            .substringBefore("internal fun CliHeaderHelpButton")
        val section = help
            .substringAfter("internal fun CliContextHelpButton")
            .substringBefore("internal fun CliTopBarHelpButton")
        val infoSheet = cli("components/CliInfoSheet.kt")
        val manual = cli("profiles/CliManualProfileEditor.kt")
        val shared = help
            .substringAfter("internal fun CliHelpNote")
            .substringBefore("internal fun CliTopBarHelpButton")

        assertTrue(section.contains("headerIconRole = CliSheetHeaderIconRole.INFORMATION"))
        assertTrue(topBar.contains("headerIconRole = CliSheetHeaderIconRole.INFORMATION"))
        assertTrue(infoSheet.contains("headerIconRole = CliSheetHeaderIconRole.INFORMATION"))

        assertTrue(topBar.contains("CliHelpNote(text = cliTopBarHelpBody(body))"))
        assertTrue(topBar.contains("CliIconTextItems(items = items, framed = true, iconColor = colors.info)"))
        assertTrue(topBar.contains("title = stringResource(presentation.titleRes)"))
        assertTrue(topBar.contains("icon = presentation.icon"))
        assertTrue(topBar.contains("joinToString(\"\\n\\n\")"))
        assertTrue(section.contains("CliHelpNote(text = stringResource(bodyRes))"))
        assertTrue(shared.contains("@DrawableRes icon: Int? = R.drawable.lin_help"))
        assertTrue(shared.contains("icon?.let { iconId ->"))
        assertTrue(shared.contains("id = iconId"))
        assertTrue(shared.contains("tint = colors.info"))
        assertTrue(shared.contains("CliFirstLineIcon("))
        assertTrue(shared.contains("Spacer(modifier = Modifier.width(CLI_HELP_NOTE_ICON_GAP))"))
        assertTrue(shared.contains("verticalAlignment = Alignment.Top"))
        assertTrue(shared.contains("textAlign = TextAlign.Start"))
        assertTrue(shared.contains("color = colors.fg"))
        assertFalse(section.contains("CliDashedInfoNote("))
        assertFalse(section.contains("centered = true"))
        assertTrue(infoSheet.contains("icon = R.drawable.lin_info.takeIf"))
        assertTrue(infoSheet.contains("LocalCliInfoSheetBodyIconVisible.current"))
        assertFalse(infoSheet.contains("CliDashedInfoNote("))
        assertTrue(manual.contains("trailing = { CliTopBarHelpButton("))
        assertTrue(
            cliTopBarHelpBody("first section\nsecond section") ==
                "first section\n\nsecond section",
        )
    }

    @Test
    fun `icon help paragraphs preserve order and fall back to info`() {
        val items = cliIconTextItems(
            body = "first\n\nsecond\nthird",
            icons = listOf(R.drawable.lin_link, R.drawable.lin_lock),
        )

        assertEquals(listOf("first", "second", "third"), items.map { it.text })
        assertEquals(
            listOf(R.drawable.lin_link, R.drawable.lin_lock, R.drawable.lin_info),
            items.map { it.icon },
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
        assertTrue(profileRow.contains("x = CliSpacing.xs"))
    }

    @Test
    fun `home help opens the existing quick start sheet from the right edge`() {
        val home = cli("home/CliHomeScreen.kt")
        val terminal = cli("home/CliHomeTerminal.kt")

        assertTrue(home.contains("CliQuickStartSheetContent(onDismiss = { quickStartOpen = false })"))
        assertTrue(home.contains("onHelpRequested = { quickStartOpen = true }"))
        assertTrue(terminal.contains("CliHeaderHelpButton("))
        assertTrue(terminal.contains("contentDescription = stringResource(R.string.cli_quick_start_title)"))
        assertTrue(terminal.contains("alignIconToFirstLine = true"))
        assertFalse(terminal.contains("HOME_HEADER_QUICK_START_ICON_OFFSET"))
        assertFalse(terminal.contains("iconOffsetY ="))
        assertFalse(terminal.contains("iconOffsetX"))
        assertFalse(cli("home/CliHomeSectionStyle.kt").contains("TopBarHelpIconOffset"))
        assertTrue(terminal.contains("BoxWithConstraints(modifier = Modifier.weight(1f))"))
    }

    @Test
    fun `statistics settings reuse the stable quick start sheet gesture contract`() {
        val quickStart = cli("onboarding/CliQuickStartSheet.kt")
        val statistics = cli("stats/CliStatsSettingsSheet.kt")

        assertTrue(quickStart.contains("sheetGesturesEnabled = false"))
        assertTrue(statistics.contains("CliStatsSettingsSheetFrame("))
        assertTrue(statistics.contains("sheetGesturesEnabled = false"))
    }

    @Test
    fun `statistics module actions follow state and disabled screen matches the map pattern`() {
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
        val collection = settings
            .substringAfter("private fun CliStatsCollectionPanel")
            .substringBefore("private fun CliStatsMetricsPanel")
        assertTrue(collection.contains("R.string.cli_stats_set_show_in_dock"))
        assertTrue(collection.contains("checked = settings.ui.statisticsDockIconEnabled"))
        assertTrue(collection.contains("onToggle = viewModel::onStatisticsDockIconEnabledChanged"))
        assertTrue(
            collection.indexOf("onToggle = viewModel::onStatisticsEnabledChanged") <
                collection.indexOf("R.string.cli_stats_set_show_in_dock"),
        )
        val disabled = screen
            .substringAfter("if (!state.settings.statistics.enabled)")
            .substringBefore("return@Column")
        assertFalse(disabled.contains("CliDashedInfoNote("))
        assertFalse(disabled.contains("R.string.cli_stats_consent_note"))
        assertTrue(disabled.contains("R.string.cli_stats_consent_body"))
        assertTrue(screen.contains("R.string.cli_stats_enable_module"))
        assertTrue(screen.contains("contentAlignment = Alignment.Center"))
        assertTrue(screen.contains("Column(horizontalAlignment = Alignment.CenterHorizontally)"))
        assertTrue(english.contains(">Enable statistics module</string>"))
        assertTrue(english.contains(">Disable statistics module</string>"))
        assertTrue(russian.contains(">Включить модуль статистики</string>"))
        assertTrue(russian.contains(">Выключить модуль статистики</string>"))
        assertTrue(english.contains(">show icon in dock</string>"))
        assertTrue(russian.contains(">отображать иконку в доке</string>"))
    }

    @Test
    fun `statistics settings use shared help color and keep clear actions concise`() {
        val settings = cli("stats/CliStatsSettingsSheet.kt")
        val clearPanel = settings.substringAfter("private fun CliStatsClearPanel")
        val english = resource("values/strings.xml")
        val russian = resource("values-ru/strings.xml")

        assertEquals(3, Regex("noteColor = colors\\.info").findAll(settings).count())
        assertFalse(clearPanel.contains("note = stringResource(R.string.cli_stats_clear_"))
        assertTrue(
            english.contains(
                ">App traffic statistics recording must be enabled</string>",
            ),
        )
        assertTrue(
            russian.contains(
                ">Требуется включение записи статистики трафика по приложениям</string>",
            ),
        )
    }

    @Test
    fun `statistics panels keep section spacing and table colour hierarchy`() {
        val i2p = cli("stats/CliStatsI2pPanel.kt")
        val i2pSpacer = i2p.indexOf("Spacer(modifier = Modifier.height(CliSpacing.sm))")
        val i2pPanel = i2p.indexOf("CliPanel(", startIndex = i2pSpacer)
        val apps = cli("stats/CliStatsAppTable.kt")
        val header = apps
            .substringAfter("Row(modifier = Modifier.fillMaxWidth().padding(vertical = APP_TABLE_ROW_PADDING))")
            .substringBefore("rows.forEach")
        val dataRows = apps.substringAfter("rows.forEach")
        val cell = apps.substringAfter("private fun RowScope.CliAppTrafficCell(")

        assertTrue(i2pSpacer >= 0)
        assertTrue(i2pPanel > i2pSpacer)
        assertEquals(4, Regex("colors\\.dim").findAll(header).count())
        assertTrue(cell.contains("color: Color = LocalCliColors.current.fg"))
        assertTrue(cell.contains("color = color"))
        assertTrue(dataRows.contains("CliAppTrafficCell(CliFormat.bytes(row.rxBytes)"))
        assertTrue(dataRows.contains("CliAppTrafficCell(CliFormat.bytes(row.txBytes)"))
        assertTrue(dataRows.contains("CliAppTrafficCell(CliFormat.bytes(row.totalBytes)"))
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
