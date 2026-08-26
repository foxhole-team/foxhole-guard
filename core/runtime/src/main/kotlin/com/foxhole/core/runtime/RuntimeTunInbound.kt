package com.foxhole.core.runtime

import com.foxhole.core.model.DnsSettings
import com.foxhole.core.model.ExpertSettings
import com.foxhole.core.model.PrivacyRouteScope
import com.foxhole.core.model.Settings
import com.foxhole.core.model.TunStack
import com.foxhole.core.model.blockedLanePackages
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

internal data class RuntimeSplitPlan(
    val vpnMode: VpnAppSelectionMode,
    val vpnIncludedPackages: List<String>,
    val vpnExcludedPackages: List<String>,
    val torAllApps: Boolean,
    val torTcpPackages: List<String>,
    val torUdpBlockedPackages: List<String>,
    val blockedPackages: List<String>,
    val directPackages: List<String> = emptyList(),
    val warnings: List<SplitWarning> = emptyList(),
)

internal enum class VpnAppSelectionMode {
    FULL_DEVICE,
    INCLUDE_ONLY,
    EXCLUDE_SELECTED,
}

internal enum class SplitWarning {
    TOR_SELECTED_APPS_FORCE_TUN_INCLUDE,
}

// Package splits stay in route rules, not TUN allowlists, so membership reloads reuse the kernel interface.
internal fun patchTunInbound(
    tunInbound: JsonObject,
    dnsSettings: DnsSettings,
    expert: ExpertSettings,
    mtu: Int,
    stack: TunStack,
): JsonObject =
    buildJsonObject {
        tunInbound.forEach { (key, value) ->
            when (key) {
                "mtu" -> put(key, mtu)
                "stack" -> put(key, stack.configValue)
                "strict_route" -> put(key, expert.strictRoute || dnsSettings.blockOutsideTunnel)
                "sniff",
                "sniff_override_destination",
                "sniff_timeout",
                "domain_strategy",
                "include_package",
                "exclude_package" -> Unit
                else -> put(key, value)
            }
        }
        put("mtu", mtu)
        put("stack", stack.configValue)
        put("strict_route", expert.strictRoute || dnsSettings.blockOutsideTunnel)
    }

