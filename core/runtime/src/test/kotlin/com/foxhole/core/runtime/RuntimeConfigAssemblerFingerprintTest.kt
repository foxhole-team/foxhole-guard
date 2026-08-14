package com.foxhole.core.runtime

import com.foxhole.core.model.AppTunnelLane
import com.foxhole.core.model.DnsSettings
import com.foxhole.core.model.ExpertSettings
import com.foxhole.core.model.LocalAuthSettings
import com.foxhole.core.model.LocalSurfaceSettings
import com.foxhole.core.model.PerAppRoutingMode
import com.foxhole.core.model.ProxyInboundSettings
import com.foxhole.core.model.ProxySurfaceMode
import com.foxhole.core.model.RoutingPreset
import com.foxhole.core.model.RoutingPresetOverrideMode
import com.foxhole.core.model.RoutingPresetSource
import com.foxhole.core.model.RoutingRule
import com.foxhole.core.model.RoutingRuleAction
import com.foxhole.core.model.SecureDnsMode
import com.foxhole.core.model.Settings
import com.foxhole.core.model.TrafficMode
import com.foxhole.core.model.TrafficSettings
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

internal class RuntimeConfigAssemblerFingerprintTest : RuntimeConfigAssemblerTestSupport() {
    @Test
    fun `runtime fingerprint changes when proxy auth changes`() {
        val runtimeSettings =
            Settings(
                traffic = com.foxhole.core.model.TrafficSettings(mode = TrafficMode.PROXY),
                expert =
                ExpertSettings(
                    localSurfaces =
                    LocalSurfaceSettings(
                        proxyMode = ProxySurfaceMode.HTTP,
                        http = ProxyInboundSettings(enabled = true, host = "127.0.0.1", port = 10809),
                        auth =
                        LocalAuthSettings(
                            username = "foxhole-user",
                            password = "foxhole-pass",
                            apiSecret = "foxhole-secret",
                        ),
                    ),
                ),
            )
        val changedAuth =
            runtimeSettings.copy(
                expert =
                runtimeSettings.expert.copy(
                    localSurfaces =
                    runtimeSettings.expert.localSurfaces.copy(
                        auth =
                        runtimeSettings.expert.localSurfaces.auth.copy(
                            username = "foxhole-user-2",
                            password = "foxhole-pass-2",
                        ),
                    ),
                ),
            )

        assertNotEquals(
            assembler.runtimeFingerprint(runtimeSettings, null),
            assembler.runtimeFingerprint(changedAuth, null),
        )
    }

    @Test
    fun `exclude split lives in route rules - tun inbound stays full-device`() {
        val settings =
            Settings(
                expert =
                ExpertSettings(
                    perAppRoutingMode = PerAppRoutingMode.EXCLUDE_SELECTED_APPS,
                    appAssignments = mapOf("com.bank.app" to AppTunnelLane.VPN),
                ),
            )

        val config = parse(assembler.assemble(baseConfigWithRules("profile.example"), settings, null))
        val tunInbound = config["inbounds"]!!.jsonArray.first().jsonObject
        val rules = config["route"]!!.jsonObject["rules"]!!.jsonArray

        // Ф-ГА2: membership must never change the kernel interface — no package keys on the tun.
        assertFalse(tunInbound.containsKey("include_package"))
        assertFalse(tunInbound.containsKey("exclude_package"))
        val splitRule =
            rules.map { it.jsonObject }.single { rule -> rule.containsKey("package_name") }
        assertEquals("com.bank.app", splitRule["package_name"]!!.jsonArray[0].jsonPrimitive.content)
        assertFalse(splitRule.containsKey("invert"))
        assertEquals("direct", splitRule["outbound"]!!.jsonPrimitive.content)
        // ...and ONLY in route rules. The split used to carry a matching "resolve on dns-direct"
        // rule, which FoxCore cannot express (one interceptor, one upstream lane) and therefore
        // refuses — taking the whole profile down with it. The split separates traffic, not DNS.
        assertFalse(config["dns"]!!.jsonObject.containsKey("rules"))
    }

