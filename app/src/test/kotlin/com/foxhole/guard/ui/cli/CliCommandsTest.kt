package com.foxhole.guard.ui.cli

import org.junit.Assert.assertEquals
import org.junit.Test

class CliCommandsTest {

    @Test
    fun `start vpn without profile is bare`() {
        assertEquals("start VPN", CliCommands.startVpn(profile = null))
        assertEquals("start VPN", CliCommands.startVpn(profile = "  "))
    }

    @Test
    fun `start vpn carries profile flag`() {
        assertEquals("start VPN -p home", CliCommands.startVpn(profile = "home"))
    }

    @Test
    fun `profile with spaces is quoted`() {
        assertEquals("start VPN -p \"my home\"", CliCommands.startVpn(profile = "my home"))
    }

    @Test
    fun `transport flag prints only when present`() {
        assertEquals(
            "start VPN -p home -t vless",
            CliCommands.startVpn(profile = "home", transport = "vless"),
        )
        assertEquals("start VPN -p home", CliCommands.startVpn(profile = "home", transport = " "))
    }

    @Test
    fun `vpn plus tor chain target`() {
        assertEquals(
            "start VPN+TOR -p home -t vless",
            CliCommands.startVpnTor(profile = "home", transport = "vless"),
        )
    }

    @Test
    fun `i2p toggles`() {
        assertEquals("i2p on", CliCommands.i2p(true))
        assertEquals("i2p off", CliCommands.i2p(false))
    }

    @Test
    fun `fixed commands are canon`() {
        assertEquals("stop", CliCommands.STOP)
        assertEquals("cancel", CliCommands.CANCEL)
        assertEquals("restart", CliCommands.RESTART)
        assertEquals("reconnect", CliCommands.RECONNECT)
        assertEquals("status", CliCommands.STATUS)
        assertEquals("start TOR", CliCommands.START_TOR)
        assertEquals("scan qr", CliCommands.SCAN_QR)
        assertEquals("mode VPN+TOR", CliCommands.MODE_VPN_TOR)
    }
}
