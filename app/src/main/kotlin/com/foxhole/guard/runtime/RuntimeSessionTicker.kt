package com.foxhole.guard.runtime

import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

// One scheduler owns all session deadlines; tasks re-arm after completion and never overlap themselves.
internal class RuntimeSessionTicker(
    private val scope: CoroutineScope,
    private val loopDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val nowMs: () -> Long = SystemClock::elapsedRealtime,
    private val onError: (taskId: String, error: Throwable) -> Unit = { _, _ -> },
) {
    private class Task(
        val id: String,
        val intervalMs: () -> Long,
        val runOn: CoroutineDispatcher?,
        val action: suspend () -> Unit,
        var nextDueMs: Long,
        var running: Boolean = false,
        var runningJob: Job? = null,
    )

    private val lock = Any()
    private val tasks = LinkedHashMap<String, Task>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var loopJob: Job? = null

    fun register(
        id: String,
        fireImmediately: Boolean,
        intervalMs: () -> Long,
        runOn: CoroutineDispatcher? = null,
        action: suspend () -> Unit,
    ) {
        val replaced: Job?
        synchronized(lock) {
            replaced = tasks[id]?.runningJob
            val now = nowMs()
            val firstDue = if (fireImmediately) now else now + sanitizedInterval(intervalMs)
            tasks[id] = Task(id, intervalMs, runOn, action, firstDue)
            ensureLoopStartedLocked()
        }
        replaced?.cancel()
        wake.trySend(Unit)
    }

    fun unregister(id: String) {
        val inFlight: Job?
        val removed: Boolean
        synchronized(lock) {
            val task = tasks.remove(id)
            removed = task != null
            inFlight = task?.runningJob
        }
        inFlight?.cancel()
        if (removed) {
            wake.trySend(Unit)
        }
    }

    fun isRegistered(id: String): Boolean = synchronized(lock) { tasks.containsKey(id) }

    fun stop() {
        val inFlight: List<Job>
        synchronized(lock) {
            inFlight = tasks.values.mapNotNull(Task::runningJob)
            tasks.clear()
            loopJob?.cancel()
            loopJob = null
        }
        inFlight.forEach(Job::cancel)
        wake.trySend(Unit)
    }

    private fun ensureLoopStartedLocked() {
        if (loopJob?.isActive != true) {
            loopJob = scope.launch(loopDispatcher) { runLoop() }
        }
    }

    private suspend fun runLoop() {
        while (currentCoroutineContext().isActive) {
            val due = claimDueTasks() ?: break
            due.forEach { task ->
                if (task.runOn == null) {
                    runTaskSafely(task)
                    finishTask(task)
                } else {
                    dispatchTask(task)
                }
            }
            val sleepMs = sleepUntilNextDeadlineMs()
            if (sleepMs > 0L) {
                withTimeoutOrNull(sleepMs) { wake.receive() }
            }
        }
    }

    private fun claimDueTasks(): List<Task>? =
        synchronized(lock) {
            if (tasks.isEmpty()) {
                loopJob = null
                return@synchronized null
            }
            val now = nowMs()
            tasks.values
                .filter { task -> !task.running && task.nextDueMs <= now }
                .onEach { task -> task.running = true }
        }

    private fun sleepUntilNextDeadlineMs(): Long =
        synchronized(lock) {
            val nextDeadline =
                tasks.values
                    .filter { task -> !task.running }
                    .minOfOrNull(Task::nextDueMs)

                    ?: return@synchronized Long.MAX_VALUE
            (nextDeadline - nowMs()).coerceAtLeast(0L)
        }

    private fun dispatchTask(task: Task) {
        val job =
            scope.launch(task.runOn ?: loopDispatcher) {
                try {
                    runTaskSafely(task)
                } finally {
                    finishTask(task)
                    wake.trySend(Unit)
                }
            }
        synchronized(lock) {
            if (tasks[task.id] === task) {
                task.runningJob = job
            } else {
                job.cancel()
            }
        }
    }

    private fun finishTask(task: Task) {
        synchronized(lock) {
            task.running = false
            task.runningJob = null
            if (tasks[task.id] === task) {
                task.nextDueMs = nowMs() + sanitizedInterval(task.intervalMs)
            }
        }
    }

    private suspend fun runTaskSafely(task: Task) {
        try {
            task.action()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            onError(task.id, error)
        }
    }

    private fun sanitizedInterval(intervalMs: () -> Long): Long =
        intervalMs().coerceAtLeast(MIN_INTERVAL_MS)

    private companion object {
        // A misbehaving interval provider (zero/negative) must not turn the loop into a busy spin.
        const val MIN_INTERVAL_MS = 10L
    }
}
