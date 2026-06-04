package com.foxhole.beta.ui

import android.util.DisplayMetrics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrafficMapStylingTest {
    @Test
    fun `traffic map uses dark gray land and translucent route colors in both themes`() {
        val darkColors = trafficMapColors(darkTheme = true, surfaceColor = Color(0xFF101011))
        val lightColors = trafficMapColors(darkTheme = false, surfaceColor = Color.White)

        assertEquals(
            Color(0xFF3E3F41),
            darkColors.countryFill,
        )
        assertEquals(
            Color(0xFF414345),
            lightColors.countryFill,
        )
        assertEquals(
            Color(0xFF8CE8B3),
            lightColors.routeLine,
        )
        assertEquals(
            Color(0xFF8CE8B3),
            darkColors.destination,
        )
        assertEquals(
            Color(0xFF8CE8B3),
            lightColors.origin,
        )
        assertEquals(Color(0xFF050606), darkColors.routeHalo)
        assertEquals(Color.White, lightColors.routeHalo)
    }

    @Test
    fun `traffic map route lines use thin translucent stroke constants`() {
        val source =
            listOf(
                java.io.File("src/main/kotlin/com/foxhole/beta/ui/TrafficMapDashboardCard.kt"),
                java.io.File("app/src/main/kotlin/com/foxhole/beta/ui/TrafficMapDashboardCard.kt"),
                java.io.File("../app/src/main/kotlin/com/foxhole/beta/ui/TrafficMapDashboardCard.kt"),
            ).first { file -> file.isFile }.readText()

        assertTrue(source.contains("TRAFFIC_MAP_ROUTE_MIN_STROKE_DP = 0.35f"))
        assertTrue(source.contains("TRAFFIC_MAP_ROUTE_MAX_STROKE_DP = 0.72f"))
        assertTrue(source.contains("TRAFFIC_MAP_ROUTE_MIN_ALPHA = 0.32f"))
        assertTrue(source.contains("TRAFFIC_MAP_ROUTE_ALPHA_RANGE = 0.18f"))
        assertTrue(source.contains("TRAFFIC_MAP_ROUTE_HALO_STROKE_EXTRA_DP = 0.2f"))
        assertTrue(source.contains("TRAFFIC_MAP_ROUTE_HALO_ALPHA_MULTIPLIER = 0.08f"))
        assertTrue(source.contains("colors.routeHalo.copy("))
        assertTrue(source.contains("colors.routeLine.copy(alpha = route.alpha)"))
        assertTrue(source.contains("TRAFFIC_MAP_ROUTE_GREEN"))
    }

    @Test
    fun `traffic map draws only filled land before routes`() {
        val source =
            listOf(
                java.io.File("src/main/kotlin/com/foxhole/beta/ui/TrafficMapDashboardCard.kt"),
                java.io.File("app/src/main/kotlin/com/foxhole/beta/ui/TrafficMapDashboardCard.kt"),
                java.io.File("../app/src/main/kotlin/com/foxhole/beta/ui/TrafficMapDashboardCard.kt"),
            ).first { file -> file.isFile }.readText()
        val landBitmapBlock =
            source.substringAfter("private fun trafficMapLandBitmap(")
                .substringBefore("private object TrafficMapLandLayerCache")
        val canvasBeforeRoutes =
            source.substringAfter("onDrawBehind {")
                .substringBefore("routeDrawModels.forEach")

        assertTrue(landBitmapBlock.contains("AndroidPaint.Style.FILL"))
        assertFalse(landBitmapBlock.contains("AndroidPaint.Style.STROKE"))
        assertTrue(canvasBeforeRoutes.contains("countryBitmap?.let"))
        assertFalse(canvasBeforeRoutes.contains("drawRect"))
        assertFalse(canvasBeforeRoutes.contains("drawRoundRect"))
        assertFalse(canvasBeforeRoutes.contains("drawLine"))
    }

    @Test
    fun `traffic map country shapes load without card local startup delay`() {
        val source =
            listOf(
                java.io.File("src/main/kotlin/com/foxhole/beta/ui/TrafficMapDashboardCard.kt"),
                java.io.File("app/src/main/kotlin/com/foxhole/beta/ui/TrafficMapDashboardCard.kt"),
                java.io.File("../app/src/main/kotlin/com/foxhole/beta/ui/TrafficMapDashboardCard.kt"),
            ).first { file -> file.isFile }.readText()
        val shapeLoadBlock =
            source.substringAfter("private fun rememberTrafficMapCountryShapes()")
                .substringBefore("internal suspend fun prewarmTrafficMapCountryShapes")

        assertFalse(shapeLoadBlock.contains("delay("))
        assertFalse(source.contains("TRAFFIC_MAP_COUNTRY_SHAPES_CARD_LOAD_DELAY_MS"))
    }

    @Test
    fun `traffic map startup prewarms nearby dark land bitmap buckets`() {
        val source =
            listOf(
                java.io.File("src/main/kotlin/com/foxhole/beta/ui/TrafficMapDashboardCard.kt"),
                java.io.File("app/src/main/kotlin/com/foxhole/beta/ui/TrafficMapDashboardCard.kt"),
                java.io.File("../app/src/main/kotlin/com/foxhole/beta/ui/TrafficMapDashboardCard.kt"),
            ).first { file -> file.isFile }.readText()
        val appPrewarmBlock =
            source.substringAfter("internal suspend fun prewarmTrafficMapCountryShapes")
                .substringBefore("private object TrafficMapCountryShapeCache")
        val shapeCacheLoadBlock =
            source.substringAfter("suspend fun load(context: Context): List<TrafficMapCountryShape>")
                .substringBefore("private data class DrawableTrafficMapDestination")
        val landCacheBlock =
            source.substringAfter("private object TrafficMapLandLayerCache")
                .substringBefore("private data class TrafficMapLandLayerKey")

        assertTrue(appPrewarmBlock.contains("TrafficMapLandLayerCache.prewarm"))
        assertTrue(shapeCacheLoadBlock.contains("context.assets.open(TRAFFIC_MAP_COUNTRY_SHAPES_ASSET).use"))
        assertTrue(shapeCacheLoadBlock.contains("TrafficMapCountryShapeAssetParser().parse(inputStream)"))
        assertFalse(shapeCacheLoadBlock.contains("readText()"))
        assertTrue(landCacheBlock.contains("suspend fun prewarm"))
        assertTrue(landCacheBlock.contains("trafficMapPrimaryPrewarmCanvasSize(displayMetrics)"))
        assertFalse(landCacheBlock.contains("TRAFFIC_MAP_DEFERRED_PREWARM_DELAY_MS"))
        assertFalse(landCacheBlock.contains("delay("))
        assertTrue(landCacheBlock.contains("TRAFFIC_MAP_DEFAULT_COUNTRY_FILL"))
        assertTrue(source.contains("trafficMapPrewarmCanvasSizes"))
        assertTrue(source.contains("trafficMapPrimaryPrewarmCanvasSize"))
        assertTrue(source.contains("TRAFFIC_MAP_PREWARM_COMPACT_WIDTH_FRACTION"))
        assertTrue(source.contains("TRAFFIC_MAP_PREWARM_PRIMARY_WIDTH_FRACTION"))
        assertTrue(source.contains("TRAFFIC_MAP_PREWARM_WIDE_WIDTH_FRACTION"))
        assertTrue(source.contains(".distinct()"))
    }

    @Test
    fun `traffic map prewarm sizes cover compact primary and wide viewport buckets`() {
        val sizes =
            trafficMapPrewarmCanvasSizes(
                DisplayMetrics().apply {
                    widthPixels = 1440
                    heightPixels = 3120
                },
            )

        assertEquals(
            listOf(
                IntSize(width = 864, height = 448),
                IntSize(width = 960, height = 480),
                IntSize(width = 1088, height = 544),
            ),
            sizes,
        )
        assertEquals(
            IntSize(width = 960, height = 480),
            trafficMapPrimaryPrewarmCanvasSize(
                DisplayMetrics().apply {
                    widthPixels = 1440
                    heightPixels = 3120
                },
            ),
        )
    }

    @Test
    fun `application does not prewarm traffic map during cold startup`() {
        val source =
            listOf(
                java.io.File("src/main/kotlin/com/foxhole/beta/FoxholeApplication.kt"),
                java.io.File("app/src/main/kotlin/com/foxhole/beta/FoxholeApplication.kt"),
                java.io.File("../app/src/main/kotlin/com/foxhole/beta/FoxholeApplication.kt"),
            ).first { file -> file.isFile }.readText()

        assertFalse(source.contains("prewarmTrafficMapCountryShapes"))
        assertFalse(source.contains("TRAFFIC_MAP_PREWARM_STARTUP_DELAY_MS"))
    }

    @Test
    fun `traffic map land layer cache keys use visible viewport size`() {
        assertEquals(
            IntSize(width = 600, height = 300),
            trafficMapLandLayerBitmapSize(IntSize(width = 900, height = 300)),
        )
        assertEquals(
            IntSize(width = 500, height = 250),
            trafficMapLandLayerBitmapSize(IntSize(width = 500, height = 500)),
        )
        assertEquals(
            IntSize.Zero,
            trafficMapLandLayerBitmapSize(IntSize.Zero),
        )
    }

    @Test
    fun `traffic map keeps collected state and cached shapes while content settles`() {
        val source =
            listOf(
                java.io.File("src/main/kotlin/com/foxhole/beta/ui/TrafficMapDashboardCard.kt"),
                java.io.File("app/src/main/kotlin/com/foxhole/beta/ui/TrafficMapDashboardCard.kt"),
                java.io.File("../app/src/main/kotlin/com/foxhole/beta/ui/TrafficMapDashboardCard.kt"),
            ).first { file -> file.isFile }.readText()
        val cardBlock =
            source.substringAfter("internal fun TrafficMapDashboardCard(")
                .substringBefore("@Composable\nprivate fun TrafficMapCanvasLoadingBlock")

        assertTrue(cardBlock.contains("contentReady: Boolean = true"))
        assertTrue(cardBlock.contains("val state by stateFlow.collectAsStateWithLifecycle()"))
        assertFalse(cardBlock.contains("remember { TrafficMapUiState() }"))
        assertTrue(cardBlock.contains("rememberTrafficMapHeavyContentReady(contentReady && !mapDisabledForPower)"))
        assertTrue(cardBlock.contains("if (heavyContentReady)"))
        assertTrue(cardBlock.contains("val countryShapesLoading = heavyContentReady && countryShapes.isEmpty()"))
        assertTrue(cardBlock.contains("} else if (!heavyContentReady || countryShapesLoading)"))
        assertTrue(cardBlock.contains("TrafficMapCanvasLoadingBlock"))
        assertTrue(source.contains("mutableStateOf(enabled && TrafficMapCountryShapeCache.current().isNotEmpty())"))
        assertTrue(source.contains("if (TrafficMapCountryShapeCache.current().isNotEmpty())"))
        assertTrue(source.contains("delay(TRAFFIC_MAP_HEAVY_CONTENT_SETTLE_DELAY_MS)"))
        assertTrue(source.contains("home_traffic_world_map_loading"))
    }

    @Test
    fun `traffic map empty state copy reports no active connections`() {
        val englishStrings =
            listOf(
                java.io.File("src/main/res/values/strings.xml"),
                java.io.File("app/src/main/res/values/strings.xml"),
                java.io.File("../app/src/main/res/values/strings.xml"),
            ).first { file -> file.isFile }.readText()
        val russianStrings =
            listOf(
                java.io.File("src/main/res/values-ru/strings.xml"),
                java.io.File("app/src/main/res/values-ru/strings.xml"),
                java.io.File("../app/src/main/res/values-ru/strings.xml"),
            ).first { file -> file.isFile }.readText()

        assertTrue(englishStrings.contains("<string name=\"traffic_map_live_requires_firewall\">No active connections</string>"))
        assertTrue(englishStrings.contains("<string name=\"traffic_map_waiting_connections\">No active connections</string>"))
        assertTrue(russianStrings.contains("<string name=\"traffic_map_live_requires_firewall\">Нет активных подключений</string>"))
        assertTrue(russianStrings.contains("<string name=\"traffic_map_waiting_connections\">Нет активных подключений</string>"))
        assertFalse(englishStrings.contains("Waiting for active firewall"))
        assertFalse(russianStrings.contains("Ожидание активного фаервола"))
    }
}
