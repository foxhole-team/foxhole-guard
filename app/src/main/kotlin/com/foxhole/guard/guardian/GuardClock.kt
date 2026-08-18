package com.foxhole.guard.guardian

import android.content.Context
import android.os.SystemClock
import android.provider.Settings

interface GuardClock {
    fun wallClockMs(): Long

    fun elapsedRealtimeMs(): Long

    fun bootCount(): Int
}

internal class AndroidGuardClock(
    context: Context,
) : GuardClock {
    private val appContext = context.applicationContext

    override fun wallClockMs(): Long = System.currentTimeMillis()

    override fun elapsedRealtimeMs(): Long = SystemClock.elapsedRealtime()

    override fun bootCount(): Int =
        runCatching {
            Settings.Global.getInt(appContext.contentResolver, Settings.Global.BOOT_COUNT, 0)
        }.getOrDefault(0)
}
