package com.foxhole.core.runtime

interface RuntimeNativeStatsSource {
    fun captureStatsReadLease(): RuntimeStatsReadLease?
}

/** A captured owner read; its handle and generation stay inside the runtime module. */
class RuntimeStatsReadLease internal constructor(
    private val reader: () -> String?,
) {
    fun readStatsJson(): String? = reader()
}
