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
        assertEquals(TrafficMapTokens.TorExit, darkColors.torExit)
        assertNotEquals(success, darkColors.destination)
        assertEquals(accent, lightColors.origin)
        assertEquals(darkSurface, darkColors.routeHalo)
        assertEquals(Color.White, lightColors.routeHalo)
        assertEquals(
            darkOnSurfaceVariant.copy(alpha = TrafficMapTokens.DarkLegendTextAlpha),
            darkColors.legendText,
        )
        assertEquals(
            lightOnSurfaceVariant.copy(alpha = TrafficMapTokens.LightInactiveTextAlpha),
            lightColors.inactiveText,
        )
    }

    @Test
    fun `traffic map route lines use readable stroke constants and semantic tokens`() {
        val source =
            listOf(
                java.io.File("src/main/kotlin/com/foxhole/beta/ui/TrafficMapDashboardCard.kt"),
                java.io.File("app/src/main/kotlin/com/foxhole/beta/ui/TrafficMapDashboardCard.kt"),
                java.io.File("../app/src/main/kotlin/com/foxhole/beta/ui/TrafficMapDashboardCard.kt"),
            ).first { file -> file.isFile }.readText()

        assertEquals(0.9f, TrafficMapTokens.RouteMinStrokeDp, 0.0f)
        assertEquals(2.2f, TrafficMapTokens.RouteMaxStrokeDp, 0.0f)
        assertEquals(0.42f, TrafficMapTokens.RouteMinAlpha, 0.0f)
        assertEquals(0.36f, TrafficMapTokens.RouteAlphaRange, 0.0f)
        assertEquals(1.4f, TrafficMapTokens.RouteHaloStrokeExtraDp, 0.0f)
        assertEquals(0.22f, TrafficMapTokens.RouteHaloAlphaMultiplier, 0.0f)
        assertEquals(Color(0xFFFAFAFA), TrafficMapTokens.LightRouteLine)
        assertEquals(Color(0xFFFF8A3D), TrafficMapTokens.TorExit)
        assertEquals(Color(0xFF3E3F41), TrafficMapTokens.DefaultCountryFill)
        assertEquals(Color(0xFF5B5D61), TrafficMapTokens.DefaultCountryBoundary)
        assertEquals(216f, TrafficMapTokens.DashboardCardTotalHeight.value, 0.0f)
        assertEquals(136f, TrafficMapTokens.HeaderOriginMinWidthDp, 0.0f)
        assertEquals(184f, TrafficMapTokens.HeaderOriginMaxWidthDp, 0.0f)
        assertTrue(source.contains("TrafficMapRoutePresentation.Expanded"))
        assertTrue(source.contains("TrafficMapTokens.RouteMinStrokeDp"))
        assertTrue(source.contains("TrafficMapTokens.RouteMaxStrokeDp"))
        assertTrue(source.contains("TrafficMapTokens.RouteMinAlpha"))
        assertTrue(source.contains("TrafficMapTokens.RouteAlphaRange"))
        assertTrue(source.contains("TrafficMapTokens.RouteHaloStrokeExtraDp"))
        assertTrue(source.contains("TrafficMapTokens.RouteHaloAlphaMultiplier"))
        assertTrue(source.contains("TrafficMapTokens.ExpandedRouteMinStrokeDp"))
        assertTrue(source.contains("TrafficMapTokens.ExpandedRouteMaxStrokeDp"))
        assertTrue(source.contains("TrafficMapTokens.RouteDirectionArrowSizeDp"))
        assertTrue(source.contains("TrafficMapTokens.DetailRouteDirectionArrowSizeDp"))
        assertTrue(source.contains("TrafficMapTokens.RoutePulseRadiusDp"))
        assertTrue(source.contains("rememberTrafficMapRouteMotionEnabled"))
        assertTrue(source.contains("Settings.Global.ANIMATOR_DURATION_SCALE"))
        assertTrue(source.contains("TrafficMapTokens.RouteNodeRadiusDp"))
        assertTrue(source.contains("TrafficMapTokens.TorNodeRadiusDp"))
        assertTrue(source.contains("TrafficMapTokens.HeaderOriginMinWidthDp"))
        assertTrue(source.contains("TrafficMapTokens.HeaderOriginMaxWidthDp"))
        assertTrue(source.contains("TrafficMapTokens.MarkerClusterBadgeLiftDp"))
        assertTrue(source.contains("TrafficMapTokens.MarkerTouchTargetDp"))
        assertTrue(source.contains("TrafficMapTokens.MarkerCalloutWidthDp"))
        assertTrue(source.contains("TrafficMapTokens.DefaultCountryFill"))
        assertTrue(source.contains("TrafficMapTokens.DefaultCountryBoundary"))
        assertTrue(source.contains("TrafficMapTokens.TorExit"))
        assertTrue(source.contains("colors.routeHalo.copy("))
        assertTrue(source.contains("trafficRouteGeometry("))
        assertTrue(source.contains("drawTrafficMapRouteDirectionCue("))
        assertTrue(source.contains("drawTrafficMapRoutePulse("))
        assertTrue(source.contains("trafficMapRouteColor(route.role, colors)"))
        assertTrue(source.contains("TrafficMapEdgeRole.VPN_ROUTE -> colors.vpnRoute"))
        assertTrue(source.contains("TrafficMapEdgeRole.TOR_ROUTE -> colors.torExit"))
        assertTrue(source.contains("resolveTrafficMapMarkerPlacements("))
        assertTrue(source.contains("TrafficMapTokens.MarkerCollisionDistancePx"))
        assertTrue(source.contains("TrafficMapTokens.TorRouteDashDp"))
        assertTrue(source.contains("PathEffect.dashPathEffect"))
        assertTrue(source.contains("rememberTrafficMapTorRouteDashPhase"))
        assertTrue(source.contains("phase = routeMotionPhase"))
        assertTrue(source.contains("trafficMapSmallCountryCallouts("))
        assertTrue(source.contains("TrafficMapTokens.SmallCountryCalloutMaxAreaPx"))
        assertTrue(source.contains("requiresProjectedCallout("))
        assertTrue(source.contains("TrafficMapMarkerSemanticsLayer"))
        assertTrue(source.contains("TrafficMapMarkerCallout"))
        assertTrue(source.contains("TrafficMapMarkerClusterBadges("))
        assertTrue(source.contains("clusterCount"))
        assertTrue(source.contains(".combinedClickable("))
        assertTrue(source.contains("onClick = { onMarkerSelected(target) }"))
        assertTrue(source.contains("onLongClick = { onMarkerSelected(target) }"))
        assertTrue(source.contains("contentDescription = target.contentDescription"))
        assertTrue(source.contains("TrafficMapDetailControls("))
        assertTrue(source.contains("TrafficMapDetailSort.TOTAL"))
        assertTrue(source.contains("TrafficMapPeriod.FIVE_MINUTES"))
        assertTrue(source.contains("TrafficMapPeriod.SESSION"))
        assertTrue(source.contains("TrafficMapPeriod.DAY_24"))
        assertTrue(source.contains("TrafficMapDetailFilter.VPN"))
        assertTrue(source.contains("TrafficMapDetailFilter.TOR"))
        assertTrue(source.contains("TrafficMapDetailFilter.DIRECT"))
        assertTrue(source.contains("TrafficMapDetailFilter.NEW"))
        assertTrue(source.contains("TrafficMapCountryVisual::isNewCountry"))
        assertTrue(source.contains("trafficMapDetailPoints("))
        assertTrue(source.contains("TrafficMapCountryDetailBottomSheet("))
        assertTrue(source.contains("traffic_map_country_detail_sheet"))
        assertTrue(source.contains("traffic_map_country_detail_apps"))
        assertTrue(source.contains("traffic_map_country_detail_hosts"))
        assertTrue(source.contains("traffic_map_status_label"))
        assertTrue(source.contains("trafficMapSampleWindowLabel("))
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
        assertTrue(source.contains("TrafficMapTokens.HeaderOriginMinWidthDp"))
        assertTrue(source.contains("TrafficMapTokens.HeaderOriginMaxWidthDp"))
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
            source.substringAfter("private fun rememberTrafficMapCountryShapes(): TrafficMapShapeLoadState")
                .substringBefore("internal suspend fun prewarmTrafficMapCountryShapes")

        assertFalse(shapeLoadBlock.contains("delay("))
        assertTrue(shapeLoadBlock.contains("TrafficMapShapeLoadState.Loading"))
        assertTrue(shapeLoadBlock.contains("TrafficMapShapeLoadState.Error"))
        assertTrue(shapeLoadBlock.contains("catch (error: IOException)"))
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
        assertTrue(landCacheBlock.contains("TrafficMapTokens.DefaultCountryFill"))
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
        assertTrue(
            cardBlock.contains(
                "val countryShapesLoading = heavyContentReady && shapeLoadState is TrafficMapShapeLoadState.Loading",
            ),
        )
        assertTrue(
            cardBlock.contains(
                "val countryShapesError = heavyContentReady && shapeLoadState is TrafficMapShapeLoadState.Error",
            ),
        )
        assertTrue(cardBlock.contains("} else if (countryShapesError)"))
        assertTrue(cardBlock.contains("} else if (!heavyContentReady || countryShapesLoading)"))
        assertTrue(cardBlock.contains("TrafficMapCanvasLoadingBlock"))
        assertTrue(cardBlock.contains("home_traffic_world_map_error"))
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
    fun `traffic map empty state copy separates unavailable waiting and loading`() {
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
        val mapSource =
            listOf(
                java.io.File("src/main/kotlin/com/foxhole/beta/ui/TrafficMapDashboardCard.kt"),
                java.io.File("app/src/main/kotlin/com/foxhole/beta/ui/TrafficMapDashboardCard.kt"),
                java.io.File("../app/src/main/kotlin/com/foxhole/beta/ui/TrafficMapDashboardCard.kt"),
            ).first { file -> file.isFile }.readText()
        val modelSource =
            listOf(
                java.io.File("src/main/kotlin/com/foxhole/beta/core/model/TrafficMapModels.kt"),
                java.io.File("app/src/main/kotlin/com/foxhole/beta/core/model/TrafficMapModels.kt"),
                java.io.File("../app/src/main/kotlin/com/foxhole/beta/core/model/TrafficMapModels.kt"),
            ).first { file -> file.isFile }.readText()
        val repositorySource =
            listOf(
                java.io.File("src/main/kotlin/com/foxhole/beta/core/traffic/TrafficMapRepository.kt"),
                java.io.File("app/src/main/kotlin/com/foxhole/beta/core/traffic/TrafficMapRepository.kt"),
                java.io.File("../app/src/main/kotlin/com/foxhole/beta/core/traffic/TrafficMapRepository.kt"),
            ).first { file -> file.isFile }.readText()

        assertTrue(
            englishStrings.contains(
                "<string name=\"traffic_map_live_requires_firewall\">Traffic map unavailable</string>",
            ),
        )
        assertTrue(englishStrings.contains("traffic_map_live_requires_firewall_helper"))
        assertTrue(englishStrings.contains("<string name=\"traffic_map_waiting_connections\">No active connections</string>"))
        assertTrue(englishStrings.contains("Start VPN or enable local guard"))
        assertTrue(englishStrings.contains("<string name=\"traffic_map_loading\">Loading traffic map</string>"))
        assertTrue(englishStrings.contains("<string name=\"traffic_map_shape_error\">Map data unavailable</string>"))
        assertTrue(englishStrings.contains("traffic_map_shape_error_helper"))
        assertTrue(englishStrings.contains("<string name=\"traffic_map_status_waiting\">Waiting</string>"))
        assertTrue(englishStrings.contains("<string name=\"traffic_map_status_unavailable\">Unavailable</string>"))
        assertTrue(englishStrings.contains("<string name=\"traffic_map_route_header\">Route</string>"))
        assertTrue(englishStrings.contains("<string name=\"traffic_map_top_countries_header\">Top countries</string>"))
        assertTrue(
            englishStrings.contains(
                "<string name=\"traffic_map_open_details\">Open traffic map details</string>",
            ),
        )
        assertTrue(
            russianStrings.contains(
                "<string name=\"traffic_map_live_requires_firewall\">Карта трафика недоступна</string>",
            ),
        )
        assertTrue(russianStrings.contains("traffic_map_live_requires_firewall_helper"))
        assertTrue(russianStrings.contains("<string name=\"traffic_map_waiting_connections\">Нет активных подключений</string>"))
        assertTrue(russianStrings.contains("<string name=\"traffic_map_loading\">Загрузка карты трафика</string>"))
        assertTrue(russianStrings.contains("<string name=\"traffic_map_shape_error\">Данные карты недоступны</string>"))
        assertTrue(russianStrings.contains("traffic_map_shape_error_helper"))
        assertTrue(russianStrings.contains("<string name=\"traffic_map_status_waiting\">Ожидание</string>"))
        assertTrue(russianStrings.contains("<string name=\"traffic_map_status_unavailable\">Недоступно</string>"))
        assertTrue(russianStrings.contains("<string name=\"traffic_map_route_header\">Маршрут</string>"))
        assertTrue(russianStrings.contains("<string name=\"traffic_map_top_countries_header\">Топ стран</string>"))
        assertTrue(
            russianStrings.contains(
                "<string name=\"traffic_map_open_details\">Открыть детали карты трафика</string>",
            ),
        )
        assertFalse(englishStrings.contains("Waiting for active firewall"))
        assertFalse(russianStrings.contains("Ожидание активного фаервола"))
        assertTrue(mapSource.contains("R.string.traffic_map_live_requires_firewall_helper"))
        assertTrue(mapSource.contains("R.string.traffic_map_status_waiting"))
        assertTrue(mapSource.contains("R.string.traffic_map_status_unavailable"))
        assertTrue(mapSource.contains("contentDescription = loadingDescription"))
        assertTrue(mapSource.contains("TrafficMapShapeErrorBlock"))
        assertTrue(mapSource.contains("R.string.traffic_map_shape_error"))
        assertTrue(mapSource.contains("R.string.traffic_map_shape_error_helper"))
        assertTrue(modelSource.contains("val sampleWindowLabel: String = \"Waiting\""))
        assertTrue(repositorySource.contains("TRAFFIC_MAP_STATUS_WAITING_LABEL"))
        assertTrue(repositorySource.contains("TRAFFIC_MAP_STATUS_UNAVAILABLE_LABEL"))
    }

    @Test
    fun `traffic map dashboard opens full detail route with country totals table`() {
        val appSource =
            listOf(
                java.io.File("src/main/kotlin/com/foxhole/beta/ui/FoxholeApp.kt"),
                java.io.File("app/src/main/kotlin/com/foxhole/beta/ui/FoxholeApp.kt"),
                java.io.File("../app/src/main/kotlin/com/foxhole/beta/ui/FoxholeApp.kt"),
            ).first { file -> file.isFile }.readText()
        val homeSource =
            listOf(
                java.io.File("src/main/kotlin/com/foxhole/beta/ui/HomeScreen.kt"),
                java.io.File("app/src/main/kotlin/com/foxhole/beta/ui/HomeScreen.kt"),
                java.io.File("../app/src/main/kotlin/com/foxhole/beta/ui/HomeScreen.kt"),
            ).first { file -> file.isFile }.readText()
        val mapSource =
            listOf(
                java.io.File("src/main/kotlin/com/foxhole/beta/ui/TrafficMapDashboardCard.kt"),
                java.io.File("app/src/main/kotlin/com/foxhole/beta/ui/TrafficMapDashboardCard.kt"),
                java.io.File("../app/src/main/kotlin/com/foxhole/beta/ui/TrafficMapDashboardCard.kt"),
            ).first { file -> file.isFile }.readText()

        assertTrue(appSource.contains("TRAFFIC_MAP_DETAIL = \"traffic-map\""))
        assertTrue(appSource.contains("composable(AppRoute.TRAFFIC_MAP_DETAIL)"))
        assertTrue(appSource.contains("TrafficMapDetailScreen("))
        assertTrue(
            appSource.contains(
                "onOpenTrafficMapDetails = { navController.navigate(AppRoute.TRAFFIC_MAP_DETAIL) }",
            ),
        )
        assertTrue(homeSource.contains("onOpenTrafficMapDetails: () -> Unit"))
        assertTrue(homeSource.contains("onOpenDetails = onOpenTrafficMapDetails"))
        assertTrue(mapSource.contains("onOpenDetails: (() -> Unit)? = null"))
        assertTrue(mapSource.contains("onOpenDetails = onOpenDetails"))
        assertTrue(mapSource.contains("onClick = openDetails"))
        assertTrue(mapSource.contains("home_traffic_map_details_action"))
        assertTrue(mapSource.contains("internal fun TrafficMapDetailScreen("))
        assertTrue(mapSource.contains("tag = \"traffic_map_detail_screen\""))
        assertTrue(mapSource.contains("traffic_map_detail_world_map"))
        assertTrue(mapSource.contains("traffic_map_detail_country_table"))
        assertTrue(mapSource.contains("TrafficMapDetailCountryTable("))
        assertTrue(mapSource.contains("TrafficMapDetailControls("))
        assertTrue(mapSource.contains("trafficMapDetailDestinations("))
        assertTrue(mapSource.contains("trafficMapDetailPoints("))
        assertTrue(mapSource.contains("TRAFFIC_MAP_DETAIL_VISIBLE_ROWS = 30"))
        assertTrue(mapSource.contains("state.periodSnapshots.snapshot(period)"))
        assertTrue(mapSource.contains("TrafficMapDetailFilter.VPN"))
        assertTrue(mapSource.contains("TrafficMapDetailFilter.TOR"))
        assertTrue(mapSource.contains("TrafficMapDetailFilter.DIRECT"))
        assertTrue(mapSource.contains("TrafficMapDetailFilter.NEW"))
        assertTrue(mapSource.contains("TrafficMapCountryDetailSelection("))
        assertTrue(mapSource.contains("state.countryDetailsByCode[point.countryCode]"))
        assertTrue(mapSource.contains("selection.detail"))
        assertTrue(mapSource.contains("TrafficMapCountryDetailAppDataRow("))
        assertTrue(mapSource.contains("TrafficMapCountryDetailHostDataRow("))
        assertTrue(mapSource.contains("traffic_map_country_detail_first_seen"))
        assertTrue(mapSource.contains("Modifier.clickable(role = Role.Button"))
        assertTrue(mapSource.contains("combinedClickable("))
        assertTrue(mapSource.contains("onLongClick = { onMarkerSelected(target) }"))
        assertTrue(mapSource.contains("CollectionInfo("))
        assertTrue(mapSource.contains("CollectionItemInfo("))
        assertTrue(mapSource.contains("collectionRowIndex = index"))
        assertTrue(mapSource.contains("TRAFFIC_MAP_DETAIL_TABLE_COLUMNS = 3"))
        assertTrue(mapSource.contains("trafficMapDetailShareLabel("))
        assertTrue(mapSource.contains("traffic_map_country_header"))
        assertTrue(mapSource.contains("traffic_map_sessions_header"))
        assertTrue(mapSource.contains("traffic_map_total_header"))
        assertTrue(mapSource.contains("snapshot.unknownCountryConnections"))
        assertTrue(mapSource.contains("snapshot.totalConnections"))
    }
}
