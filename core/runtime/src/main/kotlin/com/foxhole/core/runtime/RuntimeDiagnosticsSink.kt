package com.foxhole.core.runtime

interface RuntimeDiagnosticsSink {
    fun record(tag: String, message: String)

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