    @Test
    fun `blocked package rules are ordered before selected app rules and honor block toggle`() {
        val settings =
            Settings(
                expert =
                ExpertSettings(
                    perAppRoutingMode = PerAppRoutingMode.INCLUDE_SELECTED_APPS,
                    appAssignments =
                    mapOf(
                        "com.example.selected" to AppTunnelLane.VPN,
                        "com.example.blocked" to AppTunnelLane.BLOCK,
                    ),
                    blockedPackagesEnabled = true,
                ),
            )

        val config = parse(assembler.assemble(baseConfigWithRules("profile.example"), settings, null))
        val tunInbound = config["inbounds"]!!.jsonArray.first().jsonObject
        val rules = config["route"]!!.jsonObject["rules"]!!.jsonArray
        val blockRule = rules[0].jsonObject

        // Ф-ГА2: blocked apps no longer need forcing into the tun — it is always full-device,
        // and the block rule (first in order) wins over the split's inverted direct rule.
        assertFalse(tunInbound.containsKey("include_package"))
        assertEquals("com.example.blocked", blockRule["package_name"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("block", blockRule["outbound"]!!.jsonPrimitive.content)
        val splitRuleIndex =
            rules.indexOfFirst { rule ->
                rule.jsonObject["outbound"]?.jsonPrimitive?.content == "direct" &&
                    rule.jsonObject.containsKey("package_name")
            }
        assertTrue("split rule present and ordered after the block rule", splitRuleIndex > 0)

        val disabled =
            parse(
                assembler.assemble(
                    baseConfigWithRules("profile.example"),
                    settings.copy(expert = settings.expert.copy(blockedPackagesEnabled = false)),
                    null,
                ),
            )
        val disabledRules = disabled["route"]!!.jsonObject["rules"]!!.jsonArray
        assertTrue(disabledRules.none { rule -> rule.jsonObject["outbound"]?.jsonPrimitive?.content == "block" })
    }

    @Test
    fun `site rules map chips to normalized fields and selected managed sites follow global mode`() {
        val preset =
            RoutingPreset(
                id = 11,
                name = "sites",
                source = RoutingPresetSource.LOCAL,
                overrideMode = RoutingPresetOverrideMode.RESPECT_PROFILE,
                enabled = true,
                updatedAt = 1,
                rules =
                listOf(
                    RoutingRule(
                        id = 1,
                        presetId = 11,
                        name = "Foxhole selected site: example.com",
                        enabled = true,
                        order = 0,
                        action = RoutingRuleAction.PROXY,
                        matchDomains = listOf("example.com", "*.example.org", "kw:video", "re:^stun\\..+"),
                        matchIpCidrs = listOf("1.2.3.0/24"),
                        matchPorts = emptyList(),
                        matchProtocols = emptyList(),
                        matchNetworks = emptyList(),
                    ),
                    RoutingRule(
                        id = 2,
                        presetId = 11,
                        name = "Foxhole blocked site: kw:ads",
                        enabled = true,
                        order = 1,
                        action = RoutingRuleAction.BLOCK,
                        matchDomains = listOf("kw:ads"),
                        matchIpCidrs = emptyList(),
                        matchPorts = emptyList(),
                        matchProtocols = emptyList(),
                        matchNetworks = emptyList(),
                    ),
                    RoutingRule(
                        id = 3,
                        presetId = 11,
                        name = "custom site",
                        enabled = true,
                        order = 2,
                        action = RoutingRuleAction.PROXY,
                        matchDomains = listOf("custom.example"),
                        matchIpCidrs = emptyList(),
                        matchPorts = emptyList(),
                        matchProtocols = emptyList(),
                        matchNetworks = emptyList(),
                    ),
                ),
            )
        val settings =
            Settings(
                expert = ExpertSettings(siteRoutingAction = RoutingRuleAction.DIRECT),
            )

        val config = parse(assembler.assemble(baseConfigWithRules("profile.example"), settings, preset))
        val rules = config["route"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }
        val selectedRule = rules.first { it.stringArray("domain").contains("example.com") }
        val blockedRule = rules.first { it.stringArray("domain_keyword").contains("ads") }
        val customRule = rules.first { it.stringArray("domain").contains("custom.example") }

        assertEquals(listOf("example.com"), selectedRule.stringArray("domain"))
        assertEquals(listOf("example.org"), selectedRule.stringArray("domain_suffix"))
        assertEquals(listOf("video"), selectedRule.stringArray("domain_keyword"))
        assertEquals(listOf("^stun\\..+"), selectedRule.stringArray("domain_regex"))
        assertEquals(listOf("1.2.3.0/24"), selectedRule.stringArray("ip_cidr"))
        assertEquals("direct", selectedRule["outbound"]!!.jsonPrimitive.content)
        assertEquals("block", blockedRule["outbound"]!!.jsonPrimitive.content)
        assertEquals("proxy", customRule["outbound"]!!.jsonPrimitive.content)
    }

    @Test
    fun `foxhole dns defaults resolve through the tunnel with local bootstrap`() {
        val config = parse(assembler.assemble(baseConfigWithLegacyFoxholeDns(), Settings(), null))
        val dns = config["dns"]!!.jsonObject
        val route = config["route"]!!.jsonObject
        val servers = dns["servers"]!!.jsonArray
        val remote =
            servers.first { it.jsonObject["tag"]!!.jsonPrimitive.content == "dns-remote" }.jsonObject

        // App queries resolve through the tunnel by default; dns-local/dns-direct exist only as the
        // bootstrap resolver (VPN endpoint + DoH hostnames), never as the final for app queries.
        assertEquals("dns-remote", dns["final"]!!.jsonPrimitive.content)
        assertEquals("dns-local", servers[0].jsonObject["tag"]!!.jsonPrimitive.content)
        assertEquals("local", servers[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertFalse(servers[0].jsonObject.containsKey("detour"))
        assertEquals("dns-direct", servers[1].jsonObject["tag"]!!.jsonPrimitive.content)
        assertEquals("local", servers[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertFalse(servers[1].jsonObject.containsKey("server"))
        assertFalse(servers[1].jsonObject.containsKey("server_port"))
        assertFalse(servers[1].jsonObject.containsKey("path"))
        assertFalse(servers[1].jsonObject.containsKey("detour"))
        assertEquals("proxy", remote["detour"]!!.jsonPrimitive.content)
        assertEquals("true", route["auto_detect_interface"]!!.jsonPrimitive.content)
    }

    @Test
    fun `foxhole dns keeps tunnelled remote resolver when private dns is off`() {
        val config =
            parse(
                assembler.assemble(
                    baseConfigWithLegacyFoxholeDns(),
                    Settings(),
                    null,
                    privateDnsMode = PrivateDnsMode.OFF,
                ),
            )
        val dns = config["dns"]!!.jsonObject
        val remote =
            dns["servers"]!!.jsonArray
                .first { it.jsonObject["tag"]!!.jsonPrimitive.content == "dns-remote" }
                .jsonObject

        assertEquals("dns-remote", dns["final"]!!.jsonPrimitive.content)
        assertEquals("proxy", remote["detour"]!!.jsonPrimitive.content)
    }

    @Test
    fun `foxhole dns keeps local bootstrap resolver when private dns is strict`() {
        val config =
            parse(
                assembler.assemble(
                    baseConfigWithLegacyFoxholeDns(),
                    Settings(),
                    null,
                    privateDnsMode = PrivateDnsMode.STRICT,
                ),
            )
        val dns = config["dns"]!!.jsonObject
        val route = config["route"]!!.jsonObject
        val servers = dns["servers"]!!.jsonArray

        assertEquals("dns-remote", dns["final"]!!.jsonPrimitive.content)
        assertEquals("dns-direct", route["default_domain_resolver"]!!.jsonPrimitive.content)
        assertEquals("dns-direct", servers[1].jsonObject["tag"]!!.jsonPrimitive.content)
        assertEquals("local", servers[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertFalse(servers[1].jsonObject.containsKey("server"))
        assertFalse(servers[1].jsonObject.containsKey("server_port"))
        assertFalse(servers[1].jsonObject.containsKey("path"))
        assertFalse(servers[1].jsonObject.containsKey("detour"))
    }

    @Test
    fun `legacy udp bootstrap dns is rewritten to local bootstrap`() {
        val config = parse(assembler.assemble(baseConfigWithLegacyUdpBootstrapDns(), Settings(), null))
        val servers = config["dns"]!!.jsonObject["servers"]!!.jsonArray

        assertEquals("dns-direct", servers[1].jsonObject["tag"]!!.jsonPrimitive.content)
        assertEquals("local", servers[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertFalse(servers[1].jsonObject.containsKey("server"))
        assertFalse(servers[1].jsonObject.containsKey("server_port"))
        assertFalse(servers[1].jsonObject.containsKey("path"))
        assertFalse(servers[1].jsonObject.containsKey("detour"))
    }

    @Test
    fun `dns leak controls update tunnel strict routing and DNS hijack rules`() {
        val settings =
            Settings(
                expert = ExpertSettings(strictRoute = false),
                dns =
                DnsSettings(
                    blockOutsideTunnel = false,
                    interceptDnsRequests = false,
                ),
            )

        val config = parse(assembler.assemble(baseConfigWithRules("profile.example"), settings, null))
        val tunInbound = config["inbounds"]!!.jsonArray.first().jsonObject
        val rules = config["route"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }

        assertEquals(false, tunInbound["strict_route"]!!.jsonPrimitive.content.toBoolean())
        // Hijack is strictly opt-in now: with intercept and system-DNS replacement both off the
        // app carries hard-coded-resolver traffic through the tunnel untouched. System queries
        // still resolve via the tunnel resolver (TUN DNS server), so nothing leaks off-tunnel.
        assertTrue(rules.none { rule -> rule["action"]?.jsonPrimitive?.content == "hijack-dns" })
        assertTrue(rules.any { rule -> rule.stringArray("domain").contains("profile.example") })

        // The intercept toggle or the system-DNS replacement brings the hijack rules back.
        val intercepted =
            parse(
                assembler.assemble(
                    baseConfigWithRules("profile.example"),
                    settings.copy(dns = settings.dns.copy(interceptDnsRequests = true)),
                    null,
                ),
            )
        val interceptedRules = intercepted["route"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }
        assertTrue(interceptedRules.any { rule -> rule["action"]?.jsonPrimitive?.content == "hijack-dns" })

        val replaced =
            parse(
                assembler.assemble(
                    baseConfigWithRules("profile.example"),
                    settings.copy(dns = settings.dns.copy(replaceSystemDns = true)),
                    null,
                ),
            )
        val replacedRules = replaced["route"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }
        assertTrue(replacedRules.any { rule -> rule["action"]?.jsonPrimitive?.content == "hijack-dns" })

        val forced =
            parse(
                assembler.assemble(
                    baseConfigWithRules("profile.example"),
                    settings.copy(dns = settings.dns.copy(blockOutsideTunnel = true)),
                    null,
                ),
            )
        assertEquals(
            true,
            forced["inbounds"]!!.jsonArray.first().jsonObject["strict_route"]!!.jsonPrimitive.content.toBoolean()
        )
    }

    @Test
    fun `unverified DNS rule set disables only filtering and keeps leak controls`() {
        val settings =
            Settings(
                expert = ExpertSettings(strictRoute = false),
                dns = DnsSettings(filteringEnabled = true, filtersUpdatedAt = 42L, interceptDnsRequests = true),
            )

        val config = parse(assembler.assemble(baseConfigWithRules("profile.example"), settings, null))
        val tunInbound = config["inbounds"]!!.jsonArray.first().jsonObject
        val route = config["route"]!!.jsonObject
        val rules = route["rules"]!!.jsonArray.map { it.jsonObject }

        // The unverified list must fail open ONLY for filtering — hijack + strict_route (the
        // leak controls) keep their configured values instead of being silently dropped.
        assertEquals(true, tunInbound["strict_route"]!!.jsonPrimitive.content.toBoolean())
        assertFalse(config["dns"]!!.jsonObject.containsKey("rules"))
        assertFalse(route.containsKey("rule_set"))
        assertTrue(rules.any { rule -> rule["action"]?.jsonPrimitive?.content == "hijack-dns" })
    }

    @Test
    fun `dns through vpn and secure mode select remote resolver fields`() {
        val settings =
            Settings(
                dns =
                DnsSettings(
                    useVpnProviderDns = false,
                    dnsThroughVpn = true,
                    server = "dns.example",
                    secureMode = SecureDnsMode.DOT,
                ),
            )

        val config = parse(assembler.assemble(baseConfigWithRules("profile.example"), settings, null))
        val remote = config["dns"]!!.jsonObject["servers"]!!.jsonArray[2].jsonObject

        assertEquals("dns-remote", remote["tag"]!!.jsonPrimitive.content)
        assertEquals("tls", remote["type"]!!.jsonPrimitive.content)
        assertEquals("dns.example", remote["server"]!!.jsonPrimitive.content)
        assertEquals("853", remote["server_port"]!!.jsonPrimitive.content)
        // The tunnelled resolver is addressed by hostname, so its bootstrap A/AAAA lookup must stay
        // in the tunnel: domain_resolver points at the in-tunnel IP-literal bootstrap resolver.
        assertEquals("dns-bootstrap", remote["domain_resolver"]!!.jsonPrimitive.content)
        assertFalse(remote.containsKey("path"))
        assertEquals("proxy", remote["detour"]!!.jsonPrimitive.content)

        val servers = config["dns"]!!.jsonObject["servers"]!!.jsonArray.map { it.jsonObject }
        val bootstrap = servers.first { it["tag"]?.jsonPrimitive?.content == "dns-bootstrap" }
        assertEquals("1.1.1.1", bootstrap["server"]!!.jsonPrimitive.content)
        assertEquals("https", bootstrap["type"]!!.jsonPrimitive.content)
        assertEquals("proxy", bootstrap["detour"]!!.jsonPrimitive.content)
    }

    @Test
    fun `explicit out of tunnel dns keeps secure resolver without detour`() {
        val settings =
            Settings(
                dns =
                DnsSettings(
                    useVpnProviderDns = false,
                    dnsThroughVpn = false,
                    server = "dns.example",
                    secureMode = SecureDnsMode.DOT,
                ),
            )

        val config = parse(assembler.assemble(baseConfigWithRules("profile.example"), settings, null))
        val dns = config["dns"]!!.jsonObject
        val remote = dns["servers"]!!.jsonArray[2].jsonObject

        // The confirmed opt-out resolves off-tunnel: encrypted resolver, no proxy detour.
        assertEquals("dns-remote", dns["final"]!!.jsonPrimitive.content)
        assertEquals("tls", remote["type"]!!.jsonPrimitive.content)
        assertFalse(remote.containsKey("detour"))
    }

    @Test
    fun `strict private dns hostname is used as dns over tls upstream`() {
        val settings =
            Settings(
                dns =
                DnsSettings(
                    dnsThroughVpn = false,
                    server = "9.9.9.9",
                    secureMode = SecureDnsMode.PLAIN,
                ),
            )

        val config =
            parse(
                assembler.assemble(
                    baseConfigJson = baseConfigWithRules("profile.example"),
                    settings = settings,
                    activePreset = null,
                    privateDnsState = PrivateDnsState(
                        mode = PrivateDnsMode.STRICT,
                        hostname = "one.one.one.one",
                    ),
                ),
            )
        val remote = config["dns"]!!.jsonObject["servers"]!!.jsonArray[2].jsonObject

        assertEquals("dns-remote", remote["tag"]!!.jsonPrimitive.content)
        assertEquals("tls", remote["type"]!!.jsonPrimitive.content)
        assertEquals("one.one.one.one", remote["server"]!!.jsonPrimitive.content)
        assertEquals("853", remote["server_port"]!!.jsonPrimitive.content)
        // Strict-private-DNS hostname also rides the tunnel, so its bootstrap lookup goes through
        // the in-tunnel bootstrap resolver rather than leaking on the underlying network.
        assertEquals("dns-bootstrap", remote["domain_resolver"]!!.jsonPrimitive.content)
        assertFalse(remote.containsKey("path"))
        // Provider mode is still on, so even the strict-private-DNS upstream rides the tunnel.
        assertEquals("proxy", remote["detour"]!!.jsonPrimitive.content)

        val servers = config["dns"]!!.jsonObject["servers"]!!.jsonArray.map { it.jsonObject }
        assertTrue(servers.any { it["tag"]?.jsonPrimitive?.content == "dns-bootstrap" })
    }

    @Test
    fun `plain dns mode emits udp resolver without doh path`() {
        val settings =
            Settings(
                dns =
                DnsSettings(
                    useVpnProviderDns = false,
                    dnsThroughVpn = false,
                    server = "9.9.9.9",
                    secureMode = SecureDnsMode.PLAIN,
                ),
            )

        val config = parse(assembler.assemble(baseConfigWithRules("profile.example"), settings, null))
        val remote =
            config["dns"]!!.jsonObject["servers"]!!.jsonArray
                .firstOrNull { it.jsonObject["tag"]?.jsonPrimitive?.content == "dns-remote" }
                ?.jsonObject

        // Explicit out-of-tunnel + plain: the system resolver answers (dns-direct final) and no
        // remote resolver is emitted at all unless filtering needs one.
        assertEquals("dns-direct", config["dns"]!!.jsonObject["final"]!!.jsonPrimitive.content)
        assertEquals(null, remote)

        val filtering =
            parse(
                assembler.assemble(
                    baseConfigWithRules("profile.example"),
                    settings.copy(dns = settings.dns.copy(filteringEnabled = true, filtersUpdatedAt = 42L)),
                    null,
                    dnsFilterRuntimePaths = DnsFilterRuntimePaths(adGuardDnsFilterPath = "/tmp/filter.srs"),
                ),
            )
        val filteringRemote =
            filtering["dns"]!!.jsonObject["servers"]!!.jsonArray
                .first { it.jsonObject["tag"]!!.jsonPrimitive.content == "dns-remote" }
                .jsonObject
        assertEquals("udp", filteringRemote["type"]!!.jsonPrimitive.content)
        assertEquals("9.9.9.9", filteringRemote["server"]!!.jsonPrimitive.content)
        assertEquals("53", filteringRemote["server_port"]!!.jsonPrimitive.content)
        assertFalse(filteringRemote.containsKey("path"))
        assertFalse(filteringRemote.containsKey("domain_resolver"))
        assertFalse(filteringRemote.containsKey("detour"))
    }

    @Test
    fun `unverified DNS filtering suppresses bypass runtime rules`() {
        val settings =
            Settings(
                dns =
                DnsSettings(
                    filteringEnabled = true,
                    blockAds = false,
                    blockTrackers = false,
                    blockAppTelemetry = false,
                    blockMaliciousDomains = false,
                    appBypassPackages = listOf("com.example.bank"),
                    domainBypassRules = listOf("login.example", "push.example"),
                ),
            )

        val config = parse(assembler.assemble(baseConfigWithRules("profile.example"), settings, null))

        assertFalse(config["dns"]!!.jsonObject.containsKey("rules"))
    }

    @Test
    fun `verified DNS filtering bypass rules use remote resolver and need a rule set to exist at all`() {
        val filterPath = "/data/user/0/com.foxhole.guard/files/dns-rule-sets/adguard-dns-filter.srs"
        val settings =
            Settings(
                dns =
                DnsSettings(
                    filteringEnabled = true,
                    blockAds = false,
                    blockTrackers = false,
                    blockAppTelemetry = false,
                    blockMaliciousDomains = false,
                    appBypassPackages = listOf("com.example.bank"),
                    domainBypassRules = listOf("login.example", "push.example"),
                ),
            )

        val config =
            parse(
                assembler.assemble(
                    baseConfigWithRules("profile.example"),
                    settings,
                    null,
                    dnsFilterRuntimePaths = DnsFilterRuntimePaths(adGuardDnsFilterPath = filterPath),
                ),
            )
        // Every category is off in `settings`, so no rule set is attached — and a bypass with
        // nothing to be an exception to is a document FoxCore refuses outright, which killed the
        // session before the tun existed. This state is reachable from the settings screen, so the
        // rules must not be emitted at all.
        assertFalse(config["dns"]!!.jsonObject.containsKey("rules"))

        val filtering =
            parse(
                assembler.assemble(
                    baseConfigWithRules("profile.example"),
                    settings.copy(dns = settings.dns.copy(blockAds = true)),
                    null,
                    dnsFilterRuntimePaths = DnsFilterRuntimePaths(adGuardDnsFilterPath = filterPath),
                ),
            )
        val dnsRules = filtering["dns"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }

        assertEquals(listOf("com.example.bank"), dnsRules[0].stringArray("package_name"))
        assertEquals("dns-remote", dnsRules[0]["server"]!!.jsonPrimitive.content)
        assertEquals(listOf("login.example", "push.example"), dnsRules[1].stringArray("domain_suffix"))
        assertEquals("dns-remote", dnsRules[1]["server"]!!.jsonPrimitive.content)

        val disabled =
            parse(
                assembler.assemble(
                    baseConfigWithRules("profile.example"),
                    settings.copy(dns = settings.dns.copy(filteringEnabled = false)),
                    null,
                ),
            )
        assertFalse(disabled["dns"]!!.jsonObject.containsKey("rules"))
    }

    @Test
    fun `dns filtering attaches bundled adguard rule set when prepared`() {
        val filterPath = "/data/user/0/com.foxhole.guard/files/dns-rule-sets/adguard-dns-filter.srs"
        val config =
            parse(
                assembler.assemble(
                    baseConfigWithRules("profile.example"),
                    Settings(dns = DnsSettings(filteringEnabled = true)),
                    null,
                    dnsFilterRuntimePaths =
                    DnsFilterRuntimePaths(
                        adGuardDnsFilterPath = filterPath,
                        adGuardVpnCompatibilityDomains = listOf("adguard-vpn.com"),
                    ),
                ),
            )
        val dnsRules = config["dns"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }
        val adGuardRule =
            dnsRules.single { rule -> rule.stringArray("rule_set").contains("foxhole-adguard-dns-filter") }
        val compatibilityRule =
            dnsRules.single { rule -> rule.stringArray("domain_suffix").contains("adguard-vpn.com") }
        val ruleSet = config["route"]!!.jsonObject["rule_set"]!!.jsonArray.single().jsonObject

        assertTrue(dnsRules.indexOf(compatibilityRule) < dnsRules.indexOf(adGuardRule))
        assertEquals("dns-remote", compatibilityRule["server"]!!.jsonPrimitive.content)
        assertEquals("predefined", adGuardRule["action"]!!.jsonPrimitive.content)
        assertEquals("NXDOMAIN", adGuardRule["rcode"]!!.jsonPrimitive.content)
        assertEquals("local", ruleSet["type"]!!.jsonPrimitive.content)
        assertEquals("foxhole-adguard-dns-filter", ruleSet["tag"]!!.jsonPrimitive.content)
        assertEquals("binary", ruleSet["format"]!!.jsonPrimitive.content)
        assertEquals(filterPath, ruleSet["path"]!!.jsonPrimitive.content)
    }

    @Test
    fun `route defaults keep dns untouched and intercept toggle injects hijack rules`() {
        val config = parse(assembler.assemble(baseConfigWithRules("profile.example"), Settings(), null))
        val route = config["route"]!!.jsonObject
        val rules = route["rules"]!!.jsonArray

        assertRuntimeProxyRoute(rules[0].jsonObject)
        assertSniffRule(rules[1].jsonObject)
        // Hands-off default: no hijack rules unless the user opted into interception/replacement.
        assertTrue(
            rules.none { rule -> rule.jsonObject["action"]?.jsonPrimitive?.content == "hijack-dns" },
        )
        assertEquals("dns-direct", route["default_domain_resolver"]!!.jsonPrimitive.content)
        assertEquals("true", route["auto_detect_interface"]!!.jsonPrimitive.content)

        val intercepted =
            parse(
                assembler.assemble(
                    baseConfigWithRules("profile.example"),
                    Settings(dns = DnsSettings(interceptDnsRequests = true)),
                    null,
                ),
            )
        val interceptedRules = intercepted["route"]!!.jsonObject["rules"]!!.jsonArray
        assertPortDnsHijack(interceptedRules[2].jsonObject)
        assertProtocolDnsHijack(interceptedRules[3].jsonObject)
    }
}
