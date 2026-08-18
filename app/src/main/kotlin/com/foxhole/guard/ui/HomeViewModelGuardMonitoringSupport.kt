package com.foxhole.guard.ui

import android.app.Application
import com.foxhole.core.model.GuardHostingMode
import com.foxhole.guard.applyGuardHeartbeatSchedule
import com.foxhole.guard.guardian.FoxholeGuardService

internal fun HomeViewModel.syncGuardMonitoringLifecycle() {
    val app = getApplication<Application>()
    val enabled = securityComponents.isEventMonitoringActive()
    app.applyGuardHeartbeatSchedule(enabled = enabled)
    securityComponents.guardSentinel.reconcileActivation()
    val needsDedicatedHost =
        enabled &&
            securityComponents.guardHostingMode() == GuardHostingMode.REINFORCED &&
            !securityComponents.guardSentinel.hasAttachedHosts()
    if (needsDedicatedHost) {
        FoxholeGuardService.start(app)
    } else {
        FoxholeGuardService.stop(app)
    }
}
