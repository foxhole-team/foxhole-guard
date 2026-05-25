package com.foxhole.beta.ui

import com.foxhole.beta.core.model.NetworkActivityEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppTrafficStatisticsCardTest {
    @Test
    fun `app connection rows use real byte deltas and split port from host`() {
        val rows =
            appConnectionRows(
                packageName = "org.example.browser",
                events = listOf(
                    event(remoteHost = "149.154.167.41", remotePort = 443, bytesRx = 120, bytesTx = 30),
                    event(remoteHost = "149.154.167.41", remotePort = 443, bytesRx = 80, bytesTx = 20),
                    event(remoteHost = "149.154.167.42", remotePort = 443, bytesRx = 0, bytesTx = 0),
                    event(
                        packageNames = listOf("org.example.other"),
                        remoteHost = "149.154.167.43",
                        remotePort = 443,
                        bytesRx = 500,
                        bytesTx = 500,
                    ),
                ),
                countryCodeForDestination = { "GB" },
                ipInfo = null,
            )

        assertEquals(1, rows.size)
        assertEquals("149.154.167.41", rows.single().remoteHost)
        assertEquals(443, rows.single().remotePort)
        assertEquals("149.154.167.41", rows.single().ipAddress)
        assertEquals("GB", rows.single().countryCode)
        assertNull(rows.single().city)
        assertEquals("TCP", rows.single().protocol)
        assertEquals(2, rows.single().count)
        assertEquals(250L, rows.single().bytes)
    }

    @Test
    fun `app connection rows keep same host with different ports separate`() {
        val rows =
            appConnectionRows(
                packageName = "org.example.browser",
                events = listOf(
                    event(remoteHost = "203.0.113.10", remotePort = 443, bytesRx = 40, bytesTx = 10),
                    event(remoteHost = "203.0.113.10", remotePort = 853, bytesRx = 30, bytesTx = 10),
                ),
                countryCodeForDestination = { null },
                ipInfo = null,
            )

        assertEquals(listOf(443, 853), rows.map(AppConnectionRow::remotePort))
        assertEquals(listOf(50L, 40L), rows.map(AppConnectionRow::bytes))
    }

    @Test
    fun `app connection rows prefer stored country code for journal destinations`() {
        val rows =
            appConnectionRows(
                packageName = "org.example.browser",
                events = listOf(
                    event(
                        remoteHost = "149.154.167.41",
                        remotePort = 443,
                        countryCode = "NL",
                        bytesRx = 40,
                        bytesTx = 10,
                    ),
                ),
                countryCodeForDestination = { "GB" },
                ipInfo = null,
            )

        assertEquals("NL", rows.single().countryCode)
        assertEquals(countryDisplayName("NL"), rows.single().countryName)
    }

    private fun event(
        packageNames: List<String> = listOf("org.example.browser"),
        remoteHost: String,
        remotePort: Int?,
        countryCode: String? = null,
        bytesRx: Long,
        bytesTx: Long,
    ): NetworkActivityEvent =
        NetworkActivityEvent(
            timestampMs = 1_000L,
            packageNames = packageNames,
            protocol = "TCP",
            remoteHost = remoteHost,
            remotePort = remotePort,
            countryCode = countryCode,
            bytesRx = bytesRx,
            bytesTx = bytesTx,
            profileId = 1L,
            sessionId = "session",
        )
}
