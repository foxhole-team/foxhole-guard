package com.foxhole.guard.widget

import com.foxhole.core.model.ThemeMode
import com.foxhole.core.model.WidgetKindAppearance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetAppearanceDefaultsTest {
    @Test
    fun `explicit widget theme fallback ignores system night state`() {
        assertTrue(widgetDefaultBlackBackground(ThemeMode.DARK, systemInDarkTheme = false))
        assertTrue(widgetDefaultBlackBackground(ThemeMode.OLED, systemInDarkTheme = false))
        assertFalse(widgetDefaultBlackBackground(ThemeMode.LIGHT, systemInDarkTheme = true))
    }

    @Test
    fun `system widget theme fallback follows system night state`() {
        assertTrue(widgetDefaultBlackBackground(ThemeMode.SYSTEM, systemInDarkTheme = true))
        assertFalse(widgetDefaultBlackBackground(ThemeMode.SYSTEM, systemInDarkTheme = false))
    }

    @Test
    fun `new widget kinds default to half opacity while saved values remain exact`() {
        val darkDefault = widgetAppearanceOrDefault(saved = null, defaultBlackBackground = true)
        val lightDefault = widgetAppearanceOrDefault(saved = null, defaultBlackBackground = false)
        val saved = WidgetKindAppearance(blackBackground = true, alphaPercent = 73, outline = false)

        assertEquals(WIDGET_DEFAULT_OPACITY_PERCENT, darkDefault.alphaPercent)
        assertEquals(WIDGET_DEFAULT_OPACITY_PERCENT, lightDefault.alphaPercent)
        assertTrue(darkDefault.blackBackground)
        assertFalse(lightDefault.blackBackground)
        assertFalse(darkDefault.outline)
        assertFalse(lightDefault.outline)
        assertFalse(WidgetKindAppearance().outline)
        assertEquals(saved, widgetAppearanceOrDefault(saved, defaultBlackBackground = false))
    }

    @Test
    fun `legacy custom opacity survives while the old untouched default becomes half opacity`() {
        assertEquals(WIDGET_DEFAULT_OPACITY_PERCENT, widgetFallbackOpacityPercent(100))
        assertEquals(73, widgetFallbackOpacityPercent(73))
        assertEquals(0, widgetFallbackOpacityPercent(-1))
    }
}
