package com.foxhole.guard.ui

import androidx.biometric.BiometricManager
import androidx.lifecycle.viewModelScope
import com.foxhole.core.model.AppLockMode
import com.foxhole.core.model.AppLockTimeout
import com.foxhole.core.model.GuardHostingMode
import com.foxhole.guard.R
import com.foxhole.guard.core.security.PasswordSetupResult
import com.foxhole.guard.core.security.zeroize
import com.foxhole.guard.core.settings.readFastStoredAppLockBuiltInPad
import com.foxhole.guard.core.settings.readFastStoredAppLockScramble
import com.foxhole.guard.core.settings.updateAppLockAuthAttemptNotice
import com.foxhole.guard.core.settings.updateAppLockBiometric
import com.foxhole.guard.core.settings.updateAppLockBuiltInPinPad
import com.foxhole.guard.core.settings.updateAppLockConfirmSensitiveActions
import com.foxhole.guard.core.settings.updateAppLockEventMonitoring
import com.foxhole.guard.core.settings.updateAppLockMode
import com.foxhole.guard.core.settings.updateAppLockScrambleDigits
import com.foxhole.guard.core.settings.updateAppLockTimeout
import com.foxhole.guard.core.settings.updateGuardHosting
import com.foxhole.guard.core.settings.updateInstalledAppMonitoringEnabled
import com.foxhole.guard.guardian.GuardEvent
import com.foxhole.guard.guardian.GuardEventType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.crypto.Cipher

fun HomeViewModel.onAppLockModeSelected(mode: AppLockMode) {
    if (mode == AppLockMode.PASSWORD) {
        return
    }
    viewModelScope.launch {
        container.settingsRepository.updateAppLockMode(mode)
        appLockManager.refreshFromSettings()
    }
}

fun HomeViewModel.onAppLockTimeoutSelected(timeout: AppLockTimeout) {
    viewModelScope.launch {
        val previous = container.settingsRepository.settings.value.appLock.lockTimeout
        container.settingsRepository.updateAppLockTimeout(timeout)
        journalSecuritySettingChange("lockTimeout", previous.name, timeout.name)
    }
}

fun HomeViewModel.onGuardHostingSelected(mode: GuardHostingMode) {
    runConfirmedAction {
        viewModelScope.launch {
            val previous = container.settingsRepository.settings.value.appLock.guardHosting
            container.settingsRepository.updateGuardHosting(mode)
            journalSecuritySettingChange("guardHosting", previous.name, mode.name)
            syncGuardMonitoringLifecycle()
        }
    }
}

suspend fun HomeViewModel.enableAppLockPassword(
    password: CharArray,
    eventMonitoring: Boolean,
    appJournal: Boolean,
): PasswordSetupResult {
    val result = securityComponents.appLockSetupCoordinator.enablePasswordProtection(password)
    if (result is PasswordSetupResult.Success) {
        applyProtectionCompanions(eventMonitoring, appJournal)
        appLockManager.refreshFromSettings()
    }
    return result
}

private suspend fun HomeViewModel.applyProtectionCompanions(
    eventMonitoring: Boolean,
    appJournal: Boolean,
) {
    val sentinelEnabled = container.settingsRepository.current().anomaly.enabled
    container.settingsRepository.updateAppLockEventMonitoring(eventMonitoring && sentinelEnabled)
    if (appJournal && sentinelEnabled) {
        container.settingsRepository.updateInstalledAppMonitoringEnabled(true)
    }
    syncGuardMonitoringLifecycle()
}

fun HomeViewModel.onSystemLockEnabled(
    eventMonitoring: Boolean,
    appJournal: Boolean,
) {
    viewModelScope.launch {
        container.settingsRepository.updateAppLockMode(AppLockMode.SYSTEM)
        applyProtectionCompanions(eventMonitoring, appJournal)
        appLockManager.refreshFromSettings()
    }
}

