package com.foxhole.beta.core.data

import com.foxhole.beta.core.model.ParsedImport
import com.foxhole.beta.core.model.ParsedSubscriptionImport
import com.foxhole.beta.core.model.ParsedSubscriptionProfile
import com.foxhole.beta.core.model.ProfileSourceType
import com.foxhole.beta.core.model.ProtocolHint
import com.foxhole.beta.core.model.StoredProfileProtocolOption
import com.foxhole.beta.core.model.StoredProfileSecret
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileInsecureTlsSupportTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `detects insecure tls flags in nested sing-box json`() {
        val config =
            """
            {
              "outbounds": [
                {
                  "type": "hysteria2",
                  "tag": "proxy",
                  "tls": {
                    "enabled": true,
                    "insecure": true
                  }
                }
              ]
            }
            """.trimIndent()

        assertTrue(config.requiresInsecureTls(json))
    }

    @Test
    fun `does not mark secure tls config as insecure`() {
        val config =
            """
            {
              "outbounds": [
                {
                  "type": "vless",
                  "tag": "proxy",
                  "tls": {
                    "enabled": true,
                    "server_name": "example.org"
                  }
                }
              ]
            }
            """.trimIndent()

        assertFalse(config.requiresInsecureTls(json))
    }

    @Test
    fun `marks profile secret when any protocol option requires insecure tls`() {
        val secret =
            StoredProfileSecret(
                protocolOptions =
                    listOf(
                        StoredProfileProtocolOption(
                            id = "hysteria2",
                            displayName = "Hysteria2",
                            protocolHint = ProtocolHint.HYSTERIA2,
                            normalizedConfigJson =
                                """
                                {
                                  "outbounds": [
                                    {
                                      "type": "hysteria2",
                                      "tag": "proxy",
                                      "tls": { "enabled": true, "insecure": true }
                                    }
                                  ]
                                }
                                """.trimIndent(),
                        ),
                    ),
            )

        val marked = secret.withInsecureTlsMarkers(json)

        assertTrue(marked.requiresInsecureTls)
        assertTrue(marked.protocolOptions.single().requiresInsecureTls)
    }

    @Test
    fun `subscription refresh does not ask for insecure tls consent after global or profile consent`() {
        assertFalse(
            shouldRequireInsecureTlsRefreshConsent(
                allowInsecureTlsGlobally = true,
                profileInsecureTlsConsentGranted = false,
                strictParseFailedForInsecureTls = true,
            ),
        )
        assertFalse(
            shouldRequireInsecureTlsRefreshConsent(
                allowInsecureTlsGlobally = false,
                profileInsecureTlsConsentGranted = true,
                strictParseFailedForInsecureTls = true,
            ),
        )
        assertTrue(
            shouldRequireInsecureTlsRefreshConsent(
                allowInsecureTlsGlobally = false,
                profileInsecureTlsConsentGranted = false,
                strictParseFailedForInsecureTls = true,
            ),
        )
    }

    @Test
    fun `stored insecure tls consent is separate from raw option marker`() {
        val secret =
            StoredProfileSecret(
                requiresInsecureTls = false,
                protocolOptions =
                    listOf(
                        StoredProfileProtocolOption(
                            id = "legacy-hysteria2",
                            displayName = "Legacy Hysteria2",
                            protocolHint = ProtocolHint.HYSTERIA2,
                            normalizedConfigJson =
                                """
                                {
                                  "outbounds": [
                                    {
                                      "type": "hysteria2",
                                      "tag": "proxy",
                                      "tls": { "enabled": true, "insecure": true }
                                    }
                                  ]
                                }
                                """.trimIndent(),
                        ),
                    ),
            )

        assertFalse(secret.hasInsecureTlsConsent())
    }

    @Test
    fun `legacy stored profile-level insecure tls marker counts as refresh consent`() {
        val secret = StoredProfileSecret(requiresInsecureTls = true)

        assertTrue(secret.hasInsecureTlsConsent())
        assertFalse(
            shouldRequireInsecureTlsRefreshConsent(
                allowInsecureTlsGlobally = false,
                profileInsecureTlsConsentGranted = secret.hasInsecureTlsConsent(),
                strictParseFailedForInsecureTls = true,
            ),
        )
    }

    @Test
    fun `warning describes insecure smart protocol and allows excluding it when secure options remain`() {
        val parsed =
            ParsedImport(
                sourceType = ProfileSourceType.RAW_SINGBOX_JSON,
                protocolHint = ProtocolHint.VLESS,
                displayName = "Smart",
                normalizedConfigJson = secureConfig(ProtocolHint.VLESS),
                protocolOptions =
                    listOf(
                        option("vless", ProtocolHint.VLESS, secureConfig(ProtocolHint.VLESS)),
                        option("trojan", ProtocolHint.TROJAN, insecureConfig(ProtocolHint.TROJAN)),
                    ),
                selectedProtocolOptionId = "trojan",
            )

        val warning = requireNotNull(parsed.insecureTlsImportWarning(json))
        val filtered = parsed.withoutInsecureTlsOptions(json)

        assertTrue(warning.canExcludeAndApply)
        assertTrue(warning.issues.any { issue -> issue.protocolLabel == "TROJAN" })
        assertFalse(filtered.requiresInsecureTls(json))
        assertTrue(filtered.protocolOptions.single().id == "vless")
        assertTrue(filtered.selectedProtocolOptionId == "vless")
    }

    @Test
    fun `warning lists risky smart profile protocols using insecure tls`() {
        val parsed =
            ParsedImport(
                sourceType = ProfileSourceType.RAW_SINGBOX_JSON,
                protocolHint = ProtocolHint.VLESS,
                displayName = "Smart",
                normalizedConfigJson = secureConfig(ProtocolHint.VLESS),
                protocolOptions =
                    listOf(
                        option("vless", ProtocolHint.VLESS, insecureConfig(ProtocolHint.VLESS)),
                        option("trojan", ProtocolHint.TROJAN, insecureConfig(ProtocolHint.TROJAN)),
                        option("vmess", ProtocolHint.VMESS, insecureConfig(ProtocolHint.VMESS)),
                        option("hysteria2", ProtocolHint.HYSTERIA2, insecureConfig(ProtocolHint.HYSTERIA2)),
                        option("shadowsocks", ProtocolHint.SHADOWSOCKS, insecureConfig(ProtocolHint.SHADOWSOCKS)),
                        option("outline", ProtocolHint.OUTLINE, insecureConfig(ProtocolHint.OUTLINE)),
                        option("wireguard", ProtocolHint.WIREGUARD, insecureConfig(ProtocolHint.WIREGUARD)),
                    ),
                selectedProtocolOptionId = "vless",
            )

        val warning = requireNotNull(parsed.insecureTlsImportWarning(json))

        assertEquals(
            listOf("VLESS", "TROJAN", "HYSTERIA2"),
            warning.issues.map(InsecureTlsImportIssue::protocolLabel),
        )
    }

    @Test
    fun `exclude insecure tls drops fully insecure subscription profiles`() {
        val parsed =
            ParsedSubscriptionImport(
                displayName = "Smart",
                profiles =
                    listOf(
                        ParsedSubscriptionProfile(
                            displayName = "Secure",
                            protocolHint = ProtocolHint.VLESS,
                            normalizedConfigJson = secureConfig(ProtocolHint.VLESS),
                            protocolOptions =
                                listOf(
                                    option("vless", ProtocolHint.VLESS, secureConfig(ProtocolHint.VLESS)),
                                ),
                            selectedProtocolOptionId = "vless",
                        ),
                        ParsedSubscriptionProfile(
                            displayName = "Unsafe",
                            protocolHint = ProtocolHint.HYSTERIA2,
                            normalizedConfigJson = insecureConfig(ProtocolHint.HYSTERIA2),
                            protocolOptions =
                                listOf(
                                    option("hysteria2", ProtocolHint.HYSTERIA2, insecureConfig(ProtocolHint.HYSTERIA2)),
                                ),
                            selectedProtocolOptionId = "hysteria2",
                        ),
                    ),
            )

        val filtered = parsed.withoutInsecureTlsOptions(json)

        assertTrue(filtered.profiles.single().displayName == "Secure")
        assertFalse(filtered.requiresInsecureTls(json))
    }

    private fun option(
        id: String,
        protocolHint: ProtocolHint,
        normalizedConfigJson: String,
    ): StoredProfileProtocolOption =
        StoredProfileProtocolOption(
            id = id,
            displayName = protocolHint.name,
            protocolHint = protocolHint,
            normalizedConfigJson = normalizedConfigJson,
        )

    private fun secureConfig(protocolHint: ProtocolHint): String =
        """
        {
          "outbounds": [
            {
              "type": "${protocolHint.name.lowercase()}",
              "tag": "proxy",
              "tls": { "enabled": true }
            }
          ]
        }
        """.trimIndent()

    private fun insecureConfig(protocolHint: ProtocolHint): String =
        """
        {
          "outbounds": [
            {
              "type": "${protocolHint.name.lowercase()}",
              "tag": "proxy",
              "tls": { "enabled": true, "insecure": true }
            }
          ]
        }
        """.trimIndent()
}
