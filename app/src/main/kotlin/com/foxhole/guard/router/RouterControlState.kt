package com.foxhole.guard.router

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

internal enum class RouterControlStage { LOADING, UNPAIRED, DISABLED, CONNECTING, CONNECTED, OFFLINE, PAIRING_REQUIRED }
internal data class RouterControlState(
    val stage: RouterControlStage = RouterControlStage.LOADING,
    val preferences: RouterControlPreferences? = null,
    val address: String = "",
    val snapshot: RouterControlSnapshot? = null,
    val selectedServerId: String? = null,
    val error: RouterWizardError? = null,
    val busy: Boolean = false,
    val revision: Long = 0,
    val homeAutomationEffective: Boolean = false,
    val clientCreationNeedsSetup: Boolean = false,
    val lanDevices: List<RouterLanDevice> = emptyList(),
    val routerCountryCode: String? = null,
    val remoteClients: List<RouterRemoteClientStatus> = emptyList(),
    val savedRouters: List<RouterSavedSummary> = emptyList(),
    val selectedRouterId: String? = null,
    val engines: RouterEngineControlState = RouterEngineControlState(),
    val updates: RouterUpdateControlState = RouterUpdateControlState(),
) {
    val managementConfigured: Boolean get() = preferences != null &&
        selectedRouterId == preferences.pairingId && savedRouters.any { it.id == selectedRouterId } &&
        stage in setOf(RouterControlStage.DISABLED, RouterControlStage.CONNECTING, RouterControlStage.OFFLINE, RouterControlStage.CONNECTED)
    val managementEnabled: Boolean get() = managementConfigured && preferences?.enabled == true
    val canToggleManagement: Boolean get() = managementConfigured && stage != RouterControlStage.CONNECTING &&
        !busy && !updates.running && !engines.running
    val canAddRouter: Boolean get() = !busy && savedRouters.size < MAX_SAVED_ROUTERS
    val packageRemovalAvailable: Boolean get() = controlsAvailable && snapshot?.packageRemovalAvailable == true
    val controlsAvailable: Boolean get() = stage == RouterControlStage.CONNECTED && !busy && !updates.running && snapshot != null
    val canStopVpn: Boolean get() = stage == RouterControlStage.CONNECTED && !busy &&
        preferences?.enabled == true && snapshot?.desiredServerId != null
    val vpnButtonStarts: Boolean get() {
        val active = snapshot?.desiredServerId
        return !updates.running && !(updates.requiresVpnStop && active != null) &&
            (active == null || selectedServerId != null && selectedServerId != active)
    }
    val countryDirectEffective: Boolean? get() {
        val current = snapshot ?: return null
        val country = if (preferences?.countryAuto == true) routerCountryCode else preferences?.countryCode?.takeIf { it.isNotEmpty() }
        if (country == null) return null
        val rule = current.countries.mapNotNull { it as? JsonObject }.singleOrNull {
            (it["code"] as? JsonPrimitive)?.contentOrNull == country
        }
        return (current.settings["localRules"] as? JsonPrimitive)?.booleanOrNull == true &&
            (rule?.get("route") as? JsonPrimitive)?.contentOrNull == "direct"
    }
}

internal data class RouterEngineControlState(
    val supported: Boolean? = null,
    val status: RouterEngineStatus? = null,
    val plan: RouterEnginePlan? = null,
    val job: RouterEngineJob? = null,
    val failure: RouterEngineFailureReason? = null,
) {
    val running: Boolean get() = job?.running == true || status?.busy == true
}
