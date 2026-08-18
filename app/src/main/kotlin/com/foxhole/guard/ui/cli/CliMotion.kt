package com.foxhole.guard.ui.cli

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.ui.unit.IntOffset

/**
 * The one motion law: a smooth horizontal slide on a critically damped spring. Both surfaces
 * still move whole — no fade or blur, the terminal canon holds — but the slide itself renders
 * at the display's native frame rate. The previous law quantised the same slide into eight
 * hard steps to mimic frame redraws; on real hardware that read as dropped frames rather
 * than intent, so the quantisation is gone.
 *
 * A spring rather than a clock so that rapid dock taps retarget the running slide mid-flight
 * instead of restarting it: interruption is the case tab navigation actually lives in.
 * No bounce — an overshooting full-width surface exposes a gap of raw background at its
 * trailing edge.
 */
private val CliSlideSpring = spring<IntOffset>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMediumLow,
    visibilityThreshold = IntOffset.VisibilityThreshold,
)

internal fun cliSlide(forward: Boolean): ContentTransform {
    val direction = if (forward) 1 else -1
    return slideInHorizontally(
        animationSpec = CliSlideSpring,
        initialOffsetX = { full -> full * direction },
    ) togetherWith slideOutHorizontally(
        animationSpec = CliSlideSpring,
        targetOffsetX = { full -> -full * direction },
    )
}

private const val GENTLE_RISE_FRACTION = 12
private const val GENTLE_SCALE_FROM = 0.97f

private val CliGentleRiseSpring = spring<IntOffset>(
    dampingRatio = Spring.DampingRatioLowBouncy,
    stiffness = Spring.StiffnessMediumLow,
    visibilityThreshold = IntOffset.VisibilityThreshold,
)

internal fun cliPanelSwap(forward: Boolean, plain: Boolean): ContentTransform =
    if (plain) {
        (
            fadeIn(CliMotion.enter()) +
                slideInVertically(CliGentleRiseSpring) { full -> full / GENTLE_RISE_FRACTION } +
                scaleIn(initialScale = GENTLE_SCALE_FROM, animationSpec = CliMotion.enter())
            ) togetherWith fadeOut(CliMotion.exit())
    } else {
        cliSlide(forward)
    }
