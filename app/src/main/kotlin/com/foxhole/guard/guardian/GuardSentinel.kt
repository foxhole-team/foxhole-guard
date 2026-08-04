package com.foxhole.guard.guardian

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The install-monitoring daemon. It is a single in-process component (no extra process)
 * that is refcount-attached by whatever is currently keeping the app resident: the VPN
 * / proxy service in ECONOMY, or the dedicated guard service in REINFORCED. While
 * attached it listens for package changes and emits a heartbeat; the periodic worker
 * drives reconciliation and blackout detection when nothing is attached.
 */
class GuardSentinel internal constructor(
    context: Context,
    private val clock: GuardClock,
    private val inventoryStore: GuardInventorySnapshotStore,
    private val journal: (GuardEvent) -> Boolean,
    private val isActive: () -> Boolean,
    private val lastJournalRecordAt: () -> Long?,
) {
    private val appContext = context.applicationContext
    private val inspector = GuardPackageInspector(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val hostMonitor =
        GuardHostMonitor(
            scope = scope,
            monitoringActive = isActive,
            onHostStarted = { host ->
                journal(GuardEvent(type = GuardEventType.SERVICE_STARTED, service = host))
            },
            onHostStopped = { host, destroyed ->
                journal(
                    GuardEvent(
                        type = if (destroyed) GuardEventType.SERVICE_DESTROYED else GuardEventType.SERVICE_STOPPED,
                        service = host,
                    ),
                )
            },
            onHeartbeat = { emitHeartbeat("runtime") },
            heartbeatIntervalMs = HEARTBEAT_INTERVAL_MS,
        )

    fun attach(host: String) = hostMonitor.attach(host)

    fun detach(
        host: String,
        destroyed: Boolean = false,
    ) = hostMonitor.detach(host, destroyed)

    fun journalTaskRemoved(host: String) {
        if (isActive()) {
            journal(GuardEvent(type = GuardEventType.TASK_REMOVED, service = host))
        }
    }

    fun hasAttachedHosts(): Boolean = hostMonitor.hasHosts()

    fun hasAttachedHostOtherThan(host: String): Boolean = hostMonitor.hasHostOtherThan(host)

    /** Applies the latest monitoring setting and returns whether the heartbeat is running. */
    fun reconcileActivation(): Boolean = hostMonitor.reconcile()

    /** Clears process-local ownership after a factory reset. */
    fun clearHosts() = hostMonitor.clear()

    /** Diff the live package list against the last snapshot; journal anything missed. */
    @Synchronized
    fun reconcileInventory() {
        reconcileInventory(excludedPackageName = null)
    }

    private fun reconcileInventory(excludedPackageName: String?) {
        if (!isActive()) {
            return
        }
        val previous = inventoryStore.read()
        val current =
            GuardInventorySnapshot(
                capturedAt = clock.wallClockMs(),
                apps = inspector.snapshotInstalledApps(),
            )
        guardInventoryEventsToJournal(previous, current, excludedPackageName)
            .forEach { event -> journal(enrichReconciled(event)) }
        inventoryStore.write(current)
    }

    fun emitHeartbeat(source: String) {
        if (isActive()) {
            journal(GuardEvent(type = GuardEventType.HEARTBEAT, service = source))
        }
    }

    /**
     * Flags a suspected monitoring blackout when the elapsed gap since the last journal
     * record exceeds twice the heartbeat interval. Reported as SUSPECTED, corroborated
     * by the boot count, because Doze can legitimately delay the worker.
     */
    @Synchronized
    fun detectBlackout() {
        // The previous implementation used the package-inventory timestamp. That timestamp says
        // when apps were scanned, not when monitoring last ran, so a healthy resident heartbeat
        // could still be reported as a blackout when WorkManager was delayed by Doze.
        val last = lastJournalRecordAt() ?: inventoryStore.read().capturedAt
        val nowWall = clock.wallClockMs()
        guardBlackoutGapMs(
            lastRecordAt = last,
            nowWall = nowWall,
            thresholdMs = BLACKOUT_THRESHOLD_MS,
        )?.let { gapMs ->
            journal(
                GuardEvent(
                    type = GuardEventType.BLACKOUT_SUSPECTED,
                    gapMs = gapMs,
                    detail = "bootCount=${clock.bootCount()}",
                ),
            )
        }
    }

    /**
     * Records one package change coming from the always-on manifest receiver (which fires
     * even while the app is locked or its process was dead). Enriches installs/updates with
     * installer + signer, then refreshes the inventory snapshot so reconciliation does not
     * re-report the same change.
     */
    @Synchronized
    fun recordPackageChange(
        type: GuardEventType,
        packageName: String,
    ) {
        if (!isActive()) {
            return
        }
        val event =
            if (type == GuardEventType.PACKAGE_REMOVED) {
                GuardEvent(type = type, packageName = packageName)
            } else {
                val described = inspector.describe(packageName)
                GuardEvent(
                    type = type,
                    packageName = packageName,
                    installer = inspector.installerOf(packageName),
                    uid = described.uid,
                    signerSha256 = described.signerSha256,
                )
            }
        val recordedDirectly = journal(event)
        // Reconciliation must still catch unrelated changes missed by broadcasts, but suppress
        // the package we just wrote directly. Previously it emitted the same install/remove a
        // second time from the stale inventory snapshot.
        reconcileInventory(excludedPackageName = packageName.takeIf { recordedDirectly })
    }

    private fun enrichReconciled(event: GuardEvent): GuardEvent {
        val packageName = event.packageName ?: return event
        if (event.type == GuardEventType.PACKAGE_REMOVED) {
            return event
        }
        val described = inspector.describe(packageName)
        return event.copy(
            installer = inspector.installerOf(packageName),
            uid = described.uid,
            signerSha256 = described.signerSha256,
        )
    }

    companion object {
        const val HEARTBEAT_INTERVAL_MS = 15L * 60L * 1000L
        const val BLACKOUT_THRESHOLD_MS = 2L * HEARTBEAT_INTERVAL_MS
    }
}

internal fun guardInventoryEventsToJournal(
    previous: GuardInventorySnapshot,
    current: GuardInventorySnapshot,
    excludedPackageName: String?,
): List<GuardEvent> =
    GuardInventoryDiff.diff(previous, current)
        .filterNot { event -> event.packageName == excludedPackageName }

internal fun guardBlackoutGapMs(
    lastRecordAt: Long,
    nowWall: Long,
    thresholdMs: Long,
): Long? =
    (nowWall - lastRecordAt)
        .takeIf { gapMs -> lastRecordAt > 0L && gapMs > thresholdMs }
