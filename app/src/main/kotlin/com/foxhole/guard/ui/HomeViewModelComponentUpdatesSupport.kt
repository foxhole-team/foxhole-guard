package com.foxhole.guard.ui

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.foxhole.core.model.UpdateSourceSettings
import com.foxhole.core.model.dnsRuleSetFilteringEnabled
import com.foxhole.guard.BuildConfig
import com.foxhole.guard.R
import com.foxhole.guard.applyAppUpdateSchedule
import com.foxhole.guard.applyDnsFilterUpdateSchedule
import com.foxhole.guard.applyGeoIpUpdateSchedule
import com.foxhole.guard.applyThreatIntelUpdateSchedule
import com.foxhole.guard.applyTlsFingerprintUpdateSchedule
import com.foxhole.guard.applyTorBridgeUpdateSchedule
import com.foxhole.guard.core.settings.clearComponentUpdateStamps
import com.foxhole.guard.core.settings.updateComponentAutoUpdate
import com.foxhole.guard.core.settings.updateComponentUpdateCheckEnabled
import com.foxhole.guard.core.settings.updateDnsSettings
import com.foxhole.guard.core.settings.updateUpdateSources
import com.foxhole.guard.runtime.AppUpdateBuildSignals
import com.foxhole.guard.runtime.AppUpdatePolicy
import com.foxhole.guard.runtime.DnsFilterUpdateAvailability
import com.foxhole.guard.threatIntelBackgroundUpdateEnabled
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun HomeViewModel.onComponentUpdateCheckChanged(value: Boolean) {
    viewModelScope.launch {
        container.settingsRepository.updateComponentUpdateCheckEnabled(value)
        applyComponentUpdateSchedules()
        if (!value) {
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

internal fun HomeViewModel.onUpdateSourcesChanged(value: UpdateSourceSettings) {
    viewModelScope.launch {
        val previous = container.settingsRepository.settings.value.updateSources
        container.settingsRepository.updateUpdateSources(value)
        if (container.settingsRepository.settings.value.updateSources.databaseBaseUrl != previous.databaseBaseUrl) {
            container.settingsRepository.clearComponentUpdateStamps()
            componentGeoIpUpdateAvailableMutable.value = false
            dnsFilterUpdateAvailableMutable.value = false
        }
        applyComponentUpdateSchedules()
    }
}

private fun HomeViewModel.applyComponentUpdateSchedules() {
    val settings = container.settingsRepository.settings.value
    val permitted = settings.connection.componentUpdateCheckEnabled
    val app = getApplication<Application>()
    app.applyGeoIpUpdateSchedule(enabled = permitted && settings.connection.geoIpAutoUpdate)
    app.applyDnsFilterUpdateSchedule(
        enabled = permitted && settings.dns.autoUpdateFilters && settings.dns.dnsRuleSetFilteringEnabled(),
    )
    app.applyTorBridgeUpdateSchedule(enabled = permitted && settings.privacyRoute.bridgesAutoUpdate)
    app.applyThreatIntelUpdateSchedule(enabled = threatIntelBackgroundUpdateEnabled(settings))
    app.applyTlsFingerprintUpdateSchedule(enabled = permitted && settings.connection.tlsFingerprintAutoUpdate)
    app.applyAppUpdateSchedule(
        enabled =
        AppUpdatePolicy.backgroundCheckAllowed(
            channel = BuildConfig.UPDATE_CHANNEL,
            componentUpdateCheckEnabled = permitted,
        ),
    )
}

internal fun HomeViewModel.onDeleteDownloadedComponentData() {
    viewModelScope.launch {
        val cleared =
            withContext(Dispatchers.IO) {
                runCatching {
                    container.geoIpUpdateRepository.clearDownloaded()
                    container.dnsFilterAssetInstaller.clearLocalCache()
                    container.torBridgeUpdateRepository.clearDownloaded()
                }.isSuccess
            }
        if (!cleared) {
            emitError(getApplication<Application>().getString(R.string.component_updates_delete_failed))
            return@launch
        }
        val currentDns = container.settingsRepository.current().dns
        if (currentDns.filteringEnabled) {
            container.settingsRepository.updateDnsSettings(currentDns.copy(filteringEnabled = false))
        }
        container.settingsRepository.clearComponentUpdateStamps()
        componentGeoIpUpdateAvailableMutable.value = false
        dnsFilterUpdateAvailableMutable.value = false
        refreshGeoIpDatabaseInfo()
        emitSuccess(getApplication<Application>().getString(R.string.component_updates_deleted_banner))
    }
}

internal fun HomeViewModel.superviseComponentUpdateAvailabilityInternal() {
    viewModelScope.launch {
        raiseFdroidUpdateNoticeInternal()
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
                    when (check.availability) {
                        DnsFilterUpdateAvailability.UPDATE_AVAILABLE ->
                            dnsFilterUpdateAvailableMutable.value = true
                        DnsFilterUpdateAvailability.UP_TO_DATE ->
                            dnsFilterUpdateAvailableMutable.value = false
                        DnsFilterUpdateAvailability.UNKNOWN -> Unit
                    }
                }
        }
    }
}

internal suspend fun HomeViewModel.raiseFdroidUpdateNoticeInternal() {
    if (!AppUpdateBuildSignals.fdroidUpdateNoticeRequired()) {
        return
    }
    snackbars.emit(errorBanner(R.string.cli_updates_fdroid_banner))
}

private const val COMPONENT_UPDATE_CHECK_STARTUP_DELAY_MS = 12_000L
