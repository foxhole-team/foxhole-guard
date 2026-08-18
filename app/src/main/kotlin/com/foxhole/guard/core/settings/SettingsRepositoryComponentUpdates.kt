package com.foxhole.guard.core.settings

import com.foxhole.core.model.UpdateSourceSettings
import com.foxhole.guard.runtime.foxholeDbManifestUrl

// Component updates: the "check for updates" switch, the ONE auto-update switch of the
// "Component updates" screen and the update stamps behind it. Extracted from SettingsRepository
// (class split by domain).

/**
 * The two update channels, written together because the sheet that edits them confirms once.
 *
 * The stored values are whatever [com.foxhole.guard.core.settings.normalized] makes of them, so a
 * URL this app cannot read never reaches disk — the caller may hand over exactly what the user
 * typed. Also drops the DNS filter feed back to its own default when the repository moves: that
 * feed carries its own URL, and leaving a manually-set one behind would silently keep one data set
 * on the old mirror.
 */
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

// The ONE auto-update switch of the "Component updates" screen: it mirrors into every
// per-component flag so the workers' own re-checks (and the legacy scheduling paths) agree.
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

// After the "delete downloaded databases" action: the on-disk overrides are gone, so the
// update stamps go back to the never-downloaded state the fresh-install UI shows.
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
