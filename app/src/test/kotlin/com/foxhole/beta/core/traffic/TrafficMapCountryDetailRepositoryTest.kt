package com.foxhole.beta.core.traffic

import com.foxhole.beta.core.model.NetworkActivityEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrafficMapCountryDetailRepositoryTest {
    @Test
    fun `country details aggregate top apps hosts and first seen timestamps`() {
        val details =
            trafficMapCountryDetailsFromNetworkActivity(
                events =
                listOf(
                    networkEvent(
                        timestampMs = 1_000L,
                        packageNames = listOf("com.example.alpha", "com.example.beta"),
                        protocol = "tcp",
                        remoteHost = "api.example.test",
                        remotePort = 443,
                        countryCode = "us",
                        bytesRx = 60L,
                        bytesTx = 40L,
                    ),
                    networkEvent(
                        timestampMs = 2_000L,
                        packageNames = listOf("com.example.alpha"),
                        protocol = "udp",
                        remoteHost = "dns.example.test",
                        remotePort = 53,
                        countryCode = "US",
                        bytesRx = 30L,
                    ),
                ),
            )

        val detail = requireNotNull(details["US"])

        assertEquals("US", detail.countryCode)
        assertEquals(1_000L, detail.firstSeenAtMs)
        assertEquals(2_000L, detail.lastSeenAtMs)
        assertEquals("com.example.alpha", detail.appRows[0].packageName)
        assertEquals(80L, detail.appRows[0].bytes)
        assertEquals(2, detail.appRows[0].connections)
        assertEquals("com.example.beta", detail.appRows[1].packageName)
        assertEquals(50L, detail.appRows[1].bytes)
        assertEquals("api.example.test", detail.hostRows[0].remoteHost)
        assertEquals(443, detail.hostRows[0].remotePort)
        assertEquals("TCP", detail.hostRows[0].protocol)
        assertEquals(100L, detail.hostRows[0].bytes)
        assertEquals(2, detail.hostRows[0].appCount)
    }

    @Test
    fun `country details skip events without traffic or countries`() {
        val details =
            trafficMapCountryDetailsFromNetworkActivity(
                events =
                listOf(
                    networkEvent(
                        timestampMs = 1_000L,
                        packageNames = listOf("com.example.alpha"),
                        countryCode = "US",
                        bytesRx = 0L,
                        bytesTx = 0L,
                    ),
                    networkEvent(
                        timestampMs = 2_000L,
                        packageNames = listOf("com.example.beta"),
                        countryCode = null,
                        bytesRx = 10L,
                    ),
                ),
            )

        assertTrue(details.isEmpty())
    }

    @Test
    fun `country detail row limits keep bottom sheet compact`() {
        val details =
            trafficMapCountryDetailsFromNetworkActivity(
                events =
                (0 until 8).map { index ->
                    networkEvent(
                        timestampMs = 1_000L + index,
                        packageNames = listOf("com.example.app$index"),
                        remoteHost = "host$index.example.test",
                        countryCode = "DE",
                        bytesRx = (100L - index),
                    )
                },
                appLimit = 3,
                hostLimit = 2,
            )

        val detail = requireNotNull(details["DE"])

        assertEquals(3, detail.appRows.size)
        assertEquals(2, detail.hostRows.size)
        assertEquals("com.example.app0", detail.appRows.first().packageName)
        assertEquals("host0.example.test", detail.hostRows.first().remoteHost)
    }

    @Test
    fun `country details hide hosts when private network details are sanitized`() {
        val details =
            trafficMapCountryDetailsFromNetworkActivity(
                events =
                listOf(
                    networkEvent(
                        timestampMs = 1_000L,
                        packageNames = listOf("com.example.alpha"),
                        remoteHost = "private.example.test",
                        countryCode = "US",
                        bytesRx = 10L,
                    ),
                ),
                includeHostDetails = false,
            )

        val detail = requireNotNull(details["US"])

        assertEquals(1, detail.appRows.size)
        assertTrue(detail.hostRows.isEmpty())
    }
}

private fun networkEvent(
    timestampMs: Long,
    packageNames: List<String>,
    protocol: String = "tcp",
    remoteHost: String = "api.example.test",
    remotePort: Int? = 443,
    countryCode: String?,
    bytesRx: Long,
    bytesTx: Long = 0L,
): NetworkActivityEvent =
    NetworkActivityEvent(
        timestampMs = timestampMs,
        packageNames = packageNames,
        protocol = protocol,
        remoteHost = remoteHost,
        remotePort = remotePort,
        countryCode = countryCode,
        bytesRx = bytesRx,
        bytesTx = bytesTx,
        profileId = null,
        sessionId = null,
    )
