package com.foxhole.guard.runtime

import com.foxhole.core.runtime.RuntimeNetworkChangeLease
import com.foxhole.core.runtime.RuntimeNetworkChangeOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

internal class UpstreamNetworkChangeMailbox(
    private val scope: CoroutineScope,
    private val isOwnerCurrent: () -> Boolean,
    private val onFailure: () -> Unit,
    private val retryDelay: suspend () -> Unit = { delay(100L) },
) {
    private val lock = Any()
    private var pending: RuntimeNetworkChangeLease? = null
    private var worker: Job? = null
    private var workerToken: Any? = null
    private var closed = false

    fun offer(lease: RuntimeNetworkChangeLease): Boolean {
        if (!ownerActive()) {
            lease.abandon()
            return false
        }
        val offered = synchronized(lock) {
            if (closed) return@synchronized null
            val replaced = pending
            pending = lease
            val toStart = if (worker != null) null else newWorkerLocked()
            replaced to toStart
        }
        if (offered == null) {
            lease.abandon()
            return false
        }
        offered.first?.abandon()
        offered.second?.start()
        return true
    }

    fun close() {
        val (toCancel, toAbandon) = synchronized(lock) {
            closed = true
            val abandoned = pending
            pending = null
            worker to abandoned
        }
        toAbandon?.abandon()
        toCancel?.cancel()
    }

    private fun newWorkerLocked(): Job {
        val token = Any()
        return scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) { drain() }.also {
            workerToken = token
            worker = it
            it.invokeOnCompletion { cause -> finish(token, cause) }
        }
    }

    private suspend fun drain() {
        while (ownerActive()) {
            val next = synchronized(lock) { pending.also { pending = null } } ?: return
            submit(next)
        }
    }

    private fun finish(token: Any, cause: Throwable?) {
        val active = ownerActive()
        val settled = synchronized(lock) {
            if (workerToken !== token) return@synchronized null
            worker = null
            workerToken = null
            val canRestart = cause == null && active
            if (canRestart && !closed && pending != null) {
                null to newWorkerLocked()
            } else {
                val abandoned = pending
                pending = null
                abandoned to null
            }
        } ?: return
        settled.first?.abandon()
        settled.second?.start()
    }

    private suspend fun submit(lease: RuntimeNetworkChangeLease) {
        if (attempt(lease) != RuntimeNetworkChangeOutcome.FAILED) return
        reportFailure()
        retryDelay()
        currentCoroutineContext().ensureActive()
        val retry = synchronized(lock) { pending == null && !closed }
        if (!retry || !ownerActive()) return
        if (attempt(lease) == RuntimeNetworkChangeOutcome.FAILED) reportFailure()
    }

    private fun reportFailure() {
        try {
            onFailure()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Unit
        }
    }

    private suspend fun attempt(lease: RuntimeNetworkChangeLease): RuntimeNetworkChangeOutcome =
        try {
            currentCoroutineContext().ensureActive()
            lease.submit()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            RuntimeNetworkChangeOutcome.FAILED
        }

    private fun ownerActive(): Boolean =
        scope.coroutineContext[Job]?.isActive == true && isOwnerCurrent()
}
