package com.foxhole.core.runtime

import java.util.concurrent.atomic.AtomicLong

/**
 * The single monotonic generation source for the whole runtime control plane (Ф3c): UI transition
 * tokens, mailbox command sequence, command-owner ids — and, by injection, the Tor/I2pd process
 * lifecycles and bridge-writer epochs. Every staleness check in those domains is either equality
 * against the last value that domain minted, or an ordering comparison — both survive a shared
 * monotonic mint source unchanged, so one clock replaces six counters.
 */
internal object RuntimeGenerationClock {
    private val value = AtomicLong(0L)

    fun next(): Long = value.incrementAndGet()

    fun current(): Long = value.get()

    /** Never lets the clock fall below a restored generation (e.g. a persisted UI state's). */
    fun advanceTo(floor: Long) {
        while (true) {
            val current = value.get()
            if (current >= floor || value.compareAndSet(current, floor)) {
                return
            }
        }
    }
}
