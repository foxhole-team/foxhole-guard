package com.foxhole.beta.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class VpnDnsServerSelectorTest {
    @Test
    fun `advertises local tun dns server to Android before remote resolver`() {
        val selected =
            VpnDnsServerSelector.advertisedDnsServerAddress(
                configJson = foxholeConfig(server = "1.1.1.1"),
                fallbackServerAddress = "172.19.0.2",
            )

        assertEquals("172.19.0.2", selected)
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
