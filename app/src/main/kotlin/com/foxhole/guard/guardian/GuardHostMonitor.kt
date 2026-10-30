package com.foxhole.guard.guardian

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal class GuardHostMonitor(
    private val scope: CoroutineScope,
    private val monitoringActive: () -> Boolean,
    private val onHostStarted: (String) -> Boolean,
    private val onHostStopped: (String, Boolean) -> Unit,
    private val onHeartbeat: () -> Unit,
    private val heartbeatIntervalMs: Long,
    private val onInvalidated: () -> Unit = {},
) {
    private val hosts = mutableSetOf<String>()
    private val announcedHosts = mutableSetOf<String>()
    private var heartbeatJob: Job? = null
    private var monitoringWasActive = false

    @Synchronized
    fun attach(host: String) {
        hosts.add(host)
        reconcileLocked()
    }

    @Synchronized
    fun detach(
        host: String,
        destroyed: Boolean,
    ) {
        if (!hosts.remove(host)) {
            return
        }
        val wasAnnounced = announcedHosts.remove(host)
        if (wasAnnounced && monitoringActive()) {
            onHostStopped(host, destroyed)
        }
        reconcileLocked()
    }

    @Synchronized
    fun hasHosts(): Boolean = hosts.isNotEmpty()

    @Synchronized
    fun hasHostOtherThan(host: String): Boolean = hosts.any { candidate -> candidate != host }

    @Synchronized
    fun reconcile(): Boolean {
        reconcileLocked()
        return heartbeatJob?.isActive == true
    }

    @Synchronized
    fun clear() {
        onInvalidated()
        hosts.clear()
        announcedHosts.clear()
        monitoringWasActive = false
        stopHeartbeatLocked()
    }

    private fun reconcileLocked() {
        val active = monitoringActive()
        if (active) {
            hosts
                .filterNot(announcedHosts::contains)
                .forEach { host ->
                    if (onHostStarted(host)) announcedHosts += host
                }
        } else {
            if (monitoringWasActive) onInvalidated()
            announcedHosts.clear()
        }
        monitoringWasActive = active
        if (hosts.isNotEmpty() && active) {
            startHeartbeatLocked()
        } else {
            stopHeartbeatLocked()
        }
    }

    private fun startHeartbeatLocked() {
        if (heartbeatJob?.isActive == true) {
            return
        }
        val job =
            scope.launch(start = CoroutineStart.LAZY) {
                while (currentCoroutineContext().isActive && monitoringActive()) {
                    onHeartbeat()
                    delay(heartbeatIntervalMs)
                }
            }
        heartbeatJob = job
        job.invokeOnCompletion {
            synchronized(this) {
                if (heartbeatJob === job) {
                    heartbeatJob = null
                }
            }
        }
        job.start()
    }

    private fun stopHeartbeatLocked() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }
}
