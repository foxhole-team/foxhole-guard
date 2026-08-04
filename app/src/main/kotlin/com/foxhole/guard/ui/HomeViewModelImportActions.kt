package com.foxhole.guard.ui

import android.os.SystemClock

// The import-confirmation gate of the home view-model (every import parks here first) and the
// connection-control throttle. Extensions on the class — split from HomeViewModel.kt.

internal fun HomeViewModel.confirmProfileImport() {
    val pending = profileImportConfirmationMutable.value ?: return
    profileImportConfirmationMutable.value = null
    if (pending.insecureTls) {
        // The sheet already carried the TLS warning — Да is the consent, no second dialog.
        importRawWithTlsConsentInternal(pending.rawInput, excludeInsecureTlsOptions = false)
    } else {
        importRaw(pending.rawInput)
    }
}

internal fun HomeViewModel.confirmProfileImportExcludingInsecureTls() {
    val pending = profileImportConfirmationMutable.value ?: return
    profileImportConfirmationMutable.value = null
    importRawWithTlsConsentInternal(pending.rawInput, excludeInsecureTlsOptions = true)
}

internal fun HomeViewModel.dismissProfileImportConfirmation() {
    profileImportConfirmationMutable.value = null
}

/** Refresh on the duplicate prompt: update the already-stored profile, import nothing. */
internal fun HomeViewModel.confirmProfileImportDuplicateUpdate() {
    val pending = profileImportConfirmationMutable.value ?: return
    profileImportConfirmationMutable.value = null
    updateDuplicateProfileFromImportInternal(pending)
}

// Rapid stop/start/restart taps used to stack overlapping runtime operations and could kill the
// VPN service mid-handoff. A short shared cooldown collapses a burst of taps into one action so
// the runtime gets a beat to settle between commands ("stop, wait, start").
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
