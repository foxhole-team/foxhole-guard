package com.foxhole.guard.ui

import android.os.SystemClock

internal fun HomeViewModel.confirmProfileImport() {
    val pending = profileImportConfirmationMutable.value ?: return
    if (!pending.canConfirm) return
    profileImportConfirmationMutable.value = null
    if (pending.insecureTls) {
        importRawWithTlsConsentInternal(pending.rawInput, excludeInsecureTlsOptions = false)
    } else {
        importRaw(pending.rawInput)
    }
}

internal fun HomeViewModel.confirmProfileImportExcludingInsecureTls() {
    val pending = profileImportConfirmationMutable.value ?: return
    if (!pending.canConfirm) return
    profileImportConfirmationMutable.value = null
    importRawWithTlsConsentInternal(pending.rawInput, excludeInsecureTlsOptions = true)
}

internal fun HomeViewModel.dismissProfileImportConfirmation() {
    profileImportConfirmationMutable.value = null
}

internal fun HomeViewModel.confirmProfileImportDuplicateUpdate() {
    val pending = profileImportConfirmationMutable.value ?: return
    if (!pending.canConfirm) return
    profileImportConfirmationMutable.value = null
    updateDuplicateProfileFromImportInternal(pending)
}

private var lastConnectionControlAtMs = 0L

internal fun HomeViewModel.isConnectionControlThrottled(): Boolean {
    val now = SystemClock.elapsedRealtime()
    if (now - lastConnectionControlAtMs < HomeViewModel.CONNECTION_CONTROL_DEBOUNCE_MS) {
        container.diagnosticsLogger.record("runtime", "ignored rapid connection control tap")
        return true
    }
    lastConnectionControlAtMs = now
    return false
}
