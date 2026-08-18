package com.foxhole.core.runtime

import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.VpnSession
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

internal class FoxCoreConfigTranslatorRejectionTest : FoxCoreConfigTranslatorTestSupport() {
    @Test
    fun `runtime contract refuses an unprepared legacy session`() {
        val error =
            assertThrows(FoxCoreConfigTranslationException::class.java) {
                translator.requirePrepared(
                    VpnSession(
                        profileId = 9L,
                        profileName = "legacy fixture",
                        protocolHint = ProtocolHint.VLESS,
                        configJson = legacyConfig(vless()).toString(),
                        correlationId = "unprepared-contract-correlation",
                    ),
                )
            }

        assertEquals(FoxCoreConfigRejection.PREPARED_CONFIG_MISSING, error.rejection)
        assertEquals("$.foxcore_config", error.path)
    }

    @Test
    fun `unsupported profile kinds and protocols never enter a compatibility path`() {
        val valid = legacyConfig(vless())
        val cases =
            listOf(
                RejectionCase(
                    name = "raw custom config profile",
                    hint = ProtocolHint.CUSTOM_CONFIG,
                    config = valid,
                    expected = FoxCoreConfigRejection.PROFILE_KIND_UNSUPPORTED,
                ),
                RejectionCase(
                    name = "unknown profile",
                    hint = ProtocolHint.UNKNOWN,
                    config = valid,
                    expected = FoxCoreConfigRejection.PROFILE_KIND_UNSUPPORTED,
                ),
                RejectionCase(
                    name = "SSH outbound",
                    hint = ProtocolHint.VLESS,
                    config = legacyConfig(primary("ssh") {}),
                    expected = FoxCoreConfigRejection.PROTOCOL_UNSUPPORTED,
                ),
                RejectionCase(
                    name = "ShadowsocksR outbound",
                    hint = ProtocolHint.SHADOWSOCKS,
                    config = legacyConfig(primary("shadowsocksr") {}),
                    expected = FoxCoreConfigRejection.PROTOCOL_UNSUPPORTED,
                ),
                RejectionCase(
                    name = "hint does not match typed outbound",
                    hint = ProtocolHint.VLESS,
                    config = legacyConfig(primary("trojan") {}),
                    expected = FoxCoreConfigRejection.PROTOCOL_MISMATCH,
                ),
            )

        assertRejected(cases)
    }

    @Test
    fun `unknown and effectful fields are refused instead of being dropped`() {
        val cases =
            listOf(
                RejectionCase(
                    name = "unknown root field",
                    hint = ProtocolHint.VLESS,
                    config =
                    legacyConfig(vless()).withRootValue(
                        "legacy_runtime",
                        JsonPrimitive(true),
                    ),
                    expected = FoxCoreConfigRejection.UNSUPPORTED_FIELD,
                ),
                RejectionCase(
                    name = "unknown outbound field",
                    hint = ProtocolHint.VLESS,
                    config =
                    legacyConfig(
                        vless {
                            put("fallback", "direct")
                        },
                    ),
                    expected = FoxCoreConfigRejection.UNSUPPORTED_FIELD,
                ),
                RejectionCase(
                    name = "non-empty experimental surface",
                    hint = ProtocolHint.VLESS,
                    config =
                    legacyConfig(vless()).withRootValue(
                        "experimental",
                        buildJsonObject { put("cache_file", true) },
                    ),
                    expected = FoxCoreConfigRejection.EFFECTFUL_CONFIG_UNSUPPORTED,
                ),
                RejectionCase(
                    name = "unmanaged inbound",
                    hint = ProtocolHint.VLESS,
                    config =
                    legacyConfig(
                        primary = vless(),
                        inbounds =
                        listOf(
                            managedTun(),
                            buildJsonObject {
                                put("type", "http")
                                put("tag", "public-http")
                                put("listen", "0.0.0.0")
                                put("listen_port", 8080)
                                put(
                                    "users",
                                    buildJsonArray {
                                        add(
                                            buildJsonObject {
                                                put("username", "user")
                                                put("password", "password")
                                            },
                                        )
                                    },
                                )
                            },
                        ),
                    ),
                    expected = FoxCoreConfigRejection.EFFECTFUL_CONFIG_UNSUPPORTED,
                ),
                RejectionCase(
                    name = "runtime control proxy address is not configurable",
                    hint = ProtocolHint.VLESS,
                    config =
                    legacyConfig(
                        primary = vless(),
                        inbounds =
                        listOf(
                            managedTun(),
                            buildJsonObject {
                                managedRuntimeInbound().forEach { (key, value) ->
                                    if (key != "listen") {
                                        put(key, value)
                                    }
                                }
                                put("listen", "::1")
                            },
                        ),
                    ),
                    expected = FoxCoreConfigRejection.EFFECTFUL_CONFIG_UNSUPPORTED,
                ),
                RejectionCase(
                    name = "duplicate runtime control proxies",
                    hint = ProtocolHint.VLESS,
                    config =
                    legacyConfig(
                        primary = vless(),
                        inbounds =
                        listOf(
                            managedTun(),
                            managedRuntimeInbound(port = 18_809),
                            managedRuntimeInbound(port = 18_810),
                        ),
                    ),
                    expected = FoxCoreConfigRejection.EFFECTFUL_CONFIG_UNSUPPORTED,
                ),
                RejectionCase(
                    name = "TUN routing omitted",
                    hint = ProtocolHint.VLESS,
                    config =
                    legacyConfig(
                        primary = vless(),
                        inbounds =
                        listOf(
                            buildJsonObject {
                                managedTun().forEach { (key, value) ->
                                    if (key != "auto_route") {
                                        put(key, value)
                                    }
                                }
                            },
                        ),
                    ),
                    expected = FoxCoreConfigRejection.EFFECTFUL_CONFIG_UNSUPPORTED,
                ),
            )

        assertRejected(cases)
    }

