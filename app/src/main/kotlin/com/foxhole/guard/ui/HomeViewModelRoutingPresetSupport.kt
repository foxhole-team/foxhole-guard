package com.foxhole.guard.ui
import android.app.Application
import androidx.lifecycle.viewModelScope
import com.foxhole.core.model.PrivacyRouteScope
import com.foxhole.core.model.RoutingModePreset
import com.foxhole.core.model.RoutingPresetOverrideMode
import com.foxhole.core.model.RoutingRuleAction
import com.foxhole.core.model.tunnelSelectedPackages
import com.foxhole.guard.R
import com.foxhole.guard.core.settings.applyRoutingModePreset
import com.foxhole.guard.userFacingErrorMessage
import kotlinx.coroutines.launch

// Routing presets, rules, catalogs and geoip database handlers for HomeViewModel.
// Extracted from HomeViewModelSettingsSupport (file split by domain); extension functions only.

/** Applies the new UI's MODE choice and hot-reloads a live TUN when only rules changed. */
internal fun HomeViewModel.onRoutingModePresetSelected(
    preset: RoutingModePreset,
    scope: PrivacyRouteScope,
): Boolean {
    if (!routingModePresetSelectable(preset, scope)) {
        return false
    }
    updateRuntimeSettingAndMaybeReload {
        container.settingsRepository.applyRoutingModePreset(preset, scope)
    }
    return true
}

/** Persists the selected MODE before starting its matching runtime. */
internal fun HomeViewModel.startRoutingMode(
    preset: RoutingModePreset,
    scope: PrivacyRouteScope,
) {
    if (!routingModePresetSelectable(preset, scope)) {
        return
    }
    viewModelScope.launch {
        container.settingsRepository.applyRoutingModePreset(preset, scope)
        when (preset) {
            RoutingModePreset.TOR -> onEnableDirectTorQuickStart()
            RoutingModePreset.VPN,
            RoutingModePreset.VPN_TOR,
            RoutingModePreset.SPLIT_INCLUDE,
            RoutingModePreset.SPLIT_EXCLUDE,
            -> startVpnConnectionInternal()
        }
    }
}

private fun HomeViewModel.routingModePresetSelectable(
    preset: RoutingModePreset,
    scope: PrivacyRouteScope,
): Boolean {
    val settings = container.settingsRepository.settings.value
    val usesTor = preset == RoutingModePreset.TOR || preset == RoutingModePreset.VPN_TOR
    if (usesTor && !settings.privacyRoute.permitted) {
        snackbars.tryEmit(errorBanner(R.string.privacy_route_core_forbidden))
        return false
    }
    if (
        usesTor &&
        scope == PrivacyRouteScope.SELECTED_APPS &&
        settings.expert.tunnelSelectedPackages().none(String::isNotBlank)
    ) {
        snackbars.tryEmit(errorBanner(R.string.privacy_route_select_apps_first))
        return false
    }
    return true
}

internal fun HomeViewModel.createPreset(name: String) {
    viewModelScope.launch {
        runCatching {
            container.routingRepository.createPreset(
                name = name,
                activate = controlUiState.value.presets.isEmpty()
            )
        }
            .onSuccess { maybeReloadActiveRuntime() }
            .onFailure {
                emitError(
                    getApplication<Application>().userFacingErrorMessage(
                        it,
                        R.string.routing_preset_create_failed,
                    ),
                )
            }
    }
}

internal fun HomeViewModel.updatePreset(
    presetId: Long,
    name: String,
    overrideMode: RoutingPresetOverrideMode,
    enabled: Boolean,
) {
    viewModelScope.launch {
        runCatching { container.routingRepository.updatePreset(presetId, name, overrideMode, enabled) }
            .onSuccess { maybeReloadActiveRuntime() }
            .onFailure {
                emitError(
                    getApplication<Application>().userFacingErrorMessage(
                        it,
                        R.string.routing_preset_update_failed,
                    ),
                )
            }
    }
}

internal fun HomeViewModel.setActivePreset(presetId: Long?) {
    viewModelScope.launch {
        container.routingRepository.setActivePreset(presetId)
        maybeReloadActiveRuntime()
    }
}

internal fun HomeViewModel.deletePreset(presetId: Long) {
    viewModelScope.launch {
        container.routingRepository.deletePreset(presetId)
        maybeReloadActiveRuntime()
    }
}

internal fun HomeViewModel.saveRule(
    presetId: Long,
    ruleId: Long?,
    name: String,
    enabled: Boolean,
    order: Int?,
    action: RoutingRuleAction,
    matchDomains: List<String>,
    matchIpCidrs: List<String>,
    matchPorts: List<String>,
    matchProtocols: List<String>,
    matchNetworks: List<String>,
) {
    viewModelScope.launch {
        runCatching {
            container.routingRepository.upsertRule(
                presetId = presetId,
                ruleId = ruleId,
                name = name,
                enabled = enabled,
                order = order,
                action = action,
                matchDomains = matchDomains,
                matchIpCidrs = matchIpCidrs,
                matchPorts = matchPorts,
                matchProtocols = matchProtocols,
                matchNetworks = matchNetworks,
            )
        }.onSuccess {
            maybeReloadActiveRuntime()
        }.onFailure {
            emitError(
                getApplication<Application>().userFacingErrorMessage(
                    it,
                    R.string.routing_rule_save_failed,
                ),
            )
        }
    }
}

internal fun HomeViewModel.deleteRule(ruleId: Long) {
    viewModelScope.launch {
        container.routingRepository.deleteRule(ruleId)
        maybeReloadActiveRuntime()
    }
}

