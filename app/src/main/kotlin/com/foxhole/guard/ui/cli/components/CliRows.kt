package com.foxhole.guard.ui.cli.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.foxhole.core.model.VisualStyle
import com.foxhole.guard.R
import com.foxhole.guard.ui.cli.CliIconSize
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.LocalCliVisualStyle
import com.foxhole.guard.ui.cli.cliLabelText
import com.foxhole.guard.ui.cli.cliRowTextStyle

/**
 * Toggle state marker: modern renders a native-feel switch (palette-aware in dark and
 * light), retro keeps the `[x]` checkbox inside a fixed slot so on/off never nudges the row.
 */
@Composable
internal fun CliCheckGlyph(
    checked: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCliColors.current
    if (LocalCliVisualStyle.current == VisualStyle.PLAIN) {
        Switch(
            checked = checked,
            onCheckedChange = null,
            modifier = modifier
                .scale(PLAIN_SWITCH_SCALE)
                .height(PLAIN_SWITCH_SLOT_HEIGHT),
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.bg,
                checkedTrackColor = colors.accent,
                checkedBorderColor = colors.accent,
                uncheckedThumbColor = colors.dim,
                uncheckedTrackColor = colors.panelAlt,
                uncheckedBorderColor = colors.border,
            ),
        )
        return
    }
    val style = cliRowTextStyle()
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val slotWidth = remember(measurer, density, style) {
        with(density) { measurer.measure(CHECKBOX_SAMPLE, style).size.width.toDp() }
    }
    Box(modifier = modifier.width(slotWidth), contentAlignment = Alignment.CenterEnd) {
        Text(
            text = if (checked) "[x]" else "[ ]",
            style = style,
            color = if (checked) colors.accent else colors.faint,
            maxLines = 1,
        )
    }
}

internal val ROW_VALUE_GLYPH_GAP = 6.dp

internal val CLI_DISCLOSURE_GLYPH_SIZE = CliIconSize.glyph

/**
 * The rim a profile table reserves on both sides: the trailing disclosure glyph plus its gap,
 * mirrored on the leading side where the flag sits. One constant for both rims is what keeps
 * the table from reading skewed - a wider leading slot pushed every column right of centre.
 */
internal val CLI_PROFILE_TABLE_RIM = CLI_DISCLOSURE_GLYPH_SIZE + ROW_VALUE_GLYPH_GAP

private const val CHECKBOX_SAMPLE = "[x]"
private const val PLAIN_SWITCH_SCALE = 0.8f
private val PLAIN_SWITCH_SLOT_HEIGHT = 28.dp

@Composable
internal fun CliDisclosureGlyph(
    expanded: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
) {
    CliPixIcon(
        id = if (expanded) R.drawable.pix_arrow_down else R.drawable.pix_arrow_right,
        contentDescription = null,
        size = CLI_DISCLOSURE_GLYPH_SIZE,
        tint = color,
        modifier = modifier,
    )
}

@Composable
@Suppress("LongParameterList")
internal fun CliToggleRow(
    label: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    note: String? = null,
    noteColor: Color = Color.Unspecified,
    infoText: String? = null,
    enabled: Boolean = true,
    icon: Int? = null,
) {
    val colors = LocalCliColors.current
    var infoOpen by rememberSaveable(label) { mutableStateOf(false) }
    if (infoOpen && infoText != null) {
        CliInfoSheet(
            text = infoText,
            onDismiss = { infoOpen = false },
        )
    }
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp)
                .cliPressable(enabled = enabled) { onToggle(!checked) }
                .alpha(if (enabled) 1f else 0.4f),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f, fill = false),
            ) {
                if (icon != null) {
                    CliPixIcon(
                        id = icon,
                        contentDescription = null,
                        tint = if (checked) colors.accent else colors.dim,
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text(
                    text = cliLabelText(label),
                    style = cliRowTextStyle(),
                    color = if (checked) colors.fg else colors.dim,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (infoText != null) {
                    CliRowInfoGlyph(onTap = { infoOpen = true })
                }
            }
            CliCheckGlyph(checked = checked)
        }
        note?.let {
            CliElbowLine(
                text = it,
                color = noteColor,
                modifier = Modifier.padding(bottom = CliSpacing.xs),
            )
        }
    }
}

@Composable
internal fun CliRowInfoGlyph(onTap: () -> Unit) {
    CliHeaderHelpButton(
        contentDescription = stringResource(R.string.cli_common_information),
        onClick = onTap,
    )
}

@Composable
internal fun CliSelectRow(
    label: String,
    value: String,
    expanded: Boolean,
    onExpandToggle: () -> Unit,
    modifier: Modifier = Modifier,
    valueColor: Color = Color.Unspecified,
    enabled: Boolean = true,
    icon: Int? = null,
) {
    val colors = LocalCliColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .cliPressable(enabled = enabled, onClick = onExpandToggle)
            .alpha(if (enabled) 1f else 0.4f),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f, fill = false),
        ) {
            if (icon != null) {
                CliPixIcon(
                    id = icon,
                    contentDescription = null,
                    tint = if (enabled) colors.accent else colors.dim,
                )
                Spacer(modifier = Modifier.width(6.dp))
            }
            Text(
                text = cliLabelText(label),
                style = cliRowTextStyle(),
                color = colors.dim,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = cliLabelText(value),
                style = cliRowTextStyle(),
                color = if (valueColor == Color.Unspecified) colors.fg else valueColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.width(ROW_VALUE_GLYPH_GAP))
            CliDisclosureGlyph(
                expanded = expanded,
                color = if (enabled) colors.accent else colors.dim,
            )
        }
    }
}

