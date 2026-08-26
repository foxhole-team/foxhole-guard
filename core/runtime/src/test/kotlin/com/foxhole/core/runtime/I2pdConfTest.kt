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
    fun `every generated key is one i2pd registers`() {
        val emitted =
            i2pdConfKeys(
                buildI2pdConfLines(
                    httpProxyPort = 14444,
                    socksPort = 14447,
                    relayTransitTraffic = true,
                    transitBandwidth = "P",
                    transitTunnelsLimit = 500,
                    webConsolePort = 17070,
                    webConsolePassword = "cafebabe",
                ),
            )
        val registered =
            setOf(
                "daemon", "log", "loglevel", "ipv4", "ipv6", "notransit", "bandwidth",
                "httpproxy.enabled", "httpproxy.address", "httpproxy.port",
                "httpproxy.addresshelper", "httpproxy.outproxy",
                "socksproxy.enabled", "socksproxy.address", "socksproxy.port",
                "sam.enabled", "bob.enabled", "i2cp.enabled", "i2pcontrol.enabled",
                "http.enabled", "http.address", "http.port", "http.auth", "http.user", "http.pass",
                "upnp.enabled", "ntcp2.enabled", "ssu2.enabled",
                "limits.transittunnels",
                "addressbook.defaulturl", "addressbook.subscriptions",
            )
        val unknown = emitted.filterNot(registered::contains)
        assertEquals("i2pd would exit(EXIT_FAILURE) on these keys", emptyList<String>(), unknown)
        assertTrue("the socks credentials are not an i2pd option", emitted.none { it.endsWith("username") })
        assertTrue("the socks credentials are not an i2pd option", emitted.none { it.endsWith("password") })
    }

    @Test
    fun `templates the loopback proxy ports and disables extra surfaces`() {
        val conf =
            buildI2pdConfLines(
                httpProxyPort = 14444,
                socksPort = 14447,
            )
        val text = conf.joinToString("\n")
        assertTrue(text.contains("[httpproxy]"))
        val httpProxyIndex = conf.indexOf("[httpproxy]")
        assertEquals("enabled = true", conf[httpProxyIndex + 1])
        assertEquals("address = 127.0.0.1", conf[httpProxyIndex + 2])
        assertEquals("port = 14444", conf[httpProxyIndex + 3])
        assertTrue("jump services are the point of preferring it", text.contains("addresshelper = true"))
        assertTrue(text.contains("outproxy ="))
        assertTrue(text.contains("[socksproxy]"))
        assertTrue(text.contains("port = 14447"))
        assertTrue(text.contains("address = 127.0.0.1"))

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

    @Test
    fun `a fatal i2pd startup line reaches the journal`() {
        assertTrue(isJournalWorthyI2pdLine("unrecognised option 'socksproxy.username'"))
        assertTrue(isJournalWorthyI2pdLine("missing/unreadable config file: /data/x/i2pd.conf"))
        assertTrue(!isJournalWorthyI2pdLine("Streaming: Received MSG_CLOSE"))
    }
}
