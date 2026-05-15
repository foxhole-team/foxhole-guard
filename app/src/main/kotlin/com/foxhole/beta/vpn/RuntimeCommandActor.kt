package com.foxhole.beta.vpn

import com.foxhole.beta.core.diagnostics.DiagnosticsLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.CancellationException
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.TimeUnit
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
    private val queue = PriorityBlockingQueue<QueuedRuntimeCommand>()
    private val closed = AtomicBoolean(false)

    @Volatile
    private var currentJob: Job? = null

    private val worker =
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                val command = queue.poll(250L, TimeUnit.MILLISECONDS) ?: continue
                recordCommandEvent(
                    headline = "runtime command started",
                    command = command,
                )
                val job =
                    launch(Dispatchers.Default) {
                        command.block()
                    }
                currentJob = job
                try {
                    job.join()
                    val completionHeadline =
                        if (job.isCancelled) {
                            "runtime command cancelled"
                        } else {
                            "runtime command completed"
                        }
                    recordCommandEvent(
                        headline = completionHeadline,
                        command = command,
                    )
                } catch (error: CancellationException) {
                    recordCommandEvent(
                        headline = "runtime command cancelled",
                        command = command,
                        extra = "error=${error.javaClass.simpleName}",
                    )
                    throw error
                }
                if (currentJob == job) {
                    currentJob = null
                }
            }
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
        if (priority.value >= RuntimeCommandPriority.STOP.value) {
            preemptForPriorityCommand(command)
        } else {
            recordCommandEvent("runtime command queued", command)
        }
        queue.offer(command)
    }

    fun close() {
        closed.set(true)
        record("runtime command actor closing")
        worker.cancel()
        currentJob?.cancel()
        queue.clear()
    }

    private fun preemptForPriorityCommand(command: QueuedRuntimeCommand) {
        val removedNormalCommands = queue.removeIf { queued -> queued.priority < RuntimeCommandPriority.STOP.value }
        recordCommandEvent(
            headline = "runtime priority command queued",
            command = command,
            extra = "cleared_normal=$removedNormalCommands",
        )
        val active = currentJob?.takeIf { it.isActive }
        if (active != null) {
            active.cancel()
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
}
