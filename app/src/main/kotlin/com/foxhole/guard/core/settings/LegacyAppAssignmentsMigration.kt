package com.foxhole.guard.core.settings

import com.foxhole.core.model.AppTunnelLane
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

// Stores written before the lane model held two parallel per-app lists under `expert`
// (`selectedPackages`, `blockedPackages`). The lane model keeps a single `appAssignments` map, and
// `ignoreUnknownKeys` would silently drop the legacy lists — so fold them in at the JSON boundary,
// before the payload is decoded, exactly once (a store that already has `appAssignments` is left
// untouched). Mapping: blocked -> BLOCK; selected -> TOR when a Tor route over selected apps was
// configured, otherwise VPN; a package in both lists keeps its routing lane (selected wins), which
// mirrors the old normalization that stripped selected apps out of the block list.
internal fun migrateLegacyAppAssignments(expert: JsonObject, privacyRoute: JsonObject?): JsonObject {
    if (expert.containsKey("appAssignments")) {
        return expert
    }
    val selected = expert.stringArray("selectedPackages")
    val blocked = expert.stringArray("blockedPackages")
    if (selected.isEmpty() && blocked.isEmpty()) {
        return expert.withoutLegacyPackageKeys()
    }
    val torLane =
        privacyRoute != null &&
            privacyRoute.stringOrNull("mode")?.let { it != "OFF" } == true &&
            privacyRoute.stringOrNull("scope") == "SELECTED_APPS"
    val selectedLane = if (torLane) AppTunnelLane.TOR else AppTunnelLane.VPN
    val assignments = linkedMapOf<String, AppTunnelLane>()
    blocked.forEach { assignments[it] = AppTunnelLane.BLOCK }
    // Selected overrides block: an app the user routes must not be silently firewalled.
    selected.forEach { assignments[it] = selectedLane }

    return buildJsonObject {
        expert.forEach { (key, value) ->
            if (key != "selectedPackages" && key != "blockedPackages") {
                put(key, value)
            }
        }
        put(
            "appAssignments",
            JsonObject(assignments.mapValues { (_, lane) -> JsonPrimitive(lane.name) }),
        )
    }
}

private fun JsonObject.withoutLegacyPackageKeys(): JsonObject =
    if (!containsKey("selectedPackages") && !containsKey("blockedPackages")) {
        this
    } else {
        buildJsonObject {
            this@withoutLegacyPackageKeys.forEach { (key, value) ->
                if (key != "selectedPackages" && key != "blockedPackages") {
                    put(key, value)
                }
            }
        }
    }

private fun JsonObject.stringArray(key: String): List<String> =
    (this[key] as? JsonArray)
        ?.mapNotNull { element -> (element as? JsonPrimitive)?.takeIf { it.isString }?.content }
        ?.filter(String::isNotBlank)
        .orEmpty()

private fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
