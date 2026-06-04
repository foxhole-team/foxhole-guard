package com.foxhole.beta.vpn

import com.foxhole.beta.core.diagnostics.DiagnosticsLogger
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
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal enum class RuntimeCommandPriority(val value: Int) {
    NORMAL(10),
    STOP(100),
    SWITCH(500),
    USER_STOP(800),
    KILL(1_000),
}

internal fun interface RuntimeCommandDiagnosticsRecorder {
    fun record(
        tag: String,
        headline: String,
        details: List<String?>,
    )
}

internal data class RuntimeCommandQueueSnapshot(
    val closed: Boolean,
    val normalBuffered: Int,
    val priorityBuffered: Int,
    val pending: Int,
    val running: Boolean,
    val runningPriority: String?,
    val runningReason: String?,
    val lastSequence: Long,
) {
    val commandQueueDepth: Int
        get() = normalBuffered + priorityBuffered + pending + if (running) 1 else 0

    companion object {
        val EMPTY =
            RuntimeCommandQueueSnapshot(
                closed = true,
                normalBuffered = 0,
                priorityBuffered = 0,
                pending = 0,
                running = false,
                runningPriority = null,
                runningReason = null,
                lastSequence = 0L,
            )
    }
}

@Suppress("TooManyFunctions")
internal class RuntimeCommandActor(
    private val scope: CoroutineScope,
    private val diagnosticsLogger: DiagnosticsLogger?,
    private val emergencyKill: suspend (String) -> RuntimeKillResult,
    private val diagnosticsRecorder: RuntimeCommandDiagnosticsRecorder? = null,
) {
    private val sequence = AtomicLong(0)
    private val normalCommands =
        Channel<QueuedRuntimeCommand>(
            capacity = COMMAND_BUFFER_CAPACITY,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    private val priorityCommands = Channel<QueuedRuntimeCommand>(Channel.UNLIMITED)
    private val closed = AtomicBoolean(false)
    private val normalBuffered = AtomicInteger(0)
    private val priorityBuffered = AtomicInteger(0)
    private val pendingDepth = AtomicInteger(0)
    private val runningSnapshot = AtomicReference<RunningRuntimeCommandSnapshot?>(null)

    @Volatile
    private var currentJob: Job? = null

    private val worker =
        scope.launch(Dispatchers.IO) {
            runActorLoop()
        }

    fun launch(
        priority: RuntimeCommandPriority,
        reason: String,
        block: suspend () -> Unit,
    ) {
        if (closed.get()) {
            record(
                "runtime command rejected",
                "priority=${priority.name.lowercase()}",
                "reason=$reason",
                "closed=true",
            )
            return
        }
        val command =
            QueuedRuntimeCommand(
                priority = priority.value,
                priorityName = priority.name.lowercase(),
                sequence = sequence.incrementAndGet(),
                reason = reason,
                block = block,
            )
        if (priority.value < RuntimeCommandPriority.STOP.value) {
            recordCommandEvent("runtime command queued", command)
        }
        val coalescedBufferedPriority =
            if (priority.value >= RuntimeCommandPriority.STOP.value) {
                coalesceBufferedPriorityCommands(command)
            } else {
                false
            }
        val sendResult =
            if (priority.value >= RuntimeCommandPriority.STOP.value) {
                priorityCommands.trySend(command)
            } else {
                normalCommands.trySend(command)
            }
        if (sendResult.isSuccess) {
            if (priority.value >= RuntimeCommandPriority.STOP.value) {
                priorityBuffered.incrementAndGet()
            } else {
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
            lastSequence = sequence.get(),
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

    @Suppress("NestedBlockDepth")
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
                } else {
                    if (next.priority < RuntimeCommandPriority.STOP.value) {
                        drainPreemptedJobs(drainingPreemptedJobs)
                    }
                    running = startCommand(next)
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
            command.priority >= RuntimeCommandPriority.STOP.value -> {
                handlePriorityCommand(
                    running = running,
                    command = command,
                    pending = pending,
                    drainingPreemptedJobs = drainingPreemptedJobs,
                )
            }
            running.command.priority < RuntimeCommandPriority.STOP.value &&
                running.command.reason == command.reason -> {
                val removedPending = pending.removeIf { queued ->
                    queued.priority < RuntimeCommandPriority.STOP.value && queued.reason == command.reason
                }
                pendingDepth.set(pending.size)
                val coalescedDetails =
                    listOf(
                        "running_reason=${running.command.reason}",
                        "removed_pending=$removedPending",
                    ).joinToString(" • ")
                recordCommandEvent(
                    headline = "runtime normal command coalesced",
                    command = command,
                    extra = coalescedDetails,
                )
                RuntimeActorReceiveResult.KEEP_RUNNING
            }
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

    private fun QueuedRuntimeCommand.shouldPreempt(running: QueuedRuntimeCommand): Boolean =
        priority > running.priority ||
            (
                priority == RuntimeCommandPriority.SWITCH.value &&
                    running.priority == RuntimeCommandPriority.SWITCH.value &&
                    reason != running.reason
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

    private suspend fun drainPreemptedJobs(drainingPreemptedJobs: MutableList<Job>) {
        drainingPreemptedJobs.removeAll(Job::isCompleted)
        if (drainingPreemptedJobs.isEmpty()) {
            return
        }
        record("runtime waiting for preempted cleanup", "count=${drainingPreemptedJobs.size}")
        val completed =
            withTimeoutOrNull(PREEMPTED_CLEANUP_DRAIN_TIMEOUT_MS) {
                drainingPreemptedJobs.forEach { job -> job.join() }
                true
            } == true
        if (!completed) {
            record(
                "runtime preempted cleanup timed out",
                "count=${drainingPreemptedJobs.count { job -> !job.isCompleted }}",
                "timeout_ms=$PREEMPTED_CLEANUP_DRAIN_TIMEOUT_MS",
            )
        }
        drainingPreemptedJobs.clear()
    }

    private fun startCommand(command: QueuedRuntimeCommand): RunningRuntimeCommand {
        val job =
            scope.launch(Dispatchers.Default + CoroutineName("RuntimeCommand:${command.priorityName}")) {
                runCommandSafely(command)
            }
        currentJob = job
        runningSnapshot.set(
            RunningRuntimeCommandSnapshot(
                priorityName = command.priorityName,
                reason = command.reason,
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
                extra = "error=${error.javaClass.simpleName}",
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

    private data class QueuedRuntimeCommand(
        val priority: Int,
        val priorityName: String,
        val sequence: Long,
        val reason: String,
        val block: suspend () -> Unit,
    ) : Comparable<QueuedRuntimeCommand> {
        override fun compareTo(other: QueuedRuntimeCommand): Int =
            compareValuesBy(this, other, { -it.priority }, { it.sequence })
    }

    private data class RunningRuntimeCommand(
        val job: Job,
        val command: QueuedRuntimeCommand,
    )

    private data class RunningRuntimeCommandSnapshot(
        val priorityName: String,
        val reason: String,
    )

    private data class PendingNormalCommandResult(
        val coalesced: Boolean,
        val droppedOldest: Boolean,
    )

    private enum class RuntimeActorReceiveResult {
        KEEP_RUNNING,
        CLEAR_RUNNING,
        CLOSE,
    }

    private companion object {
        const val COMMAND_BUFFER_CAPACITY = 64
        const val MAX_PENDING_NORMAL_COMMANDS = 16
        const val PREEMPTED_CLEANUP_DRAIN_TIMEOUT_MS = 1_500L
    }
}
