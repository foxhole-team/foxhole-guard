package com.foxhole.beta.core.network

import com.foxhole.beta.core.model.NETWORK_FINGERPRINT_SCHEMA_CURRENT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkFingerprintProviderTest {
    @Test
    fun `build fingerprint is stable across dns and gateway ordering`() {
        val first =
            buildNetworkFingerprint(
                NetworkFingerprintSource(
                    transport = "WiFi",
                    isMetered = false,
                    isRoaming = false,
                    interfaceName = "wlan0",
                    dnsServers = listOf("1.1.1.1", "8.8.8.8"),
                    routeGateways = listOf("192.168.1.1", "fe80::1"),
                    privateDnsServerName = "dns.google",
                ),
            )
        val second =
            buildNetworkFingerprint(
                NetworkFingerprintSource(
                    transport = " wifi ",
                    isMetered = false,
                    isRoaming = false,
                    interfaceName = "wlan0",
                    dnsServers = listOf("8.8.8.8", "1.1.1.1"),
                    routeGateways = listOf("fe80::1", "192.168.1.1"),
                    privateDnsServerName = " DNS.Google ",
                ),
            )

        assertEquals(first, second)
        assertEquals(NETWORK_FINGERPRINT_SCHEMA_CURRENT, first?.schema)
        assertTrue(first?.key?.matches(Regex("[0-9a-f]{64}")) == true)
    }

    @Test
    fun `schema two fingerprint ignores raw dns and interface but changes for gateway`() {
        val base =
            buildNetworkFingerprint(
                NetworkFingerprintSource(
                    transport = "wifi",
                    isMetered = false,
                    isRoaming = false,
                    interfaceName = "wlan0",
                    dnsServers = listOf("1.1.1.1"),
                    routeGateways = listOf("192.168.1.1"),
                ),
            )
        val dnsAndInterfaceChanged =
            buildNetworkFingerprint(
                NetworkFingerprintSource(
                    transport = "wifi",
                    isMetered = true,
                    isRoaming = true,
                    interfaceName = "wlan1",
                    dnsServers = listOf("9.9.9.9"),
                    routeGateways = listOf("192.168.1.1"),
                ),
            )
        val gatewayChanged =
            buildNetworkFingerprint(
                NetworkFingerprintSource(
                    transport = "wifi",
                    isMetered = false,
                    isRoaming = false,
                    routeGateways = listOf("192.168.1.254"),
                ),
            )

        assertEquals(base?.key, dnsAndInterfaceChanged?.key)
        assertNotEquals(base?.key, gatewayChanged?.key)
    }

    @Test
    fun `build fingerprint returns null when no network specific traits are available`() {
        val fingerprint =
            buildNetworkFingerprint(
                NetworkFingerprintSource(
                    transport = "cellular",
                    isMetered = true,
                    isRoaming = false,
                ),
            )

        assertNull(fingerprint)
    }

    @Test
    fun `roaming fingerprint fallback stays disabled before api 28`() {
        assertFalse(resolveFingerprintRoamingState(sdkInt = 26, hasNotRoamingCapability = null))
        assertFalse(resolveFingerprintRoamingState(sdkInt = 27, hasNotRoamingCapability = null))
    }

    @Test
    fun `roaming fingerprint honors capability on api 28 and newer`() {
        assertTrue(resolveFingerprintRoamingState(sdkInt = 28, hasNotRoamingCapability = false))
        assertFalse(resolveFingerprintRoamingState(sdkInt = 28, hasNotRoamingCapability = true))
    }
}
