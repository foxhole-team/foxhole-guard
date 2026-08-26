package com.foxhole.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class I2pdWebConsoleStatusTest {
    @Test
    fun `parses the authenticated lightweight status surface`() {
        val status = requireNotNull(parseI2pdFoxHoleStatus(FOXHOLE_STATUS))

        assertEquals("Firewalled", status.networkStatus)
        assertEquals(321, status.knownRouters)
        assertEquals(4, status.clientTunnels)
        assertEquals(7, status.transitTunnels)
        assertEquals(12_345L, status.rxTotalBytes)
        assertEquals(67_890L, status.txTotalBytes)
        assertEquals(1_025L, status.rxBytesPerSec)
        assertEquals(513L, status.txBytesPerSec)
        assertEquals(111L, status.transitTotalBytes)
        assertEquals(3L, status.transitBytesPerSec)
        assertEquals("2.61.0", status.version)
    }

    @Test
    fun `rejects an unauthenticated or unrelated status body`() {
        assertNull(parseI2pdFoxHoleStatus("known_routers=321"))
    }

    @Test
    fun `parses external address, status, routers and transit tunnels`() {
        val status = parseI2pdWebConsoleStatus(PUBLISHED_PAGE)
        assertEquals("203.0.113.7:12345 (NTCP2)", status.externalAddress)
        assertEquals("OK", status.networkStatus)
        assertEquals(1543, status.knownRouters)
        assertEquals(14, status.transitTunnels)
    }

    @Test
    fun `firewalled router yields no external address but keeps the status`() {
        val status = parseI2pdWebConsoleStatus(FIREWALLED_PAGE)
        assertNull(status.externalAddress)
        assertEquals("Firewalled", status.networkStatus)
        assertEquals(88, status.knownRouters)
        assertEquals(0, status.transitTunnels)
    }

    @Test
    fun `parses the router facts the I2P window prints beside the address`() {
        val status = parseI2pdWebConsoleStatus(PUBLISHED_PAGE)
        assertEquals(8, status.clientTunnels)
        assertEquals("2.60.0", status.version)
        assertEquals("12.34 MiB (5.20 KiB/s)", status.received)
        assertEquals("7.10 MiB (1.80 KiB/s)", status.sent)
        assertEquals(12_939_428L, status.rxTotalBytes)
        assertEquals(7_444_890L, status.txTotalBytes)
        assertEquals(5_325L, status.rxBytesPerSec)
        assertEquals(1_843L, status.txBytesPerSec)
        assertEquals("dGVzdFJvdXRlcklkZW50QmFzZTY0RXhhbXBsZUFBQUFBQUFBQUE=", status.routerIdent)
    }

    @Test
    fun `transit is read from i2pd's own counter and never confused with the tunnel count`() {
        val status = parseI2pdWebConsoleStatus(PUBLISHED_PAGE)
        assertEquals("3.50 MiB (0.90 KiB/s)", status.transit)
        assertEquals(3_670_016L, status.transitTotalBytes)
        assertEquals(922L, status.transitBytesPerSec)

        assertEquals(14, status.transitTunnels)
    }

    @Test
    fun `a console without the transit line yields no transit bytes`() {
        val status = parseI2pdWebConsoleStatus(FIREWALLED_PAGE)
        assertNull(status.transit)
        assertNull(status.transitTotalBytes)
        assertNull(status.transitBytesPerSec)
    }

    @Test
    fun `garbage input degrades to an empty snapshot`() {
        val status = parseI2pdWebConsoleStatus("<html><body>not a console</body></html>")
        assertNull(status.externalAddress)
        assertNull(status.networkStatus)
        assertNull(status.knownRouters)
        assertNull(status.transitTunnels)
        assertNull(status.clientTunnels)
        assertNull(status.version)
        assertNull(status.received)
        assertNull(status.sent)
        assertNull(status.rxTotalBytes)
        assertNull(status.txTotalBytes)
        assertNull(status.rxBytesPerSec)
        assertNull(status.txBytesPerSec)
        assertNull(status.routerIdent)
    }

    private companion object {
        val FOXHOLE_STATUS =
            """
            foxhole_status=1
            network_status=1
            known_routers=321
            client_tunnels=4
            transit_tunnels=7
            received_bytes=12345
            sent_bytes=67890
            transit_bytes=111
            received_bytes_per_sec=1024.6
            sent_bytes_per_sec=512.5
            transit_bytes_per_sec=2.5
            version=2.61.0
            """.trimIndent()

        val PUBLISHED_PAGE =
            """
            <b>Uptime:</b> 12 min<br>
            <b>Network status:</b> OK<br>
            <b>Tunnel creation success rate:</b> 60%<br>
            <b>Received:</b> 12.34 MiB (5.20 KiB/s)<br>
            <b>Sent:</b> 7.10 MiB (1.80 KiB/s)<br>
            <b>Transit:</b> 3.50 MiB (0.90 KiB/s)<br>
            <b>Router Ident:</b> dGVzdFJvdXRlcklkZW50QmFzZTY0RXhhbXBsZUFBQUFBQUFBQUE=<br>
            <b>Version:</b> 2.60.0<br>
            <b>Our external address:</b><br>
            <table class="extaddr">
            <tbody>
            <tr>
            <td>NTCP2</td>
            <td style="padding-left: 0.5em;">203.0.113.7:12345</td>
            </tr>
            <tr>
            <td>SSU2</td>
            <td style="padding-left: 0.5em;">203.0.113.7:12346</td>
            </tr>
            </tbody>
            </table>
            <b>Routers:</b> 1543&nbsp;&nbsp;&nbsp;<b>Floodfills:</b> 802&nbsp;&nbsp;&nbsp;<b>LeaseSets:</b> 0<br>
            <b>Client Tunnels:</b> 8&nbsp;&nbsp;&nbsp;<b>Transit Tunnels:</b> 14<br>
            """.trimIndent()

        val FIREWALLED_PAGE =
            """
            <b>Uptime:</b> 3 min<br>
            <b>Network status:</b> Firewalled<br>
            <b>Our external address:</b><br>
            <table class="extaddr">
            <tbody>
            <tr>
            <td>SSU2</td>
            <td style="padding-left: 0.5em;">supported :12345</td>
            </tr>
            </tbody>
            </table>
            <b>Routers:</b> 88&nbsp;&nbsp;&nbsp;<b>Floodfills:</b> 40&nbsp;&nbsp;&nbsp;<b>LeaseSets:</b> 0<br>
            <b>Client Tunnels:</b> 4&nbsp;&nbsp;&nbsp;<b>Transit Tunnels:</b> 0<br>
            """.trimIndent()
    }
}
