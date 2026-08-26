package com.foxhole.core.runtime

import java.util.concurrent.atomic.AtomicLong

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
