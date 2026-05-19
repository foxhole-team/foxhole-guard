package com.foxhole.beta.vpn

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicLong

internal class RuntimeGenerationGuard(
    private val diagnosticsLogger: RuntimeDiagnosticsSink,
) {
    private val runtimeGeneration = AtomicLong(0L)

    fun current(): Long = runtimeGeneration.get()

    fun next(reason: String): Long =
        runtimeGeneration.incrementAndGet().also { generation ->
            diagnosticsLogger.recordStructured(
                "runtime",
                "runtime generation advanced",
                "reason=$reason",
                "generation=$generation",
            )
        }

    fun isCurrent(generation: Long): Boolean = runtimeGeneration.get() == generation

    suspend fun ensureCurrent(generation: Long) {
        currentCoroutineContext().ensureActive()
        if (!isCurrent(generation)) {
            throw CancellationException("runtime generation superseded")
        }
    }
}
