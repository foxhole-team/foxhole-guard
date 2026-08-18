package com.foxhole.guard.ui

import kotlinx.coroutines.flow.MutableStateFlow

internal class HomeImportFlowState {
    val profileImportInProgressMutable = MutableStateFlow(false)
    val insecureTlsImportWarningMutable = MutableStateFlow<InsecureTlsImportWarningState?>(null)
    val profileImportConfirmationMutable = MutableStateFlow<ProfileImportConfirmationState?>(null)
}
