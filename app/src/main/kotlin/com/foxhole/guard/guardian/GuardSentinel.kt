package com.foxhole.guard.guardian

import android.content.Context
import com.foxhole.guard.core.sentinel.sentinelAttempt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

class GuardSentinel internal constructor(
    context: Context,
    private val clock: GuardClock,
    private val inventoryStore: GuardInventorySnapshotStore,
    private val journal: (GuardEvent) -> Boolean,
    private val isActive: () -> Boolean,
    private val lastJournalRecordAt: () -> Long?,
    private val onReconciledPackageEvents: suspend (List<GuardEvent>) -> Unit = {},
    workScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val inventorySnapshotForTest: (() -> Result<List<GuardInventoryApp>>)? = null,
    private val journalIfCurrent: (GuardEvent, () -> Boolean) -> Boolean =
        { event, current -> current() && journal(event) },
    onIoFailure: (GuardIoFailure) -> Unit = {},
    private val packageDescriptionForTest: ((String) -> GuardInventoryApp)? = null,
    private val packageWakeForTest: (suspend () -> Unit)? = null,
    private val packageWorkStoreForTest: GuardPackageWorkStore? = null,
    private val inventoryViewForTest: (() -> Result<GuardPackageVisibleInventory>)? = null,
) {
    private val appContext = context.applicationContext
    private val inspectorDelegate = lazy { GuardPackageInspector(appContext) }
    private val inspector by inspectorDelegate
    private val packageWork by lazy {
        packageWorkStoreForTest ?: inventoryStore.packageWorkStore()
    }
    internal val journalPackageWorkStore: GuardPackageWorkStore get() = packageWork
    private val scope = workScope
    private val inventoryMutex = Mutex()
    private val lifecycleIo =
        GuardOrderedLifecycleIo(
            scope = scope,
            allowed = isActive,
            onFailure = onIoFailure,
            onCapacityAvailable = ::reconcileIoCapacity,
        )
    private val hostMonitor =
        GuardHostMonitor(
            scope = scope,
            monitoringActive = isActive,
            onHostStarted = { host ->
                lifecycleIo.start(host) { current ->
                    journalIfCurrent(GuardEvent(type = GuardEventType.SERVICE_STARTED, service = host), current)
                } == GuardIoAdmission.ACCEPTED
            },
            onHostStopped = { host, destroyed ->
                lifecycleIo.terminal(host) { current ->
                    journalIfCurrent(
                        GuardEvent(
                            type = if (destroyed) GuardEventType.SERVICE_DESTROYED else GuardEventType.SERVICE_STOPPED,
                            service = host,
                        ),
                        current,
                    )
                }
            },
            onHeartbeat = { emitHeartbeat("runtime") },
            heartbeatIntervalMs = HEARTBEAT_INTERVAL_MS,
            onInvalidated = lifecycleIo::invalidate,
        )

    fun attach(host: String) = hostMonitor.attach(host)

    fun detach(
        host: String,
        destroyed: Boolean = false,
    ) = hostMonitor.detach(host, destroyed)

    fun journalTaskRemoved(host: String) {
        if (isActive()) {
            lifecycleIo.required { current ->
                journalIfCurrent(GuardEvent(type = GuardEventType.TASK_REMOVED, service = host), current)
            }
        }
    }

    fun hasAttachedHosts(): Boolean = hostMonitor.hasHosts()

    fun hasAttachedHostOtherThan(host: String): Boolean = hostMonitor.hasHostOtherThan(host)

    fun reconcileActivation(): Boolean = hostMonitor.reconcile()

    fun clearHosts() = hostMonitor.clear()

    internal fun lifecycleIoHealth(): GuardIoHealth = lifecycleIo.health()

    private fun reconcileIoCapacity() {
        hostMonitor.reconcile()
    }

    suspend fun reconcileInventory(force: Boolean = true): Unit = withContext(Dispatchers.IO) {
        if (!this@GuardSentinel.isActive()) return@withContext
        val epoch = packageWork.currentEpoch()
        val journalEpoch = packageWork.currentJournalEpoch()
        val owner = currentCoroutineContext()
        inventoryMutex.withLock {
            owner.ensureActive()
            val previous = inventoryStore.read()
            if (force || guardFullInventoryRepairDue(previous, clock.wallClockMs())) {
                reconcileInventoryLocked(
                    emptySet(),
                    journalEpoch
                ) { owner.isActive && isActive() && packageWork.isCurrent(epoch) }
            }
        }
    }

    suspend fun refreshInventoryBaselineForActivation() = withContext(Dispatchers.IO) {
        invalidateQueueEpoch()
        settleQueueEpochInvalidation()
        val epoch = packageWork.currentEpoch()
        val owner = currentCoroutineContext()
        inventoryMutex.withLock {
            owner.ensureActive()
            val snapshot = installedAppsSnapshot().getOrThrow()
            requireUsableInventory(snapshot)
            check(
                snapshot.authoritative && snapshot.unresolvedPackages.isEmpty()
            ) { "guard inventory activation facts incomplete" }
            val installedApps = snapshot.apps
            check(installedApps.isNotEmpty()) { "guard inventory query returned no packages" }
            inventoryStore.writeGuardInventoryIfCurrent(
                guardInventoryActivationBaseline(installedApps, clock.wallClockMs())
            ) {
                owner.isActive && packageWork.isCurrent(epoch)
            }
        }
    }

    internal fun invalidateQueueEpoch() {
        packageWork.invalidateEpoch()
        inventoryStore.invalidateEpoch()
        if (inspectorDelegate.isInitialized()) inspector.invalidateEpoch()
    }

    internal suspend fun settleQueueEpochInvalidation() = withContext(Dispatchers.IO) {
        packageWork.settleInvalidation()
        inventoryStore.settleInvalidation()
    }

    private fun installedAppsSnapshot(): Result<GuardPackageVisibleInventory> =
        inventoryViewForTest?.invoke() ?: inventorySnapshotForTest?.invoke()?.map { apps ->
            GuardPackageVisibleInventory(apps, emptySet(), true)
        } ?: inspector.snapshotInstalledApps()

    private suspend fun reconcileInventoryLocked(
        excludedPackageNames: Set<String>,
        journalEpoch: String,
        currentEpoch: () -> Boolean,
    ): GuardPackageRepair? {
        if (!currentEpoch()) return null
        val previous = inventoryStore.read()
        currentCoroutineContext().ensureActive()
        if (!previous.fullRepairPending) {
            inventoryStore.writeGuardInventoryIfCurrent(previous.copy(fullRepairPending = true), currentEpoch)
        }
        val snapshot = installedAppsSnapshot().getOrThrow()
        requireUsableInventory(snapshot)
        currentCoroutineContext().ensureActive()
        val installedApps = snapshot.apps
        val authoritative = snapshot.authoritative && snapshot.unresolvedPackages.isEmpty()
        val visibleNames = installedApps.map(GuardInventoryApp::packageName).toSet()
        val retained = if (authoritative) emptyList() else previous.apps.filterNot { it.packageName in visibleNames }
        val capturedAt = if (authoritative) clock.wallClockMs() else previous.capturedAt
        val current = GuardInventorySnapshot(
            capturedAt,
            installedApps + retained,
            lastAuthoritativeFullRepairAt = if (authoritative) capturedAt else previous.lastAuthoritativeFullRepairAt,
            fullRepairPending = !authoritative,
        )
        val events = GuardInventoryDiff.diff(previous, current)
            .filterNot { it.packageName in excludedPackageNames }
            .map { event ->
                if (event.type == GuardEventType.PACKAGE_REMOVED) {
                    event
                } else {
                    event.copy(installer = installerOf(requireNotNull(event.packageName)))
                }
            }
        check(currentEpoch()) { "guard inventory epoch changed" }
        onReconciledPackageEvents(events)
        events.forEach {
            guardAppendPackageJournalIfCurrent(
                it,
                journalEpoch,
                packageWork,
                journalIfCurrent,
                currentEpoch
            )
        }
        currentCoroutineContext().ensureActive()
        inventoryStore.writeGuardInventoryIfCurrent(current, currentEpoch)
        return GuardPackageRepair(installedApps, authoritative)
    }

    fun emitHeartbeat(source: String) {
        if (isActive()) {
            lifecycleIo.required { current ->
                journalIfCurrent(GuardEvent(type = GuardEventType.HEARTBEAT, service = source), current)
            }
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

    suspend fun recordPackageChange(type: GuardEventType, packageName: String) =
        enqueuePackageChange(type, packageName)

    suspend fun enqueuePackageChange(type: GuardEventType, packageName: String) = withContext(Dispatchers.IO) {
        if (!isActive()) return@withContext
        if (inspectorDelegate.isInitialized()) inspector.invalidate(packageName)
        val admission = packageWork.enqueue(type, packageName, clock.wallClockMs(), this@GuardSentinel.isActive)
        if (admission != GuardPackageAdmission.INACTIVE) {
            packageWork.reserveWake()?.let { schedulePackageWake(it) }
        }
    }

    internal suspend fun wakePendingPackageChanges() = withContext(Dispatchers.IO) {
        if (isActive()) packageWork.reserveWake()?.let { schedulePackageWake(it) }
    }

    private suspend fun schedulePackageWake(reservation: String) {
        try {
            packageWakeForTest?.invoke() ?: appContext.enqueueGuardPackageReconcile()
        } catch (error: Throwable) {
            packageWork.releaseFailedWake(reservation)
            throw error
        }
    }

    internal suspend fun drainPackageChanges() = withContext(Dispatchers.IO) {
        if (!isActive()) return@withContext
        val owner = packageWork.beginDrain() ?: return@withContext
        var completed = false
        try {
            var batches = 0
            while (batches < MAX_PACKAGE_BATCHES) {
                batches++
                currentCoroutineContext().ensureActive()
                val claim = packageWork.claim(owner)
                if (claim.observations.isEmpty() && claim.dropped == 0L && !claim.fullRepairRequired) {
                    packageWork.acknowledge(claim, repairSettled = true)
                    break
                }
                inventoryMutex.withLock { settlePackageClaim(claim) }
                check(packageWork.acknowledge(claim, repairSettled = true)) { "guard package epoch changed" }
            }
            completed = true
        } finally {
            packageWork.finishDrain(owner, reserveSuccessor = completed)?.let { schedulePackageWake(it) }
        }
    }

    private suspend fun settlePackageClaim(claim: GuardPackageWorkClaim) {
        val owner = currentCoroutineContext()
        val current = { owner.isGuardPackageCurrent(this@GuardSentinel.isActive, packageWork, claim.epoch) }
        check(current()) { "guard package epoch changed" }
        val last = claim.observations.associateBy(GuardPackageObservation::packageName)
        val facts = last.filterValues { it.type != GuardEventType.PACKAGE_REMOVED }
            .mapValues { (name, _) -> sentinelAttempt { packageDescriptionForTest?.invoke(name) ?: inspector.describe(name) } }
        for (observation in claim.observations) {
            currentCoroutineContext().ensureActive()
            val live = facts[observation.packageName]?.getOrNull()
                ?.takeIf { last[observation.packageName]?.seq == observation.seq }
            val event = GuardEvent(
                type = observation.type,
                packageName = observation.packageName,
                installer = live?.let { installerOf(observation.packageName) },
                uid = live?.uid,
                versionCode = live?.versionCode,
                signerSha256 = live?.signerSha256,
                firstInstallTime = live?.firstInstallTime,
                detail = "observedAt=${observation.observedAt}" +
                    if (live == null && observation.type != GuardEventType.PACKAGE_REMOVED) ";metadataUnavailable=true" else "",
            )
            guardAppendPackageJournalIfCurrent(event, observation.journalEpoch, packageWork, journalIfCurrent, current)
        }
        if (claim.journalDropped > 0L) {
            guardAppendPackageJournalIfCurrent(
                GuardEvent(
                    type = GuardEventType.PACKAGE_EVENT_GAP,
                    service = "package-worker",
                    detail = "dropped=${claim.journalDropped};fullRepairRequired=true"
                ),
                claim.journalEpoch,
                packageWork,
                journalIfCurrent,
                current,
            )
        }
        if (claim.fullRepairRequired || facts.values.any { it.isFailure }) {
            val repair =
                checkNotNull(
                    reconcileInventoryLocked(last.keys, claim.repairJournalEpoch(), current)
                ) { "guard package epoch changed" }
            if (claim.fullRepairRequired) check(repair.authoritative) { "guard package full repair incomplete" }
            val repairedNames = repair.visibleApps.map(GuardInventoryApp::packageName).toSet()
            check(facts.filterValues { it.isFailure }.keys.all { it in repairedNames || repair.authoritative }) {
                "guard package metadata unresolved"
            }
        } else {
            val previous = inventoryStore.read()
            val baseline = previous.apps.associateBy(GuardInventoryApp::packageName).toMutableMap()
            last.forEach { (name, observation) ->
                if (observation.type == GuardEventType.PACKAGE_REMOVED) {
                    baseline.remove(name)
                } else {
                    baseline[name] = facts.getValue(name).getOrThrow()
                }
            }
            inventoryStore.writeGuardInventoryIfCurrent(
                GuardInventorySnapshot(
                    clock.wallClockMs(),
                    baseline.values.sortedBy(GuardInventoryApp::packageName),
                    lastAuthoritativeFullRepairAt = previous.lastAuthoritativeFullRepairAt,
                    fullRepairPending = previous.fullRepairPending,
                ),
                current,
            )
        }
    }

    private fun installerOf(name: String): String? =
        if (packageDescriptionForTest != null) null else inspector.installerOf(name)

    companion object {
        private const val MAX_PACKAGE_BATCHES = 4
        const val FULL_REPAIR_INTERVAL_MS = 6L * 60L * 60L * 1000L
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
        lastAuthoritativeFullRepairAt = capturedAt,
    )
}

internal fun guardBlackoutGapMs(
    lastRecordAt: Long,
    nowWall: Long,
    thresholdMs: Long,
): Long? =
    (nowWall - lastRecordAt)
        .takeIf { gapMs -> lastRecordAt > 0L && gapMs > thresholdMs }

private data class GuardPackageRepair(val visibleApps: List<GuardInventoryApp>, val authoritative: Boolean)

internal fun GuardSentinel.invalidatePackageJournalEpoch() = journalPackageWorkStore.invalidateJournalEpoch()

internal suspend fun GuardSentinel.settlePackageJournalEpochInvalidation() = withContext(Dispatchers.IO) {
    settlePackageJournalEpochOnIo()
}

private fun guardAppendPackageJournalIfCurrent(
    event: GuardEvent,
    expectedJournalEpoch: String,
    work: GuardPackageWorkStore,
    journal: (GuardEvent, () -> Boolean) -> Boolean,
    packageCurrent: () -> Boolean,
) {
    check(packageCurrent()) { "guard package epoch changed" }
    if (!work.isJournalCurrent(expectedJournalEpoch)) return
    val recorded = journal(event) { packageCurrent() && work.isJournalCurrent(expectedJournalEpoch) }
    check(packageCurrent()) { "guard package epoch changed" }
    check(recorded || !work.isJournalCurrent(expectedJournalEpoch)) { "guard package journal unavailable" }
}

internal fun GuardSentinel.settlePackageJournalEpochOnIo() = journalPackageWorkStore.settleJournalInvalidation()

private fun GuardPackageWorkClaim.repairJournalEpoch(): String =
    journalEpoch.takeIf { journalDropped > 0L || observations.any { it.journalEpoch == journalEpoch } }.orEmpty()

internal fun guardFullInventoryRepairDue(snapshot: GuardInventorySnapshot, nowWall: Long): Boolean {
    val lastFull = snapshot.lastAuthoritativeFullRepairAt
    if (snapshot.fullRepairPending || !guardInventoryAppsValid(snapshot.apps)) return true
    if (lastFull <= 0L || nowWall <= 0L) return true
    return nowWall < lastFull || nowWall - lastFull >= GuardSentinel.FULL_REPAIR_INTERVAL_MS
}

private fun requireUsableInventory(snapshot: GuardPackageVisibleInventory) {
    check(guardInventoryAppsValid(snapshot.apps)) { "guard inventory facts invalid" }
}

private fun guardInventoryAppsValid(apps: List<GuardInventoryApp>): Boolean {
    if (apps.isEmpty()) return false
    val names = apps.map(GuardInventoryApp::packageName)
    if (names.toSet().size != names.size || names.any { it.isBlank() || it.length > 255 }) return false
    return apps.all {
        it.versionCode >= 0 && (it.uid == null || it.uid >= 0) && it.firstInstallTime >= 0 && it.lastUpdateTime >= 0
    }
}

private suspend fun GuardInventorySnapshotStore.writeGuardInventoryIfCurrent(snapshot: GuardInventorySnapshot, current: () -> Boolean) {
    val written = writeIfCurrent(snapshot, current)
    currentCoroutineContext().ensureActive()
    check(written) { "guard inventory epoch changed" }
}

private fun CoroutineContext.isGuardPackageCurrent(active: () -> Boolean, work: GuardPackageWorkStore, epoch: String): Boolean =
    isActive && active() && work.isCurrent(epoch)
