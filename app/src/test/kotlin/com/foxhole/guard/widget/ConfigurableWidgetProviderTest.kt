package com.foxhole.guard.widget

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
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
    fun `outline defaults on and remains an instance preference`() {
        assertTrue(widgetOutlineEnabled(emptyPreferences()))
        assertFalse(widgetOutlineEnabled(mutablePreferencesOf(WIDGET_OUTLINE_KEY to false)))
        assertTrue(widgetOutlineEnabled(mutablePreferencesOf(WIDGET_OUTLINE_KEY to true)))
    }
}
