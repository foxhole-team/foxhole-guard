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
        assertFalse(
            shouldComposeDashboardCardNow(
                card = DashboardCard.TRAFFIC_MAP,
                startupStage = 2,
                activeReorderCard = null,
            ),
        )
        assertFalse(
            shouldComposeDashboardCardNow(
                card = DashboardCard.PROFILES,
                startupStage = 2,
                activeReorderCard = null,
            ),
        )
        assertTrue(
            shouldComposeDashboardCardNow(
                card = DashboardCard.ACTIONS,
                startupStage = 2,
                activeReorderCard = null,
            ),
        )
        assertTrue(
            shouldComposeDashboardCardNow(
                card = DashboardCard.PROFILES,
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
                card = DashboardCard.TRAFFIC_MAP,
                startupStage = 4,
                activeReorderCard = null,
            ),
        )
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
    fun `dashboard cold entry stages content but warm return keeps layout stable`() {
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
                card = DashboardCard.NETWORK,
                startupStage = warmStage,
                activeReorderCard = null,
            ),
        )
        assertTrue(
            shouldComposeDashboardCardNow(
                card = DashboardCard.ACTIONS,
                startupStage = warmStage,
                activeReorderCard = null,
            ),
        )
        assertTrue(
            shouldComposeDashboardCardNow(
                card = DashboardCard.PROFILES,
                startupStage = warmStage,
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
        assertFalse(
            shouldComposeTrafficMapHeavyContent(
                startupStage = 3,
                activeReorderCard = null,
            ),
        )
        assertTrue(
            shouldComposeTrafficMapHeavyContent(
                startupStage = 4,
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
    fun `traffic map heavy content has no artificial root navigation settle delay`() {
        val homeSource = testSourceFile("HomeScreen.kt").readText()
        val trafficMapSource = testSourceFile("TrafficMapDashboardCard.kt").readText()

        assertFalse(homeSource.contains("DASHBOARD_CARD_STARTUP_STAGE_DELAY_MS"))
        assertTrue(trafficMapSource.contains("TRAFFIC_MAP_HEAVY_CONTENT_SETTLE_DELAY_MS = 0L"))
        assertTrue(trafficMapSource.contains("if (TRAFFIC_MAP_HEAVY_CONTENT_SETTLE_DELAY_MS > 0L)"))
    }

    @Test
    fun `settings home has no artificial startup staging delay`() {
        val settingsSource = testSourceFile("SettingsScreens.kt").readText()

        assertFalse(settingsSource.contains("SETTINGS_HOME_STARTUP_STAGE_DELAY_MS"))
        assertTrue(settingsSource.contains("withFrameNanos"))
    }

    @Test
    fun `dashboard and settings roots use separate routes without animated double composition`() {
        val appSource = testSourceFile("FoxholeApp.kt").readText()
        val homeRouteBlock =
            appSource.substringAfter("composable(AppRoute.HOME)")
                .substringBefore("composable(AppRoute.PROFILES)")
        val settingsRouteBlock =
            appSource.substringAfter("composable(AppRoute.SETTINGS)")
                .substringBefore("composable(AppRoute.SMART_START)")

        assertTrue(homeRouteBlock.contains("HomeScreen("))
        assertFalse(homeRouteBlock.contains("SettingsHomeScreen("))
        assertTrue(settingsRouteBlock.contains("SettingsHomeScreen("))
        assertFalse(settingsRouteBlock.contains("\n                    HomeScreen("))
        assertFalse(appSource.contains("rootSection"))
        assertFalse(homeRouteBlock.contains("AnimatedContent("))
        assertFalse(homeRouteBlock.contains("label = \"root-section-transition\""))
        assertFalse(homeRouteBlock.contains("detailForwardEnter() togetherWith detailForwardExit()"))
        assertFalse(homeRouteBlock.contains("detailBackEnter() togetherWith detailBackExit()"))
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
    fun `vpn permission request enqueue does not overwrite active pending request`() {
        val viewModelSource = testSourceFile("HomeViewModel.kt").readText()
        val enqueueBlock =
            viewModelSource.substringAfter("internal fun enqueueVpnPermissionRequest")
                .substringBefore("private fun drainPendingLocalGuardPermissionSync")

        assertTrue(enqueueBlock.contains("val active = pendingConnectRequest"))
        assertTrue(enqueueBlock.contains("if (active != null)"))
        assertTrue(enqueueBlock.contains("return false"))
        assertTrue(enqueueBlock.contains("pendingConnectRequest = request"))
        assertTrue(enqueueBlock.contains("return true"))
        listOf(
            "HomeViewModelAutoConnectSupport.kt",
            "HomeViewModelConnectionToggleSupport.kt",
            "HomeViewModelSettingsSupport.kt",
        ).forEach { fileName ->
            assertFalse(testSourceFile(fileName).readText().contains("pendingConnectRequest ="))
        }
    }

    @Test
    fun `local guard permission sync defers when another vpn request is pending`() {
        val settingsSource = testSourceFile("HomeViewModelSettingsSupport.kt").readText()
        val syncBlock =
            settingsSource.substringAfter("internal suspend fun HomeViewModel.syncLocalGuardWithPermissionRequest()")
                .substringBefore("internal fun shouldDeferLocalGuardSyncForActiveProfileRuntime")
        val permissionBlock =
            testSourceFile("HomeViewModel.kt").readText()
                .substringAfter("fun onVpnPermissionResult(granted: Boolean)")
                .substringBefore("fun onRefreshProfile()")

        assertTrue(syncBlock.contains("val accepted ="))
        assertTrue(syncBlock.contains("enqueueVpnPermissionRequest"))
        assertTrue(
            syncBlock.contains(
                "if (!accepted && pendingConnectRequest?.action != PendingConnectAction.LOCAL_GUARD)",
            ),
        )
        assertTrue(syncBlock.contains("pendingLocalGuardPermissionSync = true"))
        assertTrue(permissionBlock.contains("drainPendingLocalGuardPermissionSync()"))
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
    fun `profile raw import validates payload size off main dispatcher`() {
        val source = testSourceFile("HomeViewModelProfileImportSupport.kt").readText()
        val importBlock =
            source.substringAfter("internal fun HomeViewModel.importProfileRawInternal(value: String)")
                .substringBefore("internal fun HomeViewModel.importRawInternal(value: String)")

        assertTrue(importBlock.contains("viewModelScope.launch"))
        assertTrue(importBlock.contains("withContext(Dispatchers.Default)"))
        assertFalse(importBlock.substringBefore("viewModelScope.launch").contains("isBlank"))
        assertTrue(
            importBlock.indexOf("viewModelScope.launch") <
                importBlock.indexOf("withContext(Dispatchers.Default)"),
        )
        assertTrue(
            importBlock.indexOf("withContext(Dispatchers.Default)") <
                importBlock.indexOf("takeUnless(String::isBlank)"),
        )
        assertTrue(
            importBlock.indexOf("withContext(Dispatchers.Default)") <
                importBlock.indexOf("::requireLocalProfileImportWithinLimit"),
        )
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
        assertTrue(uiChromeSource.contains("internal fun foxholeSkeletonShimmerEnabled(): Boolean"))
        assertTrue(uiChromeSource.contains("PowerManager"))
        assertTrue(uiChromeSource.contains("Settings.Global.ANIMATOR_DURATION_SCALE"))
        assertTrue(uiChromeSource.contains("remember { mutableFloatStateOf(0f) }"))
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
        assertTrue(legendLoadingBlock.contains("val skeletonRow"))
        assertTrue(legendLoadingBlock.contains("skeletonRow(false, false)"))
        assertTrue(legendLoadingBlock.contains("skeletonRow(true, false)"))
        assertTrue(legendLoadingBlock.contains("skeletonRow(true, true)"))
        assertFalse(legendLoadingBlock.contains("padding(top = 24.dp)"))
    }

    @Test
    fun `traffic map header keeps device location slot during transient origin refresh`() {
        val trafficMapSource = testSourceFile("TrafficMapDashboardCard.kt").readText()
        val headerBlock =
            trafficMapSource.substringAfter("private fun TrafficMapCardHeader(")
                .substringBefore("@Composable\nprivate fun rememberTrafficMapHeavyContentReady")

        assertTrue(headerBlock.contains("retainedOriginLabel"))
        assertTrue(headerBlock.contains("Modifier.width(86.dp).height(8.dp)"))
        assertTrue(headerBlock.contains("Modifier.width(62.dp).height(8.dp)"))
        assertFalse(headerBlock.contains("if (state.originCountryCode != null)"))
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
    fun `dashboard traffic flow suppresses sampled at only runtime ticks`() {
        val viewModelSource = testSourceFile("HomeViewModel.kt").readText()
        val dashboardTrafficBlock =
            viewModelSource.substringAfter("val dashboardTraffic: StateFlow<TrafficSnapshot> =")
                .substringBefore("val uiState:")

        assertTrue(dashboardTrafficBlock.contains("container.connectionController.traffic"))
        assertTrue(dashboardTrafficBlock.contains("hasSameDashboardTrafficContentAs"))
        assertTrue(dashboardTrafficBlock.contains("stateIn("))
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
