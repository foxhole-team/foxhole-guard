package com.foxhole.guard.core.settings

import com.foxhole.core.model.NetworkRulesSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class NetworkRulesSettingsNormalizationTest {
    @Test
    fun `normalization trims concrete protocol pins for both transports`() {
        val normalized = NetworkRulesSettings(
            wifiProfileId = 1L,
            wifiProtocolOptionId = " vless ",
            cellularProfileId = 2L,
            cellularProtocolOptionId = " trojan ",
        ).normalized()

        assertEquals("vless", normalized.wifiProtocolOptionId)
        assertEquals("trojan", normalized.cellularProtocolOptionId)
    }

    @Test
    fun `normalization disables profile rule with invalid profile id`() {
        val normalized = NetworkRulesSettings(
            useWifiProfile = true,
            wifiProfileId = 0L,
            wifiProtocolOptionId = " ",
            useCellularProfile = true,
            cellularProfileId = -1L,
            cellularProtocolOptionId = " ",
        ).normalized()

        assertFalse(normalized.useWifiProfile)
        assertFalse(normalized.useCellularProfile)
        assertNull(normalized.wifiProfileId)
        assertNull(normalized.cellularProfileId)
        assertNull(normalized.wifiProtocolOptionId)
        assertNull(normalized.cellularProtocolOptionId)
    }
}
