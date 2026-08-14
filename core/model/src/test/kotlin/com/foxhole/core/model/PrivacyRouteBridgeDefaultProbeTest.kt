package com.foxhole.core.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyRouteBridgeDefaultProbeTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

    @Test
    fun `missing bridge fields decode to bundled-on defaults`() {
        val decoded = json.decodeFromString(Settings.serializer(), """{"privacyRoute":{}}""")
        assertTrue(decoded.privacyRoute.bridgesEnabled)
        assertEquals(TorBridgeTransport.AUTO, decoded.privacyRoute.bridgeTransport)
        assertFalse(decoded.privacyRoute.bridgesAutoUpdate)
        assertFalse(decoded.privacyRoute.bridgesUseFoxholeSource)
        assertNull(decoded.privacyRoute.bridgesUpdatedAt)
        assertNull(decoded.privacyRoute.bridgesLastUpdateSuccess)
    }

    @Test
    fun `constructor defaults match the bundled-on contract`() {
        val settings = PrivacyRouteSettings()
        assertTrue(settings.bridgesEnabled)
        assertEquals(TorBridgeTransport.AUTO, settings.bridgeTransport)
        assertFalse(settings.bridgesAutoUpdate)
        assertFalse(settings.bridgesUseFoxholeSource)
    }

    @Test
    fun `i2p is off by default and decodes from a missing block`() {
        assertFalse(I2pSettings().enabled)
        val decoded = json.decodeFromString(Settings.serializer(), """{"i2p":{}}""")
        assertFalse(decoded.i2p.enabled)
    }

    @Test
    fun `meek transport matches every meek token flavour`() {
        assertTrue(TorBridgeTransport.MEEK.matchesBridgeTransportToken("meek_lite"))
        assertTrue(TorBridgeTransport.MEEK.matchesBridgeTransportToken("meek-azure"))
        assertFalse(TorBridgeTransport.MEEK.matchesBridgeTransportToken("obfs4"))
        assertTrue(TorBridgeTransport.AUTO.matchesBridgeTransportToken("anything"))
    }
}
