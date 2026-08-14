package com.foxhole.core.importer

import com.foxhole.core.model.ProfileSourceType
import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.network.testRemoteHostResolver
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Base64

class ProfileImportStrategyGoldenTest {
    private val json =
        Json {
            prettyPrint = true
            explicitNulls = false
            ignoreUnknownKeys = true
        }
    private val parser =
        ProfileImportParser(
            json = json,
            remoteHostResolver = testRemoteHostResolver(),
        )

    @Test
    fun `user input strategy order is locked`() {
        assertEquals(
            listOf(
                ProfileImportStrategyId.SUBSCRIPTION_URL,
                ProfileImportStrategyId.SMART_CONFIG,
                ProfileImportStrategyId.WIREGUARD_TEXT,
                ProfileImportStrategyId.RAW_XRAY_JSON,
                ProfileImportStrategyId.DIRECT_NODE_LINES,
            ),
            orderedUserInputStrategyIds(),
        )
    }

    @Test
    fun `subscription url strategy is selected first`() {
        val result = parser.parseUserInputWithStrategy("https://example.org/subscription")

        assertEquals(ProfileImportStrategyId.SUBSCRIPTION_URL, result.strategyId)
        assertEquals(ProfileSourceType.SUBSCRIPTION_URL, result.parsed.sourceType)
    }

    @Test
    fun `smart config strategy is selected before direct node lines`() {
        val result = parser.parseUserInputWithStrategy(smartConfigPayload())

        assertEquals(ProfileImportStrategyId.SMART_CONFIG, result.strategyId)
        assertEquals(ProtocolHint.VLESS, result.parsed.protocolHint)
        assertEquals(1, result.parsed.nodesCount)
    }

    @Test
    fun `wireguard text strategy is selected before json and direct nodes`() {
        val result = parser.parseUserInputWithStrategy(wireGuardPayload())

        assertEquals(ProfileImportStrategyId.WIREGUARD_TEXT, result.strategyId)
        assertEquals(ProfileSourceType.RAW_WIREGUARD_TEXT, result.parsed.sourceType)
        assertEquals(ProtocolHint.WIREGUARD, result.parsed.protocolHint)
    }

    @Test
    fun `raw xray json strategy is selected when outbounds protocol is present`() {
        val result = parser.parseUserInputWithStrategy(rawXrayPayload())

        assertEquals(ProfileImportStrategyId.RAW_XRAY_JSON, result.strategyId)
        assertEquals(ProtocolHint.VLESS, result.parsed.protocolHint)
        assertEquals("xray edge", result.parsed.displayName)
    }

    @Test
    fun `retired engine documents are not accepted as user profiles`() {
        assertThrows(IllegalArgumentException::class.java) {
            parser.parseUserInputWithStrategy(retiredEnginePayload())
        }
    }

    @Test
    fun `json with protocol and type still uses the xray converter`() {
        val result =
            parser.parseUserInputWithStrategy(
                """
                {
                  "outbounds": [
                    {
                      "type": "vless",
                      "protocol": "vless",
                      "settings": {
                        "vnext": [
                          {
                            "address": "node.example.org",
                            "port": 443,
                            "users": [
                              { "id": "$UUID" }
                            ]
                          }
                        ]
                      },
                      "tag": "proxy"
                    }
                  ],
                  "remarks": "hybrid json"
                }
                """.trimIndent(),
            )

        assertEquals(ProfileImportStrategyId.RAW_XRAY_JSON, result.strategyId)
        assertEquals("hybrid json", result.parsed.displayName)
    }

    @Test
    fun `direct mixed node lines strategy is selected last`() {
        val result =
            parser.parseUserInputWithStrategy(
                """
                ${vlessUri("vless-one")}
                trojan://secret@node.example.org:443?security=tls#trojan-one
                """.trimIndent(),
            )

        assertEquals(ProfileImportStrategyId.DIRECT_NODE_LINES, result.strategyId)
        assertEquals(ProfileSourceType.SHARE_URI, result.parsed.sourceType)
        assertEquals(2, result.parsed.nodesCount)
    }

    @Test
    fun `base64 subscription payload strategy preserves decoded subscription behavior`() {
        val encoded =
            Base64.getEncoder().encodeToString(
                """
                ${vlessUri("vless-one")}
                trojan://secret@node.example.org:443?security=tls#trojan-one
                """.trimIndent().toByteArray(),
            )

        val result = parser.parseSubscriptionContentWithStrategy(encoded, "remote")

        assertEquals(ProfileSubscriptionContentStrategyId.BASE64_SUBSCRIPTION_PAYLOAD, result.strategyId)
        assertEquals(ProfileSourceType.SUBSCRIPTION_URL, result.parsed.sourceType)
        assertEquals(2, result.parsed.nodesCount)
    }

