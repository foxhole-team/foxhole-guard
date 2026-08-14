package com.foxhole.guard.ui.cli

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith

/**
 * The one motion law: a fast horizontal slide quantised into hard steps, the same trick as the
 * map's marching dashes but full-screen. No fade or blur — the old screen leaves, the new arrives,
 * both whole.
 */
private const val MOTION_DURATION_MS = 120

// Eight discrete positions over 120ms, so the motion reads as frame redraws rather than a smooth
// drift. f=1 quantises to 1, so the final position is always exact.
internal fun cliSnapFraction(fraction: Float): Float =
    (fraction.coerceIn(0f, 1f) * MOTION_STEPS).toInt().toFloat() / MOTION_STEPS

internal val CliSnapEasing: Easing = Easing(::cliSnapFraction)

private const val MOTION_STEPS = 8

/**
 * Push/pop slide for [androidx.compose.animation.AnimatedContent]: with [forward] the new content
 * enters from the right, otherwise from the left.
 */
internal fun cliSlide(forward: Boolean): ContentTransform {
    val direction = if (forward) 1 else -1
    return slideInHorizontally(
        animationSpec = tween(MOTION_DURATION_MS, easing = CliSnapEasing),
        initialOffsetX = { full -> full * direction },
    ) togetherWith slideOutHorizontally(
        animationSpec = tween(MOTION_DURATION_MS, easing = CliSnapEasing),
        targetOffsetX = { full -> -full * direction },
    )
}
