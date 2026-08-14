package com.foxhole.core.runtime

import com.foxhole.core.model.I2pAddressBookEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class I2pdConfTest {
    @Test
    fun `relay toggle drives the notransit flag`() {
        val clientOnly = buildI2pdConfLines(httpProxyPort = 1, socksPort = 2)
        assertTrue("default keeps client-only", clientOnly.contains("notransit = true"))

        val relaying =
            buildI2pdConfLines(httpProxyPort = 1, socksPort = 2, relayTransitTraffic = true)
        assertTrue("relaying accepts transit", relaying.contains("notransit = false"))
    }

    @Test
    fun `renders transit bandwidth and tunnel cap`() {
        val conf =
            buildI2pdConfLines(
                httpProxyPort = 1,
                socksPort = 2,
                relayTransitTraffic = true,
                transitBandwidth = "P",
                transitTunnelsLimit = 500,
            )
        val text = conf.joinToString("\n")
        assertTrue(text.contains("bandwidth = P"))
        assertTrue(text.contains("[limits]"))
        assertTrue(text.contains("transittunnels = 500"))
    }

    @Test
    fun `transit tunnel cap is clamped to the i2pd bounds`() {
        val conf =
            buildI2pdConfLines(httpProxyPort = 1, socksPort = 2, transitTunnelsLimit = 10_000_000)
        assertTrue(conf.joinToString("\n").contains("transittunnels = 25000"))
    }

    @Test
    fun `startup config fingerprint reacts to relay bandwidth and tunnels`() {
        val entries = listOf(I2pAddressBookEntry(host = "site.i2p", destination = "dest"))
        val base =
            i2pStartupConfigFingerprint(
                entries,
                relayTransitTraffic = true,
                transitBandwidth = "L",
                transitTunnelsLimit = 250
            )
        assertNotEquals(
            base,
            i2pStartupConfigFingerprint(
                entries,
                relayTransitTraffic = false,
                transitBandwidth = "L",
                transitTunnelsLimit = 250
            )
        )
        assertNotEquals(
            base,
            i2pStartupConfigFingerprint(
                entries,
                relayTransitTraffic = true,
                transitBandwidth = "P",
                transitTunnelsLimit = 250
            )
        )
        assertNotEquals(
            base,
            i2pStartupConfigFingerprint(
                entries,
                relayTransitTraffic = true,
                transitBandwidth = "L",
                transitTunnelsLimit = 500
            )
        )
        assertEquals(
            base,
            i2pStartupConfigFingerprint(
                entries,
                relayTransitTraffic = true,
                transitBandwidth = "L",
                transitTunnelsLimit = 250
            )
        )
    }

    @Test
    fun `templates the loopback proxy ports and disables extra surfaces`() {
        val conf =
            buildI2pdConfLines(
                httpProxyPort = 14444,
                socksPort = 14447,
                socksUsername = "foxhole",
                socksPassword = "cafebabe",
            )
        val text = conf.joinToString("\n")
        assertTrue(text.contains("[httpproxy]"))
        val httpProxyIndex = conf.indexOf("[httpproxy]")
        assertEquals("enabled = false", conf[httpProxyIndex + 1])
        assertTrue(text.contains("[socksproxy]"))
        assertTrue(text.contains("port = 14447"))
        assertTrue(text.contains("username = foxhole"))
        assertTrue(text.contains("password = cafebabe"))
        assertTrue(text.contains("address = 127.0.0.1"))
        // Every remote-control / management surface stays disabled.
        listOf("[sam]", "[bob]", "[i2cp]", "[i2pcontrol]", "[http]", "[upnp]").forEach { section ->
            val idx = conf.indexOf(section)
            assertTrue("$section present", idx >= 0)
            assertTrue("$section disabled", conf[idx + 1] == "enabled = false")
        }
        assertTrue(text.contains("[ntcp2]"))
        assertTrue(text.contains("[ssu2]"))
    }

    @Test
    fun `webconsole opens loopback-only with basic auth when an endpoint is passed`() {
        val conf =
            buildI2pdConfLines(
                httpProxyPort = 1,
                socksPort = 2,
                webConsolePort = 17070,
                webConsolePassword = "cafebabe",
            )
        val idx = conf.indexOf("[http]")
        assertTrue("[http] present", idx >= 0)
        assertEquals("enabled = true", conf[idx + 1])
        assertEquals("address = 127.0.0.1", conf[idx + 2])
        assertEquals("port = 17070", conf[idx + 3])
        assertEquals("auth = true", conf[idx + 4])
        assertEquals("user = $I2PD_WEB_CONSOLE_USER", conf[idx + 5])
        assertEquals("pass = cafebabe", conf[idx + 6])
    }

    @Test
    fun `webconsole password is stripped of config-breaking characters`() {
        val conf =
            buildI2pdConfLines(
                httpProxyPort = 1,
                socksPort = 2,
                webConsolePort = 17070,
                webConsolePassword = "ca fe\nba=be",
            )
        assertTrue(conf.contains("pass = cafebabe"))
    }

    @Test
    fun `release build logs at info for the journal and phase feed`() {
        val conf = buildI2pdConfLines(httpProxyPort = 1, socksPort = 2)
        assertTrue(conf.contains("loglevel = info"))
        assertTrue(conf.contains("log = stdout"))
    }

    @Test
    fun `addressbook section suppresses remote subscriptions`() {
        val conf = buildI2pdConfLines(httpProxyPort = 1, socksPort = 2)
        val idx = conf.indexOf("[addressbook]")
        assertTrue("[addressbook] present", idx >= 0)
        assertTrue(conf.contains("defaulturl ="))
        assertTrue(conf.contains("subscriptions ="))
    }
}
