package com.foxhole.guard.ui.cli.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.foxhole.guard.R
import com.foxhole.guard.ui.cli.CliIconSize
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.cliHeadingText
import com.foxhole.guard.ui.cli.cliLabelText
import com.foxhole.guard.ui.cli.cliRowTextStyle
import com.foxhole.guard.ui.cli.cliTitleStyle

@Composable
internal fun CliCheckGlyph(
    checked: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCliColors.current
    Switch(
        checked = checked,
        onCheckedChange = null,
        modifier = modifier
            .scale(SWITCH_SCALE)
            .height(SWITCH_SLOT_HEIGHT),
        colors = SwitchDefaults.colors(
            checkedThumbColor = colors.bg,
            checkedTrackColor = colors.accent,
            checkedBorderColor = colors.accent,
            uncheckedThumbColor = colors.dim,
            uncheckedTrackColor = colors.panelAlt,
            uncheckedBorderColor = colors.border,
        ),
    )
}

internal val ROW_VALUE_GLYPH_GAP = 6.dp

internal val CLI_DISCLOSURE_GLYPH_SIZE = CliIconSize.glyph

internal val CLI_DISCLOSURE_GLYPH_OFFSET = 0.dp

internal val CLI_ROW_LEADING_ICON_OFFSET = 0.dp

internal val CLI_ROW_LEADING_ICON_SIZE = 16.dp

internal val CLI_MENU_ROW_MIN_HEIGHT = 44.dp

internal val LocalCliPanelRowContentOffset = staticCompositionLocalOf { 0.dp }

@Composable
internal fun Modifier.cliMenuRowPressable(
    enabled: Boolean = true,
    role: Role? = null,
    onClick: () -> Unit,
): Modifier =
    fillMaxWidth()
        .defaultMinSize(minHeight = CLI_MENU_ROW_MIN_HEIGHT)
        .cliPressable(enabled = enabled, role = role, onClick = onClick)

@Composable
internal fun Modifier.cliPanelRowPressable(
    enabled: Boolean = true,
    role: Role? = null,
    onClick: () -> Unit,
): Modifier =
    fillMaxWidth()
        .defaultMinSize(minHeight = CLI_MENU_ROW_MIN_HEIGHT)
        .cliPressable(enabled = enabled, role = role, onClick = onClick)
        .padding(horizontal = LocalCliPanelRowHorizontalPadding.current)
        .offset(y = LocalCliPanelRowContentOffset.current)

@Composable
internal fun Modifier.cliPanelRowContentPadding(): Modifier =
    padding(horizontal = LocalCliPanelRowHorizontalPadding.current)

@Composable
internal fun CliRowLeadingIcon(
    id: Int,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp? = null,
) {
    val iconSize = size ?: LocalCliIconMetricOverrides.current.rowLeadingIconSize
        ?: CLI_ROW_LEADING_ICON_SIZE
    CliIcon(
        id = id,
        contentDescription = null,
        size = iconSize,
        tint = tint,
        modifier = modifier.offset(y = CLI_ROW_LEADING_ICON_OFFSET),
    )
}

internal val CLI_PROFILE_TABLE_RIM = CLI_DISCLOSURE_GLYPH_SIZE + ROW_VALUE_GLYPH_GAP

private const val SWITCH_SCALE = 0.8f
private val SWITCH_SLOT_HEIGHT = 28.dp

@Composable
internal fun CliDisclosureGlyph(
    expanded: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
) {
    CliIcon(
        id = if (expanded) R.drawable.lin_arrow_down else R.drawable.lin_arrow_right,
        contentDescription = null,
        size = CLI_DISCLOSURE_GLYPH_SIZE,
        tint = color,
        modifier = modifier.offset(y = CLI_DISCLOSURE_GLYPH_OFFSET),
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
                .cliPanelRowPressable(enabled = enabled) { onToggle(!checked) }
                .alpha(if (enabled) 1f else 0.4f),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f, fill = false),
            ) {
                if (icon != null) {
                    CliRowLeadingIcon(
                        id = icon,
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
                modifier = Modifier
                    .cliPanelRowContentPadding()
                    .padding(bottom = CliSpacing.xs),
            )
        }
    }
}

@Composable
internal fun CliRowInfoGlyph(
    onTap: () -> Unit,
    iconSize: Dp? = null,
) {
    CliHeaderHelpButton(
        contentDescription = stringResource(R.string.cli_common_information),
        iconSize = iconSize ?: LocalCliIconMetricOverrides.current.rowLeadingIconSize
            ?: CLI_ROW_LEADING_ICON_SIZE,
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
            .cliPanelRowPressable(enabled = enabled, onClick = onExpandToggle)
            .alpha(if (enabled) 1f else 0.4f),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f, fill = false),
        ) {
            if (icon != null) {
                CliRowLeadingIcon(
                    id = icon,
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
            .cliPanelRowPressable(enabled = enabled, onClick = onSelect)
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
    headingLabel: Boolean = false,
    iconSize: Dp? = null,
    iconOffsetY: Dp = 0.dp,
) {
    val colors = LocalCliColors.current
    val resolvedActionColor = when {
        actionColor != Color.Unspecified -> actionColor
        icon == R.drawable.lin_trash -> colors.err
        else -> colors.accent
    }
    val resolvedLabelColor = when {
        labelColor != Color.Unspecified -> labelColor
        actionColor != Color.Unspecified -> resolvedActionColor
        else -> colors.fg
    }
    Row(
        modifier = modifier
            .cliPanelRowPressable(enabled = enabled, onClick = onTap)
            .alpha(if (enabled) 1f else 0.4f),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f, fill = false),
        ) {
            if (icon != null) {
                CliRowLeadingIcon(
                    id = icon,
                    tint = resolvedActionColor,
                    size = iconSize,
                    modifier = Modifier.offset(y = iconOffsetY),
                )
                Spacer(modifier = Modifier.width(6.dp))
            }
            val shownLabel = if (headingLabel) cliHeadingText(label) else cliLabelText(label)
            Text(
                text = shownLabel,
                style = if (headingLabel) {
                    cliTitleStyle(shownLabel).copy(
                        lineHeight = CliType.body.lineHeight,
                    )
                } else {
                    cliRowTextStyle()
                },
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
            CliIcon(
                id = R.drawable.lin_arrow_right,
                contentDescription = null,
                size = 12.dp,
                tint = resolvedActionColor,
                modifier = Modifier.offset(
                    y = CLI_DISCLOSURE_GLYPH_OFFSET,
                ),
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
            icon = R.drawable.lin_trash,
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

@Composable
internal fun CliActiveDot(active: Boolean, modifier: Modifier = Modifier) {
    val colors = LocalCliColors.current
    Box(
        modifier = modifier.width(CLI_ACTIVE_DOT_SLOT_WIDTH),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (active) {
            Box(
                modifier = Modifier
                    .size(ACTIVE_DOT_SIZE)
                    .clip(CircleShape)
                    .background(colors.accent),
            )
        }
    }
}

private val ACTIVE_DOT_SIZE = 8.dp

internal val CLI_ACTIVE_DOT_SLOT_WIDTH = ACTIVE_DOT_SIZE + 2.dp

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
        CliIcon(
            id = R.drawable.lin_arrow_right,
            contentDescription = null,
            size = 12.dp,
            tint = if (tint == Color.Unspecified) colors.accent else tint,
            modifier = Modifier.offset(
                y = CLI_DISCLOSURE_GLYPH_OFFSET,
            ),
        )
    } else {
        Spacer(modifier = Modifier.width(12.dp))
    }
    Spacer(modifier = Modifier.width(6.dp))
}
