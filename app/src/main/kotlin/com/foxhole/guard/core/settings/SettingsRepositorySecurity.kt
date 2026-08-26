package com.foxhole.guard.core.settings

import com.foxhole.core.model.AppLockMode
import com.foxhole.core.model.AppLockTimeout
import com.foxhole.core.model.GuardHostingMode

suspend fun SettingsRepository.updateAppLockMode(value: AppLockMode) =
    update { it.copy(appLock = it.appLock.copy(mode = value)) }

suspend fun SettingsRepository.updateAppLockTimeout(value: AppLockTimeout) =
    update { it.copy(appLock = it.appLock.copy(lockTimeout = value)) }

suspend fun SettingsRepository.updateGuardHosting(value: GuardHostingMode) =
    update { it.copy(appLock = it.appLock.copy(guardHosting = value)) }

suspend fun SettingsRepository.markAppLockPasswordSet(timestamp: Long? = System.currentTimeMillis()) =
    update { it.copy(appLock = it.appLock.copy(passwordSetAt = timestamp)) }

suspend fun SettingsRepository.updateAppLockBiometric(enabled: Boolean) =
    update { it.copy(appLock = it.appLock.copy(biometricEnabled = enabled)) }

suspend fun SettingsRepository.updateAppLockAuthAttemptNotice(enabled: Boolean) =
    update { it.copy(appLock = it.appLock.copy(authAttemptNoticeEnabled = enabled)) }

suspend fun SettingsRepository.updateAppLockBuiltInPinPad(enabled: Boolean) =
    update { it.copy(appLock = it.appLock.copy(builtInPinPadEnabled = enabled)) }

suspend fun SettingsRepository.updateAppLockScrambleDigits(enabled: Boolean) =
    update { it.copy(appLock = it.appLock.copy(scrambleKeypadDigits = enabled)) }

suspend fun SettingsRepository.updateAppLockConfirmSensitiveActions(enabled: Boolean) =
    update { it.copy(appLock = it.appLock.copy(confirmSensitiveActions = enabled)) }

suspend fun SettingsRepository.updateAppLockEventMonitoring(enabled: Boolean) =
    update { it.copy(appLock = it.appLock.copy(eventMonitoringEnabled = enabled)) }
