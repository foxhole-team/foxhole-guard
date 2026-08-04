package com.foxhole.guard.runtime

internal fun FoxholeVpnService.acquireRuntimeWakeLock() {
    runtimeWakeLock.acquire()
}

internal fun FoxholeVpnService.releaseRuntimeWakeLock() {
    runtimeWakeLock.release()
}
