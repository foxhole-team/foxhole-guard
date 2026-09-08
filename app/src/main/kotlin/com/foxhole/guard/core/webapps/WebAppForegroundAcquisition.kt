package com.foxhole.guard.core.webapps

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal suspend fun publishWebAppAcquisition(
    isCurrent: () -> Boolean,
    acquire: suspend () -> WebAppProxyActivation,
    release: suspend (WebAppProxyLease) -> Unit,
    publish: (WebAppProxyActivation) -> Unit,
): Boolean {
    var acquired: WebAppProxyActivation? = null
    var published = false
    try {
        // Capture ownership before returning to a cancelled caller's dispatcher.
        withContext(NonCancellable) { acquired = acquire() }
        currentCoroutineContext().ensureActive()
        val activation = acquired ?: return false
        if (!isCurrent() || !activation.applied || activation.lease?.active != true) return false
        publish(activation)
        published = true
        return true
    } finally {
        if (!published) {
            acquired?.lease?.let { lease -> withContext(NonCancellable) { release(lease) } }
        }
    }
}
