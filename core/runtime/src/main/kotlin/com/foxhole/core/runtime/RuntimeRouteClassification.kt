package com.foxhole.core.runtime

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// Route-rule classification predicates for RuntimeConfigAssembler: identify Foxhole-managed
// route / hijack / udp / package rules so re-assembly is idempotent. Pure JSON predicates.

internal fun isFoxholeManagedRoute(route: JsonObject): Boolean {
    if (!route.keys.all { it in FOXHOLE_ROUTE_KEYS }) {
        return false
    }
    if (route["final"]?.jsonPrimitive?.contentOrNull?.let { it != "proxy" } == true) {
        return false
    }
    val resolver = route["default_domain_resolver"]?.jsonPrimitive?.contentOrNull
    if (resolver != null && resolver !in setOf(DNS_LOCAL_TAG, DNS_DIRECT_TAG, DNS_REMOTE_TAG)) {
        return false
    }
    return route["rules"]?.jsonArray.orEmpty().all { isFoxholeManagedRouteRule(it.jsonObject) }
}

internal fun isFoxholeManagedRouteRule(rule: JsonObject): Boolean {
    val action = rule["action"]?.jsonPrimitive?.contentOrNull ?: return false
    return when (action) {
        "sniff" -> rule.keys.all { it == "action" }
        "hijack-dns" -> isFoxholeManagedHijackDnsRule(rule)
        "route" -> isFoxholeManagedRouteActionRule(rule)
        "reject" -> isFoxholeManagedUdpRejectRule(rule)
        else -> false
    }
}

internal fun isFoxholeManagedHijackDnsRule(rule: JsonObject): Boolean =
    foxholeHijackMatch(rule) &&
        rule.keys.all { it in setOf("protocol", "port", "action") }

internal fun isFoxholeManagedRouteActionRule(rule: JsonObject): Boolean =
    isFoxholeManagedRuntimeLoopbackRule(rule) ||
        isFoxholeManagedPrivateDirectRule(rule) ||
        isFoxholeManagedUdpBlockRule(rule) ||
        isFoxholeManagedPackageRouteRule(rule)

internal fun isFoxholeManagedRuntimeLoopbackRule(rule: JsonObject): Boolean =
    rule["inbound"]?.jsonArray?.singleOrNull()?.jsonPrimitive?.contentOrNull == RUNTIME_LOOPBACK_PROXY_INBOUND_TAG &&
        rule["network"]?.jsonPrimitive?.contentOrNull == "tcp" &&
        rule["outbound"]?.jsonPrimitive?.contentOrNull in setOf("proxy", TOR_OVER_VPN_OUTBOUND_TAG) &&
        rule.keys.all { it in setOf("inbound", "network", "action", "outbound") }

internal fun isFoxholeManagedPrivateDirectRule(rule: JsonObject): Boolean =
    rule["outbound"]?.jsonPrimitive?.contentOrNull == "direct" &&
        rule["ip_is_private"]?.jsonPrimitive?.contentOrNull == "true" &&
        rule.keys.all { it in setOf("ip_is_private", "action", "outbound") }

internal fun isFoxholeManagedUdpBlockRule(rule: JsonObject): Boolean =
    rule["network"]?.jsonPrimitive?.contentOrNull == "udp" &&
        rule["outbound"]?.jsonPrimitive?.contentOrNull == "block" &&
        rule.keys.all { it in setOf("network", "action", "outbound") }

internal fun isFoxholeManagedUdpRejectRule(rule: JsonObject): Boolean =
    rule["network"]?.jsonPrimitive?.contentOrNull == "udp" &&
        rule["method"]?.jsonPrimitive?.contentOrNull == "default" &&
        rule["no_drop"]?.jsonPrimitive?.contentOrNull == "true" &&
        rule.keys.all { it in setOf("network", "action", "method", "no_drop") }

internal fun isFoxholeManagedPackageRouteRule(rule: JsonObject): Boolean =
    rule["outbound"]?.jsonPrimitive?.contentOrNull in setOf("proxy", "direct", "block") &&
        rule["package_name"] != null &&
        rule.keys.all { it in setOf("package_name", "action", "outbound") }

internal fun foxholeHijackMatch(rule: JsonObject): Boolean {
    val protocol = rule["protocol"]?.jsonPrimitive?.contentOrNull
    if (protocol == "dns") {
        return true
    }
    return foxholeHijackPorts(rule["port"])
}

internal fun foxholeHijackPorts(port: JsonElement?): Boolean {
    if (port == null) {
        return true
    }
    return when (port) {
        is JsonPrimitive -> port.contentOrNull == "53"
        else -> false
    }
}
