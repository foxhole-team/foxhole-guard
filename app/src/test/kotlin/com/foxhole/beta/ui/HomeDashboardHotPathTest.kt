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
    fun `traffic map heavy content has no extra dashboard return delay`() {
        val homeSource = testSourceFile("HomeScreen.kt").readText()

        assertTrue(homeSource.contains("DASHBOARD_CARD_STARTUP_STAGE_DELAY_MS = 32L"))
        assertFalse(homeSource.contains("TRAFFIC_MAP_HEAVY_CONTENT_STARTUP_DELAY_MS"))
        assertFalse(homeSource.contains("DASHBOARD_CARD_STARTUP_STAGE_DELAY_MS = 48L"))
        assertFalse(homeSource.contains("TRAFFIC_MAP_HEAVY_CONTENT_STARTUP_DELAY_MS = 220L"))
        assertFalse(homeSource.contains("TRAFFIC_MAP_HEAVY_CONTENT_STARTUP_DELAY_MS = 650L"))
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
    fun `home route defers traffic map state collection to home screen stage`() {
        val appSource = testSourceFile("FoxholeApp.kt").readText()
        val homeRouteBlock =
            appSource.substringAfter("composable(AppRoute.HOME)")
                .substringBefore("composable(AppRoute.PROFILES)")

        assertFalse(homeRouteBlock.contains("trafficMapUiState.collectAsStateWithLifecycle"))
        assertTrue(homeRouteBlock.contains("trafficMapStateFlow = viewModel.trafficMapUiState"))
    }

    private fun testSourceFile(name: String): java.io.File =
        listOf(
            java.io.File("src/main/kotlin/com/foxhole/beta/ui/$name"),
            java.io.File("app/src/main/kotlin/com/foxhole/beta/ui/$name"),
            java.io.File("../app/src/main/kotlin/com/foxhole/beta/ui/$name"),
        ).first { file -> file.isFile }
}
