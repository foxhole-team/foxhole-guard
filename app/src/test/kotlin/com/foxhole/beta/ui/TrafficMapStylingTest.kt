package com.foxhole.beta.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrafficMapStylingTest {
    @Test
    fun `traffic map land fill stays dark in both themes`() {
        assertEquals(
            Color(0xFF1B2A21),
            trafficMapColors(darkTheme = true, surfaceColor = Color(0xFF101011)).countryFill,
        )
        assertEquals(
            Color(0xFF030D08),
            trafficMapColors(darkTheme = false, surfaceColor = Color.White).countryFill,
        )
        assertEquals(
            Color(0xFF278A5B),
            trafficMapColors(darkTheme = false, surfaceColor = Color.White).routeLine,
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
}