internal fun HomeViewModel.addCatalog(
    name: String,
    url: String,
) {
    viewModelScope.launch {
        runCatching { container.routingRepository.addCatalog(name, url) }
            .onFailure {
                emitError(
                    getApplication<Application>().userFacingErrorMessage(
                        it,
                        R.string.routing_catalog_add_failed,
                    ),
                )
            }
    }
}

internal fun HomeViewModel.refreshCatalog(catalogId: Long) {
    viewModelScope.launch {
        runCatching { container.routingRepository.refreshCatalog(catalogId) }
            .onSuccess { loadCatalogPreview(catalogId) }
            .onFailure {
                emitError(
                    getApplication<Application>().userFacingErrorMessage(
                        it,
                        R.string.routing_catalog_refresh_failed,
                    ),
                )
            }
    }
}

internal fun HomeViewModel.deleteCatalog(catalogId: Long) {
    viewModelScope.launch {
        container.routingRepository.deleteCatalog(catalogId)
        catalogPresetPreviewsMutable.value = catalogPresetPreviewsMutable.value - catalogId
    }
}

internal fun HomeViewModel.importPresetFromCatalog(
    catalogId: Long,
    presetId: String,
) {
    viewModelScope.launch {
        runCatching { container.routingRepository.importPresetFromCatalog(catalogId, presetId) }
            .onSuccess { emitSuccess(getApplication<Application>().getString(R.string.routing_preset_imported)) }
            .onFailure {
                emitError(
                    getApplication<Application>().userFacingErrorMessage(
                        it,
                        R.string.routing_catalog_import_failed,
                    ),
                )
            }
    }
}

internal fun HomeViewModel.loadCatalogPreview(catalogId: Long) {
    viewModelScope.launch {
        runCatching { container.routingRepository.previewCatalogPresets(catalogId) }
            .onSuccess { previews ->
                catalogPresetPreviewsMutable.value = catalogPresetPreviewsMutable.value + (catalogId to previews)
            }.onFailure {
                emitError(
                    getApplication<Application>().userFacingErrorMessage(
                        it,
                        R.string.routing_catalog_preview_failed,
                    ),
                )
            }
    }
}

internal suspend fun HomeViewModel.exportPresetDocument(presetId: Long): String = container.routingRepository.exportPresetDocument(
    presetId
)

// --- GeoIP database (offline IP→country ranges used by the dashboard, Tor exit geo and the
// traffic map). The About screen shows the installed version and lets the user pull the latest
// dataset from the open-source GitHub mirror.

data class GeoIpDatabaseUiState(
    val info: com.foxhole.core.runtime.GeoIpDatabaseInfo? = null,
    val phase: FoxholeUpdatePhase = FoxholeUpdatePhase.IDLE,
) {
    val updating: Boolean get() = phase.isRunning
}

internal fun HomeViewModel.refreshGeoIpDatabaseInfo() {
    viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
        val info = runCatching { container.geoIpUpdateRepository.currentInfo() }.getOrNull()
        geoIpDatabaseUiStateMutable.value = geoIpDatabaseUiStateMutable.value.copy(info = info)
    }
}

private fun HomeViewModel.setGeoIpUpdatePhase(phase: FoxholeUpdatePhase) {
    geoIpDatabaseUiStateMutable.value = geoIpDatabaseUiStateMutable.value.copy(phase = phase)
}

internal fun HomeViewModel.onGeoIpDatabaseUpdateRequested() {
    if (geoIpDatabaseUiStateMutable.value.updating) {
        return
    }
    setGeoIpUpdatePhase(FoxholeUpdatePhase.CHECKING)
    geoIpDatabaseUpdateJob =
        viewModelScope.launch {
            val result =
                runCatching {
                    container.geoIpUpdateRepository.refreshNow(
                        onPhase = { phase -> setGeoIpUpdatePhase(phase.toFoxholeUpdatePhase()) },
                    )
                }.getOrElse {
                    settleUpdatePhase(::setGeoIpUpdatePhase, FoxholeUpdatePhase.FAILED)
                    refreshGeoIpDatabaseInfo()
                    return@launch
                }
            val terminalPhase =
                when (result.status) {
                    com.foxhole.guard.runtime.GeoIpUpdateStatus.UPDATED -> FoxholeUpdatePhase.DONE
                    com.foxhole.guard.runtime.GeoIpUpdateStatus.UP_TO_DATE -> FoxholeUpdatePhase.NO_UPDATE
                    com.foxhole.guard.runtime.GeoIpUpdateStatus.FAILED -> FoxholeUpdatePhase.FAILED
                }
            refreshGeoIpDatabaseInfo()
            settleUpdatePhase(::setGeoIpUpdatePhase, terminalPhase)
        }
}

internal fun HomeViewModel.onGeoIpDatabaseUpdateCancel() {
    geoIpDatabaseUpdateJob?.cancel()
    geoIpDatabaseUpdateJob = null
    setGeoIpUpdatePhase(FoxholeUpdatePhase.IDLE)
}

// Holds a terminal update status ("No updates" / "Done" / "Failed") briefly so the user can read
// it, then returns the control to its resting "Update" state.
internal suspend fun HomeViewModel.settleUpdatePhase(
    set: (FoxholeUpdatePhase) -> Unit,
    terminalPhase: FoxholeUpdatePhase,
) {
    set(terminalPhase)
    kotlinx.coroutines.delay(UPDATE_TERMINAL_STATUS_HOLD_MS)
    set(FoxholeUpdatePhase.IDLE)
}

internal const val UPDATE_TERMINAL_STATUS_HOLD_MS = 2_200L
