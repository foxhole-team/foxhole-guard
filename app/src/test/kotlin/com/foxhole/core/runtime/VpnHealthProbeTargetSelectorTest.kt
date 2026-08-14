package com.foxhole.core.runtime

import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.VpnSession
import com.foxhole.guard.runtime.tcpRuntimeReadinessTarget
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
    fun `marks wireguard endpoint selected through proxy as udp probe transport`() {
        val target =
            VpnHealthProbeTargetSelector.select(
                """
                {
                  "endpoints": [
                    {
                      "type": "wireguard",
                      "tag": "wg",
                      "private_key": "private",
                      "address": ["10.0.0.2/32"],
                      "peers": [
                        {
                          "address": "wg.example.com",
                          "port": 51820,
                          "public_key": "public"
                        }
                      ]
                    }
                  ],
                  "outbounds": [
                    { "type": "selector", "tag": "proxy", "outbounds": ["wg"] },
                    { "type": "direct", "tag": "direct" }
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
    fun `marks hysteria2 as udp by default`() {
        val target =
            VpnHealthProbeTargetSelector.select(
                """
                {
                  "outbounds": [
                    { "type": "hysteria2", "tag": "hy2", "server": "hy2.example.com", "server_port": 8443 },
                    { "type": "selector", "tag": "proxy", "outbounds": ["hy2"] }
                  ]
                }
                """.trimIndent(),
            )

        requireNotNull(target)
        assertEquals("hy2.example.com", target.host)
        assertEquals(8443, target.port)
        assertEquals(VpnHealthProbeTransport.UDP, target.transport)
    }

    @Test
    fun `uses explicit tcp network for hysteria2 probe transport`() {
        val target =
            VpnHealthProbeTargetSelector.select(
                """
                {
                  "outbounds": [
                    { "type": "hysteria2", "tag": "hy2", "server": "hy2.example.com", "server_port": 8443, "network": "tcp" },
                    { "type": "selector", "tag": "proxy", "outbounds": ["hy2"] }
                  ]
                }
                """.trimIndent(),
            )

        requireNotNull(target)
        assertEquals(VpnHealthProbeTransport.TCP, target.transport)
    }

    @Test
    fun `tcp runtime readiness target is selected only for tcp transports`() {
        val tcpConfig =
            """
            {
              "outbounds": [
                { "type": "vless", "tag": "edge", "server": "edge.example.com", "server_port": 443 },
                { "type": "selector", "tag": "proxy", "outbounds": ["edge"] }
              ]
            }
            """.trimIndent()
        val udpConfig =
            """
            {
              "outbounds": [
                { "type": "hysteria2", "tag": "hy2", "server": "hy2.example.com", "server_port": 8443 },
                { "type": "selector", "tag": "proxy", "outbounds": ["hy2"] }
              ]
            }
            """.trimIndent()
        val tcpSession =
            VpnSession(
                profileId = 1L,
                profileName = "tcp",
                protocolHint = ProtocolHint.VLESS,
                configJson = tcpConfig,
                correlationId = "s-tcp",
            )
        val udpSession =
            tcpSession.copy(
                profileName = "udp",
                protocolHint = ProtocolHint.HYSTERIA2,
                configJson = udpConfig,
            )

        assertEquals(VpnHealthProbeTransport.TCP, tcpRuntimeReadinessTarget(tcpSession)?.transport)
        assertNull(tcpRuntimeReadinessTarget(udpSession))
    }

    @Test
    fun `uses explicit udp network for non udp default outbounds`() {
        val target =
            VpnHealthProbeTargetSelector.select(
                """
                {
                  "outbounds": [
                    { "type": "vless", "tag": "edge", "server": "edge.example.com", "server_port": 443, "network": "udp" },
                    { "type": "selector", "tag": "proxy", "outbounds": ["edge"] }
                  ]
                }
                """.trimIndent(),
            )

        requireNotNull(target)
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
