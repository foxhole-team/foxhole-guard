package com.foxhole.core.runtime

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
    fun `parses automatic mode as opportunistic`() {
        assertEquals(
            PrivateDnsMode.OPPORTUNISTIC,
            PrivateDnsSettings.fromValues(modeValue = "automatic", specifierValue = null),
        )
    }

    @Test
    fun `keeps opportunistic mode when android leaves stale specifier value`() {
        assertEquals(
            PrivateDnsMode.OPPORTUNISTIC,
            PrivateDnsSettings.fromValues(modeValue = "opportunistic", specifierValue = "one.one.one.one"),
        )
    }

    @Test
    fun `parses strict mode from hostname`() {
        assertEquals(
            PrivateDnsMode.STRICT,
            PrivateDnsSettings.fromValues(modeValue = "hostname", specifierValue = "dns.example"),
        )
        assertEquals(
            PrivateDnsState(PrivateDnsMode.STRICT, "dns.example"),
            PrivateDnsSettings.stateFromValues(modeValue = "hostname", specifierValue = "dns.example"),
        )
    }

    @Test
    fun `treats unknown non empty mode as unknown`() {
        assertEquals(
            PrivateDnsMode.UNKNOWN,
            PrivateDnsSettings.fromValues(modeValue = "custom", specifierValue = null),
        )
    }

    @Test
    fun `tunnel mode supports private dns off and automatic`() {
        assertEquals(true, PrivateDnsMode.OFF.isSupportedForTunnelMode())
        assertEquals(true, PrivateDnsMode.OPPORTUNISTIC.isSupportedForTunnelMode())
        assertEquals(true, PrivateDnsMode.STRICT.isSupportedForTunnelMode())
        assertEquals(false, PrivateDnsMode.UNKNOWN.isSupportedForTunnelMode())
    }

    @Test
    fun `system dns protection only supports private dns off`() {
        assertEquals(true, PrivateDnsMode.OFF.isSupportedForSystemDnsProtection())
        assertEquals(false, PrivateDnsMode.OPPORTUNISTIC.isSupportedForSystemDnsProtection())
        assertEquals(false, PrivateDnsMode.STRICT.isSupportedForSystemDnsProtection())
        assertEquals(false, PrivateDnsMode.UNKNOWN.isSupportedForSystemDnsProtection())
    }
}
