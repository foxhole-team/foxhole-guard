package com.foxhole.guard.core.webapps

import java.util.concurrent.atomic.AtomicLong

internal class WebAppOpenRequests {
    private val generation = AtomicLong()

    fun next(): Long = generation.incrementAndGet()

    fun owns(request: Long): Boolean = generation.get() == request
}
