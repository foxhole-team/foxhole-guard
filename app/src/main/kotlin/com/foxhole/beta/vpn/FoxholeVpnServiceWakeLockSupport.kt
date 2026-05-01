package com.foxhole.beta.vpn

import java.util.WeakHashMap

private val vpnRuntimeWakeLocks = WeakHashMap<FoxholeVpnService, RuntimeWakeLock>()

internal fun FoxholeVpnService.acquireRuntimeWakeLock() {
    runtimeWakeLock().acquire()
}

internal fun FoxholeVpnService.releaseRuntimeWakeLock() {
    runtimeWakeLock().release()
}

private fun FoxholeVpnService.runtimeWakeLock(): RuntimeWakeLock =
    synchronized(vpnRuntimeWakeLocks) {
        vpnRuntimeWakeLocks.getOrPut(this) {
            RuntimeWakeLock(
                context = applicationContext,
                diagnosticsLogger = container.diagnosticsLogger,
                tag = "Foxhole:VpnRuntime",
            )
        }
    }
