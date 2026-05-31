package com.foxhole.beta.vpn

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

internal object RuntimeNativeCallDispatcher {
    val dispatcher: CoroutineDispatcher =
        Executors
            .newSingleThreadExecutor(RuntimeNativeThreadFactory)
            .asCoroutineDispatcher()
}

private object RuntimeNativeThreadFactory : ThreadFactory {
    private val sequence = AtomicInteger(0)

    override fun newThread(runnable: Runnable): Thread =
        Thread(runnable, "FoxholeNativeRuntime-${sequence.incrementAndGet()}").apply {
            isDaemon = true
        }
}
