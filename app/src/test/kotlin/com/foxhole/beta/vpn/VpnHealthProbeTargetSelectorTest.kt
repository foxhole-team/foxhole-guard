package com.foxhole.beta.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VpnHealthProbeTargetSelectorTest {
    @Test
    fun `selects first remote outbound target from assembled config`() {
        val target =
            VpnHealthProbeTargetSelector.select(
                """
                {
                  "outbounds": [
                    { "type": "vless", "tag": "edge-1", "server": "edge.example.com", "server_port": 443 },
                    { "type": "selector", "tag": "proxy", "outbounds": ["edge-1"] },
                    { "type": "direct", "tag": "direct" },
                    { "type": "block", "tag": "block" }
                  ]
                }
                """.trimIndent(),
            )

        requireNotNull(target)
        assertEquals("edge.example.com", target.host)
        assertEquals(443, target.port)
        assertEquals(VpnHealthProbeTransport.TCP, target.transport)
    }

    @Test
    fun `prefers proxy selector target over first remote outbound`() {
        val target =
            VpnHealthProbeTargetSelector.select(
                """
                {
                  "outbounds": [
                    { "type": "vless", "tag": "legacy", "server": "old.example.com", "server_port": 39537 },
                    { "type": "vless", "tag": "edge-1", "server": "edge.example.com", "server_port": 43000 },
                    { "type": "selector", "tag": "proxy", "outbounds": ["edge-1"] },
                    { "type": "direct", "tag": "direct" }
                  ]
                }
                """.trimIndent(),
            )

        requireNotNull(target)
        assertEquals("edge.example.com", target.host)
        assertEquals(43000, target.port)
        assertEquals(VpnHealthProbeTransport.TCP, target.transport)
    }

    @Test
    fun `marks wireguard as udp probe transport`() {
        val target =
            VpnHealthProbeTargetSelector.select(
                """
                {
                  "outbounds": [
                    {
                      "type": "wireguard",
                      "tag": "wg",
                      "private_key": "private",
                      "local_address": ["10.0.0.2/32"],
                      "peers": [
                        {
                          "server": "wg.example.com",
                          "server_port": 51820,
                          "public_key": "public"
                        }
                      ]
                    }
                  ]
                }
                """.trimIndent(),
            )

        requireNotNull(target)
        assertEquals("wg.example.com", target.host)
        assertEquals(51820, target.port)
        assertEquals(VpnHealthProbeTransport.UDP, target.transport)
    }

    @Test
    fun `returns null when config has no remote outbound target`() {
        val target =
            VpnHealthProbeTargetSelector.select(
                """
                {
                  "outbounds": [
                    { "type": "selector", "tag": "proxy", "outbounds": [] },
                    { "type": "direct", "tag": "direct" }
                  ]
                }
                """.trimIndent(),
            )

        assertNull(target)
    }
}
