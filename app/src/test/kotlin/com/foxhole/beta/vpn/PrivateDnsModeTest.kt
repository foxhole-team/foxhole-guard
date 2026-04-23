package com.foxhole.beta.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class PrivateDnsModeTest {
    @Test
    fun `treats empty settings as off`() {
        assertEquals(PrivateDnsMode.OFF, PrivateDnsSettings.fromValues(modeValue = null, specifierValue = null))
    }

    @Test
    fun `parses opportunistic mode`() {
        assertEquals(
            PrivateDnsMode.OPPORTUNISTIC,
            PrivateDnsSettings.fromValues(modeValue = "opportunistic", specifierValue = null),
        )
    }

    @Test
    fun `parses strict mode from hostname`() {
        assertEquals(
            PrivateDnsMode.STRICT,
            PrivateDnsSettings.fromValues(modeValue = "hostname", specifierValue = "dns.example"),
        )
    }

    @Test
    fun `treats unknown non empty mode as unknown`() {
        assertEquals(
            PrivateDnsMode.UNKNOWN,
            PrivateDnsSettings.fromValues(modeValue = "custom", specifierValue = null),
        )
    }
}
