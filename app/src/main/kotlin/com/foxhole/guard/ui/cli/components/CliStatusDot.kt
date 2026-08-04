package com.foxhole.guard.ui.cli.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/**
 * Status dot drawn as a real circle (the `⏺` glyph gets emoji presentation on some OEM
 * fonts and ignores the tint). Pulses in a hard step - terminal cursor, not a fade.
 */
@Composable
internal fun CliStatusDot(
    color: Color,
    modifier: Modifier = Modifier,
    pulsing: Boolean = false,
) {
    // The pulse is read in the draw phase: reading animateFloat in composition recomposed the dot on
    // every display frame for the sake of two states a second.
    val pulseAlpha: () -> Float = if (pulsing) {
        val transition = rememberInfiniteTransition(label = "cliDot")
        val value = transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 900, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "cliDotAlpha",
        )
        val stepped = { if (value.value < 0.5f) 1f else 0.3f }
        stepped
    } else {
        { 1f }
    }
    Box(
        modifier = modifier
            .size(10.dp)
            .graphicsLayer { alpha = pulseAlpha() }
            .clip(CircleShape)
            .background(color),
    )
}
