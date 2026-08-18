package com.foxhole.guard.widget

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.foxhole.core.model.WidgetKindAppearance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal class ConfigurableWidgetProviderTest {
    @Test
    fun `shared config resolves only its two exact providers`() {
        assertEquals(
            ConfigurableWidgetProvider.CONNECTION,
            configurableWidgetProvider(StatusWidgetReceiver::class.java.name),
        )
        assertEquals(
            ConfigurableWidgetProvider.WEB_APPS,
            configurableWidgetProvider(WebAppsWidgetReceiver::class.java.name),
        )
        assertNull(configurableWidgetProvider(null))
        assertNull(configurableWidgetProvider(FoxStatusWidgetReceiver::class.java.name))
        assertNull(configurableWidgetProvider("com.example.UnknownWidget"))
    }

    @Test
    fun `outline follows the kind's app setting until the instance overrides it`() {
        val outlineOn = WidgetKindAppearance(outline = true)
        val outlineOff = WidgetKindAppearance(outline = false)
        assertTrue(widgetOutlineEnabled(emptyPreferences(), outlineOn))
        assertFalse(widgetOutlineEnabled(emptyPreferences(), outlineOff))
        assertFalse(widgetOutlineEnabled(mutablePreferencesOf(WIDGET_OUTLINE_KEY to false), outlineOn))
        assertTrue(widgetOutlineEnabled(mutablePreferencesOf(WIDGET_OUTLINE_KEY to true), outlineOff))
    }
}