    @Test
    fun `wire security and transport downgrades are typed refusals`() {
        val cases =
            listOf(
                RejectionCase(
                    "unsupported VLESS transport",
                    ProtocolHint.VLESS,
                    legacyConfig(
                        vless {
                            put("transport", buildJsonObject { put("type", "kcp") })
                        },
                    ),
                    FoxCoreConfigRejection.UNSUPPORTED_TRANSPORT,
                ),
                RejectionCase(
                    "ECH cannot be silently removed",
                    ProtocolHint.VLESS,
                    legacyConfig(
                        vless {
                            put(
                                "tls",
                                tls {
                                    put("ech", buildJsonObject { put("enabled", true) })
                                },
                            )
                        },
                    ),
                    FoxCoreConfigRejection.UNSUPPORTED_SECURITY,
                ),
                RejectionCase(
                    "uTLS outside Reality cannot be silently removed",
                    ProtocolHint.VLESS,
                    legacyConfig(
                        vless {
                            put(
                                "tls",
                                tls {
                                    put(
                                        "utls",
                                        buildJsonObject {
                                            put("enabled", true)
                                            put("fingerprint", "chrome")
                                        },
                                    )
                                },
                            )
                        },
                    ),
                    FoxCoreConfigRejection.UNSUPPORTED_SECURITY,
                ),
                RejectionCase(
                    "legacy VMess alter id",
                    ProtocolHint.VMESS,
                    legacyConfig(
                        proxyWithServer("vmess") {
                            put("uuid", CONTRACT_UUID)
                            put("alter_id", 64)
                        },
                    ),
                    FoxCoreConfigRejection.UNSUPPORTED_SECURITY,
                ),
                RejectionCase(
                    "TUIC BBR",
                    ProtocolHint.TUIC,
                    legacyConfig(
                        proxyWithServer("tuic") {
                            put("uuid", CONTRACT_UUID)
                            put("password", "password")
                            put("congestion_control", "bbr")
                            put("tls", tls())
                        },
                    ),
                    FoxCoreConfigRejection.UNSUPPORTED_TRANSPORT,
                ),
                RejectionCase(
                    "TUIC zero RTT",
                    ProtocolHint.TUIC,
                    legacyConfig(
                        proxyWithServer("tuic") {
                            put("uuid", CONTRACT_UUID)
                            put("password", "password")
                            put("zero_rtt_handshake", true)
                            put("tls", tls())
                        },
                    ),
                    FoxCoreConfigRejection.UNSUPPORTED_SECURITY,
                ),
                RejectionCase(
                    "Naive QUIC",
                    ProtocolHint.NAIVE,
                    legacyConfig(
                        proxyWithServer("naive") {
                            put("username", "user")
                            put("password", "password")
                            put("quic", true)
                            put("tls", tls())
                        },
                    ),
                    FoxCoreConfigRejection.UNSUPPORTED_TRANSPORT,
                ),
            )

        assertRejected(cases)
    }

