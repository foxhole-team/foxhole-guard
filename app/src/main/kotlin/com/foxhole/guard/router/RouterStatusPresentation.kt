package com.foxhole.guard.router

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

internal data class RouterStatusPresentation(
    val connected: Boolean?,
    val profileName: String?,
    val localTraffic: Boolean?,
    val countryCode: String?,
)

internal fun routerStatusPresentation(state: RouterControlState): RouterStatusPresentation {
    // Desired profile selection and an old snapshot do not prove a live VPN connection.
    val current = state.snapshot?.takeIf { state.stage == RouterControlStage.CONNECTED }
        ?: return RouterStatusPresentation(null, null, null, null)
    val name = current.servers.singleOrNull {
        (it["id"] as? JsonPrimitive)?.contentOrNull == current.desiredServerId
    }?.let { (it["name"] as? JsonPrimitive)?.contentOrNull }?.takeIf { it.isNotBlank() }
    val direct = routerCountryDirectControl(state.preferences, state.routerCountryCode, current)
    val rulesEnabled = (current.settings["localRules"] as? JsonPrimitive)?.booleanOrNull
    val localTraffic = rulesEnabled?.let { direct.checked }
    val country = direct.disableCode.takeIf { localTraffic == true }
    return RouterStatusPresentation(current.connected, name, localTraffic, country)
}