    @Test
    fun `hostile unicode padding does not change direct node strategy`() {
        val result = parser.parseUserInputWithStrategy("\u200B\u200E${vlessUri("unicode-node")}\u00A0")

        assertEquals(ProfileImportStrategyId.DIRECT_NODE_LINES, result.strategyId)
        assertEquals("unicode-node", result.parsed.displayName)
    }

    @Test
    fun `huge subscription-style node payload keeps direct node strategy`() {
        // Distinct servers per line — identity de-dup collapses genuinely identical outbounds, so a
        // realistic large subscription must vary the host, not just the #name.
        val payload =
            (1..128)
                .joinToString(separator = "\n") { index ->
                    "vless://$UUID@node-$index.example.org:443?encryption=none&security=none&type=tcp#node-$index"
                }

        val result = parser.parseUserInputWithStrategy(payload)

        assertEquals(ProfileImportStrategyId.DIRECT_NODE_LINES, result.strategyId)
        assertEquals(128, result.parsed.nodesCount)
    }

    @Test
    fun `mixed payload keeps supported nodes and ignores unknown protocols`() {
        val result =
            parser.parseUserInputWithStrategy(
                """
                ${vlessUri("valid")}
                ssr://secret@example.org:443
                """.trimIndent(),
            )

        assertEquals(ProfileImportStrategyId.DIRECT_NODE_LINES, result.strategyId)
        assertEquals(1, result.parsed.nodesCount)
    }

    @Test
    fun `subscription content strategy order is locked`() {
        assertEquals(
            listOf(
                ProfileSubscriptionContentStrategyId.USER_INPUT,
                ProfileSubscriptionContentStrategyId.SMART_CONFIG_PAYLOAD,
                ProfileSubscriptionContentStrategyId.BASE64_SUBSCRIPTION_PAYLOAD,
                ProfileSubscriptionContentStrategyId.DIRECT_SUBSCRIPTION_PAYLOAD,
            ),
            orderedSubscriptionContentStrategyIds(),
        )
    }

    private fun smartConfigPayload(): String =
        """
        # === vless / direct ===
        ${vlessUri("smart-direct")}
        """.trimIndent()

    private fun wireGuardPayload(): String =
        """
        [Interface]
        PrivateKey = private
        Address = 10.0.0.2/32

        [Peer]
        PublicKey = public
        Endpoint = wg.example.org:51820
        AllowedIPs = 0.0.0.0/0, ::/0
        """.trimIndent()

    private fun rawXrayPayload(): String =
        """
        {
          "outbounds": [
            {
              "protocol": "vless",
              "settings": {
                "vnext": [
                  {
                    "address": "node.example.org",
                    "port": 443,
                    "users": [
                      { "id": "$UUID" }
                    ]
                  }
                ]
              },
              "tag": "proxy"
            }
          ],
          "remarks": "xray edge"
        }
        """.trimIndent()

    private fun retiredEnginePayload(): String =
        """
        {
          "outbounds": [
            {
              "type": "vless",
              "tag": "proxy",
              "server": "node.example.org",
              "server_port": 443,
              "uuid": "$UUID"
            }
          ]
        }
        """.trimIndent()

    private fun vlessUri(name: String): String =
        "vless://$UUID@node.example.org:443?encryption=none&security=none&type=tcp#$name"

    private companion object {
        const val UUID = "11111111-1111-1111-1111-111111111111"
    }
}

// Enumeration mirrors for the golden sweep; only this test iterates the full strategy set.
private fun orderedUserInputStrategyIds(): List<ProfileImportStrategyId> =
    listOf(
        ProfileImportStrategyId.SUBSCRIPTION_URL,
        ProfileImportStrategyId.SMART_CONFIG,
        ProfileImportStrategyId.WIREGUARD_TEXT,
        ProfileImportStrategyId.RAW_XRAY_JSON,
        ProfileImportStrategyId.DIRECT_NODE_LINES,
    )

private fun orderedSubscriptionContentStrategyIds(): List<ProfileSubscriptionContentStrategyId> =
    listOf(
        ProfileSubscriptionContentStrategyId.USER_INPUT,
        ProfileSubscriptionContentStrategyId.SMART_CONFIG_PAYLOAD,
        ProfileSubscriptionContentStrategyId.BASE64_SUBSCRIPTION_PAYLOAD,
        ProfileSubscriptionContentStrategyId.DIRECT_SUBSCRIPTION_PAYLOAD,
    )
