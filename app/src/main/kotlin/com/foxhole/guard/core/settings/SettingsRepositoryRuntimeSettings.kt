package com.foxhole.guard.core.settings

import com.foxhole.core.model.Settings
import com.foxhole.core.runtime.RuntimeSettings

/**
 * App-side adapter exposing [SettingsRepository] to the runtime engine through the narrow
 * [RuntimeSettings] contract. Lives in `:app` because it knows the concrete repository; the runtime
 * only sees the interface.
 */
class SettingsRepositoryRuntimeSettings(
    private val settingsRepository: SettingsRepository,
) : RuntimeSettings {
    override suspend fun current(): Settings = settingsRepository.current()

    override suspend fun markDnsFiltersUpdated(timestamp: Long) =
        settingsRepository.markDnsFiltersUpdated(timestamp)

    override suspend fun markDnsFiltersChecked(timestamp: Long) =
        settingsRepository.markDnsFiltersChecked(timestamp)
}