fun HomeViewModel.onEventMonitoringChanged(enabled: Boolean) {
    if (enabled && !container.settingsRepository.settings.value.anomaly.enabled) {
        return
    }
    val apply: () -> Unit = {
        viewModelScope.launch {
            if (enabled && !securityComponents.isEventMonitoringActive()) {
                val baselineReady =
                    try {
                        securityComponents.guardSentinel.refreshInventoryBaselineForActivation()
                        true
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        container.diagnosticsLogger.recordFailure(
                            "app-inventory",
                            "event monitoring activation baseline failed error=${error.javaClass.simpleName}",
                        )
                        false
                    }
                if (!baselineReady) {
                    snackbars.tryEmit(errorBanner(R.string.settings_secure_storage_failed))
                    return@launch
                }
            }
            container.settingsRepository.updateAppLockEventMonitoring(enabled)
            journalSecuritySettingChange("eventMonitoring", (!enabled).toString(), enabled.toString())
            syncGuardMonitoringLifecycle()
        }
    }
    if (enabled) apply() else runConfirmedAction(apply)
}

suspend fun HomeViewModel.disableAppLockWithBiometrics(cipher: Cipher): PasswordSetupResult {
    val masterKey =
        securityComponents.biometricGate.unsealMasterKeyOrNull(cipher)
            ?: return PasswordSetupResult.Failed
    val result = securityComponents.appLockSetupCoordinator.disablePasswordProtectionWithMasterKey(masterKey)
    if (result is PasswordSetupResult.Success) {
        appLockManager.refreshFromSettings()
        syncGuardMonitoringLifecycle()
    }
    return result
}

suspend fun HomeViewModel.disableAppLockPassword(password: CharArray): PasswordSetupResult {
    val result = securityComponents.appLockSetupCoordinator.disablePasswordProtection(password)
    if (result is PasswordSetupResult.Success) {
        appLockManager.refreshFromSettings()
        syncGuardMonitoringLifecycle()
    }
    return result
}

suspend fun HomeViewModel.changeAppLockPassword(
    current: CharArray,
    next: CharArray,
): PasswordSetupResult = securityComponents.appLockSetupCoordinator.changePassword(current, next)

suspend fun HomeViewModel.loadGuardJournalReport(): com.foxhole.guard.guardian.GuardJournalReport? =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        securityComponents.verifyGuardJournal()
    }

fun HomeViewModel.systemAuthAvailable(): Boolean {
    val authenticators =
        BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
    return BiometricManager.from(getApplication()).canAuthenticate(authenticators) == BiometricManager.BIOMETRIC_SUCCESS
}

fun HomeViewModel.biometricStrongAvailable(): Boolean =
    BiometricManager
        .from(getApplication())
        .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

fun HomeViewModel.legacyPasswordCredential(): Boolean = securityComponents.legacyPasswordCredential()

fun HomeViewModel.onAppLockAuthNoticeChanged(enabled: Boolean) {
    val apply: () -> Unit = {
        viewModelScope.launch {
            container.settingsRepository.updateAppLockAuthAttemptNotice(enabled)
            journalSecuritySettingChange("authAttemptNotice", (!enabled).toString(), enabled.toString())
        }
    }
    if (enabled) apply() else runConfirmedAction(apply)
}

fun HomeViewModel.onSystemBiometricToggled(enabled: Boolean) {
    viewModelScope.launch {
        container.settingsRepository.updateAppLockBiometric(enabled)
        journalSecuritySettingChange("biometric", (!enabled).toString(), enabled.toString())
    }
}

fun HomeViewModel.biometricEncryptCipher(): Cipher? = securityComponents.biometricGate.encryptCipherOrNull()

suspend fun HomeViewModel.completeBiometricEnrolment(cipher: Cipher): Boolean {
    val masterKey = appLockManager.copyMasterKeyOrNull() ?: return false
    val stored =
        try {
            securityComponents.biometricGate.store(masterKey, cipher)
        } finally {
            masterKey.zeroize()
        }
    if (stored) {
        container.settingsRepository.updateAppLockBiometric(true)
        journalSecuritySettingChange("biometric", "false", "true")
    }
    return stored
}

fun HomeViewModel.disableBiometricUnlock() {
    securityComponents.biometricGate.clear()
    viewModelScope.launch {
        container.settingsRepository.updateAppLockBiometric(false)
        journalSecuritySettingChange("biometric", "true", "false")
    }
}

fun HomeViewModel.biometricUnlockAvailable(): Boolean = securityComponents.biometricGate.isEnrolled()

fun HomeViewModel.systemBiometricAllowed(): Boolean =
    container.settingsRepository.settings.value.appLock.biometricEnabled

fun HomeViewModel.builtInPinPadEnabled(): Boolean =
    if (container.settingsRepository.hydrated.value) {
        container.settingsRepository.settings.value.appLock.builtInPinPadEnabled
    } else {
        readFastStoredAppLockBuiltInPad(getApplication())
    }