    @Test
    fun `Tor I2P and packet tunnel effects cannot be weakened during migration`() {
        val cases =
            listOf(
                RejectionCase(
                    "engaged Tor session without a Tor outbound",
                    ProtocolHint.VLESS,
                    legacyConfig(vless()),
                    FoxCoreConfigRejection.OVERLAY_UNAVAILABLE,
                    torActive = true,
                ),
                RejectionCase(
                    "unengaged Tor outbound cannot stay live",
                    ProtocolHint.VLESS,
                    legacyConfig(vless(), extraOutbounds = listOf(legacyTor())),
                    FoxCoreConfigRejection.OVERLAY_CONFIGURATION_UNSUPPORTED,
                ),
                RejectionCase(
                    "opaque bridge and pluggable transport file",
                    ProtocolHint.VLESS,
                    legacyConfig(
                        vless(),
                        extraOutbounds =
                        listOf(
                            legacyTor {
                                put(
                                    "extra_args",
                                    JsonArray(
                                        listOf(
                                            JsonPrimitive("--defaults-torrc"),
                                            JsonPrimitive("/data/user/0/test/files/torrc-defaults"),
                                        ),
                                    ),
                                )
                            },
                        ),
                    ),
                    FoxCoreConfigRejection.OVERLAY_CONFIGURATION_UNSUPPORTED,
                    torActive = true,
                ),
                RejectionCase(
                    "relative Arti state path",
                    ProtocolHint.VLESS,
                    legacyConfig(
                        vless(),
                        extraOutbounds =
                        listOf(
                            legacyTor {
                                put("data_directory", "relative/arti")
                            },
                        ),
                    ),
                    FoxCoreConfigRejection.OVERLAY_CONFIGURATION_UNSUPPORTED,
                    torActive = true,
                ),
                RejectionCase(
                    "unknown legacy Tor directive",
                    ProtocolHint.VLESS,
                    legacyConfig(
                        vless(),
                        extraOutbounds =
                        listOf(
                            legacyTor {
                                put("SocksPort", "9050")
                            },
                        ),
                    ),
                    FoxCoreConfigRejection.UNSUPPORTED_FIELD,
                    torActive = true,
                ),
                RejectionCase(
                    "half-paired I2P authentication",
                    ProtocolHint.VLESS,
                    legacyConfig(
                        vless(),
                        extraOutbounds = listOf(legacyI2p(username = "runtime-user")),
                    ),
                    FoxCoreConfigRejection.OVERLAY_CONFIGURATION_UNSUPPORTED,
                ),
                RejectionCase(
                    "blank I2P authentication",
                    ProtocolHint.VLESS,
                    legacyConfig(
                        vless(),
                        extraOutbounds =
                        listOf(
                            legacyI2p(
                                username = "runtime-user",
                                password = "",
                            ),
                        ),
                    ),
                    FoxCoreConfigRejection.OVERLAY_CONFIGURATION_UNSUPPORTED,
                ),
                RejectionCase(
                    "oversized I2P authentication",
                    ProtocolHint.VLESS,
                    legacyConfig(
                        vless(),
                        extraOutbounds =
                        listOf(
                            legacyI2p(
                                username = "я".repeat(128),
                                password = "runtime-password",
                            ),
                        ),
                    ),
                    FoxCoreConfigRejection.OVERLAY_CONFIGURATION_UNSUPPORTED,
                ),
                RejectionCase(
                    "non-loopback I2P endpoint",
                    ProtocolHint.VLESS,
                    legacyConfig(
                        vless(),
                        extraOutbounds =
                        listOf(
                            legacyI2p(
                                host = "192.0.2.44",
                                username = "runtime-user",
                                password = "runtime-password",
                            ),
                        ),
                    ),
                    FoxCoreConfigRejection.OVERLAY_CONFIGURATION_UNSUPPORTED,
                ),
                RejectionCase(
                    "unknown I2P socket option",
                    ProtocolHint.VLESS,
                    legacyConfig(
                        vless(),
                        extraOutbounds =
                        listOf(
                            legacyI2p(
                                username = "runtime-user",
                                password = "runtime-password",
                            ).let { source ->
                                buildJsonObject {
                                    source.forEach { (key, value) -> put(key, value) }
                                    put("unix_path", "/data/user/0/test/files/i2pd.sock")
                                }
                            },
                        ),
                    ),
                    FoxCoreConfigRejection.UNSUPPORTED_FIELD,
                ),
                RejectionCase(
                    "multiple WireGuard peers cannot collapse to one",
                    ProtocolHint.WIREGUARD,
                    legacyConfig(wireGuard(peerCount = 2)),
                    FoxCoreConfigRejection.EFFECTFUL_CONFIG_UNSUPPORTED,
                ),
            )

        assertRejected(cases)
    }

