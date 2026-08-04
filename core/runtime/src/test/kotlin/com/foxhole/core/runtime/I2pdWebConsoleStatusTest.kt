package com.foxhole.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The sample mirrors the vendored i2pd HTTPServer.cpp ShowStatus output (english labels). */
class I2pdWebConsoleStatusTest {
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
        val PUBLISHED_PAGE =
            """
            <b>Uptime:</b> 12 min<br>
            <b>Network status:</b> OK<br>
            <b>Tunnel creation success rate:</b> 60%<br>
            <b>Received:</b> 12.34 MiB (5.20 KiB/s)<br>
            <b>Sent:</b> 7.10 MiB (1.80 KiB/s)<br>
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
