package com.foxhole.guard.core.sentinel.scanner

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

internal class SentinelScanController(
    private val capture: suspend (String) -> SentinelScanSource,
    private val isCurrent: suspend (SentinelScanSource) -> Boolean,
    private val visiblePackages: suspend () -> List<String>,
    private val environment: () -> SentinelScanEnvironment,
    private val scan: suspend (SentinelScanSource, SentinelScanWorkPolicy, () -> Boolean) -> SentinelScanSummary,
    private val persist: suspend (SentinelScanSummary, () -> Boolean) -> ScannerJournalOutcome,
    private val policyCurrent: (SentinelScanWorkPolicy) -> Boolean = { true },
    private val persistReport: suspend (SentinelScanReport) -> Boolean = { true },
    private val appVersion: String = "unknown",
    internal val reportOwner: String = UUID.randomUUID().toString(),
    private val now: () -> Long = System::currentTimeMillis,
) : SentinelScanControls {
    private val operationMutex = Mutex()
    private val ownerLock = Any()
    private var policy = SentinelScanWorkPolicy()
    private var revision = 0L
    private var active: Job? = null
    private var clearingReports = false
    private val cache = LinkedHashMap<ScanCacheKey, CachedScan>()
    private val mutableState = MutableStateFlow(SentinelScanControlState())
    override val state: StateFlow<SentinelScanControlState> = mutableState.asStateFlow()

    override suspend fun updatePolicy(policy: SentinelScanWorkPolicy) {
        val previous = synchronized(ownerLock) {
            if (this.policy == policy) return
            this.policy = policy
            revision++
            cache.clear()
            val lastReport = mutableState.value.lastReport?.let { report ->
                if (report.status == ScanReportStatus.RUNNING) report.copy(
                    status = ScanReportStatus.CANCELLED,
                    finishedAtMs = maxOf(report.startedAtMs, now()),
                    issues = report.issues + "POLICY_CHANGED",
                ) else report
            }
            mutableState.value = SentinelScanControlState(
                stage = if (policy.masterEnabled) SentinelScanStage.IDLE else SentinelScanStage.DISABLED,
                lastReport = lastReport,
                reportDeferred = mutableState.value.reportDeferred,
                journalDeferred = mutableState.value.journalDeferred,
            )
            active.also { active = null }
        }
        previous?.cancel(CancellationException("Scanner policy changed"))
    }

    override suspend fun cancel() {
        val previous = synchronized(ownerLock) {
            revision++
            mutableState.value = mutableState.value.copy(
                stage = SentinelScanStage.CANCELLED,
                lastSummary = null,
                lastReport = mutableState.value.lastReport?.let { report ->
                    if (report.status == ScanReportStatus.RUNNING) report.copy(
                        status = ScanReportStatus.CANCELLED,
                        finishedAtMs = maxOf(report.startedAtMs, now()),
                        issues = report.issues + "CANCELLED",
                    ) else report
                },
            )
            active.also { active = null }
        }
        previous?.cancel(CancellationException("Scanner cancelled"))
    }

    internal suspend fun clearReports(clear: suspend () -> Unit) {
        val previous = synchronized(ownerLock) {
            clearingReports = true
            revision++
            mutableState.value = mutableState.value.copy(
                stage = SentinelScanStage.CANCELLED,
                lastSummary = null,
                lastReport = mutableState.value.lastReport?.let { report ->
                    if (report.status == ScanReportStatus.RUNNING) report.copy(
                        status = ScanReportStatus.CANCELLED,
                        finishedAtMs = maxOf(report.startedAtMs, now()),
                        issues = report.issues + "CANCELLED",
                    ) else report
                },
            )
            active.also { active = null }
        }
        try {
            previous?.cancelAndJoin()
            operationMutex.withLock {
                clear()
                synchronized(ownerLock) {
                    cache.clear()
                    mutableState.value = SentinelScanControlState(
                        stage = if (policy.masterEnabled) SentinelScanStage.IDLE else SentinelScanStage.DISABLED,
                    )
                }
            }
        } finally { synchronized(ownerLock) { clearingReports = false } }
    }

    override suspend fun requestPackage(packageName: String) = runRequest({ listOf(packageName) }, automatic = false, requestedPackage = packageName)
    suspend fun requestAutomaticPackage(packageName: String) = runRequest({ listOf(packageName) }, automatic = true, requestedPackage = packageName)
    suspend fun requestAutomaticBaseline() = runRequest(
        { visiblePackages().distinct().take(MAX_BASELINE_PACKAGES) },
        automatic = true,
    )
    override suspend fun requestVisibleBaseline() = runRequest(
        { visiblePackages().distinct().take(MAX_BASELINE_PACKAGES) },
        automatic = false
    )

    private suspend fun runRequest(packagesProvider: suspend () -> List<String>, automatic: Boolean, requestedPackage: String? = null) {
        val captured = synchronized(ownerLock) {
            if (clearingReports) return
            policy to revision
        }
        if (!captured.first.masterEnabled) return
        operationMutex.withLock {
            if (!current(captured.second)) return@withLock
            if (!environmentAllows(captured.first, automatic)) {
                publish(captured.second, SentinelScanControlState(stage = SentinelScanStage.WAITING))
                return@withLock
            }
            val scanJob = Job(currentCoroutineContext()[Job])
            val admitted = synchronized(ownerLock) {
                if (revision == captured.second && policy.masterEnabled) {
                    active = scanJob
                    true
                } else {
                    false
                }
            }
            if (!admitted) {
                scanJob.complete()
                return@withLock
            }
            try {
                withContext(scanJob) {
                    inspectPackages(packagesProvider(), captured.first, captured.second, automatic)
                }
            } catch (cancelled: CancellationException) {
                if (currentCoroutineContext().isActive) {
                    publish(captured.second, SentinelScanControlState(stage = SentinelScanStage.CANCELLED))
                } else {
                    throw cancelled
                }
            } catch (_: Exception) {
                val report = unavailableReport(requestedPackage, "PACKAGE_ENUMERATION_UNAVAILABLE", captured.first.ruleEpoch,
                    if (automatic) "AUTOMATIC" else if (requestedPackage == null) "BASELINE" else "PACKAGE")
                val saved = saveReport(report)
                publish(captured.second, SentinelScanControlState(stage = SentinelScanStage.UNAVAILABLE, lastReport = report, reportDeferred = !saved))
            } finally {
                scanJob.complete()
                synchronized(ownerLock) { if (active === scanJob) active = null }
            }
        }
    }

    internal suspend fun recordUnavailableRequest(packageName: String?, reason: String) {
        val epoch = synchronized(ownerLock) {
            if (clearingReports) return
            revision
        }
        operationMutex.withLock {
            if (synchronized(ownerLock) { clearingReports || revision != epoch }) return@withLock
            val report = unavailableReport(packageName, reason, null, if (packageName == null) "BASELINE" else "PACKAGE")
            val saved = saveReport(report)
            publish(epoch, SentinelScanControlState(stage = SentinelScanStage.UNAVAILABLE, lastReport = report, reportDeferred = !saved))
        }
    }

    private fun unavailableReport(packageName: String?, reason: String, ruleEpoch: String?, type: String): SentinelScanReport {
        val stamp = now()
        return SentinelScanReport(
            id = UUID.randomUUID().toString(), processOwner = reportOwner, packageName = packageName,
            startedAtMs = stamp, finishedAtMs = stamp, requestType = type, status = ScanReportStatus.UNAVAILABLE,
            appVersion = appVersion, ruleEpoch = ruleEpoch, issues = setOf(reason), requestedPackages = null,
        )
    }

    private suspend fun inspectPackages(packages: List<String>, policy: SentinelScanWorkPolicy, epoch: Long, automatic: Boolean) {
        if (packages.isEmpty()) {
            val report = unavailableReport(null, "NO_PACKAGES_VISIBLE", policy.ruleEpoch, if (automatic) "AUTOMATIC" else "BASELINE")
                .copy(requestedPackages = 0)
            val saved = saveReport(report)
            publish(epoch, SentinelScanControlState(stage = SentinelScanStage.UNAVAILABLE, lastReport = report, reportDeferred = !saved))
            return
        }
        var completed = 0
        var incomplete = false
        val requestSessionId = UUID.randomUUID().toString()
        val allowed = { environmentAllows(policy, automatic) }
        for (packageName in packages) {
            currentCoroutineContext().ensureActive()
            if (!allowed()) {
                publish(epoch, SentinelScanControlState(SentinelScanStage.WAITING, null, completed, packages.size))
                return
            }
            publish(epoch, SentinelScanControlState(SentinelScanStage.RUNNING, packageName, completed, packages.size))
            try {
                inspectPackage(packageName, policy, epoch, completed, packages.size, allowed, automatic, requestSessionId)
                if (mutableState.value.stage != SentinelScanStage.COMPLETE) incomplete = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: ScannerConstraintsLost) {
                publish(
                    epoch,
                    SentinelScanControlState(SentinelScanStage.WAITING, packageName, completed, packages.size)
                )
                return
            } catch (_: Exception) {
                incomplete = true
                if (!allowed()) {
                    publish(
                        epoch,
                        SentinelScanControlState(SentinelScanStage.WAITING, packageName, completed, packages.size)
                    )
                    return
                }
                publish(
                    epoch,
                    SentinelScanControlState(SentinelScanStage.UNAVAILABLE, packageName, completed + 1, packages.size)
                )
            }
            completed++
        }
        if (current(epoch)) {
            val previous = mutableState.value
            val report = previous.lastReport?.let { if (it.cached) it else it.copy(requestComplete = !incomplete) }
            val saved = report?.let { if (it.cached) !previous.reportDeferred else saveReport(it) } ?: true
            if (report != null && !report.cached) synchronized(ownerLock) {
                if (revision == epoch) cache.entries.forEach { entry ->
                    if (entry.value.report.id == report.id) entry.setValue(entry.value.copy(report = report, reportDeferred = !saved))
                }
            }
            publish(epoch, previous.copy(
                stage = if (incomplete && packages.size > 1) SentinelScanStage.PARTIAL else previous.stage,
                lastReport = report,
                reportDeferred = !saved,
            ))
        }
    }

    private fun environmentAllows(policy: SentinelScanWorkPolicy, automatic: Boolean): Boolean {
        val power = environment()
        if (!power.storageNotLow || power.criticalBattery) return false
        if (!automatic) return true
        if (!policy.automaticNewUpdatedApps || !policy.dexEnabled) return false
        return (!policy.chargingOnly || power.charging) && (!policy.batteryNotLow || power.batteryNotLow)
    }

    private suspend fun inspectPackage(
        packageName: String,
        policy: SentinelScanWorkPolicy,
        epoch: Long,
        completed: Int,
        requested: Int,
        allowed: () -> Boolean,
        automatic: Boolean,
        requestSessionId: String,
    ) {
        var report = SentinelScanReport(
            id = UUID.randomUUID().toString(), processOwner = reportOwner, packageName = packageName,
            startedAtMs = now(), requestType = if (automatic) "AUTOMATIC" else if (requested > 1) "BASELINE" else "PACKAGE",
            appVersion = appVersion, ruleEpoch = policy.ruleEpoch, artifactByteLimit = policy.maxArtifactBytes,
            completedPackages = completed, requestedPackages = requested,
            requestSessionId = requestSessionId,
        )
        var reportSaved = false
        try {
            require(packageName.matches(Regex("[A-Za-z0-9_]+(?:[.][A-Za-z0-9_]+)+")))
            val source = capture(packageName)
            val key = ScanCacheKey(source, policy)
            val cached = synchronized(ownerLock) { cache[key] }
            report = cached?.report?.copy(cached = true) ?: report.copy(
                installationId = source.installationId, packageVersion = source.packageVersion,
            )
            reportSaved = if (cached == null) saveReport(report) else !cached.reportDeferred
            publish(epoch, SentinelScanControlState(
                SentinelScanStage.RUNNING, packageName, completed, requested,
                lastReport = report, reportDeferred = !reportSaved,
            ))
            val result = cached?.summary ?: scan(source, policy) { current(epoch) && allowed() }
            currentCoroutineContext().ensureActive()
            report = report.copy(
                finishedAtMs = cached?.report?.finishedAtMs ?: maxOf(report.startedAtMs, now()),
                status = result.outcome.reportStatus(), analyzerVersion = result.analyzerVersion,
                artifacts = result.artifacts, coverage = result.coverage, observations = result.observations,
                ruleIds = result.ruleIds, issues = result.issues, limitations = result.limitations,
                completedPackages = cached?.report?.completedPackages ?: completed + 1,
                requestedPackages = cached?.report?.requestedPackages ?: requested,
            )
            if (!allowed()) throw ScannerConstraintsLost()
            if (!current(epoch) || !sourceCurrent(source) || result.source != source) {
                report = report.copy(status = ScanReportStatus.STALE, issues = report.issues + "INSTALLATION_CHANGED")
                reportSaved = saveReport(report)
                publish(epoch, SentinelScanControlState(SentinelScanStage.STALE, packageName, completed + 1, requested,
                    lastReport = report, reportDeferred = !reportSaved))
                return
            }
            val journal = if (cached == null || cached.journalDeferred) {
                try {
                    persist(result.copy(reportId = report.id)) { current(epoch) && allowed() }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    ScannerJournalOutcome.DEFERRED
                }
            } else ScannerJournalOutcome.COMMITTED
            if (journal == ScannerJournalOutcome.REJECTED) {
                if (!allowed()) throw ScannerConstraintsLost()
                report = report.copy(status = ScanReportStatus.STALE, issues = report.issues + "JOURNAL_OWNER_CHANGED")
                reportSaved = saveReport(report)
                publish(epoch, SentinelScanControlState(SentinelScanStage.STALE, packageName, completed + 1, requested,
                    lastReport = report, reportDeferred = !reportSaved))
                return
            }
            report = report.copy(journalDeferred = journal == ScannerJournalOutcome.DEFERRED)
            // Reusing coverage does not create a new scan or change its original timestamp/session.
            reportSaved = if (cached == null || cached.journalDeferred || cached.reportDeferred) {
                saveReport(report.copy(cached = false))
            } else true
            currentCoroutineContext().ensureActive()
            if (!current(epoch) || !sourceCurrent(source)) {
                report = report.copy(status = ScanReportStatus.STALE, issues = report.issues + "INSTALLATION_CHANGED")
                reportSaved = saveReport(report)
                publish(epoch, SentinelScanControlState(SentinelScanStage.STALE, packageName, completed + 1, requested,
                    lastReport = report, reportDeferred = !reportSaved))
                return
            }
            cacheCompleteScan(key, result, journal, epoch, report.copy(cached = false), reportSaved)
            publish(epoch, SentinelScanControlState(
                result.outcome, packageName, completed + 1, requested, result, cached != null,
                journal == ScannerJournalOutcome.DEFERRED, report, !reportSaved,
            ))
        } catch (cancelled: CancellationException) {
            val cancelledReport = report.copy(status = ScanReportStatus.CANCELLED,
                finishedAtMs = maxOf(report.startedAtMs, now()), issues = report.issues + "CANCELLED")
            if (!report.cached) withContext(NonCancellable) { saveReport(cancelledReport) }
            throw cancelled
        } catch (failure: Exception) {
            val failed = report.copy(
                status = if (failure is ScannerConstraintsLost) ScanReportStatus.PARTIAL else ScanReportStatus.UNAVAILABLE,
                finishedAtMs = maxOf(report.startedAtMs, now()),
                issues = report.issues + if (failure is ScannerConstraintsLost) "CONSTRAINTS_CHANGED" else "SCAN_UNAVAILABLE",
            )
            reportSaved = if (report.cached) reportSaved else saveReport(failed)
            publish(epoch, SentinelScanControlState(
                if (failure is ScannerConstraintsLost) SentinelScanStage.WAITING else SentinelScanStage.UNAVAILABLE,
                packageName, completed + 1, requested, lastReport = failed, reportDeferred = !reportSaved,
            ))
            throw failure
        }
    }

    private suspend fun saveReport(report: SentinelScanReport): Boolean = try {
        persistReport(report)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    private suspend fun sourceCurrent(source: SentinelScanSource): Boolean = try {
        isCurrent(source)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    private fun cacheCompleteScan(key: ScanCacheKey, result: SentinelScanSummary, journal: ScannerJournalOutcome, epoch: Long, report: SentinelScanReport, reportSaved: Boolean) {
        if (!result.complete) return
        synchronized(ownerLock) {
            if (revision == epoch) {
                cache[key] = CachedScan(result, journal == ScannerJournalOutcome.DEFERRED, !reportSaved, report)
                while (cache.size > MAX_CACHE_ENTRIES) cache.remove(cache.keys.first())
            }
        }
    }

    private fun current(epoch: Long): Boolean = synchronized(ownerLock) {
        revision == epoch && policy.masterEnabled && policyCurrent(policy)
    }
    private fun publish(epoch: Long, value: SentinelScanControlState) = synchronized(ownerLock) {
        if (revision == epoch) mutableState.value = if (value.lastReport == null) value.copy(
            lastReport = mutableState.value.lastReport,
            reportDeferred = mutableState.value.reportDeferred,
        ) else value
    }
    private data class CachedScan(val summary: SentinelScanSummary, val journalDeferred: Boolean, val reportDeferred: Boolean, val report: SentinelScanReport)
    private class ScannerConstraintsLost : Exception()
    private data class ScanCacheKey(val source: SentinelScanSource, val policy: SentinelScanWorkPolicy)
    private companion object {
        const val MAX_CACHE_ENTRIES = 128
        const val MAX_BASELINE_PACKAGES = 4096
    }
}

private fun SentinelScanStage.reportStatus(): ScanReportStatus = when (this) {
    SentinelScanStage.COMPLETE -> ScanReportStatus.COMPLETE
    SentinelScanStage.UNAVAILABLE -> ScanReportStatus.UNAVAILABLE
    SentinelScanStage.UNSUPPORTED -> ScanReportStatus.UNSUPPORTED
    else -> ScanReportStatus.PARTIAL
}
