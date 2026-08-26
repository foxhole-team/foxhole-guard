package com.foxhole.guard.ui.cli

import com.foxhole.guard.ui.cli.home.cliHomeAdditionalInfoReady
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CliHomeAdditionalInfoContractTest {
    @Test
    fun `application settings expose an opt in category picker with map and route icons`() {
        val settings = cli("settings/CliSettingsScreen.kt")
        val rows = settings
            .substringAfter("private fun CliHomeAdditionalInfoRows(")
            .substringBefore("private fun CliMoreSection(")

        assertTrue(rows.contains("checked = settings.ui.showHomeAdditionalInfo"))
        assertTrue(rows.contains("icon = R.drawable.lin_info"))
        assertTrue(rows.contains("HomeAdditionalInfoCategory.entries.map"))
        assertTrue(rows.contains("HomeAdditionalInfoCategory.MAP -> R.drawable.lin_map"))
        assertTrue(rows.contains("HomeAdditionalInfoCategory.ROUTE -> R.drawable.lin_link"))
        assertTrue(rows.contains("enabled = settings.ui.showHomeAdditionalInfo"))
        assertTrue(rows.contains("showSelectedOptionIcon = true"))
    }

    @Test
    fun `home preview follows the logo status header and precedes the terminal output`() {
        val home = cli("home/CliHomeScreen.kt")
        val terminal = cli("home/CliHomeTerminal.kt")
        val terminalCall = home
            .substringAfter("CliTerminalPanel(")
            .substringBefore("CliClearTerminalSheet(")
        val terminalColumn = terminal
            .substringAfter("Column(modifier = modifier) {")
            .substringBefore("var outputLayoutRevision")

        assertTrue(terminalCall.contains("contentAfterHeader = {"))
        assertTrue(terminalCall.contains("CliHomeAdditionalInfoSlot("))
        assertTrue(terminalColumn.indexOf("CliTerminalHeader(") < terminalColumn.indexOf("contentAfterHeader()"))
        assertTrue(terminalColumn.indexOf("contentAfterHeader()") < terminalColumn.indexOf("CliPanel("))
        assertTrue(home.contains("visible = home.settings.ui.showHomeAdditionalInfo"))
        assertTrue(home.contains("CliChromeTailSpacer(extraGap = 0.dp)"))
        assertTrue(home.contains("CliMapOverviewContent.MAP"))
        assertTrue(home.contains("CliMapOverviewContent.ROUTE"))
        assertTrue(home.contains("enter = cliVerticalEnter()"))
        assertTrue(home.contains("exit = cliVerticalExit()"))
        assertTrue(home.contains("onClick = onOpenMap.takeIf { category == HomeAdditionalInfoCategory.MAP }"))
        assertTrue(home.contains("title = category.cliHomeAdditionalInfoTitle()"))
        assertTrue(home.contains("icon = category.cliHomeAdditionalInfoIcon()"))
        assertTrue(home.contains("titleModifier = Modifier.cliHomeSectionHeaderPlacement()"))
        assertTrue(home.contains("titleColor = colors.accent"))
        assertTrue(home.contains("mapHorizontalInset = HOME_MAP_HORIZONTAL_INSET"))
        assertTrue(home.contains("mapLegendTopSpacing = HOME_MAP_LEGEND_TOP_SPACING"))
        assertTrue(home.contains("mapLegendCentered = true"))
        assertTrue(home.contains("mapLegendOffsetY = HOME_MAP_LEGEND_OFFSET_Y"))
        assertTrue(home.contains("private val HOME_MAP_HORIZONTAL_INSET = 16.dp"))
        assertTrue(home.contains("private val HOME_MAP_LEGEND_TOP_SPACING = 2.dp"))
        assertTrue(home.contains("private val HOME_MAP_LEGEND_OFFSET_Y = 3.dp"))
        assertTrue(terminalColumn.contains("R.string.cli_home_section_console"))
        assertTrue(terminalColumn.contains("R.drawable.lin_terminal"))
        assertTrue(terminalColumn.contains("Modifier.cliHomeSectionHeaderPlacement()"))
        assertTrue(terminalColumn.contains("titleColor = colors.accent"))
        assertFalse(home.contains("CliMapCountriesPanel"))

        val app = cli("CliApp.kt")
        assertTrue(app.contains("onOpenMap = { screen = CliScreen.MAP }"))
    }

    @Test
    fun `current information has a compact title without a redundant info action`() {
        val facts = cli("home/CliHomeFacts.kt")

        assertTrue(facts.contains("title = stringResource(R.string.cli_home_section_current_info)"))
        assertTrue(resource("values/strings.xml").contains(">status</string>"))
        assertTrue(resource("values-ru/strings.xml").contains(">статус</string>"))
        assertTrue(facts.contains("titleModifier = Modifier.cliHomeStatusHeaderPlacement()"))
        assertTrue(facts.contains("titleColor = colors.accent"))
        assertTrue(facts.contains("icon = R.drawable.lin_status"))
        assertFalse(facts.contains("infoText = stringResource(R.string.cli_home_current_info_help)"))
        assertTrue(facts.contains("onClick = onProfileTap"))
        assertTrue(facts.contains("onLongClick = onProfileHold"))
        val loadedPanel = facts
            .substringAfter("internal fun CliConnectionFactsPanel(")
            .substringBefore("private fun ColumnScope.CliConnectionFactsRows(")
        assertTrue(loadedPanel.contains("CliConnectionFactsRows("))
        assertFalse(loadedPanel.contains("CliStatusSectionMetrics"))
    }

    @Test
    fun `preloader reserves the selected preview geometry`() {
        val home = cli("home/CliHomeScreen.kt")
        val panel = home
            .substringAfter("private fun CliHomeAdditionalInfoPanel(")
            .substringBefore("internal const val CLI_HOME_ADDITIONAL_INFO_TAG")

        assertTrue(panel.contains("profilesLoaded = home.profilesLoaded"))
        assertTrue(panel.contains("settingsHydrated = home.settingsHydrated"))
        assertTrue(panel.contains("rememberCliHomeMapAssetsReady("))
        assertTrue(panel.contains("targetState = previewReady"))
        assertTrue(panel.contains("transitionSpec = { cliBootstrapFade() }"))
        assertTrue(panel.contains("if (!ready)"))
        assertTrue(panel.contains("CliHomeAdditionalInfoPreloader("))
        assertTrue(panel.contains("val placeholderMap = remember { TrafficMapUiState() }"))
        assertTrue(panel.contains("val placeholderHome = remember { HomeRouteUiState() }"))
        assertTrue(panel.contains("CliLoadingRow(text = stringResource(R.string.cli_common_loading_data))"))
        assertTrue(panel.contains(".alpha(0f)"))
        assertTrue(panel.contains(".clearAndSetSemantics {}"))
        assertTrue(panel.contains("category == HomeAdditionalInfoCategory.ROUTE"))
        assertTrue(panel.contains("animateContentSize(animationSpec = cliVerticalSizeSpec())"))
        assertTrue(panel.contains("prewarmMapAssets = false"))
    }

    @Test
    fun `cold start preview waits for settings profiles and selected map assets`() {
        assertFalse(
            cliHomeAdditionalInfoReady(
                profilesLoaded = true,
                settingsHydrated = false,
                mapAssetsReady = true,
            ),
        )
        assertFalse(
            cliHomeAdditionalInfoReady(
                profilesLoaded = false,
                settingsHydrated = true,
                mapAssetsReady = true,
            ),
        )
        assertFalse(
            cliHomeAdditionalInfoReady(
                profilesLoaded = true,
                settingsHydrated = true,
                mapAssetsReady = false,
            ),
        )
        assertTrue(
            cliHomeAdditionalInfoReady(
                profilesLoaded = true,
                settingsHydrated = true,
                mapAssetsReady = true,
            ),
        )
    }

    @Test
    fun `map screen and home preview render through the same overview panel`() {
        val map = cli("map/CliMapScreen.kt")
        val route = cli("map/CliRouteScheme.kt")
        val home = cli("home/CliHomeScreen.kt")
        val pixelMap = cli("map/CliPixelMap.kt")

        assertTrue(map.contains("internal fun CliMapOverviewPanel("))
        assertTrue(map.contains("content = CliMapOverviewContent.ALL"))
        assertTrue(map.contains("if (content != CliMapOverviewContent.ROUTE)"))
        assertTrue(map.contains("if (content != CliMapOverviewContent.MAP)"))
        assertTrue(home.contains("showRouteHeader = category != HomeAdditionalInfoCategory.ROUTE"))
        assertTrue(route.contains("showHeader: Boolean = true"))
        assertTrue(pixelMap.contains("CliPixelMapAssetPrewarmEffect("))
        assertTrue(pixelMap.contains("assetState is TrafficMapAssetState.Idle"))
        assertFalse(pixelMap.contains("LaunchedEffect(Unit) { TrafficMapAssets.prewarm(context) }"))

        val assets = app("ui/trafficmap/TrafficMapAssets.kt")
        assertTrue(
            assets.contains(
                "_state.compareAndSet(TrafficMapAssetState.Idle, TrafficMapAssetState.Loading)",
            ),
        )
    }

    @Test
    fun `fast ui snapshot carries both cold start preview fields`() {
        val store = app("core/settings/SettingsFastUiStore.kt")

        assertTrue(store.contains("putBoolean(FAST_SHOW_HOME_ADDITIONAL_INFO_KEY"))
        assertTrue(store.contains("putString(FAST_HOME_ADDITIONAL_INFO_CATEGORY_KEY"))
        assertTrue(store.contains("showHomeAdditionalInfo ="))
        assertTrue(store.contains("homeAdditionalInfoCategory ="))
        assertTrue(store.contains("fastUiPreferences.contains(FAST_SHOW_HOME_ADDITIONAL_INFO_KEY)"))
        assertTrue(store.contains("fastUiPreferences.contains(FAST_HOME_ADDITIONAL_INFO_CATEGORY_KEY)"))
    }

    private fun cli(path: String): String =
        File(requireNotNull(System.getProperty("user.dir")), "src/main/kotlin/com/foxhole/guard/ui/cli/$path")
            .readText()

    private fun app(path: String): String =
        File(requireNotNull(System.getProperty("user.dir")), "src/main/kotlin/com/foxhole/guard/$path")
            .readText()

    private fun resource(path: String): String =
        File(requireNotNull(System.getProperty("user.dir")), "src/main/res/$path").readText()
}
