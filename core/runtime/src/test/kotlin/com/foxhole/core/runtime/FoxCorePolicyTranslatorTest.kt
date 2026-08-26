package com.foxhole.core.runtime

import com.foxhole.core.model.FoxCoreDnsRuleSetBootstrap
import com.foxhole.core.model.KnownApplicationIdentity
import com.foxhole.core.model.ProtocolHint
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

internal class FoxCorePolicyTranslatorTest : FoxCoreConfigTranslatorTestSupport() {
    @Test
    fun `DNS filter becomes a pinned FoxCore FST with user bypasses`() {
        val packageBypass =
            buildJsonObject {
                put("package_name", stringArray("com.example.allowed"))
                put("action", "route")
                put("server", "dns-remote")
            }
        val domainBypass =
            buildJsonObject {
                put("domain_suffix", stringArray("safe.example"))
                put("action", "route")
                put("server", "dns-remote")
            }
        val block =
            buildJsonObject {
                put("rule_set", stringArray("foxhole-adguard-dns-filter"))
                put("action", "predefined")
                put("rcode", "NXDOMAIN")
            }
        val bootstrap =
            FoxCoreDnsRuleSetBootstrap(
                name = "foxhole-adguard-dns-filter",
                artifactPath = "/private/adguard.fhds",
                artifactSha256 = "a".repeat(64),
                publicKeyBase64 = "AQID",
            )

        val result =
            translate(
                hint = ProtocolHint.VLESS,
                primary = vless(),
                dnsRules = listOf(packageBypass, domainBypass, block),
                dnsRuleSetBootstrap = bootstrap,
            )
        val dns = policy(result).getValue("dns").jsonObject
        val source = dns.getValue("rule_sets").jsonArray.single().jsonObject
        val blocklist = dns.getValue("blocklist").jsonObject

        assertEquals("foxhole-adguard-dns-filter", source.getValue("name").jsonPrimitive.content)
        assertEquals("AQID", source.getValue("public_key").jsonPrimitive.content)
        assertTrue(source.getValue("required").jsonPrimitive.content.toBoolean())
        assertEquals(
            listOf("safe.example"),
            blocklist.getValue("allow_suffixes").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(
            listOf("com.example.allowed"),
            blocklist.getValue("bypass_packages").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(bootstrap, result.dnsRuleSetBootstrap)
    }

    @Test
    fun `full-device migration emits explicit closed network toggles`() {
        val result = translate(ProtocolHint.VLESS, vless(), expectedPolicyRevision = 12L)
        val policy = policy(result)
        val traffic = policy.getValue("traffic").jsonObject
        val dns = policy.getValue("dns").jsonObject

        assertEquals(setOf("expected_revision", "dns", "traffic"), policy.keys)
        assertEquals("12", policy.getValue("expected_revision").jsonPrimitive.content)
        assertEquals("vpn", traffic.getValue("default_action").jsonPrimitive.content)
        assertFalse(traffic.containsKey("applications"))
        assertFalse(traffic.getValue("tor_enabled").jsonPrimitive.content.toBoolean())
        assertFalse(traffic.getValue("i2p_enabled").jsonPrimitive.content.toBoolean())
        assertFalse(traffic.getValue("kill_switch").jsonPrimitive.content.toBoolean())
        assertFalse(traffic.getValue("quarantine_new_apps").jsonPrimitive.content.toBoolean())
        assertEquals("real_ip", dns.getValue("mode").jsonPrimitive.content)
        assertEquals("primary", dns.getValue("route").jsonPrimitive.content)
    }

    @Test
    fun `native quarantine carries deterministic installed application identities`() {
        val result =
            translate(
                hint = ProtocolHint.VLESS,
                primary = vless(),
                quarantineNewApps = true,
                knownApplications =
                listOf(
                    KnownApplicationIdentity(
                        packageName = "com.example.unique",
                        signingCertificateSha256 = "ab".repeat(32),
                        firstSeenAtMs = 123L,
                    ),
                    KnownApplicationIdentity(packageName = "com.example.shared"),
                ),
            )
        val traffic = policy(result).getValue("traffic").jsonObject
        val known = traffic.getValue("known_apps").jsonArray.map { value -> value.jsonObject }

        assertTrue(traffic.getValue("quarantine_new_apps").jsonPrimitive.content.toBoolean())
        assertEquals(
            listOf("com.example.shared", "com.example.unique"),
            known.map { value -> value.getValue("package").jsonPrimitive.content },
        )
        assertFalse(known.first().containsKey("signing_digest"))
        assertEquals("ab".repeat(32), known.last().getValue("signing_digest").jsonPrimitive.content)
        assertEquals("123", known.last().getValue("first_seen_at_ms").jsonPrimitive.content)
    }

    @Test
    fun `package-only direct and block rules become typed application policy`() {
        val direct =
            buildJsonObject {
                put("package_name", stringArray("com.example.direct"))
                put("action", "route")
                put("outbound", "direct")
            }
        val block =
            buildJsonObject {
                put("package_name", stringArray("com.example.blocked"))
                put("action", "reject")
                put("method", "default")
                put("no_drop", true)
            }
        val result =
            translate(
                ProtocolHint.VLESS,
                vless(),
                routeRules = managedRouteRules() + direct + block,
            )
        val traffic = policy(result).getValue("traffic").jsonObject
        val applications =
            traffic.getValue("applications").jsonArray.associate { element ->
                val application = element.jsonObject
                application.getValue("package").jsonPrimitive.content to
                    application.getValue("action").jsonPrimitive.content
            }

        assertEquals("vpn", traffic.getValue("default_action").jsonPrimitive.content)
        assertEquals(
            mapOf(
                "com.example.direct" to "direct",
                "com.example.blocked" to "block",
            ),
            applications,
        )
        assertFalse(policy(result).containsKey("routes"))
    }

    @Test
    fun `inverted include-only rule becomes direct default with an explicit VPN allowlist`() {
        val includeOnly =
            buildJsonObject {
                put("package_name", stringArray("com.example.alpha", "com.example.beta"))
                put("invert", true)
                put("action", "route")
                put("outbound", "direct")
            }
        val result =
            translate(
                ProtocolHint.VLESS,
                vless(),
                routeRules = listOf(includeOnly) + managedRouteRules(),
            )
        val traffic = policy(result).getValue("traffic").jsonObject
        val applications =
            traffic.getValue("applications").jsonArray.map { element ->
                element.jsonObject.fromApplication()
            }

        assertEquals("direct", traffic.getValue("default_action").jsonPrimitive.content)
        assertEquals(
            listOf(
                "com.example.alpha" to "vpn",
                "com.example.beta" to "vpn",
            ),
            applications,
        )
    }

    @Test
    fun `an include split keeps blocked apps blocked instead of refusing the config`() {
        val block =
            buildJsonObject {
                put("package_name", stringArray("com.example.blocked"))
                put("action", "reject")
                put("method", "default")
                put("no_drop", true)
            }
        val includeOnly =
            buildJsonObject {
                put("package_name", stringArray("com.example.blocked", "com.example.tunnelled"))
                put("invert", true)
                put("action", "route")
                put("outbound", "direct")
            }
        val result =
            translate(
                ProtocolHint.VLESS,
                vless(),
                routeRules = listOf(block, includeOnly) + managedRouteRules(),
            )
        val traffic = policy(result).getValue("traffic").jsonObject
        val applications =
            traffic.getValue("applications").jsonArray.associate { element ->
                val application = element.jsonObject
                application.getValue("package").jsonPrimitive.content to
                    application.getValue("action").jsonPrimitive.content
            }

        assertEquals("direct", traffic.getValue("default_action").jsonPrimitive.content)
        assertEquals(
            mapOf(
                "com.example.blocked" to "block",
                "com.example.tunnelled" to "vpn",
            ),
            applications,
        )
    }

    @Test
    fun `representable destination matchers preserve every route dimension`() {
        val route =
            buildJsonObject {
                put("package_name", "com.example.browser")
                put("domain", stringArray("exact.example"))
                put("domain_suffix", stringArray(".private.example"))
                put("ip_cidr", stringArray("203.0.113.0/24"))
                put("ip_is_private", true)
                put("port", JsonArray(listOf(JsonPrimitive(443), JsonPrimitive(8443))))
                put("port_range", stringArray("9000-9010"))
                put("network", "tcp")
                put("action", "route")
                put("outbound", "direct")
            }
        val result =
            translate(
                ProtocolHint.VLESS,
                vless(),
                routeRules = managedRouteRules() + route,
            )
        val translated = policy(result).getValue("routes").jsonArray.single().jsonObject

        assertEquals("com.example.browser", translated.getValue("package").jsonPrimitive.content)
        assertEquals(
            listOf("exact.example"),
            translated.getValue("exact_domains").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(
            listOf(".private.example"),
            translated.getValue("domain_suffixes").jsonArray.map { it.jsonPrimitive.content },
        )
        assertTrue(
            translated.getValue("cidrs").jsonArray.map { it.jsonPrimitive.content }
                .containsAll(listOf("203.0.113.0/24", "10.0.0.0/8", "fc00::/7")),
        )
        assertEquals(3, translated.getValue("ports").jsonArray.size)
        assertEquals("tcp", translated.getValue("transport").jsonPrimitive.content)
        assertEquals("direct", translated.getValue("action").jsonObject.getValue("type").jsonPrimitive.content)
    }

    @Test
    fun `non DNS UDP blocks survive strict translation without matching port 53`() {
        val result =
            translate(
                hint = ProtocolHint.TROJAN,
                primary =
                primary("trojan") {
                    put("server", "203.0.113.20")
                    put("server_port", 443)
                    put("password", "contract-secret")
                    put("tls", tls())
                },
                routeRules =
                listOf(
                    udpBlockRule("1-52"),
                    udpBlockRule("54-65535"),
                ) + managedRouteRules(),
            )
        val translated =
            policy(result)
                .getValue("routes")
                .jsonArray
                .map { it.jsonObject }
                .filter { route -> route["transport"]?.jsonPrimitive?.content == "udp" }

        assertEquals(2, translated.size)
        assertEquals(
            listOf(1 to 52, 54 to 65_535),
            translated.map { route ->
                val port = route.getValue("ports").jsonArray.single().jsonObject
                port.getValue("start").jsonPrimitive.content.toInt() to
                    port.getValue("end").jsonPrimitive.content.toInt()
            },
        )
        assertTrue(
            translated.all { route ->
                route.getValue("action").jsonObject.getValue("type").jsonPrimitive.content == "block"
            },
        )
        assertTrue(
            "strict policy must leave UDP/53 to FoxCore's managed DNS interceptor",
            translated.none { route ->
                val port = route.getValue("ports").jsonArray.single().jsonObject
                val start = port.getValue("start").jsonPrimitive.content.toInt()
                val end = port.getValue("end").jsonPrimitive.content.toInt()
                53 in start..end
            },
        )
    }

    @Test
    fun `Tor over VPN becomes Arti with an explicit FoxCore stream upstream`() {
        val tor =
            buildJsonObject {
                put("type", "tor")
                put("tag", TOR_OVER_VPN_OUTBOUND_TAG)
                put("data_directory", "/data/user/0/test/files/tor-data/identity-7")
                put("detour", "proxy")
            }
        val udpToVpn =
            buildJsonObject {
                put("network", "udp")
                put("action", "route")
                put("outbound", "proxy")
            }
        val onionRoute =
            buildJsonObject {
                put("domain_suffix", stringArray(".onion"))
                put("action", "route")
                put("outbound", TOR_OVER_VPN_OUTBOUND_TAG)
            }
        val torDnsServers =
            listOf(
                taggedOutbound("local", "dns-local"),
                taggedOutbound("local", "dns-direct"),
                buildJsonObject {
                    put("tag", "dns-remote")
                    put("type", "https")
                    put("server", "1.1.1.1")
                    put("server_port", 443)
                    put("path", "/dns-query")
                    put("detour", TOR_OVER_VPN_OUTBOUND_TAG)
                },
            )
        val result =
            translate(
                hint = ProtocolHint.VLESS,
                primary = vless(),
                torActive = true,
                extraOutbounds = listOf(tor),
                routeRules = listOf(udpToVpn, onionRoute) + managedRouteRules(),
                routeFinal = TOR_OVER_VPN_OUTBOUND_TAG,
                dnsServers = torDnsServers,
            )
        val targetEngine = engine(result)
        val named = targetEngine.getValue("outbounds").jsonArray.single().jsonObject
        val arti = named.getValue("outbound").jsonObject
        val translatedPolicy = policy(result)
        val traffic = translatedPolicy.getValue("traffic").jsonObject
        val routes = translatedPolicy.getValue("routes").jsonArray.map { it.jsonObject }

        assertEquals("tor", named.getValue("id").jsonPrimitive.content)
        assertEquals("tor", arti.getValue("type").jsonPrimitive.content)
        assertEquals(
            "/data/user/0/test/files/tor-data/identity-7/arti-state",
            arti.getValue("state_dir").jsonPrimitive.content,
        )
        assertEquals(
            "/data/user/0/test/files/tor-data/identity-7/arti-cache",
            arti.getValue("cache_dir").jsonPrimitive.content,
        )
        assertFalse(arti.containsKey("data_directory"))
        assertEquals(
            "vless",
            arti.getValue("upstream").jsonObject.getValue("type").jsonPrimitive.content,
        )
        assertEquals("tor", traffic.getValue("default_action").jsonPrimitive.content)
        assertTrue(traffic.getValue("tor_enabled").jsonPrimitive.content.toBoolean())
        assertEquals("fake_ip", translatedPolicy.getValue("dns").jsonObject.getValue("mode").jsonPrimitive.content)
        assertEquals("tor", translatedPolicy.getValue("dns").jsonObject.getValue("route").jsonPrimitive.content)
        assertTrue(
            routes.any { route ->
                route["domain_suffixes"]?.jsonArray?.map { it.jsonPrimitive.content } == listOf(".onion") &&
                    route.getValue("action").jsonObject.getValue("type").jsonPrimitive.content == "tor"
            },
        )
        assertTrue(
            routes.any { route ->
                route["transport"]?.jsonPrimitive?.content == "udp" &&
                    route.getValue("action").jsonObject.getValue("type").jsonPrimitive.content == "outbound"
            },
        )
    }

    @Test
    fun `authenticated loopback I2P preserves its RFC1929 pair in FoxCore`() {
        val password = "i2p-contract-password"
        val i2p =
            buildJsonObject {
                put("type", "socks")
                put("tag", I2P_OUTBOUND_TAG)
                put("server", "127.0.0.1")
                put("server_port", 4447)
                put("version", "5")
                put("network", "tcp")
                put("username", "i2p-contract-user")
                put("password", password)
            }
        val i2pRoute =
            buildJsonObject {
                put("domain_suffix", stringArray(I2P_DOMAIN_SUFFIX))
                put("action", "route")
                put("outbound", I2P_OUTBOUND_TAG)
            }
        val i2pDns =
            buildJsonObject {
                put("tag", I2P_FAKEIP_DNS_TAG)
                put("type", "fakeip")
                put("inet4_range", I2P_FAKEIP_INET4_RANGE)
            }
        val i2pDnsRule =
            buildJsonObject {
                put("domain_suffix", stringArray(I2P_DOMAIN_SUFFIX))
                put("server", I2P_FAKEIP_DNS_TAG)
            }
        val result =
            translate(
                hint = ProtocolHint.VLESS,
                primary = vless(),
                extraOutbounds = listOf(i2p),
                routeRules = listOf(i2pRoute) + managedRouteRules(),
                dnsServers = managedDnsServers() + i2pDns,
                dnsRules = listOf(i2pDnsRule),
            )
        val engine = engine(result)
        val named = engine.getValue("outbounds").jsonArray.single().jsonObject
        val translatedPolicy = policy(result)

        assertEquals("i2p", named.getValue("id").jsonPrimitive.content)
        assertEquals("i2p", named.getValue("outbound").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals(
            "127.0.0.1:4447",
            named.getValue("outbound").jsonObject.getValue("socks_address").jsonPrimitive.content,
        )
        assertEquals(
            "i2p-contract-user",
            named.getValue("outbound").jsonObject.getValue("username").jsonPrimitive.content,
        )
        assertEquals(
            password,
            named.getValue("outbound").jsonObject.getValue("password").jsonPrimitive.content,
        )
        assertFalse(result.toString().contains(password))
        assertEquals("fake_ip", translatedPolicy.getValue("dns").jsonObject.getValue("mode").jsonPrimitive.content)
        assertTrue(
            translatedPolicy.getValue("traffic").jsonObject.getValue("i2p_enabled").jsonPrimitive.content.toBoolean(),
        )
        assertEquals(
            "i2p",
            translatedPolicy.getValue("routes").jsonArray.single().jsonObject
                .getValue("action").jsonObject.getValue("type").jsonPrimitive.content,
        )
    }

    private fun vless(): JsonObject =
        primary("vless") {
            put("server", "203.0.113.10")
            put("server_port", 443)
            put("uuid", "d0cf0001-0000-4000-8000-000000000000")
            put("tls", tls())
        }

    private fun stringArray(vararg values: String): JsonArray =
        JsonArray(values.map(::JsonPrimitive))

    private fun udpBlockRule(portRange: String): JsonObject =
        buildJsonObject {
            put("network", "udp")
            put("port_range", portRange)
            put("action", "reject")
            put("method", "default")
            put("no_drop", true)
        }

    private fun JsonObject.fromApplication(): Pair<String, String> =
        getValue("package").jsonPrimitive.content to getValue("action").jsonPrimitive.content
}
