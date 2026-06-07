package com.foxhole.beta.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import kotlin.math.roundToInt

internal fun rootEnter(): EnterTransition =
    fadeIn(animationSpec = tween(durationMillis = ROOT_TRANSITION_MS, easing = FoxholeMotionTokens.NavigationEnterEasing))

internal fun rootExit(): ExitTransition =
    fadeOut(animationSpec = tween(durationMillis = ROOT_TRANSITION_MS, easing = FoxholeMotionTokens.NavigationExitEasing))

internal fun detailForwardEnter(): EnterTransition =
    fadeIn(
        animationSpec = tween(durationMillis = DETAIL_FADE_IN_MS, easing = FoxholeMotionTokens.NavigationEnterEasing),
    ) +
        slideInHorizontally(
            initialOffsetX = { fullWidth -> detailTransitionOffsetPx(fullWidth) },
            animationSpec = tween(durationMillis = DETAIL_ENTER_TRANSITION_MS, easing = FoxholeMotionTokens.NavigationEnterEasing),
        )

internal fun detailForwardExit(): ExitTransition =
    fadeOut(
        animationSpec = tween(durationMillis = DETAIL_FADE_OUT_MS, easing = FoxholeMotionTokens.NavigationExitEasing),
    ) +
        slideOutHorizontally(
            targetOffsetX = { fullWidth -> -detailSecondaryOffsetPx(fullWidth) },
            animationSpec = tween(durationMillis = DETAIL_EXIT_TRANSITION_MS, easing = FoxholeMotionTokens.NavigationExitEasing),
        )

internal fun detailBackEnter(): EnterTransition =
    fadeIn(
        animationSpec = tween(durationMillis = DETAIL_FADE_IN_MS, easing = FoxholeMotionTokens.NavigationEnterEasing),
    ) +
        slideInHorizontally(
            initialOffsetX = { fullWidth -> -detailSecondaryOffsetPx(fullWidth) },
            animationSpec = tween(durationMillis = DETAIL_EXIT_TRANSITION_MS, easing = FoxholeMotionTokens.NavigationEnterEasing),
        )

internal fun detailBackExit(): ExitTransition =
    fadeOut(
        animationSpec = tween(durationMillis = DETAIL_FADE_OUT_MS, easing = FoxholeMotionTokens.NavigationExitEasing),
    ) +
        slideOutHorizontally(
            targetOffsetX = { fullWidth -> detailTransitionOffsetPx(fullWidth) },
            animationSpec = tween(durationMillis = DETAIL_EXIT_TRANSITION_MS, easing = FoxholeMotionTokens.NavigationExitEasing),
        )

internal fun detailTransitionOffsetPx(fullWidthPx: Int): Int =
    (fullWidthPx * DETAIL_TRANSITION_OFFSET_FRACTION).roundToInt().coerceAtLeast(1)

internal fun detailSecondaryOffsetPx(fullWidthPx: Int): Int =
    (fullWidthPx * DETAIL_SECONDARY_OFFSET_FRACTION).roundToInt().coerceAtLeast(1)

internal fun String?.isRootRoute(): Boolean =
    this == "home" || this == "settings"

internal fun String?.isSettingsDetailRoute(): Boolean =
    this?.startsWith("settings/") == true

private const val ROOT_TRANSITION_MS = FoxholeMotionTokens.EmphasisDurationMs
private const val DETAIL_FADE_IN_MS = FoxholeMotionTokens.NavigationFadeDurationMs
private const val DETAIL_FADE_OUT_MS = FoxholeMotionTokens.NavigationExitDurationMs
private const val DETAIL_ENTER_TRANSITION_MS = FoxholeMotionTokens.NavigationEnterDurationMs
private const val DETAIL_EXIT_TRANSITION_MS = FoxholeMotionTokens.NavigationExitDurationMs
private const val DETAIL_TRANSITION_OFFSET_FRACTION = FoxholeMotionTokens.NavigationSlideFraction
private const val DETAIL_SECONDARY_OFFSET_FRACTION = 0.03f
