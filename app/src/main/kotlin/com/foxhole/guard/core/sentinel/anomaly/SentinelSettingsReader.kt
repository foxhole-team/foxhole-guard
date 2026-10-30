package com.foxhole.guard.core.sentinel.anomaly

import com.foxhole.core.model.Settings
import com.foxhole.guard.core.settings.SettingsRepository
import kotlinx.coroutines.flow.StateFlow

internal interface SentinelSettingsReader {
    val settings: StateFlow<Settings>
    suspend fun current(): Settings
}

internal fun SettingsRepository.asSentinelSettingsReader(): SentinelSettingsReader =
    object : SentinelSettingsReader {
        override val settings: StateFlow<Settings> get() = this@asSentinelSettingsReader.settings
        override suspend fun current(): Settings = this@asSentinelSettingsReader.current()
    }
