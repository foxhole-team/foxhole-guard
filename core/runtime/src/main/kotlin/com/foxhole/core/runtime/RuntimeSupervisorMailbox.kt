package com.foxhole.core.runtime

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import java.util.PriorityQueue
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@Suppress("TooManyFunctions")
internal class RuntimeSupervisorMailbox(
    private val scope: CoroutineScope,
    private val diagnosticsLogger: RuntimeDiagnosticsSink?,
    private val emergencyKill: suspend (String) -> RuntimeKillResult,
    private val diagnosticsRecorder: RuntimeCommandDiagnosticsRecorder? = null,
    switchCleanupTimeoutMs: Long = RuntimePreemptedCleanupBarrier.PREEMPTED_SWITCH_CLEANUP_TIMEOUT_MS,
) {
    // Sequence values mint from the shared RuntimeGenerationClock (FIFO tie-breaks need only
    // monotonicity); the local mirror keeps queueSnapshot().lastSequence scoped to THIS mailbox
    // instead of global clock activity.
    @Volatile
    private var lastMintedSequence = 0L
    private val normalBuffered = AtomicInteger(0)
    private val priorityBuffered = AtomicInteger(0)
    private val pendingDepth = AtomicInteger(0)
    private val normalCommands =
        Channel<QueuedRuntimeCommand>(
            capacity = COMMAND_BUFFER_CAPACITY,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
            onUndeliveredElement = {
                normalBuffered.decrementAndGet()
            },
        )

    // Priority commands must never be dropped, but an unlimited channel lets concurrent framework
    // callbacks outrun the actor and grow without a memory bound. Producers are serialized around
    // coalescing below; with four priority classes the retained frontier is smaller than this cap.
    private val priorityCommands = Channel<QueuedRuntimeCommand>(PRIORITY_COMMAND_BUFFER_CAPACITY)
    private val priorityEnqueueLock = Any()
    private val closed = AtomicBoolean(false)
    private val runningSnapshot = AtomicReference<RunningRuntimeCommandSnapshot?>(null)
    private val cleanupBarrier =
        RuntimePreemptedCleanupBarrier(
            recorder = RuntimeCleanupEventRecorder(::record),
            switchCleanupTimeoutMs = switchCleanupTimeoutMs,
        )

    @Volatile
    private var currentJob: Job? = null

    private val worker =
        scope.launch(Dispatchers.IO) {
            runActorLoop()
        }

    fun launch(
        priority: RuntimeCommandPriority,
        reason: String,
        owner: RuntimeCommandOwner? = null,
        block: suspend () -> Unit,
    ) {
        val actorClosed = closed.get()
        val ownerClosed = owner?.isClosed == true
        if (actorClosed || ownerClosed) {
            record(
                "runtime command rejected",
                "priority=${priority.name.lowercase()}",
                "reason=$reason",
                "closed=$actorClosed",
                "owner_closed=$ownerClosed",
                owner?.let { "owner=${it.id}" },
            )
            return
        }
        val command =
            QueuedRuntimeCommand(
                priority = priority.value,
                priorityName = priority.name.lowercase(),
                sequence = RuntimeGenerationClock.next().also { minted -> lastMintedSequence = minted },
                reason = reason,
                owner = owner,
                block = block,
            )
        if (priority.value < RuntimeCommandPriority.STOP.value) {
            recordCommandEvent("runtime command queued", command)
        }
        var coalescedBufferedPriority = false
        val sendResult =
            if (priority.value >= RuntimeCommandPriority.STOP.value) {
                synchronized(priorityEnqueueLock) {
                    coalescedBufferedPriority = coalesceBufferedPriorityCommands(command)
                    priorityCommands.trySend(command).also { result ->
                        if (result.isSuccess) priorityBuffered.incrementAndGet()
                    }
                }
            } else {
                normalCommands.trySend(command)
            }
        if (sendResult.isSuccess) {
            if (priority.value < RuntimeCommandPriority.STOP.value) {
                normalBuffered.incrementAndGet()
            }
        }
        if (!sendResult.isSuccess) {
            record(
                "runtime command rejected",
                "priority=${priority.name.lowercase()}",
                "reason=$reason",
                "closed=${closed.get()}",
                "queue_full=${!closed.get() && !sendResult.isClosed}",
                "buffer_rejected=true",
            )
        } else if (coalescedBufferedPriority) {
            recordCommandEvent(
                headline = "runtime priority command coalesced",
                command = command,
                extra = "buffered=true",
            )
        }
    }

    fun queueSnapshot(): RuntimeCommandQueueSnapshot {
        val running = runningSnapshot.get()
        return RuntimeCommandQueueSnapshot(
            closed = closed.get(),
            normalBuffered = normalBuffered.get().coerceAtLeast(0),
            priorityBuffered = priorityBuffered.get().coerceAtLeast(0),
            pending = pendingDepth.get().coerceAtLeast(0),
            running = running != null,
            runningPriority = running?.priorityName,
            runningReason = running?.reason,
            lastSequence = lastMintedSequence,
        )
    }

    fun close() {
        closed.set(true)
        record("runtime command actor closing")
        normalCommands.close()
        priorityCommands.close()
        worker.cancel()
        currentJob?.cancel()
        runningSnapshot.set(null)
    }

    fun cancelOwner(owner: RuntimeCommandOwner) {
        val running = runningSnapshot.get()
        if (running?.owner === owner) {
            running.job.cancel()
        }
        record(
            "runtime command owner closed",
            "owner=${owner.id}",
            "mode=${owner.mode.name.lowercase()}",
            "label=${owner.label}",
            "running=${running?.owner === owner}",
        )
    }

    @Suppress("CyclomaticComplexMethod", "NestedBlockDepth")
    private suspend fun runActorLoop() {
        val pending = PriorityQueue<QueuedRuntimeCommand>()
        val drainingPreemptedJobs = mutableListOf<Job>()
        suspend fun awaitRunningCommand(active: RunningRuntimeCommand): RuntimeActorReceiveResult =
            select {
                active.job.onJoin {
                    if (currentJob == active.job) {
                        currentJob = null
                    }
                    runningSnapshot.set(null)
                    RuntimeActorReceiveResult.CLEAR_RUNNING
                }
                priorityCommands.onReceiveCatching { result ->
                    handleReceivedCommand(
                        result = result,
                        running = active,
                        pending = pending,
                        drainingPreemptedJobs = drainingPreemptedJobs,
                    )
                }
                normalCommands.onReceiveCatching { result ->
                    handleReceivedCommand(
                        result = result,
                        running = active,
                        pending = pending,
                        drainingPreemptedJobs = drainingPreemptedJobs,
                    )
                }
            }

        var running: RunningRuntimeCommand? = null
        var acceptingCommands = true
        while (acceptingCommands && !closed.get()) {
            val active = running
            if (active == null) {
                val next =
                    priorityCommands.tryReceive().getOrNull()?.also(::onCommandReceived)
                        ?: pending.poll()?.also { pendingDepth.set(pending.size) }
                        ?: receiveNextCommand()
                if (next == null) {
                    acceptingCommands = false
                } else if (next.owner?.isClosed == true) {
                    recordCommandEvent("runtime command skipped for closed owner", next)
                } else {
                    val preemptedJobsForSwitch =
                        if (next.priority == RuntimeCommandPriority.SWITCH.value) {
                            drainingPreemptedJobs.filterNot(Job::isCompleted)
                        } else {
                            emptyList()
                        }
                    if (next.priority < RuntimeCommandPriority.STOP.value) {
                        cleanupBarrier.drain(drainingPreemptedJobs)
                    }
                    running = startCommand(next, preemptedJobsForSwitch)
                }
            } else {
                when (awaitRunningCommand(active)) {
                    RuntimeActorReceiveResult.KEEP_RUNNING -> Unit
                    RuntimeActorReceiveResult.CLEAR_RUNNING -> running = null
                    RuntimeActorReceiveResult.CLOSE -> {
                        running = null
                        acceptingCommands = false
                    }
                }
            }
        }
        running?.job?.cancel()
        pending.clear()
        drainingPreemptedJobs.clear()
    }

    private suspend fun receiveNextCommand(): QueuedRuntimeCommand? {
        priorityCommands.tryReceive().getOrNull()?.let { command ->
            onCommandReceived(command)
            return command
        }
        return select {
            priorityCommands.onReceiveCatching { result -> result.getOrNull()?.also(::onCommandReceived) }
            normalCommands.onReceiveCatching { result -> result.getOrNull()?.also(::onCommandReceived) }
        }
    }

    private suspend fun handleReceivedCommand(
        result: kotlinx.coroutines.channels.ChannelResult<QueuedRuntimeCommand>,
        running: RunningRuntimeCommand,
        pending: PriorityQueue<QueuedRuntimeCommand>,
        drainingPreemptedJobs: MutableList<Job>,
    ): RuntimeActorReceiveResult {
        val command = result.getOrNull()
        if (command != null) {
            onCommandReceived(command)
        }
        return when {
            command == null -> {
                running.job.cancel()
                RuntimeActorReceiveResult.CLOSE
            }
            command.owner?.isClosed == true -> {
                recordCommandEvent("runtime command skipped for closed owner", command)
                RuntimeActorReceiveResult.KEEP_RUNNING
            }
            command.priority >= RuntimeCommandPriority.STOP.value -> {
                handlePriorityCommand(
                    running = running,
                    command = command,
                    pending = pending,
                    drainingPreemptedJobs = drainingPreemptedJobs,
                )
            }
            // A same-reason normal command arriving mid-run is NOT dropped: the running pass may
            // have read settings older than this request (e.g. a reload that loaded its session
            // before the latest Apps-screen toggle landed), so swallowing it latched stale config
            // until a manual reconnect. It trails instead — enqueuePendingNormalCommand keeps at
            // most ONE pending per reason, so a rapid toggle burst collapses to two passes total:
            // the running one and a single trailing latest-wins pass that re-reads fresh settings.
            else -> {
                val result = enqueuePendingNormalCommand(command, pending)
                pendingDepth.set(pending.size)
                val extraDetails =
                    buildList {
                        if (result.coalesced) {
                            add("coalesced=true")
                        }
                        if (result.droppedOldest) {
                            add("dropped_oldest=true")
                        }
                    }.joinToString(" • ").ifBlank { null }
                recordCommandEvent(
                    headline = "runtime command queued",
                    command = command,
                    extra = extraDetails,
                )
                RuntimeActorReceiveResult.KEEP_RUNNING
            }
        }
    }

    private fun enqueuePendingNormalCommand(
        command: QueuedRuntimeCommand,
        pending: PriorityQueue<QueuedRuntimeCommand>,
    ): PendingNormalCommandResult {
        val coalesced =
            pending.removeIf { queued ->
                queued.priority < RuntimeCommandPriority.STOP.value && queued.reason == command.reason
            }
        val droppedOldest =
            if (pending.count { queued -> queued.priority < RuntimeCommandPriority.STOP.value } >= MAX_PENDING_NORMAL_COMMANDS) {
                removeOldestNormalCommand(pending) != null
            } else {
                false
            }
        pending.offer(command)
        pendingDepth.set(pending.size)
        return PendingNormalCommandResult(
            coalesced = coalesced,
            droppedOldest = droppedOldest,
        )
    }

    private fun removeOldestNormalCommand(pending: PriorityQueue<QueuedRuntimeCommand>): QueuedRuntimeCommand? {
        val oldest =
            pending
                .filter { command -> command.priority < RuntimeCommandPriority.STOP.value }
                .minByOrNull(QueuedRuntimeCommand::sequence)
        if (oldest != null) {
            pending.remove(oldest)
        }
        return oldest
    }

    private suspend fun handlePriorityCommand(
        running: RunningRuntimeCommand,
        command: QueuedRuntimeCommand,
        pending: PriorityQueue<QueuedRuntimeCommand>,
        drainingPreemptedJobs: MutableList<Job>,
    ): RuntimeActorReceiveResult =
        when {
            command.shouldPreempt(running.command) -> {
                preemptRunningCommand(
                    running = running,
                    command = command,
                    pending = pending,
                    drainingPreemptedJobs = drainingPreemptedJobs,
                )
                RuntimeActorReceiveResult.CLEAR_RUNNING
            }
            !command.isCoalescedByRunning(running.command) -> {
                val removedSupersededPriority =
                    pending.removeIf { queued -> command.supersedesBufferedPriority(queued) }
                pending.offer(command)
                pendingDepth.set(pending.size)
                val queuedDetails =
                    listOf(
                        "running_priority=${running.command.priorityName}",
                        "running_reason=${running.command.reason}",
                        "removed_pending=$removedSupersededPriority",
                    ).joinToString(" • ")
                recordCommandEvent(
                    headline = "runtime priority command queued",
                    command = command,
                    extra = queuedDetails,
                )
                RuntimeActorReceiveResult.KEEP_RUNNING
            }
            else -> {
                val removedPendingPriority =
                    pending.removeIf { queued ->
                        command.supersedesBufferedPriority(queued)
                    }
                pendingDepth.set(pending.size)
                recordCommandEvent(
                    headline = "runtime priority command coalesced",
                    command = command,
                    extra = priorityCoalescedDetails(running, removedPendingPriority),
                )
                RuntimeActorReceiveResult.KEEP_RUNNING
            }
        }

    // A running transition (SWITCH and above) is atomic: only a user-initiated stop or a kill may
    // interrupt it. Everything else queues behind it — a newer switch replaces the pending one
    // (latest wins) instead of force-killing the runtime mid-transition. Running NORMAL/STOP work
    // stays preemptible by any higher class, e.g. Stop cancelling an in-flight reload.
    private fun QueuedRuntimeCommand.shouldPreempt(running: QueuedRuntimeCommand): Boolean =
        priority > running.priority &&
            (
                running.priority < RuntimeCommandPriority.SWITCH.value ||
                    priority >= RuntimeCommandPriority.USER_STOP.value
                )

    private fun QueuedRuntimeCommand.isCoalescedByRunning(running: QueuedRuntimeCommand): Boolean =
        when (running.priority) {
            RuntimeCommandPriority.KILL.value ->
                priority != RuntimeCommandPriority.SWITCH.value && priority <= running.priority
            RuntimeCommandPriority.USER_STOP.value ->
                priority == RuntimeCommandPriority.USER_STOP.value ||
                    priority == RuntimeCommandPriority.STOP.value
            RuntimeCommandPriority.STOP.value ->
                priority == RuntimeCommandPriority.STOP.value
            RuntimeCommandPriority.SWITCH.value ->
                priority == RuntimeCommandPriority.SWITCH.value && reason == running.reason
            else -> false
        }

    private fun priorityCoalescedDetails(
        running: RunningRuntimeCommand,
        removedPendingPriority: Boolean,
    ): String =
        listOf(
            "running_priority=${running.command.priorityName}",
            "running_reason=${running.command.reason}",
            "removed_pending=$removedPendingPriority",
        ).joinToString(" • ")

    private fun startCommand(
        command: QueuedRuntimeCommand,
        preemptedJobsForSwitch: List<Job> = emptyList(),
    ): RunningRuntimeCommand {
        val job =
            scope.launch(Dispatchers.Default + CoroutineName("RuntimeCommand:${command.priorityName}")) {
                if (preemptedJobsForSwitch.isNotEmpty() &&
                    !cleanupBarrier.awaitBeforeSwitch(preemptedJobsForSwitch)
                ) {
                    // A hung cleanup must not eat the user's transition: the preempted runtime is
                    // already force-killed, so dropping the command here would park the mailbox on
                    // a dead snapshot forever. Latest wins — run the switch anyway.
                    recordCommandEvent(
                        headline = "runtime switch proceeding after cleanup timeout",
                        command = command,
                    )
                }
                runCommandSafely(command)
            }
        currentJob = job
        runningSnapshot.set(
            RunningRuntimeCommandSnapshot(
                priorityName = command.priorityName,
                reason = command.reason,
                owner = command.owner,
                job = job,
            ),
        )
        return RunningRuntimeCommand(job = job, command = command)
    }

    private suspend fun preemptRunningCommand(
        running: RunningRuntimeCommand,
        command: QueuedRuntimeCommand,
        pending: PriorityQueue<QueuedRuntimeCommand>,
        drainingPreemptedJobs: MutableList<Job>,
    ) {
        val removedNormalCommands = pending.removeIf { queued -> queued.priority < RuntimeCommandPriority.STOP.value }
        val removedSupersededPriorityCommands =
            pending.removeIf { queued ->
                command.supersedesBufferedPriority(queued)
            }
        pendingDepth.set(pending.size)
        val removedBufferedNormalCommands = clearBufferedNormalCommands()
        val extra =
            "cleared_normal=${removedNormalCommands || removedBufferedNormalCommands > 0}, " +
                "cleared_priority=$removedSupersededPriorityCommands"
        recordCommandEvent(
            headline = "runtime priority command queued",
            command = command,
            extra = extra,
        )
        if (running.job.isActive) {
            running.job.cancel()
            if (currentJob == running.job) {
                currentJob = null
            }
            runningSnapshot.set(null)
            drainingPreemptedJobs += running.job
            recordCommandEvent(
                headline = "runtime command preempted",
                command = command,
            )
            val kill = emergencyKill("priority_command_preempt:${command.reason}")
            recordCommandEvent(
                headline = "runtime command force-killed",
                command = command,
                extra = listOf(
                    "kill_reason=${kill.reason}",
                    "close_detached=${kill.closeDetached}",
                ).joinToString(" • "),
            )
        }
        pending.offer(command)
        pendingDepth.set(pending.size)
    }

    private fun clearBufferedNormalCommands(): Int {
        var removed = 0
        while (normalCommands.tryReceive().isSuccess) {
            removed += 1
            normalBuffered.decrementAndGet()
        }
        return removed
    }

    private fun coalesceBufferedPriorityCommands(command: QueuedRuntimeCommand): Boolean {
        var removed = false
        val retained = mutableListOf<QueuedRuntimeCommand>()
        while (true) {
            val queued = priorityCommands.tryReceive().getOrNull() ?: break
            priorityBuffered.decrementAndGet()
            if (command.supersedesBufferedPriority(queued)) {
                removed = true
            } else {
                retained += queued
            }
        }
        retained.forEach { queued ->
            if (!priorityCommands.trySend(queued).isSuccess) {
                recordCommandEvent(
                    headline = "runtime priority command dropped during coalesce restore",
                    command = queued,
                )
            } else {
                priorityBuffered.incrementAndGet()
            }
        }
        return removed
    }

    private fun onCommandReceived(command: QueuedRuntimeCommand) {
        if (command.priority >= RuntimeCommandPriority.STOP.value) {
            priorityBuffered.decrementAndGet()
        } else {
            normalBuffered.decrementAndGet()
        }
    }

    private fun QueuedRuntimeCommand.supersedesBufferedPriority(queued: QueuedRuntimeCommand): Boolean =
        when (priority) {
            RuntimeCommandPriority.KILL.value ->
                queued.priority >= RuntimeCommandPriority.STOP.value &&
                    queued.priority <= priority
            RuntimeCommandPriority.USER_STOP.value ->
                queued.priority >= RuntimeCommandPriority.STOP.value &&
                    queued.priority <= priority
            RuntimeCommandPriority.SWITCH.value ->
                queued.priority <= priority &&
                    queued.priority >= RuntimeCommandPriority.STOP.value
            RuntimeCommandPriority.STOP.value ->
                queued.priority >= RuntimeCommandPriority.STOP.value &&
                    queued.priority <= RuntimeCommandPriority.SWITCH.value
            else ->
                queued.priority <= priority && queued.reason == reason
        }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun runCommandSafely(command: QueuedRuntimeCommand) {
        if (command.owner?.isClosed == true) {
            recordCommandEvent("runtime command skipped for closed owner", command)
            return
        }
        recordCommandEvent("runtime command started", command)
        try {
            command.block()
            recordCommandEvent("runtime command completed", command)
        } catch (cancelled: CancellationException) {
            recordCommandEvent(
                headline = "runtime command cancelled",
                command = command,
                extra = "error=${cancelled.javaClass.simpleName}",
            )
            throw cancelled
        } catch (error: Throwable) {
            recordCommandEvent(
                headline = "runtime command failed",
                command = command,
                extra = "error=${error.javaClass.simpleName} message=${error.message.orEmpty()}",
            )
        }
    }

    private fun recordCommandEvent(
        headline: String,
        command: QueuedRuntimeCommand,
        extra: String? = null,
    ) {
        record(
            headline,
            "priority=${command.priorityName}",
            "reason=${command.reason}",
            "sequence=${command.sequence}",
            command.owner?.let { "owner=${it.id}" },
            extra,
        )
    }

    private fun record(
        headline: String,
        vararg details: String?,
    ) {
        diagnosticsRecorder?.record("runtime", headline, details.toList())
        diagnosticsLogger?.recordStructured(
            "runtime",
            headline,
            *details,
        )
    }

    private companion object {
        const val COMMAND_BUFFER_CAPACITY = 64
        const val PRIORITY_COMMAND_BUFFER_CAPACITY = 8
        const val MAX_PENDING_NORMAL_COMMANDS = 16
    }
}

