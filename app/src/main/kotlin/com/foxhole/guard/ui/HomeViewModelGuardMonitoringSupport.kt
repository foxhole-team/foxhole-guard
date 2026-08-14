package com.foxhole.guard.ui

import android.app.Application
import com.foxhole.core.model.GuardHostingMode
import com.foxhole.guard.applyGuardHeartbeatSchedule
import com.foxhole.guard.guardian.FoxholeGuardService

/**
 * Reconciles all three owners of guard monitoring after a settings/credential transition:
 * WorkManager fallback, process-local heartbeat and the optional reinforced foreground host.
 * Repeated calls are safe and do not restart an already attached host.
 */
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
