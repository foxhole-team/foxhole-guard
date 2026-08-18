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

// TUN inbound assembly + per-app split planning for RuntimeConfigAssembler.
// Behaviour-preserving Phase B extraction; the shared RuntimeSplitPlan model lives here.

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

// The tun inbound is always full-device — include_package/exclude_package are stripped and
// never re-emitted. The VPN split lives in package_name route/dns rules instead (see
// buildVpnSplitRouteRules), so membership changes alter the box config, not the kernel interface,
// and a reload can reuse the live tun fd (RuntimeTunFingerprint).
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
        // Android 16/system TUN can validate ICMP while dropping TCP; profile tunnels need TCP-stable delivery.
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
    // This package rides its own include split, always — not only when web apps are on.
    //
    // The app validates a tunnel by probing THROUGH it. In include mode it is not in the selection
    // (the picker filters this package out, so a user cannot put it there), so it sat outside the
    // tunnel it was validating: the probe went out on the underlying network, validation never
    // confirmed, and the session never reached CONNECTED. Measured on the bench Pixel — "include
    // one other app" left the VPN stuck below connected while the selected app would have been
    // tunnelled correctly. Its own traffic following the tunnel it reports on is also the honest
    // shape: the diagnostics the user reads are then about the path their apps take.
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
        // IPv4 stays DNS-only. IPv6 is captured in full and forwarded by the direct outbound:
        // Android cannot express "all possible IPv6 DNS resolvers" as a narrow route, while
        // allowFamily(AF_INET6) would let an app send DNS directly to any IPv6 resolver.
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
        // A WebView cannot be protected socket-by-socket. When web apps are enabled, FoxHole's
        // own UID therefore belongs inside this TUN; FoxCore's outbound sockets are protected at
        // the runtime boundary, so they still leave without recursing into the VPN.
        if (!settings.webApps.enabled) {
            putJsonArray("exclude_package") {
                localGuardExcludedPackages(selfPackageName).forEach { packageName ->
                    add(JsonPrimitive(packageName))
                }
            }
        }
    }

// Normally FoxHole stays out of a local/standalone-Tor TUN so its control-plane is independent.
// Web apps are the explicit exception above: WebView has no per-socket VpnService.protect seam, so
// the app UID must be captured to make the promised route real. Always use the package that is
// actually running; BuildConfig.APPLICATION_ID is the release id and misses suffixed variants.
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