internal fun effectiveTunMtu(
    base: JsonObject,
    configuredMtu: Int,
): Int {
    val wireGuardMtu =
        base["endpoints"]
            ?.jsonArray
            .orEmpty()
            .map { it.jsonObject }
            .filter { endpoint ->
                endpoint["type"]?.jsonPrimitive?.contentOrNull.equals(
                    "wireguard",
                    ignoreCase = true
                )
            }
            .mapNotNull { endpoint -> endpoint["mtu"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() }
            .minOrNull()
    return wireGuardMtu?.let { minOf(configuredMtu, it) } ?: configuredMtu
}

internal fun effectiveTunnelTunStack(
    configured: TunStack,
): TunStack =
    when (configured) {
        TunStack.SYSTEM -> TunStack.GVISOR
        TunStack.GVISOR -> TunStack.GVISOR
    }

// Reject rules and split rules must not name the same package; FoxCore refuses that policy.
private fun failClosedTorPackages(
    settings: Settings,
    privacyRouteActive: Boolean,
): Set<String> =
    if (settings.privacyRoute.blockAppsWhenTorUnavailable) {
        settings.expert
            .failClosedBlockPackages(torLaneCarried = privacyRouteActive, vpnLaneCarried = true)
            .toSet()
    } else {
        emptySet()
    }

internal fun buildSplitPlan(
    settings: Settings,
    privacyRouteActive: Boolean,
    selfPackageName: String? = null,
): RuntimeSplitPlan {
    val blockedPackages =
        normalizedRuntimePackages(
            settings.expert.blockedLanePackages().takeIf { settings.expert.blockedPackagesEnabled }.orEmpty(),
        )
    val selectedTorPackages =
        if (privacyRouteActive && settings.privacyRoute.scope == PrivacyRouteScope.SELECTED_APPS) {
            settings.expert.torLanePackages()
        } else {
            emptyList()
        }
    val torAllApps = privacyRouteActive && settings.privacyRoute.scope == PrivacyRouteScope.ALL_APPS

    val selfPackage = selfPackageName?.trim()?.takeIf(String::isNotEmpty)
    val excludedSelfPackage = selfPackage?.takeIf { settings.webApps.enabled }
    val baseIncluded = settings.expert.vpnIncludedPackages()
    val baseExcluded =
        settings.expert.vpnExcludedPackages()
            .filterNot { packageName -> packageName == excludedSelfPackage }
    val selectedTorPackageSet = selectedTorPackages.toSet()
    val includePackages =
        if (baseIncluded.isNotEmpty()) {
            normalizedRuntimePackages(baseIncluded + selectedTorPackages + listOfNotNull(selfPackage))
        } else {
            baseIncluded
        }
    val failClosedTorPackages = failClosedTorPackages(settings, privacyRouteActive)
    val excludePackages =
        if (includePackages.isNotEmpty()) {
            emptyList()
        } else if (selectedTorPackageSet.isNotEmpty()) {
            baseExcluded.filterNot { packageName -> packageName in selectedTorPackageSet }
        } else {
            baseExcluded
        }.filterNot { packageName -> packageName in failClosedTorPackages }
    val torChangesTunPolicy =
        selectedTorPackageSet.isNotEmpty() &&
            (baseIncluded.isNotEmpty() || baseExcluded != excludePackages)
    val vpnMode =
        when {
            includePackages.isNotEmpty() -> VpnAppSelectionMode.INCLUDE_ONLY
            excludePackages.isNotEmpty() -> VpnAppSelectionMode.EXCLUDE_SELECTED
            else -> VpnAppSelectionMode.FULL_DEVICE
        }
    return RuntimeSplitPlan(
        vpnMode = vpnMode,
        vpnIncludedPackages = includePackages,
        vpnExcludedPackages = excludePackages,
        torAllApps = torAllApps,
        torTcpPackages = selectedTorPackages,
        torUdpBlockedPackages = selectedTorPackages,
        blockedPackages = blockedPackages,
        warnings =
        if (torChangesTunPolicy) {
            listOf(SplitWarning.TOR_SELECTED_APPS_FORCE_TUN_INCLUDE)
        } else {
            emptyList()
        },
    )
}

internal fun localGuardTunInbound(
    settings: Settings,
    mode: LocalGuardMode,
    selfPackageName: String,
    dnsGuardFullCapture: Boolean = false,
): JsonObject =
    buildJsonObject {
        put("type", "tun")
        put("tag", "tun-in")
        put("interface_name", "foxhole")
        put("mtu", settings.traffic.mtu)
        put("auto_route", true)
        put("strict_route", false)
        put("stack", effectiveTunnelTunStack(settings.traffic.tunStack).configValue)

        val dnsNarrowRoutes = mode == LocalGuardMode.DNS && !dnsGuardFullCapture
        if (dnsNarrowRoutes) {
            putJsonArray("route_address") {
                add(JsonPrimitive("$LOCAL_GUARD_DNS_SERVER_ADDRESS/32"))
                add(JsonPrimitive(LOCAL_GUARD_INET6_CAPTURE_ROUTE))
            }
        }
        putJsonArray("address") {
            add(JsonPrimitive(LOCAL_GUARD_TUN_ADDRESS))
            add(JsonPrimitive(LOCAL_GUARD_TUN_INET6_ADDRESS))
        }

        if (!settings.webApps.enabled) {
            putJsonArray("exclude_package") {
                localGuardExcludedPackages(selfPackageName).forEach { packageName ->
                    add(JsonPrimitive(packageName))
                }
            }
        }
    }

internal fun localGuardExcludedPackages(selfPackageName: String): List<String> =
    listOf(selfPackageName)
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()

internal fun torOnlyTunInbound(
    settings: Settings,
    selfPackageName: String,
): JsonObject =
    buildJsonObject {
        put("type", "tun")
        put("tag", "tun-in")
        put("interface_name", "foxhole")
        put("mtu", settings.traffic.mtu)
        put("auto_route", true)
        put("strict_route", settings.expert.strictRoute || settings.dns.blockOutsideTunnel)
        put("stack", effectiveTunnelTunStack(settings.traffic.tunStack).configValue)
        putJsonArray("address") {
            add(JsonPrimitive("172.19.0.1/30"))
            add(JsonPrimitive(LOCAL_GUARD_TUN_INET6_ADDRESS))
        }
        val torPackages =
            if (settings.privacyRoute.scope == PrivacyRouteScope.SELECTED_APPS) {
                settings.expert.torLanePackages()
            } else {
                emptyList()
            }
        val failClosedVpnPackages =
            if (settings.privacyRoute.scope == PrivacyRouteScope.SELECTED_APPS &&
                settings.privacyRoute.blockAppsWhenTorUnavailable
            ) {
                settings.expert.vpnLanePackages()
            } else {
                emptyList()
            }
        val includePackages =
            if (settings.privacyRoute.scope == PrivacyRouteScope.SELECTED_APPS) {
                normalizedRuntimePackages(
                    torPackages +
                        failClosedVpnPackages +
                        listOfNotNull(selfPackageName.takeIf { settings.webApps.enabled }),
                )
            } else {
                emptyList()
            }
        val excludePackages =
            if (settings.privacyRoute.scope == PrivacyRouteScope.SELECTED_APPS) {
                emptyList()
            } else if (settings.webApps.enabled) {
                emptyList()
            } else {
                localGuardExcludedPackages(selfPackageName)
            }
        when {
            includePackages.isNotEmpty() ->
                putJsonArray("include_package") {
                    includePackages.forEach { add(JsonPrimitive(it)) }
                }
            excludePackages.isNotEmpty() ->
                putJsonArray("exclude_package") {
                    excludePackages.forEach { add(JsonPrimitive(it)) }
                }
        }
    }