internal data class QueuedRuntimeCommand(
    val priority: Int,
    val priorityName: String,
    val sequence: Long,
    val reason: String,
    val owner: RuntimeCommandOwner?,
    val block: suspend () -> Unit,
) : Comparable<QueuedRuntimeCommand> {
    override fun compareTo(other: QueuedRuntimeCommand): Int =
        compareValuesBy(this, other, { -it.priority }, { it.sequence })
}

internal data class RunningRuntimeCommand(
    val job: Job,
    val command: QueuedRuntimeCommand,
)

internal data class RunningRuntimeCommandSnapshot(
    val priorityName: String,
    val reason: String,
    val owner: RuntimeCommandOwner?,
    val job: Job,
)

internal data class PendingNormalCommandResult(
    val coalesced: Boolean,
    val droppedOldest: Boolean,
)

internal enum class RuntimeActorReceiveResult {
    KEEP_RUNNING,
    CLEAR_RUNNING,
    CLOSE,
}

internal fun interface RuntimeCleanupEventRecorder {
    fun record(
        headline: String,
        vararg details: String?,
    )
}

internal class RuntimePreemptedCleanupBarrier(
    private val recorder: RuntimeCleanupEventRecorder,
    private val switchCleanupTimeoutMs: Long = PREEMPTED_SWITCH_CLEANUP_TIMEOUT_MS,
) {
    suspend fun drain(jobs: MutableList<Job>) {
        jobs.removeAll(Job::isCompleted)
        if (jobs.isEmpty()) {
            return
        }
        recorder.record("runtime waiting for preempted cleanup", "count=${jobs.size}")
        val completed =
            withTimeoutOrNull(PREEMPTED_CLEANUP_DRAIN_TIMEOUT_MS) {
                jobs.forEach { job -> job.join() }
                true
            } == true
        if (!completed) {
            recorder.record(
                "runtime preempted cleanup timed out",
                "count=${jobs.count { job -> !job.isCompleted }}",
                "timeout_ms=$PREEMPTED_CLEANUP_DRAIN_TIMEOUT_MS",
            )
        }
        jobs.clear()
    }

    suspend fun awaitBeforeSwitch(jobs: List<Job>): Boolean {
        recorder.record("runtime switch waiting for preempted cleanup", "count=${jobs.size}")
        val completed =
            withTimeoutOrNull(switchCleanupTimeoutMs) {
                jobs.forEach { job -> job.join() }
                true
            } == true
        if (!completed) {
            recorder.record(
                "runtime switch cleanup timed out",
                "count=${jobs.count { job -> !job.isCompleted }}",
                "timeout_ms=$switchCleanupTimeoutMs",
            )
        }
        return completed
    }

    companion object {
        const val PREEMPTED_CLEANUP_DRAIN_TIMEOUT_MS = 1_500L
        const val PREEMPTED_SWITCH_CLEANUP_TIMEOUT_MS = 20_000L
    }
}
