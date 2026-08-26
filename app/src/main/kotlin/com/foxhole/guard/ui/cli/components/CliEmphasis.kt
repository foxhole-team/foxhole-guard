package com.foxhole.guard.ui.cli.components

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.foxhole.guard.ui.cli.CliMotion
import com.foxhole.guard.ui.cli.LocalCliColors

private val CLI_SELECTION_SHAPE_RADIUS = 8.dp

private const val SELECTED_FILL_ALPHA = 0.14f
private const val SELECTED_SCALE = 1.02f

@Composable
fun Modifier.cliSelectionEmphasis(
    selected: Boolean,
    cornerRadius: Dp = CLI_SELECTION_SHAPE_RADIUS,
    color: Color = Color.Unspecified,
): Modifier {
    val colors = LocalCliColors.current
    val selectionColor = if (color == Color.Unspecified) colors.accent else color
    val shape = RoundedCornerShape(cornerRadius)
    val fill by animateColorAsState(
        targetValue = if (selected) {
            selectionColor.copy(alpha = SELECTED_FILL_ALPHA)
        } else {
            Color.Transparent
        },
        animationSpec = CliMotion.standard(),
        label = "cliSelectionFill",
    )
    val edge by animateColorAsState(
        targetValue = if (selected) selectionColor else Color.Transparent,
        animationSpec = CliMotion.standard(),
        label = "cliSelectionEdge",
    )
    val lift by animateFloatAsState(
        targetValue = if (selected) SELECTED_SCALE else 1f,
        animationSpec = CliMotion.emphasis(),
        label = "cliSelectionLift",
    )
    return this
        .graphicsLayer {
            scaleX = lift
            scaleY = lift
        }
        .clip(shape)
        .background(fill)
        .border(1.dp, edge, shape)
}

private val REJECT_SHAKE_TRAVEL = 8.dp
private const val REJECT_SHAKE_MS = 360

@Composable
fun rememberCliRejectFeedback(): CliRejectFeedback {
    val view = LocalView.current
    val offset = remember { Animatable(0f) }
    return remember(view, offset) { CliRejectFeedback(view = view, offset = offset) }
}

class CliRejectFeedback internal constructor(
    private val view: android.view.View,
    internal val offset: Animatable<Float, *>,
) {
    suspend fun play() {
        view.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                HapticFeedbackConstants.REJECT
            } else {
                HapticFeedbackConstants.LONG_PRESS
            },
        )
        offset.snapTo(0f)
        offset.animateTo(
            targetValue = 0f,
            animationSpec = keyframes {
                durationMillis = REJECT_SHAKE_MS
                0f at 0
                1f at REJECT_SHAKE_MS / 6 using CliMotion.EasingStandard
                -0.8f at REJECT_SHAKE_MS * 2 / 6 using CliMotion.EasingStandard
                0.55f at REJECT_SHAKE_MS * 3 / 6 using CliMotion.EasingStandard
                -0.3f at REJECT_SHAKE_MS * 4 / 6 using CliMotion.EasingStandard
                0.12f at REJECT_SHAKE_MS * 5 / 6 using CliMotion.EasingStandard
                0f at REJECT_SHAKE_MS
            },
        )
    }
}

fun Modifier.cliRejectShake(feedback: CliRejectFeedback): Modifier =
    graphicsLayer { translationX = feedback.offset.value * REJECT_SHAKE_TRAVEL.toPx() }
