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

/**
 * One timer coroutine that drives every periodic session task, replacing a pile of independent
 * `while { work; delay(interval) }` loops (Ф3e). Each registered task keeps its own next-due
 * deadline; the single loop sleeps until the NEAREST deadline, fires the tasks that came due and
 * goes back to sleep — so N samplers cost one timer, and a slow-cadence task folded behind a
 * fast-cadence one adds zero extra wakeups.
 *
 * Scheduling parity with the loops this replaces: every old loop was sequential, so its period was
 * `work + delay(interval)` with the interval read AFTER the work. The ticker reproduces that
 * exactly — a task's next deadline is set when its action COMPLETES, from a fresh [Task.intervalMs]
 * read (adaptive intervals such as the health-probe backoff therefore see the state the action just
 * produced).
 *
 * Two task shapes:
 * - `runOn = null` — the action runs inline on the loop. Only for fast, non-blocking samplers: an
 *   inline action delays every later deadline until it returns, and is not cancelled by
 *   [unregister] once started (a sample is never torn mid-write).
 * - `runOn = dispatcher` — the action is launched into [scope] on that dispatcher; the loop never
 *   waits for it. The task cannot overlap itself (it is re-armed only on completion) and
 *   [unregister]/[stop] cancel an in-flight run, matching the `job.cancel()` semantics of the
 *   standalone loops this replaces. Use for anything that does I/O or can suspend for long
 *   (health probe, watchdog heal path).
 *
 * A task throwing is caught per-task ([onError]) and never stops the loop or the other tasks.
 * The loop parks on a conflated wake channel with a deadline timeout; register/unregister send a
 * wake, so schedule changes take effect immediately and an empty ticker costs nothing.
 */
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

    /**
     * Adds a periodic task; re-registering an id replaces the task (cancelling its in-flight run,
     * if dispatched). [fireImmediately] mirrors the two loop shapes being replaced: `true` =
     * "work then delay" (first run now); `false` = "delay then work" (first run one interval from
     * now).
     */
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
                // Parks until the nearest deadline OR a wake (register/unregister/task completion).
                // The channel is conflated: a pending wake makes receive() return at once — one
                // cheap recompute, never a busy spin, and a fresh schedule change is never missed.
                withTimeoutOrNull(sleepMs) { wake.receive() }
            }
        }
    }

    /** Marks due tasks running and returns them; null when the ticker has no tasks at all. */
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
                    // Every task is mid-run (or the map just emptied): nothing to time out for —
                    // park until a completion/registration wake.
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
            // Register-replace or unregister may have raced the launch; cancel instead of tracking
            // a job for a task this ticker no longer owns.
            if (tasks[task.id] === task) {
                task.runningJob = job
            } else {
                job.cancel()
            }
        }
    }

    /** Re-arms the task off its completion time — unless it was unregistered or replaced mid-run. */
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
