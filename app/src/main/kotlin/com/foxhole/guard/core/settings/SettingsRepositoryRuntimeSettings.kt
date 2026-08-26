package com.foxhole.guard.core.settings

import com.foxhole.core.model.Settings
import com.foxhole.core.runtime.RuntimeSettings

class SettingsRepositoryRuntimeSettings(
    private val settingsRepository: SettingsRepository,
) : RuntimeSettings {
    override suspend fun current(): Settings = settingsRepository.current()

    override suspend fun markDnsFiltersUpdated(timestamp: Long) =
        settingsRepository.markDnsFiltersUpdated(timestamp)

    override suspend fun markDnsFiltersChecked(timestamp: Long) =
        settingsRepository.markDnsFiltersChecked(timestamp)
}
