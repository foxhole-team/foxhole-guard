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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.foxhole.guard.ui.cli.cliScaledSp

internal const val CLI_ATTENTION_PULSE_MS = 900

private const val ATTENTION_PULSE_MIN_ALPHA = 0.25f

@Composable
internal fun CliAttentionPixel(
    color: Color,
    modifier: Modifier = Modifier,
) {
    val alpha = cliAttentionPulse(label = "cliAttention")
    Box(
        modifier = modifier
            .size(6.dp)
            .graphicsLayer { this.alpha = alpha }
            .clip(CircleShape)
            .background(color),
    )
}

internal const val PIXEL_CAP_HEIGHT_RATIO = 0.64f

@Composable
internal fun CliStatusDot(
    color: Color,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = cliScaledSp(17f),
    pulsing: Boolean = false,
) {
    val alpha = if (cliStatusDotAnimates(pulsing)) cliAttentionPulse(label = "cliDot") else 1f
    val side = with(LocalDensity.current) { (fontSize.toPx() * PIXEL_CAP_HEIGHT_RATIO).toDp() }
    Box(
        modifier = modifier
            .size(side)
            .graphicsLayer { this.alpha = alpha }
            .clip(CircleShape)
            .background(color),
    )
}

@Composable
private fun cliAttentionPulse(label: String): Float {
    val transition = rememberInfiniteTransition(label = label)
    val value = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = CLI_ATTENTION_PULSE_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "$label-alpha",
    )
    return ATTENTION_PULSE_MIN_ALPHA + (1f - ATTENTION_PULSE_MIN_ALPHA) * value.value
}

internal fun cliStatusDotAnimates(pulsing: Boolean): Boolean = pulsing
