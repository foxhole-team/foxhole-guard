package com.foxhole.guard.ui.cli

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.IntSize

internal fun cliSlide(
    forward: Boolean,
): ContentTransform {
    val direction = if (forward) 1 else -1
    return (
        fadeIn() +
            slideInHorizontally(
                initialOffsetX = { full -> full * direction / NAVIGATION_SHIFT_DIVISOR },
            )
        ) togetherWith (
        fadeOut() +
            slideOutHorizontally(
                targetOffsetX = { full -> -full * direction / NAVIGATION_SHIFT_DIVISOR },
            )
        )
}

private const val NAVIGATION_SHIFT_DIVISOR = 12

internal fun cliVerticalEnter(
    expandFrom: Alignment.Vertical = Alignment.Top,
): EnterTransition =
    expandVertically(
        expandFrom = expandFrom,
        animationSpec = cliVerticalSizeSpec(),
    ) + fadeIn()

internal fun cliVerticalExit(
    shrinkTowards: Alignment.Vertical = Alignment.Top,
): ExitTransition =
    shrinkVertically(
        shrinkTowards = shrinkTowards,
        animationSpec = cliVerticalSizeSpec(),
    ) + fadeOut()

internal fun cliVerticalSizeSpec(): FiniteAnimationSpec<IntSize> =
    spring(stiffness = Spring.StiffnessLow)

internal fun cliProfileSelectorSizeSpec(): FiniteAnimationSpec<IntSize> =
    spring(stiffness = Spring.StiffnessMedium)

internal fun cliVerticalScalarSpec(): FiniteAnimationSpec<Float> =
    spring(stiffness = Spring.StiffnessLow)

internal fun cliVerticalSwap(): ContentTransform = ContentTransform(
    targetContentEnter = fadeIn(),
    initialContentExit = fadeOut(),
    sizeTransform = SizeTransform(clip = true) { _, _ -> cliVerticalSizeSpec() },
)

internal fun cliBootstrapSwap(): ContentTransform =
    ContentTransform(
        targetContentEnter = fadeIn(),
        initialContentExit = fadeOut(),
        sizeTransform = SizeTransform(clip = false) { _, _ -> cliVerticalSizeSpec() },
    )

internal fun cliBootstrapFade(): ContentTransform =
    ContentTransform(
        targetContentEnter = fadeIn(),
        initialContentExit = fadeOut(),
        sizeTransform = SizeTransform(clip = false) { _, _ -> snap() },
    )
