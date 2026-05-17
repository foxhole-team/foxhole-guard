package com.foxhole.beta.vpn

import com.foxhole.beta.core.diagnostics.DiagnosticsLogger
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import java.util.PriorityQueue
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal enum class RuntimeCommandPriority(val value: Int) {
    NORMAL(10),
    STOP(100),
    KILL(1_000),
}

internal class RuntimeCommandActor(
    private val scope: CoroutineScope,
    private val diagnosticsLogger: DiagnosticsLogger?,
    private val emergencyKill: suspend (String) -> RuntimeKillResult,
) {
    private val sequence = AtomicLong(0)
    private val commands = Channel<QueuedRuntimeCommand>(Channel.UNLIMITED)
    private val closed = AtomicBoolean(false)

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
        if (!commands.trySend(command).isSuccess) {
            record(
                "runtime command rejected",
                "priority=${priority.name.lowercase()}",
                "reason=$reason",
                "closed=true",
            )
        }
    }

    fun close() {
        closed.set(true)
        record("runtime command actor closing")
        commands.close()
        worker.cancel()
        currentJob?.cancel()
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
                    RuntimeActorReceiveResult.CLEAR_RUNNING
                }
                commands.onReceiveCatching { result ->
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
                val next = pending.poll() ?: commands.receiveCatching().getOrNull()
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

    private suspend fun handleReceivedCommand(
        result: kotlinx.coroutines.channels.ChannelResult<QueuedRuntimeCommand>,
        running: RunningRuntimeCommand,
        pending: PriorityQueue<QueuedRuntimeCommand>,
        drainingPreemptedJobs: MutableList<Job>,
    ): RuntimeActorReceiveResult {
        val command = result.getOrNull()
        return when {
            command == null -> {
                running.job.cancel()
                RuntimeActorReceiveResult.CLOSE
            }
            command.priority >= RuntimeCommandPriority.STOP.value -> {
                preemptRunningCommand(
                    running = running,
                    command = command,
                    pending = pending,
                    drainingPreemptedJobs = drainingPreemptedJobs,
                )
                RuntimeActorReceiveResult.CLEAR_RUNNING
            }
            else -> {
                recordCommandEvent("runtime command queued", command)
                pending.offer(command)
                RuntimeActorReceiveResult.KEEP_RUNNING
            }
        }
    }

    private suspend fun drainPreemptedJobs(drainingPreemptedJobs: MutableList<Job>) {
        drainingPreemptedJobs.removeAll(Job::isCompleted)
        if (drainingPreemptedJobs.isEmpty()) {
            return
        }
        record("runtime waiting for preempted cleanup", "count=${drainingPreemptedJobs.size}")
        drainingPreemptedJobs.forEach { job -> job.join() }
        drainingPreemptedJobs.clear()
    }

    private fun startCommand(command: QueuedRuntimeCommand): RunningRuntimeCommand {
        val job =
            scope.launch(Dispatchers.Default + CoroutineName("RuntimeCommand:${command.priorityName}")) {
                runCommandSafely(command)
            }
        currentJob = job
        return RunningRuntimeCommand(job = job)
    }

    private suspend fun preemptRunningCommand(
        running: RunningRuntimeCommand,
        command: QueuedRuntimeCommand,
        pending: PriorityQueue<QueuedRuntimeCommand>,
        drainingPreemptedJobs: MutableList<Job>,
    ) {
        val removedNormalCommands = pending.removeIf { queued -> queued.priority < RuntimeCommandPriority.STOP.value }
        recordCommandEvent(
            headline = "runtime priority command queued",
            command = command,
            extra = "cleared_normal=$removedNormalCommands",
        )
        if (running.job.isActive) {
            running.job.cancel()
            if (currentJob == running.job) {
                currentJob = null
            }
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
    )

    private enum class RuntimeActorReceiveResult {
        KEEP_RUNNING,
        CLEAR_RUNNING,
        CLOSE,
    }
}
