package com.foxhole.guard.ui.cli.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foxhole.core.model.VisualStyle
import com.foxhole.guard.ui.cli.CliMotion
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.LocalCliVisualStyle
import com.foxhole.guard.ui.cli.cliLabelText
import com.foxhole.guard.ui.cli.cliScaledSp

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CliButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    @DrawableRes icon: Int? = null,
    iconContent: (@Composable (Color) -> Unit)? = null,
    filled: Boolean = false,
    dashed: Boolean = false,
    enabled: Boolean = true,
    dimWhenDisabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    animatedLabel: Boolean = false,
) {
    val colors = LocalCliColors.current
    val tint = if (color == Color.Unspecified) colors.accent else color
    val shape = RoundedCornerShape(6.dp)
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val shade = lerp(tint, Color.Black, SHADE_TOWARD_BLACK)
    val pressBright = lerp(tint, Color.White, PRESS_TOWARD_WHITE)
    val emphasis = cliButtonEmphasis(enabled = enabled, dimWhenDisabled = dimWhenDisabled)
    val borderColor =
        cliButtonBorder(filled, pressed, tint, pressBright)
            .scaledAlpha(emphasis.borderAlpha)
    val fillColor =
        cliButtonFill(filled, pressed, tint)
            .scaledAlpha(emphasis.fillAlpha)
    val plainPress = LocalCliVisualStyle.current == VisualStyle.PLAIN
    val pressScale by animateFloatAsState(
        targetValue = if (plainPress && pressed) PLAIN_PRESS_SCALE else 1f,
        animationSpec = CliMotion.press(),
        label = "cliButtonPressScale",
    )
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = 48.dp)
            .offset {
                if (pressed && !plainPress) {
                    IntOffset(PIXEL_STEP.roundToPx(), PIXEL_STEP.roundToPx())
                } else {
                    IntOffset.Zero
                }
            }
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .cliButtonElevation(filled = filled, pressed = pressed, shape = shape)
            .clip(shape)
            .background(fillColor)
            .then(
                if (dashed && !filled && !plainPress) {
                    Modifier.drawBehind { dashedFrame(borderColor) }
                } else {
                    Modifier.border(1.dp, borderColor, shape)
                },
            )
            .drawBehind { if (pressed && !plainPress) pixelInsetFrame(if (filled) shade else pressBright) }
            .then(
                if (onLongClick == null) {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        enabled = enabled,
                        onClick = onClick,
                    )
                } else {
                    Modifier.combinedClickable(
                        interactionSource = interactionSource,
                        indication = null,
                        enabled = enabled,
                        onLongClick = onLongClick,
                        onClick = onClick,
                    )
                },
            )
            .padding(horizontal = 12.dp, vertical = CliSpacing.md),
        contentAlignment = Alignment.Center,
    ) {
        CliButtonLabelRow(
            label = label,
            icon = icon,
            iconContent = iconContent,
            content = cliButtonContentColor(
                filled = filled,
                pressed = pressed,
                bg = colors.bg,
                pressBright = pressBright,
                tint = tint,
            ),
            contentAlpha = emphasis.contentAlpha,
            animatedLabel = animatedLabel,
        )
    }
}

private fun Modifier.cliButtonElevation(
    filled: Boolean,
    pressed: Boolean,
    shape: RoundedCornerShape,
): Modifier = if (filled) shadow(if (pressed) 1.dp else 2.dp, shape) else this

private fun cliButtonContentColor(
    filled: Boolean,
    pressed: Boolean,
    bg: Color,
    pressBright: Color,
    tint: Color,
): Color = when {
    filled -> bg
    pressed -> pressBright
    else -> tint
}

