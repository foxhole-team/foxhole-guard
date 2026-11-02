package com.foxhole.guard.core.settings

import com.foxhole.core.model.InterfaceMode
import com.foxhole.core.model.Settings
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InterfaceModeDefaultsTest {
    @Test
    fun `a new installation uses the simpler interface with automatic database updates`() {
        val actual = newInstallationSettings("https://example.com/ip")

        assertEquals(InterfaceMode.USER, actual.ui.interfaceMode)
        assertEquals(Settings().connection.copy(ipInfoEndpoint = "https://example.com/ip",
            componentAutoUpdateEnabled = true, geoIpAutoUpdate = true), actual.connection)
        assertTrue(actual.dns.autoUpdateFilters)
        assertTrue(actual.privacyRoute.bridgesAutoUpdate)
        assertEquals(Settings().expert, actual.expert)
    }

    @Test
    fun `existing database update opt out survives missing and explicit stored fields`() {
        val json = Json { ignoreUnknownKeys = true }
        listOf("{}", """{"connection":{"componentAutoUpdateEnabled":false},"dns":{"autoUpdateFilters":false},"privacyRoute":{"bridgesAutoUpdate":false}}""").forEach { payload ->
            val stored = decodeStoredSettingsPayload(payload, json)
            assertFalse(stored.connection.componentAutoUpdateEnabled)
            assertFalse(stored.connection.geoIpAutoUpdate)
            assertFalse(stored.dns.autoUpdateFilters)
            assertFalse(stored.privacyRoute.bridgesAutoUpdate)
        }
    }

    @Test
    fun `stored modern and legacy settings retain the expert interface`() {
        val json = Json { ignoreUnknownKeys = true }
        listOf("{}", """{"ui":{"locale":"RU"}}""", """{"autoReconnect":false}""").forEach { payload ->
            assertEquals(InterfaceMode.EXPERT, decodeStoredSettingsPayload(payload, json).ui.interfaceMode)
        }
    }
}
