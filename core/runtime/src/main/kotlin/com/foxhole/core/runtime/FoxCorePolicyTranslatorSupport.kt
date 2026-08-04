package com.foxhole.core.runtime

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

// The policy translator's vocabulary: intermediate representations, the keys the schema accepts,
// and the predicates separating our own managed rules from user ones. No decisions here — only the
// terms they are expressed in.

internal class MutableRouteTranslation(
    var defaultAction: String,
) {
    val applications = linkedMapOf<String, String>()
    val routes = mutableListOf<JsonObject>()

    fun addApplication(
        packageName: String,
        action: String,
        path: String,
    ) {
        if (!PACKAGE_PATTERN.matches(packageName)) {
            rejectFoxCoreConfig(FoxCoreConfigRejection.INVALID_SHAPE, "$path.package_name")
        }
        val previous = applications.putIfAbsent(packageName, action)
        if (previous != null && previous != action) {
            rejectFoxCoreConfig(FoxCoreConfigRejection.POLICY_UNREPRESENTABLE, path)
        }
    }

    /**
     * Classifies [packageName] only if no earlier rule did.
     *
     * The catch-all counterpart of [addApplication]: a disagreement between two SPECIFIC rules is a
     * config that means two things at once and is refused, but an inverted include set is the
     * fallback for everything the specific rules did not name — so an earlier verdict (a blocked
     * app) wins instead of colliding with it.
     */
    fun retainApplication(
        packageName: String,
        action: String,
        path: String,
    ) {
        if (!PACKAGE_PATTERN.matches(packageName)) {
            rejectFoxCoreConfig(FoxCoreConfigRejection.INVALID_SHAPE, "$path.package_name")
        }
        applications.putIfAbsent(packageName, action)
    }
}

internal data class RouteTranslation(
    val defaultAction: String,
    val applications: Map<String, String>,
    val routes: JsonArray,
)

internal sealed class RouteTargetAction(
    val trafficAction: String,
    val routeAction: JsonObject,
) {
    data object Vpn : RouteTargetAction(
        trafficAction = "vpn",
        routeAction =
        buildJsonObject {
            put("type", "outbound")
            put("id", "default")
        },
    )

    data object Direct : RouteTargetAction(
        trafficAction = "direct",
        routeAction = buildJsonObject { put("type", "direct") },
    )

    data object Block : RouteTargetAction(
        trafficAction = "block",
        routeAction = buildJsonObject { put("type", "block") },
    )

    data object Tor : RouteTargetAction(
        trafficAction = "tor",
        routeAction = buildJsonObject { put("type", "tor") },
    )

    data object I2p : RouteTargetAction(
        trafficAction = "vpn",
        routeAction = buildJsonObject { put("type", "i2p") },
    )
}

internal data class DnsTranslation(
    val config: JsonObject,
    val packetTunnelAdvertise: String?,
)

internal data class DnsUpstreamTranslation(
    val config: JsonObject,
    val advertise: String,
    val route: String,
)

internal data class DnsFilterTranslation(
    val enabled: Boolean,
    val allowSuffixes: List<String>,
    val bypassPackages: List<String>,
)

internal fun JsonObject.stringOrArrayValues(
    key: String,
    path: String,
): List<String> =
    when (val value = this[key]) {
        null -> emptyList()
        is JsonArray ->
            value.mapIndexed { index, element ->
                element.asFoxCoreString("$path.$key[$index]")
            }
        else -> listOf(value.asFoxCoreString("$path.$key"))
    }

internal fun JsonObject.intOrArrayValues(
    key: String,
    path: String,
): List<Int> =
    when (val value = this[key]) {
        null -> emptyList()
        is JsonArray ->
            value.mapIndexed { index, element ->
                element.asFoxCoreInt("$path.$key[$index]")
            }
        else -> listOf(value.asFoxCoreInt("$path.$key"))
    }

