package com.foxhole.beta.ui

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import com.foxhole.beta.BuildConfig
import java.util.concurrent.atomic.AtomicInteger

@Composable
internal fun DebugRecompositionCounter(name: String) {
    if (!BuildConfig.DEBUG) {
        return
    }
    val counter = remember(name) { AtomicInteger(0) }
    SideEffect {
        val count = counter.incrementAndGet()
        if (count == 1 || count % RECOMPOSITION_LOG_INTERVAL == 0) {
            Log.d("FoxholeRecompose", "$name recompositions=$count")
        }
    }
}

private const val RECOMPOSITION_LOG_INTERVAL = 25
