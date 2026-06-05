package com.foxhole.beta.ui

import com.foxhole.beta.core.model.InstalledAppOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomePerformanceSourceContractTest {
    @Test
    fun `route ui state models are marked immutable for compose stability reports`() {
        val routeSource = testSourceFile("RouteUiState.kt").readText()

        assertTrue(routeSource.contains("import androidx.compose.runtime.Immutable"))
        listOf(
            "AutoConnectProbeOptionUiState",
            "AutoConnectUiState",
            "HomeTorOperationUiState",
            "HomeRouteUiState",
            "ProfilesRouteUiState",
            "SettingsRouteUiState",
            "StatisticsRouteUiState",
            "StatisticsDashboardUiState",
            "RoutingRouteUiState",
            "DiagnosticsRouteUiState",
        ).forEach { modelName ->
            assertTrue(routeSource.contains("@Immutable\ndata class $modelName("))
        }
    }

    @Test
    fun `statistics route does not collect traffic map twice in compose`() {
        val appSource = testSourceFile("FoxholeApp.kt").readText()
        val statisticsRouteMarker = "composable(AppRoute.STATISTICS)"
        val statisticsRouteBlock =
            appSource.substringAfter(statisticsRouteMarker)
                .substringBefore(
                    "composable(AppRoute.DIAGNOSTICS)",
                    missingDelimiterValue = appSource.substringAfter(statisticsRouteMarker),
                )

        assertFalse(statisticsRouteBlock.contains("trafficMapUiState.collectAsStateWithLifecycle"))
        assertFalse(statisticsRouteBlock.contains("trafficMapState ="))
    }

    @Test
    fun `statistics route state uses dedicated model instead of settings state`() {
        val viewModelSource = testSourceFile("HomeViewModel.kt").readText()
        val statisticsRouteBlock =
            viewModelSource.substringAfter("val statisticsRouteState:")
                .substringBefore("val routingRouteState:")

        assertTrue(statisticsRouteBlock.contains("StateFlow<StatisticsRouteUiState>"))
        assertTrue(statisticsRouteBlock.contains("toStatisticsRouteUiState()"))
        assertTrue(statisticsRouteBlock.contains("StatisticsRouteUiState()"))
        assertFalse(statisticsRouteBlock.contains("toSettingsRouteUiState("))
        assertFalse(statisticsRouteBlock.contains("dnsFilterRefreshInProgressMutable"))
    }

    @Test
    fun `statistics route state is not subscribed to diagnostics log updates`() {
        val viewModelSource = testSourceFile("HomeViewModel.kt").readText()
        val statisticsActivityStreamsBlock =
            viewModelSource.substringAfter("private val statisticsActivityStreams")
                .substringBefore("private val coreUiState")
        val statisticsUiStateBlock =
            viewModelSource.substringAfter("private val statisticsUiState:")
                .substringBefore("val themeMode:")

        assertFalse(statisticsActivityStreamsBlock.contains("diagnosticsLogger.entries"))
        assertTrue(statisticsUiStateBlock.contains("coreUiState"))
        assertTrue(statisticsUiStateBlock.contains("statisticsActivityStreams"))
        assertFalse(statisticsUiStateBlock.contains("diagnosticEntries ="))
        assertFalse(statisticsUiStateBlock.contains("uiState,"))
    }

    @Test
    fun `dashboard root collects scoped card state flows instead of full home route state`() {
        val appSource = testSourceFile("FoxholeApp.kt").readText()
        val homeRouteBlock =
            appSource.substringAfter("composable(AppRoute.HOME)")
                .substringBefore("composable(AppRoute.TRAFFIC_MAP_DETAIL)")

        assertFalse(homeRouteBlock.contains("homeRouteState.collectAsStateWithLifecycle"))
        listOf(
            "dashboardLayoutState",
            "dashboardHeaderState",
            "dashboardProfileCardState",
            "dashboardActionsCardState",
            "dashboardNetworkCardState",
            "dashboardTrafficCardState",
            "dashboardMapCardState",
            "dashboardDialogState",
        ).forEach { flowName ->
            assertTrue(homeRouteBlock.contains("viewModel.$flowName"))
        }
        assertTrue(homeRouteBlock.contains("viewModel::onActiveProfileAutoConnectExcludedOptionsChanged"))
        assertFalse(homeRouteBlock.contains("state.activeProfile?.id"))
    }

    @Test
    fun `dashboard screen accepts scoped flows and cards collect their own state`() {
        val homeSource = testSourceFile("HomeScreen.kt").readText()
        val homeSignature =
            homeSource.substringAfter("internal fun HomeScreen(")
                .substringBefore("snackbarHostState:")

        assertFalse(homeSignature.contains("state: HomeRouteUiState"))
        assertFalse(homeSignature.contains("trafficStateFlow"))
        listOf(
            "layoutStateFlow: StateFlow<DashboardLayoutUiState>",
            "headerStateFlow: StateFlow<DashboardHeaderUiState>",
            "profileCardStateFlow: StateFlow<DashboardProfileCardUiState>",
            "actionsCardStateFlow: StateFlow<DashboardActionsCardUiState>",
            "networkCardStateFlow: StateFlow<DashboardNetworkCardUiState>",
            "trafficCardStateFlow: StateFlow<DashboardTrafficCardUiState>",
            "mapCardStateFlow: StateFlow<DashboardMapCardUiState>",
            "dialogStateFlow: StateFlow<DashboardDialogUiState>",
        ).forEach { parameter ->
            assertTrue(homeSignature.contains(parameter))
        }
        listOf(
            "HomeDashboardHeaderItem(" to "val state by stateFlow.collectAsStateWithLifecycle()",
            "HomeDashboardProfileCardItem(" to "val state by stateFlow.collectAsStateWithLifecycle()",
            "HomeDashboardActionsCardItem(" to "val state by stateFlow.collectAsStateWithLifecycle()",
            "HomeDashboardNetworkCardItem(" to "val state by stateFlow.collectAsStateWithLifecycle()",
            "HomeDashboardTrafficCardItem(" to "val state by stateFlow.collectAsStateWithLifecycle()",
        ).forEach { (functionName, collectionLine) ->
            val block = homeSource.substringAfter(functionName)
            assertTrue(block.contains(collectionLine))
        }
    }

    @Test
    fun `dashboard view model exposes scoped state flows for hot cards`() {
        val viewModelSource = testSourceFile("HomeViewModel.kt").readText()
        val dashboardFlowBlock =
            viewModelSource.substringAfter("internal val dashboardLayoutState:")
                .substringBefore("private val trafficMapRuntimeAvailable")

        listOf(
            "toDashboardLayoutUiState",
            "toDashboardHeaderUiState",
            "toDashboardProfileCardUiState",
            "toDashboardActionsCardUiState",
            "toDashboardNetworkCardUiState",
            "toDashboardTrafficCardUiState",
            "toDashboardMapCardUiState",
            "DashboardDialogUiState",
        ).forEach { builderName ->
            assertTrue(dashboardFlowBlock.contains(builderName))
        }
        assertTrue(dashboardFlowBlock.contains("combine(\n            homeRouteState,\n            dashboardTraffic,"))
        assertTrue(viewModelSource.contains("fun onActiveProfileAutoConnectExcludedOptionsChanged"))
    }

    @Test
    fun `dashboard scoped state models are immutable`() {
        val dashboardStateSource = testSourceFile("HomeDashboardUiState.kt").readText()

        listOf(
            "DashboardLayoutUiState",
            "DashboardHeaderUiState",
            "DashboardProfileCardUiState",
            "DashboardActionsCardUiState",
            "DashboardNetworkCardUiState",
            "DashboardTrafficCardUiState",
            "DashboardMapCardUiState",
            "DashboardDialogUiState",
        ).forEach { modelName ->
            assertTrue(dashboardStateSource.contains("@Immutable\ninternal data class $modelName("))
        }
    }

    @Test
    fun `network card model is not keyed by full route state`() {
        val homeSource = testSourceFile("HomeScreen.kt").readText()
        val networkModelBlock =
            homeSource.substringAfter("val networkModel =")
                .substringBefore("val visibleNetworkIpInfo")

        assertFalse(networkModelBlock.contains("remember(state,"))
        assertTrue(networkModelBlock.contains("state.connection"))
        assertTrue(networkModelBlock.contains("state.ipInfoLoading"))
        assertTrue(networkModelBlock.contains("state.autoConnectRunning"))
    }

    @Test
    fun `dns filter enable preflight downloads fresh rule set before applying`() {
        val settingsSource = testSourceFile("HomeViewModelSettingsSupport.kt").readText()
        val preflightBlock =
            settingsSource.substringAfter("private suspend fun HomeViewModel.ensureVerifiedDownloadedDnsRuleSet")
                .substringBefore("internal fun HomeViewModel.onDnsBypassPackagesChangedInternal")
        val beforeRefreshBlock =
            preflightBlock.substringBefore("container.dnsFilterUpdateRepository.refreshNow(")

        assertTrue(preflightBlock.contains("container.dnsFilterUpdateRepository.refreshNow("))
        assertFalse(beforeRefreshBlock.contains("container.dnsFilterAssetInstaller.prepareVerifiedOrNull()"))
    }

    @Test
    fun `app picker defaults to user applications`() {
        val appPickerSource = testSourceFile("RoutingAppScreens.kt").readText()

        assertTrue(appPickerSource.contains("mutableStateOf(InstalledAppFilter.USER)"))
        assertFalse(appPickerSource.contains("mutableStateOf(InstalledAppFilter.ALL)"))
    }

    @Test
    fun `app picker uses set membership on visible rows`() {
        val appPickerSource = testSourceFile("RoutingAppScreens.kt").readText()

        assertTrue(appPickerSource.contains("val draftSelectionSet = remember(draftSelection)"))
        assertTrue(appPickerSource.contains("val checked = app.packageName in draftSelectionSet || locked"))
        assertFalse(appPickerSource.contains("draftSelection.contains(app.packageName)"))
    }

    @Test
    fun `dashboard lazy list uses stable content types for hot cards`() {
        val homeSource = testSourceFile("HomeScreen.kt").readText()

        assertTrue(homeSource.contains("item(key = \"connection_header\", contentType = \"dashboard_header\")"))
        listOf(
            "dashboard_card_traffic_map",
            "dashboard_card_profiles",
            "dashboard_card_actions",
            "dashboard_card_network",
            "dashboard_card_traffic",
        ).forEach { contentType ->
            assertTrue(homeSource.contains("contentType = \"$contentType\""))
        }
    }

    @Test
    fun `routing app icon grid caps preview work and exposes overflow tile`() {
        val appPickerSource = testSourceFile("RoutingAppScreens.kt").readText()

        assertTrue(appPickerSource.contains("apps.take(APP_ICON_GRID_PREVIEW_LIMIT)"))
        assertTrue(appPickerSource.contains("APP_ICON_GRID_PREVIEW_LIMIT = 12"))
        assertTrue(appPickerSource.contains("AppGridMoreTile("))
        assertTrue(appPickerSource.contains("testTag(\"app_grid_more_tile\")"))
        assertTrue(appPickerSource.contains("contentType = \"app-grid-more\""))
    }

    @Test
    fun `app picker builds one normalized search index per app list`() {
        val appPickerSource = testSourceFile("RoutingAppScreens.kt").readText()

        assertTrue(
            appPickerSource.contains(
                "remember(state.installedApps) { buildInstalledAppSearchIndex(state.installedApps) }",
            ),
        )
        assertTrue(appPickerSource.contains("filterIndexedApps(appSearchIndex, query)"))
        assertFalse(appPickerSource.contains("filterApps(apps, query)"))
    }

    @Test
    fun `app picker search index filters labels and package names consistently`() {
        val apps =
            listOf(
                InstalledAppOption(
                    packageName = "org.telegram.messenger",
                    label = "Telegram",
                    isSystemApp = false,
                ),
                InstalledAppOption(
                    packageName = "com.android.settings",
                    label = "Settings",
                    isSystemApp = true,
                ),
                InstalledAppOption(
                    packageName = "com.example.camera",
                    label = "Camera",
                    isSystemApp = false,
                ),
            )
        val index = buildInstalledAppSearchIndex(apps)

        assertEquals(listOf(apps[0]), filterIndexedApps(index, " TELE "))
        assertEquals(listOf(apps[1]), filterIndexedApps(index, "android.set"))
        assertEquals(apps, filterIndexedApps(index, " "))
    }

    @Test
    fun `app picker and icon loading expose macrobenchmark trace sections`() {
        val routingSource = testSourceFile("RoutingAppScreens.kt").readText()
        val protocolSource = testSourceFile("ProfileProtocolUi.kt").readText()

        assertTrue(routingSource.contains("traceAppPickerSection(\"AppPicker/filter\")"))
        assertTrue(routingSource.contains("filterIndexedApps(appSearchIndex, query)"))
        assertTrue(protocolSource.contains("traceAppIconSection(\"AppIcon/load\")"))
        assertTrue(protocolSource.contains("withContext(Dispatchers.IO)"))
    }

    @Test
    fun `app picker saves selection changes with debounce instead of every toggle`() {
        val routingSource = testSourceFile("RoutingAppScreens.kt").readText()
        val pickerBlock =
            routingSource.substringAfter("fun AppPickerScreen(")
                .substringBefore("@Composable\nprivate fun rememberAppPickerPopupsAllowed")
        val toggleBlock =
            pickerBlock.substringAfter("onToggle = { value ->")
                .substringBefore(")")

        assertTrue(routingSource.contains("APP_PICKER_SELECTION_SAVE_DEBOUNCE_MS = 350L"))
        assertTrue(pickerBlock.contains("LaunchedEffect(draftSelection, selectedPackages)"))
        assertTrue(pickerBlock.contains("delay(APP_PICKER_SELECTION_SAVE_DEBOUNCE_MS)"))
        assertTrue(pickerBlock.contains("latestOnSelectionChanged(draftSelection)"))
        assertFalse(toggleBlock.contains("onSelectionChanged(nextSelection)"))
        assertFalse(toggleBlock.contains("latestOnSelectionChanged(nextSelection)"))
    }

    @Test
    fun `app icon cache key includes package version update time and size`() {
        val first =
            AppIconCacheKey(
                packageName = "com.example.app",
                versionCode = 10L,
                lastUpdateTime = 100L,
                sizePx = 48,
            )
        val updated = first.copy(versionCode = 11L)
        val reinstalled = first.copy(lastUpdateTime = 200L)
        val larger = first.copy(sizePx = 72)

        assertNotEquals(first, updated)
        assertNotEquals(first, reinstalled)
        assertNotEquals(first, larger)
    }

    @Test
    fun `installed app icons use package manager metadata for cache invalidation`() {
        val runtimeSource = testSourceFile("HomeViewModelRuntimeSupport.kt").readText()
        val protocolSource = testSourceFile("ProfileProtocolUi.kt").readText()
        val routingSource = testSourceFile("RoutingAppScreens.kt").readText()
        val homeSupportSource = testSourceFile("HomeScreenSupport.kt").readText()

        assertTrue(runtimeSource.contains("val packageInfo = packageManager.packageInfoOrNull(packageName)"))
        assertTrue(runtimeSource.contains("versionCode = packageInfo.versionCodeOrNull()"))
        assertTrue(runtimeSource.contains("lastUpdateTime = packageInfo?.lastUpdateTime"))
        assertTrue(protocolSource.contains("AppIconCacheKey("))
        assertTrue(protocolSource.contains("versionCode = versionCode"))
        assertTrue(protocolSource.contains("lastUpdateTime = lastUpdateTime"))
        assertTrue(routingSource.contains("versionCode = app.versionCode"))
        assertTrue(homeSupportSource.contains("lastUpdateTime = app.lastUpdateTime"))
    }

    @Test
    fun `protocol selector width measurement is memoized`() {
        val protocolSource = testSourceFile("ProfileProtocolUi.kt").readText()
        val selectorWidthBlock =
            protocolSource.substringAfter("private fun rememberProtocolSelectorFixedWidth(")
                .substringBefore("internal fun protocolSelectorWidthBasisPx")

        assertTrue(selectorWidthBlock.contains("return remember("))
        assertTrue(selectorWidthBlock.contains("textMeasurer.measure("))
    }

    @Test
    fun `tor renew ip action reads as a primary button`() {
        val homeSupportSource = testSourceFile("HomeScreenSupport.kt").readText()
        val torActionBlock =
            homeSupportSource.substringAfter("val changeIpInProgress =")
                .substringBefore("internal data class HomeTorIpPresentation")

        assertTrue(torActionBlock.contains("Button("))
        assertFalse(torActionBlock.contains("OutlinedButton("))
    }

    private fun testSourceFile(name: String): java.io.File =
        listOf(
            java.io.File("src/main/kotlin/com/foxhole/beta/ui/$name"),
            java.io.File("src/main/kotlin/com/foxhole/beta/$name"),
            java.io.File("app/src/main/kotlin/com/foxhole/beta/ui/$name"),
            java.io.File("app/src/main/kotlin/com/foxhole/beta/$name"),
            java.io.File("../app/src/main/kotlin/com/foxhole/beta/ui/$name"),
            java.io.File("../app/src/main/kotlin/com/foxhole/beta/$name"),
        ).first { file -> file.isFile }
}
