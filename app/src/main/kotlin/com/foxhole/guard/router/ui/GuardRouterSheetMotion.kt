package com.foxhole.guard.router.ui

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut

// Material3 1.4 keeps MotionScheme internal; use its standard fast motion tokens.
internal object GuardRouterSheetMotion {
    fun <T> defaultSpatialSpec(): FiniteAnimationSpec<T> = spring(dampingRatio = .9f, stiffness = 1400f)
    fun <T> fastEffectsSpec(): FiniteAnimationSpec<T> = spring(dampingRatio = 1f, stiffness = 3800f)

    fun pageTransition(animateSize: Boolean = true): ContentTransform = ContentTransform(
        targetContentEnter = fadeIn(fastEffectsSpec()),
        initialContentExit = fadeOut(fastEffectsSpec()),
        sizeTransform = if (animateSize) SizeTransform { _, _ -> defaultSpatialSpec() } else null,
    )
}
