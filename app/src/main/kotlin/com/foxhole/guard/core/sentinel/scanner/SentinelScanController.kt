package com.foxhole.guard.core.sentinel.scanner

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class SentinelScanController(
    private val capture: suspend (String) -> SentinelScanSource,
    private val isCurrent: suspend (SentinelScanSource) -> Boolean,
    private val visiblePackages: suspend () -> List<String>,
    private val environment: () -> SentinelScanEnvironment,
    private val scan: suspend (SentinelScanSource, SentinelScanWorkPolicy, () -> Boolean) -> SentinelScanSummary,
    private val persist: suspend (SentinelScanSummary, () -> Boolean) -> ScannerJournalOutcome,
    private val policyCurrent: (SentinelScanWorkPolicy) -> Boolean = { true },
) : SentinelScanControls {
    private val operationMutex = Mutex()
    private val ownerLock = Any()
    private var policy = SentinelScanWorkPolicy()
    private var revision = 0L
    private var active: Job? = null
    private val cache = LinkedHashMap<ScanCacheKey, CachedScan>()
    private val mutableState = MutableStateFlow(SentinelScanControlState())
    override val state: StateFlow<SentinelScanControlState> = mutableState.asStateFlow()

    override suspend fun updatePolicy(policy: SentinelScanWorkPolicy) {
        val previous = synchronized(ownerLock) {
            if (this.policy == policy) return
            this.policy = policy
            revision++
            cache.clear()
            mutableState.value = SentinelScanControlState(
                stage = if (policy.masterEnabled) SentinelScanStage.IDLE else SentinelScanStage.DISABLED,
            )
            active.also { active = null }
        }
        previous?.cancel(CancellationException("Scanner policy changed"))
    }

    override suspend fun cancel() {
        val previous = synchronized(ownerLock) {
            revision++
            mutableState.value = mutableState.value.copy(stage = SentinelScanStage.CANCELLED, lastSummary = null)
            active.also { active = null }
        }
        previous?.cancel(CancellationException("Scanner cancelled"))
    }

    override suspend fun requestPackage(packageName: String) = runRequest({ listOf(packageName) }, automatic = false)
    suspend fun requestAutomaticPackage(packageName: String) = runRequest({ listOf(packageName) }, automatic = true)
    suspend fun requestAutomaticBaseline() = runRequest(
        { visiblePackages().distinct().take(MAX_BASELINE_PACKAGES) },
        automatic = true,
    )
    override suspend fun requestVisibleBaseline() = runRequest(
        { visiblePackages().distinct().take(MAX_BASELINE_PACKAGES) },
        automatic = false
    )

    private suspend fun runRequest(packagesProvider: suspend () -> List<String>, automatic: Boolean) {
        val captured = synchronized(ownerLock) { policy to revision }
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
                publish(captured.second, SentinelScanControlState(stage = SentinelScanStage.UNAVAILABLE))
            } finally {
                scanJob.complete()
                synchronized(ownerLock) { if (active === scanJob) active = null }
            }
        }
    }

    private suspend fun inspectPackages(packages: List<String>, policy: SentinelScanWorkPolicy, epoch: Long, automatic: Boolean) {
        var completed = 0
        val allowed = { environmentAllows(policy, automatic) }
        for (packageName in packages) {
            currentCoroutineContext().ensureActive()
            if (!allowed()) {
                publish(epoch, SentinelScanControlState(SentinelScanStage.WAITING, null, completed, packages.size))
                return
            }
            require(packageName.matches(Regex("[A-Za-z0-9_]+(?:[.][A-Za-z0-9_]+)+")))
            publish(epoch, SentinelScanControlState(SentinelScanStage.RUNNING, packageName, completed, packages.size))
            try {
                inspectPackage(packageName, policy, epoch, completed, packages.size, allowed)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: ScannerConstraintsLost) {
                publish(
                    epoch,
                    SentinelScanControlState(SentinelScanStage.WAITING, packageName, completed, packages.size)
                )
                return
            } catch (_: Exception) {
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
    ) {
        val source = capture(packageName)
        val key = ScanCacheKey(source, policy)
        val cached = synchronized(ownerLock) { cache[key] }
        val result = cached?.summary ?: scan(source, policy) { current(epoch) && allowed() }
        currentCoroutineContext().ensureActive()
        if (!allowed()) throw ScannerConstraintsLost()
        if (!current(epoch) || !isCurrent(source) || result.source != source) {
            publish(epoch, SentinelScanControlState(SentinelScanStage.STALE, packageName, completed, requested))
            return
        }
        val journal = if (cached == null || cached.journalDeferred) {
            persist(result) { current(epoch) && allowed() }
        } else {
            ScannerJournalOutcome.COMMITTED
        }
        if (journal == ScannerJournalOutcome.REJECTED) {
            if (!allowed()) throw ScannerConstraintsLost()
            publish(epoch, SentinelScanControlState(SentinelScanStage.STALE, packageName, completed, requested))
            return
        }
        currentCoroutineContext().ensureActive()
        if (!current(epoch) || !isCurrent(source)) return
        cacheCompleteScan(key, result, journal, epoch)
        publish(
            epoch,
            SentinelScanControlState(
                result.outcome,
                packageName,
                completed + 1,
                requested,
                result,
                cached != null,
                journal == ScannerJournalOutcome.DEFERRED,
            )
        )
    }

    private fun cacheCompleteScan(key: ScanCacheKey, result: SentinelScanSummary, journal: ScannerJournalOutcome, epoch: Long) {
        if (!result.complete) return
        synchronized(ownerLock) {
            if (revision == epoch) {
                cache[key] = CachedScan(result, journal == ScannerJournalOutcome.DEFERRED)
                while (cache.size > MAX_CACHE_ENTRIES) cache.remove(cache.keys.first())
            }
        }
    }

    private fun current(epoch: Long): Boolean = synchronized(ownerLock) {
        revision == epoch && policy.masterEnabled && policyCurrent(policy)
    }
    private fun publish(epoch: Long, value: SentinelScanControlState) = synchronized(ownerLock) {
        if (revision == epoch) mutableState.value = value
    }
    private data class CachedScan(val summary: SentinelScanSummary, val journalDeferred: Boolean)
    private class ScannerConstraintsLost : Exception()
    private data class ScanCacheKey(val source: SentinelScanSource, val policy: SentinelScanWorkPolicy)
    private companion object {
        const val MAX_CACHE_ENTRIES = 128
        const val MAX_BASELINE_PACKAGES = 4096
    }
}
