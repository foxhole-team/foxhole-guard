package com.foxhole.guard.guardian

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    private val onReconciledPackageEvents: suspend (List<GuardEvent>) -> Unit = {},
) {
    private val appContext = context.applicationContext
    private val inspector = GuardPackageInspector(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inventoryMutex = Mutex()
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
    suspend fun reconcileInventory() =
        inventoryMutex.withLock {
            reconcileInventoryLocked(excludedPackageName = null)
        }

    /**
     * Establishes the enable-time inventory without turning already-present packages into
     * installation events.
     *
     * Monitoring deliberately retains its sealed journal while it is off, but its package cursor
     * has a different lifetime: an app installed during that pause predates the next opt-in. The
     * activation path calls this before it flips the setting. Using the same mutex as broadcasts
     * and reconciliation keeps the baseline from racing an already-running sentinel owner.
     */
    suspend fun refreshInventoryBaselineForActivation() =
        inventoryMutex.withLock {
            val installedApps = inspector.snapshotInstalledApps().getOrThrow()
            check(installedApps.isNotEmpty()) { "guard inventory query returned no packages" }
            inventoryStore.write(
                guardInventoryActivationBaseline(
                    installedApps = installedApps,
                    capturedAt = clock.wallClockMs(),
                ),
            )
        }

    private suspend fun reconcileInventoryLocked(excludedPackageName: String?) {
        if (!isActive()) {
            return
        }
        val previous = inventoryStore.read()
        // getOrThrow, not getOrElse(emptyList()): a PackageManager failure means the inventory is
        // unknown. Throwing aborts the pass before a single event or snapshot is written, and the
        // WorkManager tick that drives it retries; assuming an empty device would have journalled a
        // removal for every installed app and then persisted that fiction as the new baseline.
        val installedApps = inspector.snapshotInstalledApps().getOrThrow()
        check(installedApps.isNotEmpty()) { "guard inventory query returned no packages" }
        val current =
            GuardInventorySnapshot(
                capturedAt = clock.wallClockMs(),
                apps = installedApps,
            )
        val events =
            guardInventoryEventsToJournal(previous, current, excludedPackageName)
                .map(::enrichReconciled)
        // Apply the protection decision before advancing either journal or snapshot. The callback
        // is idempotent; if a later write fails, WorkManager retries the same diff without ever
        // admitting a package merely because its inventory was observed.
        onReconciledPackageEvents(events)
        val recorded = events.all(journal)
        check(recorded) { "guard inventory journal unavailable" }
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
    suspend fun recordPackageChange(
        type: GuardEventType,
        packageName: String,
    ) = inventoryMutex.withLock {
        if (!isActive()) {
            return@withLock
        }
        // One APK update fans out into ACTION_PACKAGE_REMOVED(replacing), ACTION_PACKAGE_ADDED
        // (replacing) and ACTION_PACKAGE_REPLACED, and the receiver maps two of those onto
        // PACKAGE_REPLACED. Re-describing the package and comparing it against the snapshot the
        // previous broadcast already absorbed makes the second delivery a no-op, whichever of them
        // arrives first, instead of a second journal record for the same install.
        val event =
            guardPackageChangeEventOrNull(
                type = type,
                packageName = packageName,
                previous = inventoryStore.read(),
                live = liveInventoryAppOrNull(type, packageName),
                installer = if (type == GuardEventType.PACKAGE_REMOVED) null else inspector.installerOf(packageName),
            )
        val recordedDirectly = event != null && journal(event)
        // Reconciliation must still catch unrelated changes missed by broadcasts, but suppress
        // the package we just wrote directly. Previously it emitted the same install/remove a
        // second time from the stale inventory snapshot.
        reconcileInventoryLocked(excludedPackageName = packageName.takeIf { recordedDirectly })
    }

    /** PackageManager's view of the package right now; null once it is gone. */
    private fun liveInventoryAppOrNull(
        type: GuardEventType,
        packageName: String,
    ): GuardInventoryApp? = if (type == GuardEventType.PACKAGE_REMOVED) null else inspector.describe(packageName)

    private fun enrichReconciled(event: GuardEvent): GuardEvent {
        val packageName = event.packageName ?: return event
        if (event.type == GuardEventType.PACKAGE_REMOVED) {
            return event
        }
        val described = inspector.describe(packageName)
        return event.copy(
            installer = inspector.installerOf(packageName),
            uid = described.uid,
            versionCode = described.versionCode,
            signerSha256 = described.signerSha256,
            firstInstallTime = described.firstInstallTime.takeIf { value -> value > 0L },
        )
    }

    companion object {
        const val HEARTBEAT_INTERVAL_MS = 15L * 60L * 1000L
        const val BLACKOUT_THRESHOLD_MS = 2L * HEARTBEAT_INTERVAL_MS
    }
}

/**
 * Whether a package broadcast is news, and what to journal for it.
 *
 * One APK update fans out into several broadcasts, and the receiver maps more than one of them onto
 * the same [GuardEventType]. Deciding against the stored inventory rather than against the
 * broadcast makes the second delivery a no-op whichever order they arrive in: the first one writes
 * the record and refreshes the snapshot, the second one finds the snapshot already describing this
 * exact version, uid and signer and returns null.
 *
 * A change that *is* news is re-evaluated from [live] — the point of an update event is the new
 * signer and version, so it must never be copied from the entry the update replaced. Deliberately
 * pure: this decides, the caller journals.
 */
internal fun guardPackageChangeEventOrNull(
    type: GuardEventType,
    packageName: String,
    previous: GuardInventorySnapshot,
    live: GuardInventoryApp?,
    installer: String?,
): GuardEvent? {
    val known = previous.apps.firstOrNull { app -> app.packageName == packageName }
    if (type == GuardEventType.PACKAGE_REMOVED) {
        // Nothing to remove: either the removal was already absorbed, or the package was never in
        // the inventory. A record here would be a removal the device cannot corroborate.
        return known?.let { GuardEvent(type = type, packageName = packageName) }
    }
    if (live == null) {
        // PackageManager could not describe a package it just told us about. The broadcast is still
        // evidence that something changed, so the event is kept — without invented facts — and the
        // reconciliation pass fills in the description once the query works again.
        return GuardEvent(type = type, packageName = packageName, installer = installer)
    }
    if (known != null && known.sameInstallationAs(live)) {
        return null
    }
    return GuardEvent(
        type = type,
        packageName = packageName,
        installer = installer,
        uid = live.uid,
        versionCode = live.versionCode,
        signerSha256 = live.signerSha256,
        firstInstallTime = live.firstInstallTime.takeIf { value -> value > 0L },
    )
}

/**
 * The identity-bearing fields only. `firstInstallTime` is excluded on purpose: it survives an
 * update unchanged, so including it would not help, and it is rewritten by a restore — which is a
 * change worth reporting, and reporting it is what the version and signer already do.
 */
private fun GuardInventoryApp.sameInstallationAs(other: GuardInventoryApp): Boolean =
    versionCode == other.versionCode &&
        uid == other.uid &&
        signerSha256 == other.signerSha256

internal fun guardInventoryEventsToJournal(
    previous: GuardInventorySnapshot,
    current: GuardInventorySnapshot,
    excludedPackageName: String?,
): List<GuardEvent> =
    GuardInventoryDiff.diff(previous, current)
        .filterNot { event -> event.packageName == excludedPackageName }

internal fun guardInventoryActivationBaseline(
    installedApps: List<GuardInventoryApp>,
    capturedAt: Long,
): GuardInventorySnapshot {
    require(installedApps.isNotEmpty()) { "guard inventory activation baseline is empty" }
    return GuardInventorySnapshot(
        capturedAt = capturedAt,
        apps = installedApps,
    )
}

internal fun guardBlackoutGapMs(
    lastRecordAt: Long,
    nowWall: Long,
    thresholdMs: Long,
): Long? =
    (nowWall - lastRecordAt)
        .takeIf { gapMs -> lastRecordAt > 0L && gapMs > thresholdMs }
