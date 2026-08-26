package com.foxhole.guard.runtime

import android.app.Service
import com.foxhole.core.model.GuardHostingMode
import com.foxhole.guard.FoxholeApplication
import com.foxhole.guard.guardian.FoxholeGuardService
import com.foxhole.guard.guardian.GuardSentinel

internal fun Service.guardDaemonOrNull(): GuardSentinel? =
    (applicationContext as? FoxholeApplication)?.appGraph?.securityComponents?.guardSentinel

internal fun Service.attachGuardDaemon(host: String) {
    val security = (applicationContext as? FoxholeApplication)?.appGraph?.securityComponents ?: return

    security.guardSentinel.attach(host)

    FoxholeGuardService.stop(this)
}

internal fun Service.detachGuardDaemon(host: String) {
    val security = (applicationContext as? FoxholeApplication)?.appGraph?.securityComponents ?: return
    security.guardSentinel.detach(host, destroyed = true)

    if (
        security.isEventMonitoringActive() &&
        security.guardHostingMode() == GuardHostingMode.REINFORCED &&
        !security.guardSentinel.hasAttachedHosts()
    ) {
        FoxholeGuardService.start(this)
    }
}
