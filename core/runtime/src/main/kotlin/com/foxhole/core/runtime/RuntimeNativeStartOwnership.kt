package com.foxhole.core.runtime

internal class RuntimeNativeStartOwnership(
    diagnosticsLogger: RuntimeDiagnosticsSink,
    private val tunOwner: FoxCoreTunOwner,
) {
    private val generationGuard = RuntimeGenerationGuard(diagnosticsLogger)
    private val diagnostics = diagnosticsLogger

    fun open(reason: String): Long = generationGuard.next(reason)

    fun invalidate(
        generation: Long,
        reason: String,
    ): Boolean = generationGuard.invalidateIfCurrent(generation, reason)

    fun isCurrent(generation: Long): Boolean = generationGuard.isCurrent(generation)

    fun commitIfCurrent(
        generation: Long,
        publish: () -> Unit,
    ): Boolean = generationGuard.commitIfCurrent(generation, publish)

    fun rejectBeforeNative(
        stage: String,
        markSuperseded: () -> Unit,
    ): Result<Unit> {
        markSuperseded()
        diagnostics.recordStructured(
            "foxcore",
            "stale native start skipped",
            "stage=$stage",
        )
        return foxCoreFailure(FoxCoreRuntimeFailure.NATIVE_START_FAILED)
    }

    suspend fun discardLateSession(
        session: ActiveFoxCoreSession,
        releaseNative: suspend (Long) -> Boolean,
    ): LateNativeSessionDiscard {
        val nativeReleased = releaseNative(session.handle)
        val nativeTunProbe = probeTunDescriptor(session.nativeTunFd)
        val tunClosed =
            if (shouldCloseMasterTun(nativeTunProbe)) {
                tunOwner.close(session.tun)
            } else {
                false
            }
        tunOwner.recordOwnershipAfterClose(
            session = session,
            nativeTunProbe = nativeTunProbe,
            masterTunClosed = tunClosed,
            reason = "superseded_start",
        )
        diagnostics.recordStructured(
            "foxcore",
            "stale native start discarded",
            "handle_released=$nativeReleased",
            "tun_closed=$tunClosed",
        )
        return LateNativeSessionDiscard(nativeReleased, tunClosed)
    }
}

internal data class LateNativeSessionDiscard(
    val nativeReleased: Boolean,
    val tunClosed: Boolean,
)
