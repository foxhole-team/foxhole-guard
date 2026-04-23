package com.foxhole.beta.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class VpnDnsServerSelectorTest {
    @Test
    fun `prefers foxhole remote dns server when it is an ip literal`() {
        val selected =
            VpnDnsServerSelector.advertisedDnsServerAddress(
                configJson = foxholeConfig(server = "1.1.1.1"),
                fallbackServerAddress = "172.19.0.2",
            )

        assertEquals("1.1.1.1", selected)
    }

    @Test
    fun `returns legacy foxhole address host when available`() {
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
                fallbackServerAddress = "172.19.0.2",
            )

        assertEquals("1.1.1.1", selected)
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
