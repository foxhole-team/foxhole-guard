package com.foxhole.guard.core.settings

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import com.foxhole.core.model.AppLocale
import com.foxhole.core.model.InterfaceMode
import com.foxhole.core.model.ThemeMode
import com.foxhole.core.model.UiSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class InterfaceModeCacheTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferences = base.getSharedPreferences("interface_mode_cache_test", Context.MODE_PRIVATE)
    private val context = object : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = preferences
    }

    @Before
    fun reset() {
        preferences.edit().clear().commit()
    }

    @After
    fun clear() {
        preferences.edit().clear().commit()
    }

    @Test
    fun selectedInterfaceSurvivesColdStartWithAppearanceAndLocale() {
        val store = SettingsFastUiStore(context)
        val selected = UiSettings(
            interfaceMode = InterfaceMode.USER,
            themeMode = ThemeMode.LIGHT,
            locale = AppLocale.RU
        )
        store.writeUiSnapshot(selected)
        var fallbackReads = 0
        val restored = SettingsFastUiStore(context).readBootstrapUi(UiSettings()) {
            fallbackReads++
            UiSettings()
        }
        assertEquals(0, fallbackReads)
        assertEquals(InterfaceMode.USER, restored.interfaceMode)
        assertEquals(ThemeMode.LIGHT, restored.themeMode)
        assertEquals(AppLocale.RU, restored.locale)
    }

    @Test
    fun oldOrUnknownCacheReadsEncryptedModeInsteadOfResettingIt() {
        val store = SettingsFastUiStore(context)
        store.writeUiSnapshot(UiSettings())
        for (cached in listOf(null, "FUTURE")) {
            preferences.edit().putString("interface_mode", cached).commit()
            var reads = 0
            val restored = store.readBootstrapUi(UiSettings()) {
                reads++
                UiSettings(interfaceMode = InterfaceMode.USER)
            }
            assertEquals(1, reads)
            assertEquals(InterfaceMode.USER, restored.interfaceMode)
        }
    }
}
