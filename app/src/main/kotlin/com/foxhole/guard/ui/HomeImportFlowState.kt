package com.foxhole.guard.ui

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Profile-import flow state: the in-progress flag, the insecure-TLS warning and the add-profile
 * confirmation that parks every import until the user accepts. HomeViewModel exposes same-named
 * aliases so the Support-file call sites read unchanged.
 */
internal class HomeImportFlowState {
    val profileImportInProgressMutable = MutableStateFlow(false)
    val insecureTlsImportWarningMutable = MutableStateFlow<InsecureTlsImportWarningState?>(null)
    val profileImportConfirmationMutable = MutableStateFlow<ProfileImportConfirmationState?>(null)
}
