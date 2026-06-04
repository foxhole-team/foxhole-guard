package com.foxhole.beta.ui

import com.foxhole.beta.core.model.DashboardCard
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeDashboardHotPathTest {
    @Test
    fun `dashboard card placement animation is disabled outside reorder`() {
        assertFalse(shouldAnimateDashboardCardPlacement(activeCard = null, card = DashboardCard.NETWORK))
    }

    @Test
    fun `dashboard card placement animation skips actively dragged card`() {
        assertFalse(
            shouldAnimateDashboardCardPlacement(
                activeCard = DashboardCard.NETWORK,
                card = DashboardCard.NETWORK,
            ),
        )
    }

    @Test
    fun `dashboard card placement animation runs only for other cards during reorder`() {
        assertTrue(
            shouldAnimateDashboardCardPlacement(
                activeCard = DashboardCard.NETWORK,
                card = DashboardCard.TRAFFIC,
            ),
        )
    }

    @Test
    fun `startup composition keeps first frame card free`() {
        DashboardCard.entries.forEach { card ->
            assertFalse(
                shouldComposeDashboardCardNow(
                    card = card,
                    startupStage = 0,
                    activeReorderCard = null,
                ),
            )
        }
    }

    @Test
    fun `startup composition prioritizes network before map`() {
        assertFalse(
            shouldComposeDashboardCardNow(
                card = DashboardCard.TRAFFIC_MAP,
                startupStage = 1,
                activeReorderCard = null,
            ),
        )
        assertTrue(
            shouldComposeDashboardCardNow(
                card = DashboardCard.NETWORK,
                startupStage = 1,
                activeReorderCard = null,
            ),
        )
        assertFalse(
            shouldComposeDashboardCardNow(
                card = DashboardCard.PROFILES,
                startupStage = 1,
                activeReorderCard = null,
            ),
        )
        assertFalse(
            shouldComposeDashboardCardNow(
                card = DashboardCard.ACTIONS,
                startupStage = 1,
                activeReorderCard = null,
            ),
        )
        assertFalse(
            shouldComposeDashboardCardNow(
                card = DashboardCard.TRAFFIC,
                startupStage = 1,
                activeReorderCard = null,
            ),
        )
    }

    @Test
    fun `startup composition adds remaining dashboard cards in short stages`() {
        assertTrue(
            shouldComposeDashboardCardNow(
                card = DashboardCard.TRAFFIC_MAP,
                startupStage = 2,
                activeReorderCard = null,
            ),
        )
        assertTrue(
            shouldComposeDashboardCardNow(
                card = DashboardCard.PROFILES,
                startupStage = 2,
                activeReorderCard = null,
            ),
        )
        assertTrue(
            shouldComposeDashboardCardNow(
                card = DashboardCard.ACTIONS,
                startupStage = 3,
                activeReorderCard = null,
            ),
        )
        assertTrue(
            shouldComposeDashboardCardNow(
                card = DashboardCard.NETWORK,
                startupStage = 3,
                activeReorderCard = null,
            ),
        )
        assertFalse(
            shouldComposeDashboardCardNow(
                card = DashboardCard.TRAFFIC,
                startupStage = 3,
                activeReorderCard = null,
            ),
        )
    }

    @Test
    fun `startup composition restores traffic card after first frame window`() {
        assertTrue(
            shouldComposeDashboardCardNow(
                card = DashboardCard.TRAFFIC,
                startupStage = 4,
                activeReorderCard = null,
            ),
        )
        assertTrue(
            shouldComposeDashboardCardNow(
                card = DashboardCard.TRAFFIC,
                startupStage = 0,
                activeReorderCard = DashboardCard.NETWORK,
            ),
        )
    }

    @Test
    fun `warm dashboard return restores cached map content immediately`() {
        val coldStage = initialDashboardStartupStage(dashboardAlreadyWarm = false)
        val warmStage = initialDashboardStartupStage(dashboardAlreadyWarm = true)

        assertFalse(
            shouldComposeDashboardCardNow(
                card = DashboardCard.TRAFFIC_MAP,
                startupStage = coldStage,
                activeReorderCard = null,
            ),
        )
        assertTrue(
            shouldComposeDashboardCardNow(
                card = DashboardCard.TRAFFIC_MAP,
                startupStage = warmStage,
                activeReorderCard = null,
            ),
        )
        assertTrue(
            shouldComposeDashboardCardNow(
                card = DashboardCard.NETWORK,
                startupStage = warmStage,
                activeReorderCard = null,
            ),
        )
        assertFalse(
            shouldComposeDashboardCardNow(
                card = DashboardCard.ACTIONS,
                startupStage = warmStage,
                activeReorderCard = null,
            ),
        )
        assertFalse(
            shouldComposeDashboardCardNow(
                card = DashboardCard.TRAFFIC,
                startupStage = warmStage,
                activeReorderCard = null,
            ),
        )
        assertTrue(
            shouldComposeTrafficMapHeavyContent(
                startupStage = warmStage,
                activeReorderCard = null,
            ),
        )
    }

    @Test
    fun `traffic map heavy content appears as soon as map startup stage is reached`() {
        assertFalse(
            shouldComposeTrafficMapHeavyContent(
                startupStage = 1,
                activeReorderCard = null,
            ),
        )
        assertTrue(
            shouldComposeTrafficMapHeavyContent(
                startupStage = 2,
                activeReorderCard = null,
            ),
        )
        assertTrue(
            shouldComposeTrafficMapHeavyContent(
                startupStage = 3,
                activeReorderCard = null,
            ),
        )
        assertTrue(
            shouldComposeTrafficMapHeavyContent(
                startupStage = 0,
                activeReorderCard = DashboardCard.TRAFFIC_MAP,
            ),
        )
    }

    @Test
    fun `traffic map heavy content waits for root navigation settle before parsing`() {
        val homeSource = testSourceFile("HomeScreen.kt").readText()
        val trafficMapSource = testSourceFile("TrafficMapDashboardCard.kt").readText()

        assertTrue(homeSource.contains("DASHBOARD_CARD_STARTUP_STAGE_DELAY_MS = 16L"))
        assertTrue(trafficMapSource.contains("TRAFFIC_MAP_HEAVY_CONTENT_SETTLE_DELAY_MS = 650L"))
        assertTrue(trafficMapSource.contains("delay(TRAFFIC_MAP_HEAVY_CONTENT_SETTLE_DELAY_MS)"))
        assertFalse(homeSource.contains("DASHBOARD_CARD_STARTUP_STAGE_DELAY_MS = 32L"))
        assertFalse(homeSource.contains("DASHBOARD_CARD_STARTUP_STAGE_DELAY_MS = 48L"))
    }

    @Test
    fun `settings home startup staging stays under one frame per stage`() {
        val settingsSource = testSourceFile("SettingsScreens.kt").readText()

        assertTrue(settingsSource.contains("SETTINGS_HOME_STARTUP_STAGE_DELAY_MS = 16L"))
        assertFalse(settingsSource.contains("SETTINGS_HOME_STARTUP_STAGE_DELAY_MS = 80L"))
    }

    @Test
    fun `dashboard and settings root sections animate without keep alive prewarm panes`() {
        val appSource = testSourceFile("FoxholeApp.kt").readText()
        val homeRouteBlock =
            appSource.substringAfter("composable(AppRoute.HOME)")
                .substringBefore("composable(AppRoute.PROFILES)")

        assertTrue(homeRouteBlock.contains("AnimatedContent("))
        assertTrue(homeRouteBlock.contains("targetState = rootSection"))
        assertTrue(homeRouteBlock.contains("when (section)"))
        assertTrue(homeRouteBlock.contains("detailForwardEnter() togetherWith detailForwardExit()"))
        assertTrue(homeRouteBlock.contains("detailBackEnter() togetherWith detailBackExit()"))
        assertTrue(homeRouteBlock.contains("AppSection.DASHBOARD ->"))
        assertTrue(homeRouteBlock.contains("AppSection.SETTINGS ->"))
        assertFalse(homeRouteBlock.contains("RootSectionKeepAliveHost"))
        assertFalse(appSource.contains("RootSectionKeepAlivePane"))
        assertFalse(appSource.contains("ROOT_SETTINGS_PREWARM_DELAY_MS"))
        assertFalse(appSource.contains("delay(ROOT_SETTINGS_PREWARM_DELAY_MS)"))
        assertFalse(appSource.contains("rootSectionKeepAlivePane"))
    }

    @Test
    fun `main activity renders app content without fixed startup gate`() {
        val activitySource = testSourceFile("MainActivity.kt").readText()

        assertFalse(activitySource.contains("appContentReady"))
        assertFalse(activitySource.contains("ACTIVITY_APP_CONTENT_STARTUP_DELAY_MS"))
        assertFalse(activitySource.contains("ACTIVITY_VIEWMODEL_STARTUP_DELAY_MS"))
        assertFalse(activitySource.contains("withFrameNanos"))
    }

    @Test
    fun `stale vpn permission callback is ignored instead of denied`() {
        val viewModelSource = testSourceFile("HomeViewModel.kt").readText()
        val permissionBlock =
            viewModelSource.substringAfter("fun onVpnPermissionResult(granted: Boolean)")
                .substringBefore("fun onRefreshProfile()")

        assertTrue(permissionBlock.contains("if (request == null)"))
        assertTrue(permissionBlock.contains("ignored vpn permission result without active request"))
        assertTrue(permissionBlock.indexOf("if (request == null)") < permissionBlock.indexOf("if (!granted)"))
        assertFalse(
            permissionBlock.substringAfter("if (request == null)").substringBefore("if (!granted)")
                .contains("vpn_permission_denied"),
        )
    }

    @Test
    fun `profile file import reads content off main dispatcher`() {
        val appSource = testSourceFile("FoxholeApp.kt").readText()
        val importBlock =
            appSource.substringAfter("val importProfileLauncher =")
                .substringBefore("Scaffold(")

        assertTrue(importBlock.contains("withContext(Dispatchers.IO)"))
        assertTrue(importBlock.indexOf("withContext(Dispatchers.IO)") < importBlock.indexOf("openInputStream(uri)"))
    }

    @Test
    fun `dashboard reorder persists only after drag session ends`() {
        val homeSource = testSourceFile("HomeScreen.kt").readText()
        val moveBlock =
            homeSource.substringAfter("fun moveDashboardCard(")
                .substringBefore("var pinnedIpInfo")
        val finishBlock =
            homeSource.substringAfter("fun updateActiveReorderCard(")
                .substringBefore("fun moveDashboardCard(")

        assertFalse(moveBlock.contains("onDashboardCardOrderChanged(nextOrder)"))
        assertTrue(finishBlock.contains("pendingCommittedDashboardCardOrder = dashboardCardOrder"))
        assertTrue(finishBlock.contains("onDashboardCardOrderChanged(dashboardCardOrder)"))
    }

    @Test
    fun `startup defers work manager scheduling beyond first dashboard frame`() {
        val applicationSource = testSourceFile("FoxholeApplication.kt").readText()
        val initializationBlock =
            applicationSource.substringAfter("private suspend fun initializeInBackground()")
                .substringBefore("private fun installDebugStrictMode()")

        assertTrue(initializationBlock.contains("delay(BACKGROUND_WORK_SCHEDULE_STARTUP_DELAY_MS)"))
        assertTrue(applicationSource.contains("BACKGROUND_WORK_SCHEDULE_STARTUP_DELAY_MS = 4_500L"))
        assertFalse(applicationSource.contains("prewarmTrafficMapCountryShapes"))
        assertFalse(applicationSource.contains("TRAFFIC_MAP_PREWARM_STARTUP_DELAY_MS"))
        assertTrue(
            initializationBlock.indexOf("delay(BACKGROUND_WORK_SCHEDULE_STARTUP_DELAY_MS)") <
                initializationBlock.indexOf("applyProfileSecretCleanupSchedule()"),
        )
    }

    @Test
    fun `skeleton blocks expose shared shimmer progress for hot loading groups`() {
        val uiChromeSource = testSourceFile("UiChrome.kt").readText()
        val skeletonBlock =
            uiChromeSource.substringAfter("internal fun FoxholeSkeletonBlock(")
                .substringBefore("val baseColor")

        assertTrue(uiChromeSource.contains("internal fun rememberFoxholeSkeletonProgress(): State<Float>"))
        assertTrue(skeletonBlock.contains("shimmerProgress: State<Float> = rememberFoxholeSkeletonProgress()"))
        assertTrue(uiChromeSource.contains("shimmerProgress.value"))
        assertTrue(uiChromeSource.contains("onDrawWithContent"))
        assertFalse(skeletonBlock.contains("rememberInfiniteTransition("))
    }

    @Test
    fun `network and map loading groups share one skeleton animation`() {
        val homeSource = testSourceFile("HomeScreenSupport.kt").readText()
        val networkLoadingBlock =
            homeSource.substringAfter("internal fun HomeNetworkLoadingBlock(")
                .substringBefore("@Composable\ninternal fun HomeConnectionStatusLoadingBlock")
        val trafficMapSource = testSourceFile("TrafficMapDashboardCard.kt").readText()
        val legendLoadingBlock =
            trafficMapSource.substringAfter("private fun TrafficMapLegendLoadingBlock(")
                .substringBefore("private fun TrafficMapPowerSaveBlock")

        assertTrue(networkLoadingBlock.contains("val shimmerProgress = rememberFoxholeSkeletonProgress()"))
        assertTrue(networkLoadingBlock.contains("shimmerProgress = shimmerProgress"))
        assertTrue(legendLoadingBlock.contains("val shimmerProgress = rememberFoxholeSkeletonProgress()"))
        assertTrue(legendLoadingBlock.contains("shimmerProgress = shimmerProgress"))
    }

    @Test
    fun `traffic map parsing and bitmap rendering stay off shared default dispatcher`() {
        val trafficMapSource = testSourceFile("TrafficMapDashboardCard.kt").readText()

        assertTrue(trafficMapSource.contains("TrafficMapRenderDispatcher.dispatcher"))
        assertTrue(trafficMapSource.contains("Process.THREAD_PRIORITY_BACKGROUND"))
        assertFalse(trafficMapSource.contains("Dispatchers.Default"))
    }

    @Test
    fun `home route defers traffic map state collection to home screen stage`() {
        val appSource = testSourceFile("FoxholeApp.kt").readText()
        val homeRouteBlock =
            appSource.substringAfter("composable(AppRoute.HOME)")
                .substringBefore("composable(AppRoute.PROFILES)")

        assertFalse(homeRouteBlock.contains("trafficMapUiState.collectAsStateWithLifecycle"))
        assertTrue(homeRouteBlock.contains("trafficMapStateFlow = viewModel.trafficMapUiState"))
    }

    @Test
    fun `home route core state is not subscribed to one second traffic ticks`() {
        val viewModelSource = testSourceFile("HomeViewModel.kt").readText()
        val realtimeStreamsBlock =
            viewModelSource.substringAfter("internal val realtimeStreams =")
                .substringBefore("internal val connectionStreams =")
        val coreUiStateBlock =
            viewModelSource.substringAfter("private val coreUiState")
                .substringBefore("internal val controlUiState")

        assertFalse(realtimeStreamsBlock.contains("connectionController.traffic"))
        assertFalse(realtimeStreamsBlock.contains("traffic ="))
        assertFalse(coreUiStateBlock.contains("traffic ="))
        assertTrue(viewModelSource.contains("val dashboardTraffic: StateFlow<TrafficSnapshot> ="))
        assertTrue(viewModelSource.contains("container.connectionController.traffic"))
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
    fun `network card model is not keyed by full route state`() {
        val homeSource = testSourceFile("HomeScreen.kt").readText()
        val networkModelBlock =
            homeSource.substringAfter("val networkModel =")
                .substringBefore("val visibleNetworkIpInfo")

        assertFalse(networkModelBlock.contains("remember(state,"))
        assertTrue(networkModelBlock.contains("state.connection"))
        assertTrue(networkModelBlock.contains("state.ipInfoLoading"))
        assertTrue(networkModelBlock.contains("state.autoConnect.running"))
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
