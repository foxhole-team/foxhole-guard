package com.foxhole.guard.guardian

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Hosts stay remembered while monitoring is disabled, so enabling the guard under an already-running VPN service starts the heartbeat without restarting the tunnel.
 * Every mutation is synchronized: service callbacks, WorkManager and settings actions arrive on different threads.
 */
internal class GuardHostMonitor(
    private val scope: CoroutineScope,
    private val monitoringActive: () -> Boolean,
    private val onHostStarted: (String) -> Unit,
    private val onHostStopped: (String, Boolean) -> Unit,
    private val onHeartbeat: () -> Unit,
    private val heartbeatIntervalMs: Long,
) {
    private val hosts = mutableSetOf<String>()
    private val announcedHosts = mutableSetOf<String>()
    private var heartbeatJob: Job? = null

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
        hosts.clear()
        announcedHosts.clear()
        stopHeartbeatLocked()
    }

    private fun reconcileLocked() {
        val active = monitoringActive()
        if (active) {
            hosts
                .filterNot(announcedHosts::contains)
                .forEach { host ->
                    onHostStarted(host)
                    announcedHosts += host
                }
        } else {
            announcedHosts.clear()
        }
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
