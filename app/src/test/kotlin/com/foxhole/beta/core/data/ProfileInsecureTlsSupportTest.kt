package com.foxhole.beta.core.data

import com.foxhole.beta.core.model.ProtocolHint
import com.foxhole.beta.core.model.StoredProfileProtocolOption
import com.foxhole.beta.core.model.StoredProfileSecret
import kotlinx.serialization.json.Json
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
}
