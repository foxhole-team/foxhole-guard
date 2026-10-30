package com.foxhole.guard.guardian

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong

internal enum class GuardIoAdmission { ACCEPTED, FULL, HOST_PENDING, UNAVAILABLE }

internal enum class GuardIoFailure { CAPACITY, HOST_PENDING, OWNER_STOPPED, APPEND_FAILED, WORKER_FAILED, CALLBACK_FAILED }

internal data class GuardIoHealth(
    val queued: Int,
    val reservedHosts: Int,
    val rejected: Long,
    val failed: Long,
    val invalidated: Long,
    val available: Boolean,
)

internal class GuardOrderedLifecycleIo(
    scope: CoroutineScope,
    private val ordinaryCapacity: Int = 128,
    private val terminalCapacity: Int = 16,
    private val allowed: () -> Boolean = { true },
    private val onFailure: (GuardIoFailure) -> Unit = {},
    private val onCapacityAvailable: () -> Unit = {},
) {
    private data class Work(
        val epoch: Long,
        val host: String?,
        val terminal: Boolean,
        val append: (() -> Boolean) -> Boolean,
    )

    private val lock = Any()
    private val pending = ArrayDeque<Work>()
    private val reservations = mutableMapOf<String, Boolean>()
    private val pendingFailures = mutableSetOf<GuardIoFailure>()
    private val wakeups = Channel<Unit>(Channel.CONFLATED)
    private val epoch = AtomicLong()
    private var ordinaryQueued = 0
    private var rejected = 0L
    private var failed = 0L
    private var invalidated = 0L
    private var available = true

    init {
        require(ordinaryCapacity > 0 && terminalCapacity > 0)
        scope.launch(Dispatchers.IO) {
            var workerFailure: Throwable? = null
            try {
                while (wakeups.receiveCatching().isSuccess) {
                    flushFailures()
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val work = synchronized(lock) {
                            pending.pollFirst()?.also { if (!it.terminal) ordinaryQueued -= 1 }
                        } ?: break
                        process(work)
                        flushFailures()
                        notifyCapacityAvailable()
                    }
                }
            } catch (failure: Throwable) {
                workerFailure = failure
                throw failure
            } finally {
                markUnavailable()
                workerFailure?.let { original ->
                    try {
                        notifyFailure(GuardIoFailure.WORKER_FAILED)
                    } catch (reportingFailure: Throwable) {
                        if (reportingFailure !== original) original.addSuppressed(reportingFailure)
                    }
                }
            }
        }.invokeOnCompletion { markUnavailable() }
    }

    private fun markUnavailable() {
        synchronized(lock) {
            available = false
            failed += pending.size
            pending.clear()
            reservations.clear()
            ordinaryQueued = 0
        }
    }

    private fun process(work: Work) {
        val current = { epoch.get() == work.epoch && allowed() }
        try {
            when {
                !current() -> synchronized(lock) { invalidated += 1 }
                !work.append(current) -> {
                    if (!current()) {
                        synchronized(lock) { invalidated += 1 }
                    } else {
                        appendFailed()
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            synchronized(lock) { failed += 1 }
            throw cancelled
        } catch (_: Exception) {
            appendFailed()
        } catch (fatal: Error) {
            synchronized(lock) { failed += 1 }
            throw fatal
        } finally {
            if (work.terminal && work.host != null) {
                synchronized(lock) {
                    if (epoch.get() == work.epoch) reservations.remove(work.host)
                }
            }
        }
    }

    fun start(host: String, append: (() -> Boolean) -> Boolean): GuardIoAdmission {
        val admission = synchronized(lock) {
            when {
                !available -> GuardIoAdmission.UNAVAILABLE
                host in reservations -> GuardIoAdmission.HOST_PENDING
                ordinaryQueued >= ordinaryCapacity || reservations.size >= terminalCapacity -> GuardIoAdmission.FULL
                else -> {
                    reservations[host] = false
                    add(Work(epoch.get(), host, false, append))
                    GuardIoAdmission.ACCEPTED
                }
            }
        }
        return reportAdmission(admission)
    }

    fun terminal(host: String, append: (() -> Boolean) -> Boolean): GuardIoAdmission {
        val admission = synchronized(lock) {
            when {
                !available -> GuardIoAdmission.UNAVAILABLE
                reservations[host] != false -> GuardIoAdmission.HOST_PENDING
                else -> {
                    // Admission of START reserves its terminal slot independently of ordinary traffic.
                    reservations[host] = true
                    add(Work(epoch.get(), host, true, append))
                    GuardIoAdmission.ACCEPTED
                }
            }
        }
        return reportAdmission(admission)
    }

    fun required(append: (() -> Boolean) -> Boolean): GuardIoAdmission {
        val admission = synchronized(lock) {
            when {
                !available -> GuardIoAdmission.UNAVAILABLE
                ordinaryQueued >= ordinaryCapacity -> GuardIoAdmission.FULL
                else -> {
                    add(Work(epoch.get(), null, false, append))
                    GuardIoAdmission.ACCEPTED
                }
            }
        }
        return reportAdmission(admission)
    }

    fun invalidate() {
        synchronized(lock) {
            epoch.incrementAndGet()
            invalidated += pending.size
            pending.clear()
            reservations.clear()
            ordinaryQueued = 0
        }
    }

    fun health(): GuardIoHealth = synchronized(lock) {
        GuardIoHealth(pending.size, reservations.size, rejected, failed, invalidated, available)
    }

    private fun add(work: Work) {
        pending.addLast(work)
        if (!work.terminal) ordinaryQueued += 1
        wakeups.trySend(Unit)
    }

    private fun reportAdmission(admission: GuardIoAdmission): GuardIoAdmission {
        if (admission == GuardIoAdmission.ACCEPTED) return admission
        synchronized(lock) { rejected += 1 }
        val failure = when (admission) {
            GuardIoAdmission.UNAVAILABLE -> GuardIoFailure.OWNER_STOPPED
            GuardIoAdmission.HOST_PENDING -> GuardIoFailure.HOST_PENDING
            else -> GuardIoFailure.CAPACITY
        }
        queueFailure(failure)
        return admission
    }

    private fun appendFailed() {
        synchronized(lock) { failed += 1 }
        queueFailure(GuardIoFailure.APPEND_FAILED)
    }

    private fun queueFailure(failure: GuardIoFailure) {
        synchronized(lock) { pendingFailures += failure }
        wakeups.trySend(Unit)
    }

    private fun flushFailures() {
        val failures = synchronized(lock) { pendingFailures.toList().also { pendingFailures.clear() } }
        failures.forEach(::notifyFailure)
    }

    private fun notifyFailure(failure: GuardIoFailure) {
        try {
            onFailure(failure)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            synchronized(lock) { failed += 1 }
        }
    }

    private fun notifyCapacityAvailable() {
        try {
            onCapacityAvailable()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            synchronized(lock) { failed += 1 }
            queueFailure(GuardIoFailure.CALLBACK_FAILED)
        }
    }
}
