package com.foxhole.guard.ui.cli.components

import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.MotionDurationScale
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay

@Composable
internal fun cliMotionPhase(cycleMs: Int = CLI_SHIMMER_CYCLE_MS): State<Float> {
    val phase = remember(cycleMs) { mutableFloatStateOf(0f) }
    LaunchedEffect(cycleMs) {
        while (true) {
            if (!cliSystemMotionEnabled()) {
                phase.floatValue = 0f
                delay(CLI_DISABLED_MOTION_POLL_MS)
                continue
            }
            withInfiniteAnimationFrameNanos { frameNanos ->
                phase.floatValue = cliMotionPhaseAt(frameNanos, cycleMs)
            }
        }
    }
    return phase
}

internal fun cliMotionPhaseAt(frameNanos: Long, cycleMs: Int): Float {
    val cycleNanos = cycleMs.toLong() * NANOS_PER_MS
    if (cycleNanos <= 0L) return 0f
    return frameNanos.mod(cycleNanos).toFloat() / cycleNanos.toFloat()
}

internal suspend fun cliSystemMotionEnabled(): Boolean =
    (currentCoroutineContext()[MotionDurationScale]?.scaleFactor ?: 1f) > 0f

private const val NANOS_PER_MS = 1_000_000L
private const val CLI_DISABLED_MOTION_POLL_MS = 100L

internal const val CLI_TYPE_STEP_MS = 18L
internal const val CLI_ERASE_STEP_MS = 9L
internal const val CLI_CHARS_PER_STEP = 1
