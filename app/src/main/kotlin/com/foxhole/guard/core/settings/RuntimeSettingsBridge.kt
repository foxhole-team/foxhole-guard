package com.foxhole.guard.core.settings

import com.foxhole.core.runtime.RuntimeSettings

fun SettingsRepository.asRuntimeSettings(): RuntimeSettings = SettingsRepositoryRuntimeSettings(this)
