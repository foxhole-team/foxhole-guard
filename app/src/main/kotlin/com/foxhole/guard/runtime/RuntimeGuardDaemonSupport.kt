package com.foxhole.guard.runtime

import android.app.Service
import com.foxhole.core.model.GuardHostingMode
import com.foxhole.guard.FoxholeApplication
import com.foxhole.guard.guardian.FoxholeGuardService
import com.foxhole.guard.guardian.GuardSentinel

// Runtime services host the guard daemon in ECONOMY mode: attaching while running keeps
// the sealed install journal live at zero extra cost, and stops the dedicated REINFORCED
// service (which only exists to cover the gaps when no runtime is up).

internal fun Service.guardDaemonOrNull(): GuardSentinel? =
    (applicationContext as? FoxholeApplication)?.appGraph?.securityComponents?.guardSentinel

internal fun Service.attachGuardDaemon(host: String) {
    val security = (applicationContext as? FoxholeApplication)?.appGraph?.securityComponents ?: return
    // Register the live runtime even while monitoring is off. If the user enables ECONOMY mode
    // without restarting this tunnel, GuardHostMonitor can start immediately from the remembered
    // host instead of waiting for a reconnect.
    security.guardSentinel.attach(host)
    // A runtime host makes the standalone guard service redundant.
    FoxholeGuardService.stop(this)
}

internal fun Service.detachGuardDaemon(host: String) {
    val security = (applicationContext as? FoxholeApplication)?.appGraph?.securityComponents ?: return
    security.guardSentinel.detach(host, destroyed = true)
    // REINFORCED handoff: keep the daemon resident through a dedicated lightweight service
    // whenever the runtime goes down. Best effort - the 15-min worker retries the start.
    if (
        security.isEventMonitoringActive() &&
        security.guardHostingMode() == GuardHostingMode.REINFORCED &&
        !security.guardSentinel.hasAttachedHosts()
    ) {
        FoxholeGuardService.start(this)
    }
}
