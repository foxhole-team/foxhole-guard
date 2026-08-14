package com.foxhole.core.runtime

enum class RuntimeCommandPriority(val value: Int) {
    NORMAL(10),
    STOP(100),
    SWITCH(500),
    USER_STOP(800),
    KILL(1_000),
}

fun interface RuntimeCommandDiagnosticsRecorder {
    fun record(
        tag: String,
        headline: String,
        details: List<String?>,
    )
}

data class RuntimeCommandQueueSnapshot(
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