@Composable
internal fun CliOptionRow(
    text: String,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null,
    enabled: Boolean = true,
) {
    val colors = LocalCliColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .cliPressable(enabled = enabled, onClick = onSelect)
            .alpha(if (enabled) 1f else 0.4f)
            .padding(start = CliSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CliMenuCursor(selected = selected, tint = colors.ok)
        Text(
            text = cliLabelText(text),
            style = cliRowTextStyle(),
            color = if (selected) colors.fg else colors.dim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        detail?.let {
            Text(
                text = " · $it",
                style = cliRowTextStyle(),
                color = colors.faint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun CliActionRow(
    label: String,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    value: String? = null,
    enabled: Boolean = true,
    icon: Int? = null,
    attention: Boolean = false,
    attentionColor: Color = Color.Unspecified,
    actionColor: Color = Color.Unspecified,
    labelColor: Color = Color.Unspecified,
) {
    val colors = LocalCliColors.current
    val resolvedActionColor = when {
        actionColor != Color.Unspecified -> actionColor
        icon == R.drawable.pix_trash -> colors.err
        else -> colors.accent
    }
    val resolvedLabelColor = when {
        labelColor != Color.Unspecified -> labelColor
        actionColor != Color.Unspecified -> resolvedActionColor
        else -> colors.fg
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .cliPressable(enabled = enabled, onClick = onTap)
            .alpha(if (enabled) 1f else 0.4f),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f, fill = false),
        ) {
            if (icon != null) {
                CliPixIcon(id = icon, contentDescription = null, tint = resolvedActionColor)
                Spacer(modifier = Modifier.width(6.dp))
            }
            Text(
                text = cliLabelText(label),
                style = cliRowTextStyle(),
                color = resolvedLabelColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (attention) {
                Spacer(modifier = Modifier.width(6.dp))
                CliAttentionPixel(
                    color = if (attentionColor == Color.Unspecified) colors.warn else attentionColor,
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            value?.let {
                Text(
                    text = cliLabelText(it),
                    style = cliRowTextStyle(),
                    color = colors.dim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.width(ROW_VALUE_GLYPH_GAP))
            }
            CliPixIcon(
                id = R.drawable.pix_arrow_right,
                contentDescription = null,
                size = 12.dp,
                tint = resolvedActionColor,
            )
        }
    }
}

@Composable
internal fun CliDestructiveRow(
    key: String,
    label: String,
    confirmAction: String?,
    onArm: (String?) -> Unit,
    onConfirm: () -> Unit,
    note: String? = null,
    icon: Int? = null,
) {
    CliActionRow(label = label, icon = icon, onTap = { onArm(key) })
    note?.let { CliElbowLine(text = it) }
    if (confirmAction == key) {
        CliConfirmSheet(
            title = label,
            icon = R.drawable.pix_trash,
            question = stringResource(R.string.cli_data_destructive_confirm, label),
            note = note,
            onConfirm = {
                onArm(null)
                onConfirm()
            },
            onDismiss = { onArm(null) },
        )
    }
}

/**
 * Active-row marker for profile tables: a breathing green dot that sits inside the name cell,
 * so it offsets the active name by about one space instead of owning a column of its own.
 */
@Composable
internal fun CliActiveDot(active: Boolean, modifier: Modifier = Modifier) {
    val colors = LocalCliColors.current
    Box(
        modifier = modifier.width(CLI_ACTIVE_DOT_SLOT_WIDTH),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (active) {
            val transition = rememberInfiniteTransition(label = "cliActiveDot")
            val pulse = transition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = ACTIVE_DOT_PULSE_MS, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "cliActiveDotPulse",
            )
            Box(
                modifier = Modifier
                    .size(ACTIVE_DOT_SIZE)
                    .graphicsLayer {
                        alpha = ACTIVE_DOT_MIN_ALPHA + ACTIVE_DOT_ALPHA_SPAN * pulse.value
                    }
                    .clip(CircleShape)
                    .background(colors.vpn),
            )
        }
    }
}

private val ACTIVE_DOT_SIZE = 8.dp

internal val CLI_ACTIVE_DOT_SLOT_WIDTH = ACTIVE_DOT_SIZE + 2.dp
private const val ACTIVE_DOT_PULSE_MS = 900
private const val ACTIVE_DOT_MIN_ALPHA = 0.45f
private const val ACTIVE_DOT_ALPHA_SPAN = 0.55f

@Composable
internal fun CliColumnRule(modifier: Modifier = Modifier) {
    val colors = LocalCliColors.current
    Box(
        modifier = modifier
            .padding(horizontal = 6.dp)
            .width(1.dp)
            .height(COLUMN_RULE_HEIGHT)
            .background(colors.border.copy(alpha = 0.6f)),
    )
}

private val COLUMN_RULE_HEIGHT = 14.dp

@Composable
internal fun CliMenuCursor(selected: Boolean, tint: Color = Color.Unspecified) {
    val colors = LocalCliColors.current
    if (selected) {
        CliPixIcon(
            id = R.drawable.pix_arrow_right,
            contentDescription = null,
            size = 12.dp,
            tint = if (tint == Color.Unspecified) colors.accent else tint,
        )
    } else {
        Spacer(modifier = Modifier.width(12.dp))
    }
    Spacer(modifier = Modifier.width(6.dp))
}
