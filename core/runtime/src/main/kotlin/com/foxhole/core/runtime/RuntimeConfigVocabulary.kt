@file:Suppress("MatchingDeclarationName")

package com.foxhole.core.runtime

import com.foxhole.core.model.RoutingRule
import com.foxhole.core.model.RoutingRuleAction
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

internal data class NormalizedRoutePort(
    val ports: List<Int>,
    val portRanges: List<String>,
)

internal fun RoutingRule.runtimeAction(siteRoutingAction: RoutingRuleAction): RoutingRuleAction =
    if (isManagedSelectedSiteRule()) {
        when (siteRoutingAction) {
            RoutingRuleAction.PROXY,
            RoutingRuleAction.DIRECT,
            -> siteRoutingAction
            RoutingRuleAction.BLOCK,
            RoutingRuleAction.TOR,
            -> RoutingRuleAction.PROXY
        }
    } else {
        action
    }

internal fun RoutingRule.isManagedSelectedSiteRule(): Boolean =
    name.startsWith(MANAGED_SELECTED_SITE_RULE_PREFIX)

internal const val MANAGED_SELECTED_SITE_RULE_PREFIX = "Foxhole selected site:"

internal fun JsonArray?.orEmpty(): List<JsonElement> = this?.toList().orEmpty()

internal val LOCAL_HOSTS = setOf("127.0.0.1", "localhost", "::1")

internal val FOXHOLE_ROUTE_KEYS =
    setOf("rules", "rule_set", "final", "default_domain_resolver", "auto_detect_interface")

internal const val DNS_LOCAL_TAG = "dns-local"

internal const val DNS_DIRECT_TAG = "dns-direct"

internal const val DNS_REMOTE_TAG = "dns-remote"

internal const val DNS_BOOTSTRAP_TAG = "dns-bootstrap"

internal const val DNS_ADGUARD_RULE_SET_TAG = "foxhole-adguard-dns-filter"

internal const val WIREGUARD_DNS_TAG = "dns-wireguard"

internal const val TOR_OVER_VPN_OUTBOUND_TAG = "tor-over-vpn"

internal const val RUNTIME_LOOPBACK_PROXY_INBOUND_TAG = "foxhole-runtime-proxy-in"

internal const val FOXHOLE_REMOTE_DNS_SERVER = "1.1.1.1"

internal const val FOXHOLE_DOH_ADDRESS = "https://1.1.1.1/dns-query"

internal const val LOCAL_GUARD_TUN_ADDRESS = "172.19.0.1/30"

internal const val LOCAL_GUARD_DNS_SERVER_ADDRESS = "172.19.0.2"

internal const val LOCAL_GUARD_TUN_INET6_ADDRESS = "fdfe:dcba:9876::1/126"

internal const val LOCAL_GUARD_INET6_CAPTURE_ROUTE = "::/0"

internal const val MOBILE_TCP_KEEP_ALIVE = "30s"

internal const val MOBILE_TCP_KEEP_ALIVE_INTERVAL = "15s"

internal const val ROUTE_NETWORK_TCP = "tcp"

internal const val ROUTE_NETWORK_UDP = "udp"

internal val TCP_UDP_NETWORKS = listOf(ROUTE_NETWORK_TCP, ROUTE_NETWORK_UDP)

internal const val SITE_KEYWORD_PREFIX = "kw:"

internal const val SITE_REGEX_PREFIX = "re:"

internal val TCP_RELIABILITY_OUTBOUND_TYPES = setOf("vless", "trojan", "vmess", "shadowsocks", "http", "socks")

internal val TCP_RELIABILITY_TRANSPORT_TYPES = setOf("tcp", "ws", "grpc", "http", "httpupgrade")

internal val PORT_RANGE_REGEX = Regex("""\d{1,5}-\d{1,5}""")
