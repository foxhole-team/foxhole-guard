// Component-updates settings support (the "Component updates" screen): the two master switches,
// the delete-downloaded-databases action behind the top-bar trash icon, and the once-per-start
// background availability probe feeding the settings-home blue update dot. Extracted from
// HomeViewModel by domain, same pattern as the DNS-filter / GeoIP / Tor-bridge support files.

package com.foxhole.guard.ui

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.foxhole.core.model.dnsRuleSetFilteringEnabled
import com.foxhole.guard.R
import com.foxhole.guard.applyDnsFilterUpdateSchedule
import com.foxhole.guard.applyGeoIpUpdateSchedule
import com.foxhole.guard.applyTorBridgeUpdateSchedule
import com.foxhole.guard.runtime.DnsFilterUpdateAvailability
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun HomeViewModel.onComponentUpdateCheckChanged(value: Boolean) {
    viewModelScope.launch {
        container.settingsRepository.updateComponentUpdateCheckEnabled(value)
        applyComponentUpdateSchedules()
        if (!value) {
            // With checking off nothing may claim "update available" anymore.
            componentGeoIpUpdateAvailableMutable.value = false
            dnsFilterUpdateAvailableMutable.value = false
        }
    }
}

internal fun HomeViewModel.onComponentAutoUpdateChanged(value: Boolean) {
    viewModelScope.launch {
        container.settingsRepository.updateComponentAutoUpdate(value)
        applyComponentUpdateSchedules()
    }
}

// Re-derives every scheduled component refresh from the CURRENT settings: the check master gates
// them all, the auto flags pick which ones run (12h cadence lives on the work requests).
private fun HomeViewModel.applyComponentUpdateSchedules() {
    val settings = container.settingsRepository.settings.value
    val permitted = settings.connection.componentUpdateCheckEnabled
    val app = getApplication<Application>()
    app.applyGeoIpUpdateSchedule(enabled = permitted && settings.connection.geoIpAutoUpdate)
    app.applyDnsFilterUpdateSchedule(
        enabled = permitted && settings.dns.autoUpdateFilters && settings.dns.dnsRuleSetFilteringEnabled(),
    )
    app.applyTorBridgeUpdateSchedule(enabled = permitted && settings.privacyRoute.bridgesAutoUpdate)
}

// The top-bar trash action: every downloaded override (GeoIP ranges, DNS filter lists, Tor bridge
// list) is deleted and the bundled data takes over; the update stamps reset to "never".
internal fun HomeViewModel.onDeleteDownloadedComponentData() {
    viewModelScope.launch {
        val cleared =
            withContext(Dispatchers.IO) {
                runCatching {
                    container.geoIpUpdateRepository.clearDownloaded()
                    container.dnsFilterAssetInstaller.clearLocalCache()
                    container.torBridgeUpdateRepository.clearDownloaded()
                    // With filtering live the runtime still needs a verified list on disk — the
                    // installer re-materializes the BUNDLED one right away.
                    container.dnsFilterAssetInstaller.prepareVerifiedOrNull()
                }.isSuccess
            }
        if (!cleared) {
            emitError(getApplication<Application>().getString(R.string.component_updates_delete_failed))
            return@launch
        }
        container.settingsRepository.clearComponentUpdateStamps()
        componentGeoIpUpdateAvailableMutable.value = false
        dnsFilterUpdateAvailableMutable.value = false
        refreshGeoIpDatabaseInfo()
        emitSuccess(getApplication<Application>().getString(R.string.component_updates_deleted_banner))
    }
}

/**
 * Once-per-start availability probe (cheap version manifests only, nothing downloads): runs a
 * while after launch and only under the check master. Auto-update on means the workers install
 * updates themselves — the dot stays dark — so the probe only matters for the manual story.
 */
internal fun HomeViewModel.superviseComponentUpdateAvailabilityInternal() {
    viewModelScope.launch {
        delay(COMPONENT_UPDATE_CHECK_STARTUP_DELAY_MS)
        val settings = container.settingsRepository.settings.value
        if (!settings.connection.componentUpdateCheckEnabled) {
            return@launch
        }
        runCatching { container.geoIpUpdateRepository.checkForUpdate() }
            .getOrNull()
            ?.let { available -> componentGeoIpUpdateAvailableMutable.value = available }
        if (settings.dns.dnsRuleSetFilteringEnabled() && !dnsFilterRefreshInProgressMutable.value) {
            runCatching { container.dnsFilterUpdateRepository.checkForUpdate() }
                .getOrNull()
                ?.let { check ->
                    if (check.availability == DnsFilterUpdateAvailability.UPDATE_AVAILABLE) {
                        dnsFilterUpdateAvailableMutable.value = true
                    }
                }
        }
    }
}

// Late enough that cold-start rendering, settings warm-up and the first IP refresh are done.
private const val COMPONENT_UPDATE_CHECK_STARTUP_DELAY_MS = 12_000L
