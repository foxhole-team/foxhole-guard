package com.foxhole.guard.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

internal class WidgetOpacityPresentationTest {
    @Test
    fun `in app choices contain only the half opacity default and custom`() {
        val presentation = widgetOpacityPresentation(WIDGET_DEFAULT_OPACITY_PERCENT)

        assertEquals(2, presentation.choices.size)
        assertEquals(WIDGET_DEFAULT_OPACITY_PERCENT, presentation.choices.first().percent)
        assertEquals(WIDGET_DEFAULT_OPACITY_PERCENT.toString(), presentation.selectedId)
        assertFalse(presentation.choices.first().custom)
        assertTrue(presentation.choices.last().custom)
    }

    @Test
    fun `a non-preset opacity remains an exact selected custom choice`() {
        val presentation = widgetOpacityPresentation(37)
        val custom = presentation.choices.single(WidgetOpacityChoice::custom)

        assertEquals(37, presentation.percent)
        assertEquals(WIDGET_OPACITY_CUSTOM_OPTION_ID, presentation.selectedId)
        assertEquals(WIDGET_OPACITY_CUSTOM_OPTION_ID, custom.id)
        assertEquals(37, custom.percent)
        assertEquals(2, presentation.choices.size)
    }

    @Test
    fun `external opacity values clamp to valid custom choices`() {
        val below = widgetOpacityPresentation(-1)
        val above = widgetOpacityPresentation(101)

        assertEquals(WIDGET_OPACITY_MIN_PERCENT, below.percent)
        assertEquals(WIDGET_OPACITY_MAX_PERCENT, above.percent)
        assertEquals(WIDGET_OPACITY_CUSTOM_OPTION_ID, below.selectedId)
        assertEquals(WIDGET_OPACITY_CUSTOM_OPTION_ID, above.selectedId)
        assertEquals(0, below.choices.single(WidgetOpacityChoice::custom).percent)
        assertEquals(100, above.choices.single(WidgetOpacityChoice::custom).percent)
    }

    @Test
    fun `custom draft accepts only an exact zero through one hundred value`() {
        assertEquals("", widgetOpacityDraft(""))
        assertEquals("0", widgetOpacityDraft("0"))
        assertEquals("37", widgetOpacityDraft("3x7"))
        assertEquals("100", widgetOpacityDraft("100"))
        assertEquals(null, widgetOpacityDraft("101"))
        assertEquals(null, widgetOpacityDraft("9999"))
    }
}