@Composable
private fun CliButtonLabelRow(
    label: String,
    @DrawableRes icon: Int?,
    iconContent: (@Composable (Color) -> Unit)?,
    content: Color,
    contentAlpha: Float,
    animatedLabel: Boolean,
) {
    val bracketed = LocalCliVisualStyle.current == VisualStyle.PIXEL
    Row(
        modifier = Modifier.alpha(contentAlpha),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (bracketed) {
            Text(
                text = "[ ",
                style = CliType.button,
                color = content,
                maxLines = 1,
            )
        }
        CliButtonLeadingIcon(icon = icon, iconContent = iconContent, tint = content)
        when {
            animatedLabel -> CliShimmerText(
                text = if (bracketed) "$label ]" else cliLabelText(label),
                style = CliType.button,
                baseColor = content,
                maxLines = 1,
            )
            !bracketed -> BasicText(
                text = cliLabelText(label),
                style = CliType.button.copy(color = content),
                maxLines = 1,
                autoSize = TextAutoSize.StepBased(
                    minFontSize = BUTTON_LABEL_MIN_FONT,
                    maxFontSize = CliType.button.fontSize,
                    stepSize = BUTTON_LABEL_FONT_STEP,
                ),
                modifier = Modifier.weight(1f, fill = false),
            )
            else -> Text(
                text = "$label ]",
                style = CliType.button,
                color = content,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun CliButtonLeadingIcon(
    @DrawableRes icon: Int?,
    iconContent: (@Composable (Color) -> Unit)?,
    tint: Color,
) {
    when {
        icon != null ->
            CliPixIcon(
                id = icon,
                contentDescription = null,
                size = 16.dp,
                tint = tint,
                modifier = Modifier.offset(y = (-1).dp),
            )

        iconContent != null -> iconContent(tint)
        else -> return
    }
    Spacer(modifier = Modifier.width(5.dp))
}

internal data class CliButtonEmphasis(
    val fillAlpha: Float,
    val borderAlpha: Float,
    val contentAlpha: Float,
)

internal fun cliButtonEmphasis(
    enabled: Boolean,
    dimWhenDisabled: Boolean,
): CliButtonEmphasis =
    if (enabled || !dimWhenDisabled) {
        CliButtonEmphasis(fillAlpha = 1f, borderAlpha = 1f, contentAlpha = 1f)
    } else {
        CliButtonEmphasis(
            fillAlpha = DISABLED_FILL_ALPHA,
            borderAlpha = 1f,
            contentAlpha = DISABLED_CONTENT_ALPHA,
        )
    }

private fun Color.scaledAlpha(multiplier: Float): Color = copy(alpha = alpha * multiplier)

private const val DISABLED_FILL_ALPHA = 0.58f
private const val DISABLED_CONTENT_ALPHA = 0.55f

@Composable
internal fun CliChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    selected: Boolean = false,
    enabled: Boolean = true,
    contentDescription: String? = null,
) {
    val colors = LocalCliColors.current
    val description = contentDescription
    val tint = if (color == Color.Unspecified) colors.dim else color
    val shape = RoundedCornerShape(6.dp)
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val plainPress = LocalCliVisualStyle.current == VisualStyle.PLAIN
    val chipScale by animateFloatAsState(
        targetValue = if (plainPress && pressed) PLAIN_PRESS_SCALE else 1f,
        animationSpec = CliMotion.press(),
        label = "cliChipPressScale",
    )
    val chipFill by animateColorAsState(
        targetValue = cliChipFill(tint, selected = selected, pressed = pressed),
        animationSpec = CliMotion.standard(),
        label = "cliChipFill",
    )
    val chipBorder by animateColorAsState(
        targetValue = if (selected || pressed) tint else colors.border,
        animationSpec = CliMotion.standard(),
        label = "cliChipBorder",
    )
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = 48.dp)
            .offset {
                if (pressed && !plainPress) {
                    IntOffset(CHIP_PRESS_STEP.roundToPx(), CHIP_PRESS_STEP.roundToPx())
                } else {
                    IntOffset.Zero
                }
            }
            .graphicsLayer {
                scaleX = chipScale
                scaleY = chipScale
            }
            .clip(shape)
            .background(chipFill)
            .border(1.dp, chipBorder, shape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .then(
                if (description == null) {
                    Modifier
                } else {
                    Modifier.semantics { this.contentDescription = description }
                },
            )
            .alpha(if (enabled) 1f else 0.4f)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        val labelColor by animateColorAsState(
            targetValue = if (selected) tint else colors.dim,
            animationSpec = CliMotion.standard(),
            label = "cliChipLabel",
        )
        Text(
            text = if (LocalCliVisualStyle.current == VisualStyle.PIXEL) "[$label]" else cliLabelText(label),
            style = CliType.small,
            color = labelColor,
            maxLines = 1,
        )
    }
}

private fun cliChipFill(tint: Color, selected: Boolean, pressed: Boolean): Color = when {
    selected -> tint.copy(alpha = 0.18f)
    pressed -> tint.copy(alpha = 0.12f)
    else -> Color.Transparent
}

private fun cliButtonFill(filled: Boolean, pressed: Boolean, tint: Color): Color = when {
    filled -> tint
    pressed -> tint.copy(alpha = 0.12f)
    else -> Color.Transparent
}

private fun cliButtonBorder(filled: Boolean, pressed: Boolean, tint: Color, bright: Color): Color =
    when {
        filled -> tint
        pressed -> bright
        else -> tint.copy(alpha = 0.65f)
    }

private fun DrawScope.dashedFrame(color: Color) {
    val stroke = 1.dp.toPx()
    drawRoundRect(
        color = color,
        topLeft = Offset(stroke / 2f, stroke / 2f),
        size = Size(size.width - stroke, size.height - stroke),
        cornerRadius = CornerRadius(6.dp.toPx()),
        style = Stroke(
            width = stroke,
            pathEffect = PathEffect.dashPathEffect(
                floatArrayOf(4.dp.toPx(), 3.dp.toPx()),
            ),
        ),
    )
}

private fun DrawScope.pixelInsetFrame(color: Color) {
    val inset = PIXEL_STEP.toPx()
    drawRect(
        color = color,
        topLeft = Offset(inset, inset),
        size = Size(size.width - inset * 2, size.height - inset * 2),
        style = Stroke(width = 1.dp.toPx()),
    )
}

private val PIXEL_STEP = 2.dp

private const val PLAIN_PRESS_SCALE = 0.97f

private val BUTTON_LABEL_MIN_FONT = cliScaledSp(11f)
private val BUTTON_LABEL_FONT_STEP = 0.5.sp

private const val SHADE_TOWARD_BLACK = 0.45f
private const val PRESS_TOWARD_WHITE = 0.30f

private val CHIP_PRESS_STEP = 1.dp

@Composable
internal fun Modifier.cliPressable(
    enabled: Boolean = true,
    role: Role? = null,
    onClick: () -> Unit,
): Modifier {
    val colors = LocalCliColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    return this
        .clip(CLI_PRESS_SHAPE)
        .background(if (pressed && enabled) colors.accent.copy(alpha = 0.12f) else Color.Transparent)
        .clickable(
            interactionSource = interactionSource,
            indication = null,
            enabled = enabled,
            role = role,
            onClick = onClick,
        )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Modifier.cliCombinedPressable(
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier {
    val colors = LocalCliColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    return this
        .clip(CLI_PRESS_SHAPE)
        .background(if (pressed && enabled) colors.accent.copy(alpha = 0.12f) else Color.Transparent)
        .combinedClickable(
            interactionSource = interactionSource,
            indication = null,
            enabled = enabled,
            onLongClick = onLongClick,
            onClick = onClick,
        )
}

private val CLI_PRESS_SHAPE = RoundedCornerShape(6.dp)
