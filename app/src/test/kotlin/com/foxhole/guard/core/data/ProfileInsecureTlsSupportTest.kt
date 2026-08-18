package com.foxhole.guard.core.data

import com.foxhole.core.model.ParsedImport
import com.foxhole.core.model.ParsedSubscriptionImport
import com.foxhole.core.model.ParsedSubscriptionProfile
import com.foxhole.core.model.ProfileSourceType
import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.StoredProfileProtocolOption
import com.foxhole.core.model.StoredProfileSecret
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileInsecureTlsSupportTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `detects insecure tls flags in normalized config json`() {
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
    fun `detector flags every spelling the strict importer rejects`() {
        assertTrue("""{"outbounds":[{"tls":{"allowInsecure":true}}]}""".requiresInsecureTls(json))
        assertTrue("""{"outbounds":[{"tls":{"allow_insecure":true}}]}""".requiresInsecureTls(json))
        assertTrue("""{"outbounds":[{"tls":{"Insecure":true}}]}""".requiresInsecureTls(json))
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
    fun `runtime insecure tls allowance ignores raw option markers without consent`() {
        val secret =
            StoredProfileSecret(
                requiresInsecureTls = false,
                insecureTlsConsentGranted = false,
                protocolOptions =
                listOf(
                    StoredProfileProtocolOption(
                        id = "hysteria2",
                        displayName = "Hysteria2",
                        protocolHint = ProtocolHint.HYSTERIA2,
                        normalizedConfigJson = insecureConfig(ProtocolHint.HYSTERIA2),
                        requiresInsecureTls = true,
                    ),
                ),
            )

        assertFalse(
            allowsInsecureTlsForStoredProfileRuntime(
                allowInsecureTlsGlobally = false,
                secret = secret,
            ),
        )
        assertTrue(
            allowsInsecureTlsForStoredProfileRuntime(
                allowInsecureTlsGlobally = true,
                secret = secret,
            ),
        )
    }

    @Test
    fun `legacy stored profile-level insecure tls marker counts as refresh consent`() {
        val secret = StoredProfileSecret(requiresInsecureTls = true)

        assertTrue(secret.hasInsecureTlsConsent())
        assertTrue(
            allowsInsecureTlsForStoredProfileRuntime(
                allowInsecureTlsGlobally = false,
                secret = secret,
            ),
        )
    }

    @Test
    fun `explicit stored insecure tls denial overrides profile marker for runtime`() {
        val secret =
            StoredProfileSecret(
                requiresInsecureTls = true,
                insecureTlsConsentGranted = false,
            )

        assertFalse(secret.hasInsecureTlsConsent())
        assertFalse(
            allowsInsecureTlsForStoredProfileRuntime(
                allowInsecureTlsGlobally = false,
                secret = secret,
            ),
        )
    }

    @Test
    fun `warning describes insecure smart protocol and allows excluding it when secure options remain`() {
        val parsed =
            ParsedImport(
                sourceType = ProfileSourceType.RAW_CONFIG_JSON,
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
                sourceType = ProfileSourceType.RAW_CONFIG_JSON,
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

    @Test
    fun `warning allows excluding insecure option inside grouped subscription profile`() {
        val parsed =
            ParsedSubscriptionImport(
                displayName = "vpn.example.com",
                profiles =
                listOf(
                    ParsedSubscriptionProfile(
                        displayName = "vpn.example.com",
                        protocolHint = ProtocolHint.HYSTERIA2,
                        normalizedConfigJson = insecureConfig(ProtocolHint.HYSTERIA2),
                        protocolOptions =
                        listOf(
                            option("hysteria2", ProtocolHint.HYSTERIA2, insecureConfig(ProtocolHint.HYSTERIA2)),
                            option("vless", ProtocolHint.VLESS, secureConfig(ProtocolHint.VLESS)),
                        ),
                        selectedProtocolOptionId = "hysteria2",
                    ),
                ),
            )

        val warning = requireNotNull(parsed.insecureTlsImportWarning(json))
        val filtered = parsed.withoutInsecureTlsOptions(json)
        val profile = filtered.profiles.single()

        assertTrue(warning.canExcludeAndApply)
        assertEquals(listOf("HYSTERIA2"), warning.issues.map(InsecureTlsImportIssue::protocolLabel))
        assertFalse(filtered.requiresInsecureTls(json))
        assertEquals(ProtocolHint.VLESS, profile.protocolHint)
        assertEquals("vless", profile.selectedProtocolOptionId)
        assertEquals(listOf("vless"), profile.protocolOptions.map(StoredProfileProtocolOption::id))
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
