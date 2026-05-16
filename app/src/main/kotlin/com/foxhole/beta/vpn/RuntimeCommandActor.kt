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
    private val emergencyKill: (String) -> RuntimeKillResult,
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

    private suspend fun runActorLoop() {
        val pending = PriorityQueue<QueuedRuntimeCommand>()
        var running: RunningRuntimeCommand? = null
        var acceptingCommands = true
        while (acceptingCommands && !closed.get()) {
            if (running == null) {
                val next = pending.poll() ?: receiveNextCommandOrNull()
                if (next == null) {
                    acceptingCommands = false
                } else {
                    running = startCommand(next)
                }
            } else {
                val active = running
                select {
                    active.job.onJoin {
                        if (currentJob == active.job) {
                            currentJob = null
                        }
                        running = null
                    }
                    commands.onReceiveCatching { result ->
                        val command = result.getOrNull()
                        if (command == null) {
                            active.job.cancel()
                            running = null
                            acceptingCommands = false
                        } else if (command.priority >= RuntimeCommandPriority.STOP.value) {
                            preemptRunningCommand(
                                running = active,
                                command = command,
                                pending = pending,
                            )
                            running = null
                        } else {
                            recordCommandEvent("runtime command queued", command)
                            pending.offer(command)
                        }
                    }
                }
            }
        }
        running?.job?.cancel()
        pending.clear()
    }

    private suspend fun receiveNextCommandOrNull(): QueuedRuntimeCommand? =
        commands.receiveCatching().getOrNull()

    private fun startCommand(command: QueuedRuntimeCommand): RunningRuntimeCommand {
        val job =
            scope.launch(Dispatchers.Default + CoroutineName("RuntimeCommand:${command.priorityName}")) {
                runCommandSafely(command)
            }
        currentJob = job
        return RunningRuntimeCommand(job = job)
    }

    private fun preemptRunningCommand(
        running: RunningRuntimeCommand,
        command: QueuedRuntimeCommand,
        pending: PriorityQueue<QueuedRuntimeCommand>,
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
            recordCommandEvent(
                headline = "runtime command preempted",
                command = command,
            )
            val kill = emergencyKill("priority_command_preempt:${command.reason}")
            recordCommandEvent(
                headline = "runtime command force-killed",
                command = command,
                extra = "kill_reason=${kill.reason}",
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
}