internal fun JsonObject.singleNetworkValue(path: String): String? {
    val networks = stringOrArrayValues("network", path)
    if (networks.isEmpty()) {
        return null
    }
    if (networks.size != 1 || networks.single() !in setOf("tcp", "udp")) {
        rejectFoxCoreConfig(FoxCoreConfigRejection.POLICY_UNREPRESENTABLE, "$path.network")
    }
    return networks.single()
}

internal fun isManagedDnsHijack(source: JsonObject): Boolean {
    if (!source.keys.all { it in setOf("protocol", "port", "action") }) {
        return false
    }
    val protocol = source["protocol"]?.jsonPrimitive?.contentOrNull
    val port = source["port"]?.jsonPrimitive?.contentOrNull
    return protocol == "dns" || port == "53" || (protocol == null && port == null)
}

internal fun isManagedRuntimeLoopbackRule(source: JsonObject): Boolean =
    source.keys.all { it in setOf("inbound", "network", "action", "outbound") } &&
        source.stringOrArrayValues("inbound", "$.route.rules") == listOf(RUNTIME_LOOPBACK_PROXY_INBOUND_TAG) &&
        source.optionalString("network", "$.route.rules") == "tcp" &&
        source.optionalString("action", "$.route.rules") == "route" &&
        source.optionalString("outbound", "$.route.rules") in
        setOf(
            "proxy",
            TOR_OVER_VPN_OUTBOUND_TAG,
            "tor",
        )

internal fun defaultDnsPort(
    type: String,
    path: String,
): Int =
    when (type) {
        "udp", "tcp" -> 53
        "tls" -> 853
        "https" -> 443
        else -> rejectFoxCoreConfig(FoxCoreConfigRejection.POLICY_UNREPRESENTABLE, path)
    }

internal fun socketAddress(
    host: String,
    port: Int,
): String = if (host.contains(':')) "[$host]:$port" else "$host:$port"

internal fun urlHost(host: String): String = if (host.contains(':')) "[$host]" else host

private val PACKAGE_PATTERN = Regex("""^[A-Za-z0-9._]{1,255}$""")

internal val ROUTE_KEYS =
    setOf(
        "rules",
        "rule_set",
        "final",
        "default_domain_resolver",
        "auto_detect_interface",
        "override_android_vpn",
    )

internal val ROUTE_RULE_KEYS =
    setOf(
        "type",
        "rules",
        "action",
        "outbound",
        "inbound",
        "package_name",
        "domain",
        "domain_suffix",
        "domain_keyword",
        "domain_regex",
        "ip_cidr",
        "ip_is_private",
        "port",
        "port_range",
        "network",
        "protocol",
        "invert",
        "rule_set",
        "method",
        "no_drop",
    )

internal val DNS_KEYS =
    setOf(
        "servers",
        "rules",
        "strategy",
        "final",
    )

internal val DNS_SERVER_KEYS =
    setOf(
        "tag",
        "type",
        "server",
        "server_ip",
        "server_port",
        "path",
        "detour",
        "domain_resolver",
    )

internal val DNS_ALL_SERVER_KEYS =
    DNS_SERVER_KEYS +
        setOf(
            "inet4_range",
            "inet6_range",
        )

internal val DNS_FAKE_IP_SERVER_KEYS =
    setOf(
        "tag",
        "type",
        "inet4_range",
        "inet6_range",
    )

internal val DNS_STRATEGIES =
    setOf(
        "as_is",
        "prefer_ipv4",
        "prefer_ipv6",
        "ipv4_only",
        "ipv6_only",
    )

internal const val TOR_DOMAIN_SUFFIX = ".onion"

internal val PRIVATE_NETWORK_CIDRS =
    listOf(
        "10.0.0.0/8",
        "100.64.0.0/10",
        "127.0.0.0/8",
        "169.254.0.0/16",
        "172.16.0.0/12",
        "192.168.0.0/16",
        "::1/128",
        "fc00::/7",
        "fe80::/10",
    )
