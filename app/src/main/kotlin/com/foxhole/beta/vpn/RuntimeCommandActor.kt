package com.foxhole.beta.vpn

import com.foxhole.beta.core.diagnostics.DiagnosticsLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

internal enum class RuntimeCommandPriority(val value: Int) {
    NORMAL(10),
    STOP(100),
    KILL(1_000),
}

internal class RuntimeCommandActor(
    private val scope: CoroutineScope,
    private val diagnosticsLogger: DiagnosticsLogger,
    private val emergencyKill: (String) -> RuntimeKillResult,
) {
    private val sequence = AtomicLong(0)
    private val queue = PriorityBlockingQueue<QueuedRuntimeCommand>()

    @Volatile
    private var currentJob: Job? = null

    private val worker =
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                val command = queue.poll(250L, TimeUnit.MILLISECONDS) ?: continue
                val job =
                    launch(Dispatchers.Default) {
                        command.block()
                    }
                currentJob = job
                job.join()
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
        if (priority.value >= RuntimeCommandPriority.STOP.value) {
            interruptCurrent(reason)
            scope.launch(Dispatchers.Default) { block() }
            return
        }
        queue.offer(
            QueuedRuntimeCommand(
                priority = priority.value,
                sequence = sequence.incrementAndGet(),
                block = block,
            ),
        )
    }

    fun close() {
        worker.cancel()
        currentJob?.cancel()
        queue.clear()
    }

    private fun interruptCurrent(reason: String) {
        val active = currentJob?.takeIf { it.isActive } ?: return
        active.cancel()
        diagnosticsLogger.recordStructured(
            "runtime",
            "priority command preempted active command",
            "reason=$reason",
        )
        emergencyKill("priority_command_preempt:$reason")
    }

    private data class QueuedRuntimeCommand(
        val priority: Int,
        val sequence: Long,
        val block: suspend () -> Unit,
    ) : Comparable<QueuedRuntimeCommand> {
        override fun compareTo(other: QueuedRuntimeCommand): Int =
            compareValuesBy(this, other, { -it.priority }, { it.sequence })
    }
}
