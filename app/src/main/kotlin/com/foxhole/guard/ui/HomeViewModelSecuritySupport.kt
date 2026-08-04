package com.foxhole.guard.ui

import androidx.biometric.BiometricManager
import androidx.lifecycle.viewModelScope
import com.foxhole.core.model.AppLockMode
import com.foxhole.core.model.AppLockTimeout
import com.foxhole.core.model.GuardHostingMode
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.crypto.Cipher

// App-lock settings actions. Enable/disable go through the setup coordinator (key
// migration + Argon2 off-thread); mode changes away from PASSWORD are only reachable
// through the disable flow, which requires the password.

fun HomeViewModel.onAppLockModeSelected(mode: AppLockMode) {
    // Only OFF <-> SYSTEM is a plain settings write. PASSWORD is entered via the enable
    // flow and left via the disable flow; the UI never calls this with PASSWORD.
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

/**
 * Enabling protection also offers two companions, both ticked by default in the modal:
 * the event-monitoring service and the app-install journal. Whatever the user leaves
 * ticked is what gets switched on — a PIN with both cleared guards entry and nothing else.
 */
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
    container.settingsRepository.updateAppLockEventMonitoring(eventMonitoring)
    if (appJournal) {
        container.settingsRepository.updateInstalledAppMonitoringEnabled(true)
    }
    syncGuardMonitoringLifecycle()
}

/** The device-code gate runs the same companion opt-ins as the PIN. */
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
    val apply: () -> Unit = {
        viewModelScope.launch {
            container.settingsRepository.updateAppLockEventMonitoring(enabled)
            journalSecuritySettingChange("eventMonitoring", (!enabled).toString(), enabled.toString())
            syncGuardMonitoringLifecycle()
        }
    }
    // Switching monitoring OFF blinds the app: gate it like the other weakening moves.
    if (enabled) apply() else runConfirmedAction(apply)
}

/** Disables the PIN after the biometric prompt unsealed the master key. */
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
    // Silencing the entry-attempt report hides an intruder's tracks: gate the OFF way.
    if (enabled) apply() else runConfirmedAction(apply)
}

/** SYSTEM mode: biometrics only widen the device-credential prompt — a plain settings write. */
fun HomeViewModel.onSystemBiometricToggled(enabled: Boolean) {
    viewModelScope.launch {
        container.settingsRepository.updateAppLockBiometric(enabled)
        journalSecuritySettingChange("biometric", (!enabled).toString(), enabled.toString())
    }
}

fun HomeViewModel.biometricEncryptCipher(): Cipher? = securityComponents.biometricGate.encryptCipherOrNull()

/**
 * Finishes PIN-mode biometric enrolment after the prompt authorized the cipher:
 * seals a master-key copy into keybox.bio and only then flips the setting on.
 */
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

// Unlock-screen plumbing: the bio blob on disk is the pre-hydration source of truth
// for "the shortcut exists" (the settings toggle always clears it when turned off).

fun HomeViewModel.biometricUnlockAvailable(): Boolean = securityComponents.biometricGate.isEnrolled()

/** SYSTEM gate: whether the device-credential prompt may also offer biometrics. */
fun HomeViewModel.systemBiometricAllowed(): Boolean =
    container.settingsRepository.settings.value.appLock.biometricEnabled

// The unlock screen composes before the settings hydrate: its style flags read the
// fast mirror until then.

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

// Before anything that weakens protection or erases evidence (journal clears,
// firewall off, app-audit off, guard daemon change) the
// user re-proves identity with the PIN or biometrics. Off, or with no lock at all,
// the action simply runs.

fun HomeViewModel.onConfirmSensitiveActionsChanged(enabled: Boolean) {
    val apply: () -> Unit = {
        viewModelScope.launch {
            container.settingsRepository.updateAppLockConfirmSensitiveActions(enabled)
            journalSecuritySettingChange("confirmActions", (!enabled).toString(), enabled.toString())
        }
    }
    // Turning the gate itself off must pass through the gate one last time.
    if (enabled) apply() else runConfirmedAction(apply)
}

/** True when the gate is armed: the toggle is on AND some lock actually protects the app. */
internal fun HomeViewModel.sensitiveActionGateArmed(): Boolean {
    val appLock = container.settingsRepository.settings.value.appLock
    return appLock.confirmSensitiveActions && appLock.mode != AppLockMode.OFF
}

/** The gate's shape for the dialog: PIN field, biometric button, or the device prompt. */
fun HomeViewModel.actionAuthUsesPin(): Boolean =
    container.settingsRepository.settings.value.appLock.mode == AppLockMode.PASSWORD

fun HomeViewModel.actionAuthBiometricAvailable(): Boolean =
    container.settingsRepository.settings.value.appLock.biometricEnabled && biometricStrongAvailable()

/**
 * Runs [action] behind the gate. The pending action is held in the ViewModel (not in
 * the composition) so a rotation mid-prompt cannot lose it.
 */
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

/** The biometric / device-credential prompt already proved presence. */
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
