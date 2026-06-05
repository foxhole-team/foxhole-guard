package com.foxhole.beta.ui

import android.util.DisplayMetrics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrafficMapStylingTest {
    @Test
    fun `traffic map uses theme aware land and semantic route colors in both themes`() {
        val darkSurface = Color(0xFF101011)
        val darkSurfaceVariant = Color(0xFF242629)
        val darkOnSurfaceVariant = Color(0xFFC6CBD1)
        val lightSurface = Color.White
        val lightSurfaceVariant = Color(0xFFE6E8EC)
        val lightOnSurfaceVariant = Color(0xFF62676E)
        val success = Color(0xFF5EE4A1)
        val accent = Color(0xFF8AAED8)
        val darkColors =
            trafficMapColors(
                darkTheme = true,
                surfaceColor = darkSurface,
                surfaceVariantColor = darkSurfaceVariant,
                onSurfaceVariantColor = darkOnSurfaceVariant,
                successColor = success,
                accentColor = accent,
            )
        val lightColors =
            trafficMapColors(
                darkTheme = false,
                surfaceColor = lightSurface,
                surfaceVariantColor = lightSurfaceVariant,
                onSurfaceVariantColor = lightOnSurfaceVariant,
                successColor = success,
                accentColor = accent,
            )

        assertNotEquals(Color.Gray, darkColors.countryFill)
        assertNotEquals(Color.Gray, lightColors.countryFill)
        assertNotEquals(darkColors.countryFill, lightColors.countryFill)
        assertNotEquals(darkColors.countryFill, darkColors.countryBoundary)
        assertEquals(accent, lightColors.countryDestinationHighlight)
        assertEquals(success, lightColors.vpnRoute)
        assertEquals(Color(0xFFFF8A3D), darkColors.torExit)
        assertNotEquals(success, darkColors.destination)
        assertEquals(accent, lightColors.origin)
        assertEquals(darkSurface, darkColors.routeHalo)
        assertEquals(Color.White, lightColors.routeHalo)
        assertEquals(darkOnSurfaceVariant.copy(alpha = 0.88f), darkColors.legendText)
        assertEquals(lightOnSurfaceVariant.copy(alpha = 0.76f), lightColors.inactiveText)
    }

    @Test
    fun `traffic map route lines use readable stroke constants and semantic tokens`() {
        val source =
            listOf(
                java.io.File("src/main/kotlin/com/foxhole/beta/ui/TrafficMapDashboardCard.kt"),
                java.io.File("app/src/main/kotlin/com/foxhole/beta/ui/TrafficMapDashboardCard.kt"),
                java.io.File("../app/src/main/kotlin/com/foxhole/beta/ui/TrafficMapDashboardCard.kt"),
            ).first { file -> file.isFile }.readText()

        assertTrue(source.contains("TRAFFIC_MAP_ROUTE_MIN_STROKE_DP = 0.9f"))
        assertTrue(source.contains("TRAFFIC_MAP_ROUTE_MAX_STROKE_DP = 2.2f"))
        assertTrue(source.contains("TRAFFIC_MAP_ROUTE_MIN_ALPHA = 0.42f"))
        assertTrue(source.contains("TRAFFIC_MAP_ROUTE_ALPHA_RANGE = 0.36f"))
        assertTrue(source.contains("TRAFFIC_MAP_ROUTE_HALO_STROKE_EXTRA_DP = 1.4f"))
        assertTrue(source.contains("TRAFFIC_MAP_ROUTE_HALO_ALPHA_MULTIPLIER = 0.22f"))
        assertTrue(source.contains("val routeNodeRadius = 2.75.dp.toPx()"))
        assertTrue(source.contains("val torNodeRadius = 2.65.dp.toPx()"))
        assertTrue(source.contains("colors.routeHalo.copy("))
        assertTrue(source.contains("when (route.role)"))
        assertTrue(source.contains("TrafficMapEdgeRole.VPN_ROUTE -> colors.vpnRoute"))
        assertTrue(source.contains("TrafficMapEdgeRole.TOR_ROUTE -> colors.torExit"))
        assertTrue(source.contains("successColor = semanticColors.success"))
        assertTrue(source.contains("accentColor = colorScheme.primary"))
        assertTrue(source.contains("surfaceVariantColor = colorScheme.surfaceVariant"))
        assertTrue(source.contains("onSurfaceVariantColor = colorScheme.onSurfaceVariant"))
        assertTrue(source.contains("colors.legendText"))
        assertTrue(source.contains("TRAFFIC_MAP_WEIGHT = 0.62f"))
        assertTrue(source.contains("TRAFFIC_MAP_LEGEND_WEIGHT = 0.38f"))
        assertTrue(source.contains("text = point.label"))
        assertTrue(source.contains("TRAFFIC_MAP_DASHBOARD_TOP_COUNTRIES = 3"))
        assertTrue(source.contains("TrafficMapLegendSummaryRow"))
        assertTrue(source.contains(".widthIn(min = 136.dp, max = 184.dp)"))
        assertTrue(source.contains("Icons.Outlined.PhoneAndroid"))
        assertTrue(source.contains("fontSize = 10.sp"))
        assertFalse(source.contains("fontSize = 8.5.sp"))
    }

    @Test
    fun `traffic map legend bytes stay compact for narrow table cells`() {
        assertEquals("0 B", formatTrafficMapLegendBytes(0L))
        assertEquals("512 B", formatTrafficMapLegendBytes(512L))
        assertEquals("1.0 KB", formatTrafficMapLegendBytes(1_024L))
        assertEquals("12.1 KB", formatTrafficMapLegendBytes(12_345L))
        assertEquals("118 MB", formatTrafficMapLegendBytes(123_456_789L))
    }

    @Test
    fun `traffic map draws land boundaries and highlighted countries before routes`() {
        val source =
            listOf(
                java.io.File("src/main/kotlin/com/foxhole/beta/ui/TrafficMapDashboardCard.kt"),
                java.io.File("app/src/main/kotlin/com/foxhole/beta/ui/TrafficMapDashboardCard.kt"),
                java.io.File("../app/src/main/kotlin/com/foxhole/beta/ui/TrafficMapDashboardCard.kt"),
            ).first { file -> file.isFile }.readText()
        val landBitmapBlock =
            source.substringAfter("private fun trafficMapLandBitmap(")
                .substringBefore("private fun trafficMapCountryHighlightBitmap")
        val highlightBitmapBlock =
            source.substringAfter("private fun trafficMapCountryHighlightBitmap(")
                .substringBefore("private object TrafficMapCountryHighlightLayerCache")
        val canvasBeforeRoutes =
            source.substringAfter("onDrawBehind {")
                .substringBefore("routeDrawModels.forEach")

        assertTrue(landBitmapBlock.contains("AndroidPaint.Style.FILL"))
        assertTrue(landBitmapBlock.contains("AndroidPaint.Style.STROKE"))
        assertTrue(highlightBitmapBlock.contains("dominantTrafficMapCountryVisuals"))
        assertTrue(highlightBitmapBlock.contains("visual.trafficMapHighlightColor(colors)"))
        assertTrue(canvasBeforeRoutes.contains("countryBitmap?.let"))
        assertTrue(canvasBeforeRoutes.contains("countryHighlightBitmap?.let"))
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
        assertTrue(source.contains("TRAFFIC_MAP_HEAVY_CONTENT_SETTLE_DELAY_MS = 0L"))
        assertTrue(source.contains("if (TRAFFIC_MAP_HEAVY_CONTENT_SETTLE_DELAY_MS > 0L)"))
        assertTrue(source.contains("home_traffic_world_map_loading"))
    }

    @Test
    fun `traffic map exposes trace sections for route build draw and cache work`() {
        val source =
            listOf(
                java.io.File("src/main/kotlin/com/foxhole/beta/ui/TrafficMapDashboardCard.kt"),
                java.io.File("app/src/main/kotlin/com/foxhole/beta/ui/TrafficMapDashboardCard.kt"),
                java.io.File("../app/src/main/kotlin/com/foxhole/beta/ui/TrafficMapDashboardCard.kt"),
            ).first { file -> file.isFile }.readText()
        val routeCacheSource =
            listOf(
                java.io.File("src/main/kotlin/com/foxhole/beta/ui/TrafficMapRouteModelCache.kt"),
                java.io.File("app/src/main/kotlin/com/foxhole/beta/ui/TrafficMapRouteModelCache.kt"),
                java.io.File("../app/src/main/kotlin/com/foxhole/beta/ui/TrafficMapRouteModelCache.kt"),
            ).first { file -> file.isFile }.readText()

        listOf(
            "TrafficMap/loadShapes",
            "TrafficMap/renderLandBitmap",
            "TrafficMap/renderHighlightBitmap",
            "TrafficMap/buildRoutes",
            "TrafficMap/draw",
        ).forEach { section ->
            assertTrue(source.contains(section))
        }
        assertTrue(source.contains(".semantics { contentDescription = mapContentDescription }"))
        assertTrue(source.contains("trafficMapContentDescription(state)"))
        assertTrue(source.contains("Trace.beginSection(name)"))
        assertTrue(source.contains("Trace.endSection()"))
        assertTrue(routeCacheSource.contains("internal object TrafficMapRouteModelCache"))
        assertTrue(routeCacheSource.contains("TrafficMapRouteDrawCacheKey("))
        assertTrue(routeCacheSource.contains("val edges: List<DrawableTrafficMapEdge>"))
        assertTrue(routeCacheSource.contains("val viewportSize: IntSize"))
        assertTrue(routeCacheSource.contains("getOrBuild("))
        assertTrue(routeCacheSource.contains("TRAFFIC_MAP_ROUTE_MODEL_CACHE_SIZE = 64"))
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
        assertTrue(englishStrings.contains("Start VPN or enable local guard"))
        assertTrue(englishStrings.contains("<string name=\"traffic_map_route_header\">Route</string>"))
        assertTrue(englishStrings.contains("<string name=\"traffic_map_top_countries_header\">Top countries</string>"))
        assertTrue(russianStrings.contains("<string name=\"traffic_map_live_requires_firewall\">Нет активных подключений</string>"))
        assertTrue(russianStrings.contains("<string name=\"traffic_map_waiting_connections\">Нет активных подключений</string>"))
        assertTrue(russianStrings.contains("<string name=\"traffic_map_route_header\">Маршрут</string>"))
        assertTrue(russianStrings.contains("<string name=\"traffic_map_top_countries_header\">Топ стран</string>"))
        assertFalse(englishStrings.contains("Waiting for active firewall"))
        assertFalse(russianStrings.contains("Ожидание активного фаервола"))
    }
}
