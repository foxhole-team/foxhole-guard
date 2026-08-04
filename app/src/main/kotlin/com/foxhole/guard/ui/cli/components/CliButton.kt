package com.foxhole.guard.ui.cli.components

import androidx.annotation.DrawableRes
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliColors

/**
 * Bracket button: `[ CONNECT ]`. Transparent with a colored border by default; `filled`
 * inverts it (accent background, dark label) for the primary action. Touch target >= 48dp.
 *
 * 16-bit feedback instead of a Material ripple: a press sinks the whole button by one
 * pixel step while a second 1dp frame steps inside the border.
 */
@Composable
internal fun CliButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    @DrawableRes icon: Int? = null,
    filled: Boolean = false,
    enabled: Boolean = true,
) {
    val colors = LocalCliColors.current
    val tint = if (color == Color.Unspecified) colors.accent else color
    val shape = RoundedCornerShape(6.dp)
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    // Pressed-frame shade: the tint stepped hard towards black, no translucency,
    // so the edge stays a flat sprite-like facet on any palette. The filled button's outer pixel
    // shadow was removed: on START it read as a stray outline.
    val shade = lerp(tint, Color.Black, SHADE_TOWARD_BLACK)
    // Outlined buttons flash border and label a bright step on press, with a light fill — discrete,
    // no fade.
    val pressBright = lerp(tint, Color.White, PRESS_TOWARD_WHITE)
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = 48.dp)
            .offset {
                if (pressed) {
                    IntOffset(PIXEL_STEP.roundToPx(), PIXEL_STEP.roundToPx())
                } else {
                    IntOffset.Zero
                }
            }
            .clip(shape)
            .background(cliButtonFill(filled, pressed, tint))
            .border(1.dp, cliButtonBorder(filled, pressed, tint, pressBright), shape)
            .drawBehind { if (pressed) pixelInsetFrame(if (filled) shade else pressBright) }
            .clickable(
                interactionSource = interactionSource,
                // No ripple mush - the sink offset plus the stepped frame IS the indication.
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .alpha(if (enabled) 1f else 0.4f)
            .padding(horizontal = 14.dp, vertical = CliSpacing.md),
        contentAlignment = Alignment.Center,
    ) {
        // The pack icon sits inside the brackets: `[ ⚙ LABEL ]`.
        val content = if (filled) colors.bg else if (pressed) pressBright else tint
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "[ ",
                style = CliType.button,
                color = content,
                maxLines = 1,
            )
            if (icon != null) {
                // Pixel glyphs sit above the line centre; without this nudge the icon read as
                // sunken.
                CliPixIcon(
                    id = icon,
                    contentDescription = null,
                    size = 16.dp,
                    tint = content,
                    modifier = Modifier.offset(y = (-1).dp),
                )
                Spacer(modifier = Modifier.width(5.dp))
            }
            Text(
                text = "$label ]",
                style = CliType.button,
                color = content,
                maxLines = 1,
            )
        }
    }
}

/** Small inline chip in the same bracket style, for quick secondary actions. */
@Composable
internal fun CliChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    selected: Boolean = false,
    enabled: Boolean = true,
) {
    val colors = LocalCliColors.current
    val tint = if (color == Color.Unspecified) colors.dim else color
    val shape = RoundedCornerShape(6.dp)
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    Box(
        // 48dp: Android touch-target floor; the visual density is kept by the paddings.
        modifier = modifier
            .defaultMinSize(minHeight = 48.dp)
            // Chips are lighter than buttons: a 1px sink, no shadow or second frame.
            .offset {
                if (pressed) {
                    IntOffset(CHIP_PRESS_STEP.roundToPx(), CHIP_PRESS_STEP.roundToPx())
                } else {
                    IntOffset.Zero
                }
            }
            .clip(shape)
            .background(
                when {
                    selected -> tint.copy(alpha = 0.18f)
                    pressed -> tint.copy(alpha = 0.12f)
                    else -> Color.Transparent
                },
            )
            .border(1.dp, if (selected || pressed) tint else colors.border, shape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .alpha(if (enabled) 1f else 0.4f)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "[$label]",
            style = CliType.small,
            color = if (selected) tint else colors.dim,
            maxLines = 1,
        )
    }
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

/**
 * The second stair of the pressed state: a square-cornered 1dp frame inset one pixel step
 * from the (rounded) outer border - together they read as a stepped, sunken bezel.
 */
private fun DrawScope.pixelInsetFrame(color: Color) {
    val inset = PIXEL_STEP.toPx()
    drawRect(
        color = color,
        topLeft = Offset(inset, inset),
        size = Size(size.width - inset * 2, size.height - inset * 2),
        style = Stroke(width = 1.dp.toPx()),
    )
}

/** The pixel unit of the 16-bit effects: shadow depth and press sink. */
private val PIXEL_STEP = 2.dp

// Press steps: a filled button's shadow goes hard to black, an outlined one's flash to white.
private const val SHADE_TOWARD_BLACK = 0.45f
private const val PRESS_TOWARD_WHITE = 0.30f

/** Chips sink a single pixel - half the button step, matching their smaller type. */
private val CHIP_PRESS_STEP = 1.dp

/**
 * The shared row/cell click without a Material ripple: indication = null plus a sharp accent tint
 * on press — the same 16-bit response as [CliButton], without borders or offset. Use instead of a
 * bare `clickable {}` in every Cli row.
 */
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
        .background(if (pressed && enabled) colors.accent.copy(alpha = 0.12f) else Color.Transparent)
        .clickable(
            interactionSource = interactionSource,
            indication = null,
            enabled = enabled,
            role = role,
            onClick = onClick,
        )
}

/** [cliPressable] for rows with long-press: combinedClickable without a ripple. */
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
        .background(if (pressed && enabled) colors.accent.copy(alpha = 0.12f) else Color.Transparent)
        .combinedClickable(
            interactionSource = interactionSource,
            indication = null,
            enabled = enabled,
            onLongClick = onLongClick,
            onClick = onClick,
        )
}
