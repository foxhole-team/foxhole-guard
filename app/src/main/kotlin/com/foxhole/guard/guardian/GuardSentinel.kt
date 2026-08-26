package com.foxhole.guard.guardian

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

    fun reconcileActivation(): Boolean = hostMonitor.reconcile()

    fun clearHosts() = hostMonitor.clear()

    suspend fun reconcileInventory() =
        inventoryMutex.withLock {
            reconcileInventoryLocked(excludedPackageName = null)
        }

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

    @Synchronized
    fun detectBlackout() {
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

    suspend fun recordPackageChange(
        type: GuardEventType,
        packageName: String,
    ) = inventoryMutex.withLock {
        if (!isActive()) {
            return@withLock
        }
        val event =
            guardPackageChangeEventOrNull(
                type = type,
                packageName = packageName,
                previous = inventoryStore.read(),
                live = liveInventoryAppOrNull(type, packageName),
                installer = if (type == GuardEventType.PACKAGE_REMOVED) null else inspector.installerOf(packageName),
            )
        val recordedDirectly = event != null && journal(event)
        reconcileInventoryLocked(excludedPackageName = packageName.takeIf { recordedDirectly })
    }

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

internal fun guardPackageChangeEventOrNull(
    type: GuardEventType,
    packageName: String,
    previous: GuardInventorySnapshot,
    live: GuardInventoryApp?,
    installer: String?,
): GuardEvent? {
    val known = previous.apps.firstOrNull { app -> app.packageName == packageName }
    if (type == GuardEventType.PACKAGE_REMOVED) {
        return known?.let { GuardEvent(type = type, packageName = packageName) }
    }
    if (live == null) {
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