    @Test
    fun `unrepresentable DNS and route policy cannot become permissive`() {
        val keywordRule =
            buildJsonObject {
                put("domain_keyword", "bank")
                put("action", "route")
                put("outbound", "direct")
            }
        val regexRule =
            buildJsonObject {
                put("domain_regex", ".*")
                put("action", "route")
                put("outbound", "direct")
            }
        val unknownDns =
            buildJsonObject {
                put("tag", "dns-unused")
                put("type", "script")
                put("server", "198.51.100.53")
            }
        val unknownDnsField =
            buildJsonObject {
                put("tag", "dns-unused")
                put("type", "udp")
                put("server", "198.51.100.53")
                put("server_port", 53)
                put("fallback", "system")
            }
        val perAppDnsRule =
            buildJsonObject {
                put("package_name", JsonArray(listOf(JsonPrimitive("com.example.direct"))))
                put("action", "route")
                put("server", "dns-direct")
            }
        val cases =
            listOf(
                RejectionCase(
                    "domain keyword matcher",
                    ProtocolHint.VLESS,
                    legacyConfig(vless(), routeRules = managedRouteRules() + keywordRule),
                    FoxCoreConfigRejection.POLICY_UNREPRESENTABLE,
                ),
                RejectionCase(
                    "domain regex matcher",
                    ProtocolHint.VLESS,
                    legacyConfig(vless(), routeRules = managedRouteRules() + regexRule),
                    FoxCoreConfigRejection.POLICY_UNREPRESENTABLE,
                ),
                RejectionCase(
                    "unknown unselected DNS implementation",
                    ProtocolHint.VLESS,
                    legacyConfig(vless(), dnsServers = managedDnsServers() + unknownDns),
                    FoxCoreConfigRejection.EFFECTFUL_CONFIG_UNSUPPORTED,
                ),
                RejectionCase(
                    "unknown unselected DNS option",
                    ProtocolHint.VLESS,
                    legacyConfig(vless(), dnsServers = managedDnsServers() + unknownDnsField),
                    FoxCoreConfigRejection.UNSUPPORTED_FIELD,
                ),
                RejectionCase(
                    "system local DNS cannot become a different upstream",
                    ProtocolHint.VLESS,
                    configWithDnsFinal("dns-direct"),
                    FoxCoreConfigRejection.POLICY_UNREPRESENTABLE,
                ),
                RejectionCase(
                    "per-app DNS route has no schema-v1 equivalent",
                    ProtocolHint.VLESS,
                    legacyConfig(vless(), dnsRules = listOf(perAppDnsRule)),
                    FoxCoreConfigRejection.POLICY_UNREPRESENTABLE,
                ),
            )

        assertRejected(cases)
    }

