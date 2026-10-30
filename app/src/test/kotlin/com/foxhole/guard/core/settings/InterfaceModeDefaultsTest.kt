package com.foxhole.guard.core.settings

import com.foxhole.core.model.InterfaceMode
import com.foxhole.core.model.Settings
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class InterfaceModeDefaultsTest {
    @Test
    fun `a new installation uses the simpler interface without changing connection defaults`() {
        val actual = newInstallationSettings("https://example.com/ip")

        assertEquals(InterfaceMode.USER, actual.ui.interfaceMode)
        assertEquals(Settings().connection.copy(ipInfoEndpoint = "https://example.com/ip"), actual.connection)
        assertEquals(Settings().expert, actual.expert)
    }

    @Test
    fun `stored modern and legacy settings retain the expert interface`() {
        val json = Json { ignoreUnknownKeys = true }
        listOf("{}", """{"ui":{"locale":"RU"}}""", """{"autoReconnect":false}""").forEach { payload ->
            assertEquals(InterfaceMode.EXPERT, decodeStoredSettingsPayload(payload, json).ui.interfaceMode)
        }
    }
}
