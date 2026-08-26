package com.foxhole.core.runtime

internal class NativeForceStopPoisonLatch {
    @Volatile
    private var poisonedOutcome: NativeForceStopOutcome = NativeForceStopOutcome.NOT_ATTEMPTED

    val current: NativeForceStopOutcome
        get() = poisonedOutcome

    val processPoisoned: Boolean
        get() = poisonedOutcome.processPoisoned

    fun remember(
        outcome: NativeForceStopOutcome,
        onFirstPoisoned: (NativeForceStopOutcome) -> Unit = {},
    ): NativeForceStopOutcome {
        var firstPoisoned: NativeForceStopOutcome? = null
        val effective =
            synchronized(this) {
                if (outcome.processPoisoned) {
                    if (!poisonedOutcome.processPoisoned) {
                        firstPoisoned = outcome
                    }
                    poisonedOutcome = outcome
                }
                poisonedOutcome.takeIf(NativeForceStopOutcome::processPoisoned) ?: outcome
            }
        firstPoisoned?.let(onFirstPoisoned)
        return effective
    }
}

internal class NativeForceStopPoisonController(
    private val diagnosticsLogger: RuntimeDiagnosticsSink,
) {
    private val latch = NativeForceStopPoisonLatch()

    val current: NativeForceStopOutcome
        get() = latch.current

    val poisonedOutcome: NativeForceStopOutcome?
        get() = latch.current.takeIf(NativeForceStopOutcome::processPoisoned)

    val processPoisoned: Boolean
        get() = latch.processPoisoned

    fun remember(
        outcome: NativeForceStopOutcome,
        host: RuntimeServiceHost,
    ): NativeForceStopOutcome =
        latch.remember(outcome) { poisoned ->
            runCatching { host.onNativeProcessPoisoned(poisoned) }
                .onFailure { diagnosticsLogger.record("runtime", "native process poison observer failed") }
        }

    fun failure(
        operation: String,
        markRuntimeError: () -> Unit,
    ): Result<Unit> {
        markRuntimeError()
        return nativeForceStopPoisonedFailure(current, operation)
    }
}