fun HomeViewModel.scrambleKeypadDigits(): Boolean =
    if (container.settingsRepository.hydrated.value) {
        container.settingsRepository.settings.value.appLock.scrambleKeypadDigits
    } else {
        readFastStoredAppLockScramble(getApplication())
    }

fun HomeViewModel.onBuiltInPinPadChanged(enabled: Boolean) {
    viewModelScope.launch {
        container.settingsRepository.updateAppLockBuiltInPinPad(enabled)
        journalSecuritySettingChange("builtInPinPad", (!enabled).toString(), enabled.toString())
    }
}

fun HomeViewModel.onScrambleDigitsChanged(enabled: Boolean) {
    viewModelScope.launch {
        container.settingsRepository.updateAppLockScrambleDigits(enabled)
        journalSecuritySettingChange("scrambleDigits", (!enabled).toString(), enabled.toString())
    }
}

fun HomeViewModel.biometricDecryptCipher(): Cipher? = securityComponents.biometricGate.decryptCipherOrNull()

suspend fun HomeViewModel.unlockWithBiometricCipher(cipher: Cipher): Boolean =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
        val masterKey = securityComponents.biometricGate.unsealMasterKeyOrNull(cipher)
        masterKey != null && appLockManager.unlockWithBiometricMasterKey(masterKey)
    }

fun HomeViewModel.recordPromptAuthFailure() = appLockManager.recordPromptAuthFailure()

fun HomeViewModel.onConfirmSensitiveActionsChanged(enabled: Boolean) {
    val apply: () -> Unit = {
        viewModelScope.launch {
            container.settingsRepository.updateAppLockConfirmSensitiveActions(enabled)
            journalSecuritySettingChange("confirmActions", (!enabled).toString(), enabled.toString())
        }
    }

    if (enabled) apply() else runConfirmedAction(apply)
}

internal fun HomeViewModel.sensitiveActionGateArmed(): Boolean {
    val appLock = container.settingsRepository.settings.value.appLock
    return appLock.confirmSensitiveActions && appLock.mode != AppLockMode.OFF
}

fun HomeViewModel.actionAuthUsesPin(): Boolean =
    container.settingsRepository.settings.value.appLock.mode == AppLockMode.PASSWORD

fun HomeViewModel.actionAuthBiometricAvailable(): Boolean =
    container.settingsRepository.settings.value.appLock.biometricEnabled && biometricStrongAvailable()

internal fun HomeViewModel.runConfirmedAction(action: () -> Unit) {
    if (!sensitiveActionGateArmed()) {
        action()
        return
    }
    pendingSensitiveAction = action
    actionAuthVisibleMutable.value = true
}

suspend fun HomeViewModel.verifyActionPin(pin: CharArray): Boolean {
    val pinBytes = encodeCharsToUtf8Bytes(pin)
    val verified =
        try {
            withContext(Dispatchers.Default) { securityComponents.verifyKeyboxPassword(pinBytes) }
        } finally {
            pinBytes.zeroize()
        }
    if (verified) {
        completeSensitiveAction()
    }
    return verified
}

fun HomeViewModel.confirmActionByPrompt() = completeSensitiveAction()

fun HomeViewModel.cancelActionAuth() {
    pendingSensitiveAction = null
    actionAuthVisibleMutable.value = false
}

private fun HomeViewModel.completeSensitiveAction() {
    val action = pendingSensitiveAction
    pendingSensitiveAction = null
    actionAuthVisibleMutable.value = false
    action?.invoke()
}

private fun encodeCharsToUtf8Bytes(chars: CharArray): ByteArray {
    val buffer = java.nio.charset.StandardCharsets.UTF_8.encode(java.nio.CharBuffer.wrap(chars))
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    if (buffer.hasArray()) {
        java.util.Arrays.fill(buffer.array(), 0)
    }
    return bytes
}

private fun HomeViewModel.journalSecuritySettingChange(
    field: String,
    from: String,
    to: String,
) {
    if (!securityComponents.isPasswordProtectionActive()) {
        return
    }
    securityComponents.journalEvent(
        GuardEvent(
            type = GuardEventType.SECURITY_SETTING_CHANGED,
            detail = "$field:$from->$to",
        ),
    )
}
