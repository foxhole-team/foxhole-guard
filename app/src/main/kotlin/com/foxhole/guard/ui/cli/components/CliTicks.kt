package com.foxhole.guard.ui.cli.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

/**
 * A one-second `nowMs` for uptime rows. One canon instead of copies of `while(true)+delay`: unlike
 * `rememberInfiniteTransition` a delay loop is not tied to the frame clock and would keep ticking in
 * the background, so this one lives only in STARTED. [resetKey] restarts the count.
 */
@Composable
internal fun rememberNowMsTicker(resetKey: Any?): State<Long> {
    val nowMs = remember(resetKey) { mutableLongStateOf(System.currentTimeMillis()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(resetKey, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                nowMs.longValue = System.currentTimeMillis()
                delay(1_000L)
            }
        }
    }
    return nowMs
}
