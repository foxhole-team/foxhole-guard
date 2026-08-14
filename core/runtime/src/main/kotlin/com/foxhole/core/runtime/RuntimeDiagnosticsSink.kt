package com.foxhole.core.runtime

interface RuntimeDiagnosticsSink {
    fun record(tag: String, message: String)

    /**
     * The same line, marked as a failure by the code that is in the failure path.
     *
     * Defaulted to [record] so a sink that keeps no levels — a test double, a plain logcat sink —
     * stays correct without knowing about them. The one consumer that reads the level back is the
     * status block, and it prefers an entry unmarked to an entry guessed at by its wording.
     */
    fun recordFailure(tag: String, message: String) {
        record(tag, message)
    }

    fun recordStructured(
        tag: String,
        headline: String,
        vararg details: String?,
    )

    fun recordThrottled(
        tag: String,
        throttleKey: String,
        windowMs: Long,
        message: String,
    ) {
        record(tag, message)
    }
}
