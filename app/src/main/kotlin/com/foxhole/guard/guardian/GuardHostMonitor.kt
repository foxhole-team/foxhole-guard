package com.foxhole.guard.guardian

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Single owner for guard-daemon hosts and its process-local heartbeat.
 *
 * Hosts are remembered even while monitoring is disabled. This matters when the user enables the
 * guard while an already-running VPN service is hosting it: the heartbeat can start immediately,
 * without restarting the tunnel. Every mutation is synchronized because service callbacks,
 * WorkManager and settings actions can arrive on different threads.
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

    /** Re-evaluates the setting after enable/disable or a hosting-mode change. */
    @Synchronized
    fun reconcile(): Boolean {
        reconcileLocked()
        return heartbeatJob?.isActive == true
    }

    /** Factory-reset hook: no stale host or coroutine may survive deleted guard state. */
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