    @Test
    fun `malformed input and refusal messages never expose source values`() {
        val secret = "never-log-this-profile-secret"
        val malformed =
            VpnSession(
                profileId = 9L,
                profileName = secret,
                protocolHint = ProtocolHint.VLESS,
                configJson = """{"password":"$secret"""",
                correlationId = secret,
            )
        val malformedFailure =
            assertThrows(FoxCoreConfigTranslationException::class.java) {
                translator.translate(malformed)
            }
        assertEquals(FoxCoreConfigRejection.MALFORMED_JSON, malformedFailure.rejection)
        assertFalse(malformedFailure.toString().contains(secret))

        val unsupported =
            vless {
                put("unsupported_secret", secret)
            }
        val unsupportedFailure =
            assertThrows(FoxCoreConfigTranslationException::class.java) {
                translate(ProtocolHint.VLESS, unsupported)
            }
        assertEquals(FoxCoreConfigRejection.UNSUPPORTED_FIELD, unsupportedFailure.rejection)
        assertFalse(unsupportedFailure.toString().contains(secret))

        val halfPairedI2pFailure =
            assertThrows(FoxCoreConfigTranslationException::class.java) {
                translate(
                    hint = ProtocolHint.VLESS,
                    primary = vless(),
                    extraOutbounds = listOf(legacyI2p(password = secret)),
                )
            }
        assertEquals(
            FoxCoreConfigRejection.OVERLAY_CONFIGURATION_UNSUPPORTED,
            halfPairedI2pFailure.rejection,
        )
        assertFalse(halfPairedI2pFailure.toString().contains(secret))
    }

    private fun assertRejected(cases: List<RejectionCase>) {
        cases.forEach { case ->
            val failure =
                assertThrows(case.name, FoxCoreConfigTranslationException::class.java) {
                    translateConfig(case.hint, case.config, case.torActive)
                }
            assertEquals(case.name, case.expected, failure.rejection)
        }
    }

    private fun vless(extension: JsonObjectBuilder.() -> Unit = {}): JsonObject =
        proxyWithServer("vless") {
            put("uuid", CONTRACT_UUID)
            put("tls", tls())
            extension()
        }

    private fun proxyWithServer(
        type: String,
        extension: JsonObjectBuilder.() -> Unit,
    ): JsonObject =
        primary(type) {
            put("server", "203.0.113.10")
            put("server_port", 443)
            extension()
        }

    private fun wireGuard(
        peerCount: Int = 1,
        extension: JsonObjectBuilder.() -> Unit = {},
    ): JsonObject =
        primary("wireguard") {
            put("private_key", WIREGUARD_KEY)
            put("address", JsonArray(listOf(JsonPrimitive("10.17.0.2/32"))))
            put(
                "peers",
                buildJsonArray {
                    repeat(peerCount) { index ->
                        add(
                            buildJsonObject {
                                put("address", "198.51.100.${20 + index}")
                                put("port", 51820 + index)
                                put("public_key", WIREGUARD_KEY)
                                put(
                                    "allowed_ips",
                                    JsonArray(listOf(JsonPrimitive("0.0.0.0/0"))),
                                )
                            },
                        )
                    }
                },
            )
            extension()
        }

    private fun legacyTor(extension: JsonObjectBuilder.() -> Unit = {}): JsonObject =
        buildJsonObject {
            put("type", "tor")
            put("tag", TOR_OVER_VPN_OUTBOUND_TAG)
            put("data_directory", "/data/user/0/test/files/tor-data/identity-1")
            extension()
        }

    private fun legacyI2p(
        host: String = "127.0.0.1",
        username: String? = null,
        password: String? = null,
    ): JsonObject =
        buildJsonObject {
            put("type", "socks")
            put("tag", I2P_OUTBOUND_TAG)
            put("server", host)
            put("server_port", 4447)
            put("version", "5")
            put("network", "tcp")
            username?.let { put("username", it) }
            password?.let { put("password", it) }
        }

    private fun configWithDnsFinal(finalTag: String): JsonObject {
        val config = legacyConfig(vless())
        return config.withRootValue(
            "dns",
            buildJsonObject {
                put("servers", JsonArray(managedDnsServers()))
                put("strategy", "prefer_ipv4")
                put("final", finalTag)
            },
        )
    }

    private data class RejectionCase(
        val name: String,
        val hint: ProtocolHint,
        val config: JsonObject,
        val expected: FoxCoreConfigRejection,
        val torActive: Boolean = false,
    )

    private companion object {
        const val CONTRACT_UUID = "d0cf0001-0000-4000-8000-000000000000"
        const val WIREGUARD_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
    }
}
