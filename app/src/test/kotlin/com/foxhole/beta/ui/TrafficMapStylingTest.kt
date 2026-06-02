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
            Color(0xFF26332B),
            trafficMapColors(darkTheme = true, surfaceColor = Color(0xFF101011)).countryFill,
        )
        assertEquals(
            Color(0xFF07130D),
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
}
