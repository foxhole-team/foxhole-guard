package com.foxhole.guard.widget

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/** Квадрат 2×2 — 4 аппа в один ряд; прямоугольник 4×2 — 8 в два ряда. */
internal class WebAppsWidgetLayoutTest {
    @Test
    fun `square widget shows four apps`() {
        assertEquals(4, webAppSlots(DpSize(110.dp, 110.dp)))
    }

    @Test
    fun `wide widget shows eight apps`() {
        assertEquals(8, webAppSlots(DpSize(250.dp, 110.dp)))
        assertEquals(8, webAppSlots(DpSize(320.dp, 140.dp)))
    }
}
