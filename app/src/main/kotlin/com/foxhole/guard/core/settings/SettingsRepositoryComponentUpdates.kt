package com.foxhole.guard.core.settings

import com.foxhole.core.model.UpdateSourceSettings
import com.foxhole.guard.runtime.foxholeDbManifestUrl

suspend fun SettingsRepository.updateUpdateSources(value: UpdateSourceSettings) =
    update { current ->
        val next = value.normalized()
        current.copy(
            updateSources = next,
            dns = current.dns.copy(dnsFilterUpdateUrl = foxholeDbManifestUrl(next.databaseBaseUrl)),
        )
    }

suspend fun SettingsRepository.updateGeoIpAutoUpdate(value: Boolean) =
    update { it.copy(connection = it.connection.copy(geoIpAutoUpdate = value)) }

suspend fun SettingsRepository.updateTlsFingerprintAutoUpdate(value: Boolean) =
    update { it.copy(connection = it.connection.copy(tlsFingerprintAutoUpdate = value)) }

suspend fun SettingsRepository.updateComponentUpdateCheckEnabled(value: Boolean) =
    update { it.copy(connection = it.connection.copy(componentUpdateCheckEnabled = value)) }

suspend fun SettingsRepository.updateComponentAutoUpdate(value: Boolean) =
    update {
        it.copy(
            connection =
            it.connection.copy(
                componentAutoUpdateEnabled = value,
                geoIpAutoUpdate = value,
                tlsFingerprintAutoUpdate = value,
            ),
            dns = it.dns.copy(autoUpdateFilters = value),
            privacyRoute = it.privacyRoute.copy(bridgesAutoUpdate = value),
        )
    }

suspend fun SettingsRepository.clearComponentUpdateStamps() =
    update {
        it.copy(
            dns = it.dns.copy(filtersUpdatedAt = null, filtersCheckedAt = null),
            privacyRoute =
            it.privacyRoute.copy(
                bridgesUpdatedAt = null,
                bridgesCheckedAt = null,
                bridgesLastUpdateSuccess = null,
            ),
        )
    }
