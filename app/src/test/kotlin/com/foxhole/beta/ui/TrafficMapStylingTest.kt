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
    fun `traffic map uses default gray land color in both themes`() {
        val darkColors = trafficMapColors(darkTheme = true, surfaceColor = Color(0xFF101011))
        val lightColors = trafficMapColors(darkTheme = false, surfaceColor = Color.White)

        assertEquals(
            Color.Gray,
            darkColors.countryFill,
        )
        assertEquals(
            darkColors.countryFill,
            lightColors.countryFill,
        )
        assertEquals(
            Color(0xFF666B70),
            lightColors.routeLine,
        )
        assertEquals(
            Color(0xFFD0D3D6),
            darkColors.destination,
        )
        assertEquals(
            Color(0xFF3D4247),
            lightColors.origin,
        )
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
    fun `traffic map startup prewarms nearby gray land bitmap buckets`() {
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
        assertTrue(landCacheBlock.contains("delay(TRAFFIC_MAP_DEFERRED_PREWARM_DELAY_MS)"))
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
    fun `application starts traffic map prewarm shortly after startup`() {
        val source =
            listOf(
                java.io.File("src/main/kotlin/com/foxhole/beta/FoxholeApplication.kt"),
                java.io.File("app/src/main/kotlin/com/foxhole/beta/FoxholeApplication.kt"),
                java.io.File("../app/src/main/kotlin/com/foxhole/beta/FoxholeApplication.kt"),
            ).first { file -> file.isFile }.readText()
        val prewarmBlock =
            source.substringAfter("appScope.launch {")
                .substringBefore("appScope.launch {\n            delay(BACKGROUND_INITIALIZATION_STARTUP_DELAY_MS)")

        assertTrue(prewarmBlock.contains("delay(TRAFFIC_MAP_PREWARM_STARTUP_DELAY_MS)"))
        assertTrue(source.contains("TRAFFIC_MAP_PREWARM_STARTUP_DELAY_MS = 120L"))
        assertFalse(source.contains("TRAFFIC_MAP_PREWARM_STARTUP_DELAY_MS = 300L"))
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
    fun `traffic map defers json backed canvas until content is ready`() {
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
        assertTrue(cardBlock.contains("if (contentReady && !mapDisabledForPower)"))
        assertTrue(cardBlock.contains("val countryShapesLoading = contentReady && !mapDisabledForPower && countryShapes.isEmpty()"))
        assertTrue(cardBlock.contains("} else if (!contentReady || countryShapesLoading)"))
        assertTrue(cardBlock.contains("TrafficMapCanvasLoadingBlock"))
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
