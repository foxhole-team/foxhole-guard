package com.foxhole.beta.ui

import androidx.compose.ui.graphics.Color
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
            Color(0xFF7B7F84),
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
    fun `traffic map startup prewarms gray land bitmap`() {
        val source =
            listOf(
                java.io.File("src/main/kotlin/com/foxhole/beta/ui/TrafficMapDashboardCard.kt"),
                java.io.File("app/src/main/kotlin/com/foxhole/beta/ui/TrafficMapDashboardCard.kt"),
                java.io.File("../app/src/main/kotlin/com/foxhole/beta/ui/TrafficMapDashboardCard.kt"),
            ).first { file -> file.isFile }.readText()
        val appPrewarmBlock =
            source.substringAfter("internal suspend fun prewarmTrafficMapCountryShapes")
                .substringBefore("private object TrafficMapCountryShapeCache")
        val landCacheBlock =
            source.substringAfter("private object TrafficMapLandLayerCache")
                .substringBefore("private data class TrafficMapLandLayerKey")

        assertTrue(appPrewarmBlock.contains("TrafficMapLandLayerCache.prewarm"))
        assertTrue(landCacheBlock.contains("suspend fun prewarm"))
        assertTrue(landCacheBlock.contains("TRAFFIC_MAP_DEFAULT_COUNTRY_FILL"))
        assertTrue(source.contains("trafficMapPrewarmCanvasSizes"))
        assertTrue(source.contains("TRAFFIC_MAP_PREWARM_COMPACT_WIDTH_FRACTION"))
        assertTrue(source.contains("TRAFFIC_MAP_PREWARM_MID_WIDTH_FRACTION"))
        assertTrue(source.contains("TRAFFIC_MAP_PREWARM_WIDE_WIDTH_FRACTION"))
    }
}
