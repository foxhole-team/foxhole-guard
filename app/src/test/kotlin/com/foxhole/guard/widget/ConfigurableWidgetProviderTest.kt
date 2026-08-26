package com.foxhole.guard.widget

import androidx.compose.ui.graphics.Color
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

    @Test
    fun `widget icon tone remains readable on both configurable backgrounds`() {
        val defaults = WidgetKindAppearance()
        val black = widgetBackground(mutablePreferencesOf(WIDGET_BG_BLACK_KEY to true), defaults)
        val white = widgetBackground(mutablePreferencesOf(WIDGET_BG_BLACK_KEY to false), defaults)

        assertEquals(Color.White, black.icon)
        assertEquals(Color.Black, white.icon)
    }

    @Test
    fun `new status widget uses the durable layout default`() {
        assertEquals(
            listOf(StatusWidgetLayoutMode.SIMPLE),
            SELECTABLE_STATUS_WIDGET_LAYOUT_MODES,
        )
        assertEquals(
            StatusWidgetLayoutMode.SIMPLE,
            initialStatusWidgetLayoutMode(emptyPreferences(), StatusWidgetLayoutMode.SIMPLE),
        )
    }

    @Test
    fun `legacy widget without a layout key now uses the simple default`() {
        val legacyPreferences = mutablePreferencesOf(WIDGET_BG_BLACK_KEY to false)

        assertEquals(
            StatusWidgetLayoutMode.SIMPLE,
            initialStatusWidgetLayoutMode(legacyPreferences, StatusWidgetLayoutMode.SIMPLE),
        )
    }

    @Test
    fun `explicit durable expanded default is retained as data but activates simple`() {
        val legacyPreferences = mutablePreferencesOf(WIDGET_BG_BLACK_KEY to false)

        assertEquals(
            StatusWidgetLayoutMode.SIMPLE,
            initialStatusWidgetLayoutMode(legacyPreferences, StatusWidgetLayoutMode.EXPANDED),
        )
    }

    @Test
    fun `per instance expanded value is kept compatible but no longer activates`() {
        val simple = mutablePreferencesOf(
            STATUS_WIDGET_LAYOUT_MODE_KEY to StatusWidgetLayoutMode.SIMPLE.persistedValue,
            WIDGET_OUTLINE_KEY to false,
        )
        val expanded = mutablePreferencesOf(
            STATUS_WIDGET_LAYOUT_MODE_KEY to StatusWidgetLayoutMode.EXPANDED.persistedValue,
        )

        assertEquals(
            StatusWidgetLayoutMode.SIMPLE,
            initialStatusWidgetLayoutMode(simple, StatusWidgetLayoutMode.EXPANDED),
        )
        assertEquals(
            StatusWidgetLayoutMode.SIMPLE,
            initialStatusWidgetLayoutMode(expanded, StatusWidgetLayoutMode.SIMPLE),
        )
    }
}
