package com.foxhole.beta.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class VpnDnsServerSelectorTest {
    @Test
    fun `advertises public config dns to Android before local tun dns`() {
        val selected =
            VpnDnsServerSelector.advertisedDnsServerAddress(
                configJson = foxholeConfig(server = "1.1.1.1"),
                fallbackServerAddress = "172.19.0.2",
            )

        assertEquals("1.1.1.1", selected)
    }

    @Test
    fun `advertises only remote dns literals when local tun dns is also available`() {
        val selected =
            VpnDnsServerSelector.advertisedDnsServerAddresses(
                configJson = foxholeConfig(server = "1.1.1.1"),
                fallbackServerAddress = "172.19.0.2",
            )

        assertEquals(listOf("1.1.1.1"), selected)
    }

    @Test
    fun `falls back to legacy remote address host when local tun dns is unavailable`() {
        val selected =
            VpnDnsServerSelector.advertisedDnsServerAddress(
                configJson =
                    """
                    {
                      "dns": {
                        "servers": [
                          { "tag": "dns-remote", "address": "https://1.1.1.1/dns-query" }
                        ]
                      }
                    }
                    """.trimIndent(),
                fallbackServerAddress = "",
            )

        assertEquals("1.1.1.1", selected)
    }

    @Test
    fun `keeps remote dns url before local tun dns server`() {
        val selected =
            VpnDnsServerSelector.advertisedDnsServerAddress(
                configJson = foxholeConfig(server = "https://1.1.1.1/dns-query"),
                fallbackServerAddress = "172.19.0.2",
            )

        assertEquals("1.1.1.1", selected)
    }

    @Test
    fun `extracts remote dns ip literal from non http endpoint url when local tun dns is unavailable`() {
        val selected =
            VpnDnsServerSelector.advertisedDnsServerAddress(
                configJson =
                    """
                    {
                      "dns": {
                        "servers": [
                          { "tag": "dns-remote", "address": "tls://[2606:4700:4700::1111]:853" }
                        ]
                      }
                    }
                    """.trimIndent(),
                fallbackServerAddress = "",
            )

        assertEquals("2606:4700:4700::1111", selected)
    }

    @Test
    fun `falls back to local tun dns server when no remote ip exists`() {
        val selected =
            VpnDnsServerSelector.advertisedDnsServerAddresses(
                configJson = foxholeConfig(server = "dns.example"),
                fallbackServerAddress = "172.19.0.2",
            )

        assertEquals(listOf("172.19.0.2"), selected)
    }

    @Test
    fun `falls back when remote dns server is not an ip literal`() {
        val selected =
            VpnDnsServerSelector.advertisedDnsServerAddress(
                configJson = foxholeConfig(server = "cloudflare-dns.com"),
                fallbackServerAddress = "172.19.0.2",
            )

        assertEquals("172.19.0.2", selected)
    }

    @Test
    fun `does not synthesize public dns servers when config has no ip literal`() {
        val selected =
            VpnDnsServerSelector.advertisedDnsServerAddresses(
                configJson = foxholeConfig(server = "cloudflare-dns.com"),
                fallbackServerAddress = "172.19.0.2",
            )

        assertEquals(listOf("172.19.0.2"), selected)
    }

    @Test
    fun `returns empty dns server list when neither tun fallback nor remote ip literal exists`() {
        val selected =
            VpnDnsServerSelector.advertisedDnsServerAddresses(
                configJson = foxholeConfig(server = "cloudflare-dns.com"),
                fallbackServerAddress = null,
            )

        assertEquals(emptyList<String>(), selected)
    }

    private fun foxholeConfig(server: String): String =
        """
        {
          "dns": {
            "servers": [
              { "tag": "dns-local", "type": "local" },
              { "tag": "dns-remote", "type": "https", "server": "$server", "server_port": 443 }
            ]
          }
        }
        """.trimIndent()
}
