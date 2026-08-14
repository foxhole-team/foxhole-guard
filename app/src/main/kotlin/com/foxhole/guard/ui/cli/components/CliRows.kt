package com.foxhole.guard.ui.cli.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.foxhole.guard.R
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliColors

/**
 * Interactive setting rows of the CLI idiom. The affordance contract: a row is tappable
 * only when it carries a glyph - `[x]`/`[ ]` toggle, `value ▸` select trigger (`▾` when
 * expanded), pointer-cursor radio option, trailing `▸` action (the arrows are 16x16
 * pixel-pack icons). Plain text never reacts to taps. All rows keep the >= 48dp
 * touch-target floor while staying one text line tall visually.
 */

/**
 * The shared disclosure arrow of select triggers and collapsible captions - a right/down
 * pointer from the pixel pack, slightly smaller than the neighbouring text and anchored
 * at the far right so it reads as an affordance without pulling the value out of
 * alignment. Color contract: accent while interactive, dim when disabled.
 */
@Composable
internal fun CliDisclosureGlyph(
    expanded: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
) {
    CliPixIcon(
        id = if (expanded) R.drawable.pix_arrow_down else R.drawable.pix_arrow_right,
        contentDescription = null,
        size = 12.dp,
        tint = color,
        modifier = modifier,
    )
}

/**
 * `label ............ [x]` - instant toggle, no confirmation. An optional leading [icon] puts the
 * row in the same `glyph + word` grammar as [CliActionRow], the sheet titles and the screen
 * headers; module rows use it so a component reads as a thing rather than as a preference.
 */
@Composable
internal fun CliToggleRow(
    label: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    note: String? = null,
    noteColor: Color = Color.Unspecified,
    enabled: Boolean = true,
    icon: Int? = null,
) {
    val colors = LocalCliColors.current
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
                    // Toggle-state colour law: a disabled option is neutral grey, an active
                    // option is green. It is intentionally distinct from the orange navigation
                    // affordance used by action/dropdown rows.
                    CliPixIcon(
                        id = icon,
                        contentDescription = null,
                        tint = if (checked) colors.ok else colors.dim,
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text(
                    text = label,
                    style = CliType.body,
                    color = if (checked) colors.fg else colors.dim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = if (checked) "[x]" else "[ ]",
                style = CliType.body,
                color = if (checked) colors.ok else colors.faint,
                maxLines = 1,
            )
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

/** `label ....... value ▸` select trigger; the glyph flips to `▾` while expanded. */
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
                text = label,
                style = CliType.body,
                color = colors.dim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = value,
                style = CliType.body,
                color = if (valueColor == Color.Unspecified) colors.fg else valueColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.width(6.dp))
            CliDisclosureGlyph(
                expanded = expanded,
                color = if (enabled) colors.accent else colors.dim,
            )
        }
    }
}

/** Indented `● option · detail` radio row inside an expanded select. */
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
            text = text,
            style = CliType.body,
            color = if (selected) colors.fg else colors.dim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        detail?.let {
            Text(
                text = " · $it",
                style = CliType.body,
                color = colors.faint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * `label ....... value ▸` one-shot action row (opens a flow or a sub-screen). [attention]
 * blinks the warn pixel after the label — "this destination wants an action" (pending update).
 */
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
    val resolvedActionColor = if (actionColor == Color.Unspecified) colors.accent else actionColor
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
                text = label,
                style = CliType.body,
                color = resolvedLabelColor,
                maxLines = 1,
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
                    text = "$it ",
                    style = CliType.body,
                    color = colors.dim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
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

/**
 * A destructive action row: the label of what it erases, and on tap the shared bottom confirm
 * modal — never a system dialog and never an inline y/n. The host keeps one armed key per group,
 * so tapping another row re-arms it and only one sheet can ever be up.
 *
 * The row itself stays put while the sheet is open: the question is asked over the screen, not by
 * replacing the row that asked it, so the list under the finger never reflows. [note] is printed
 * both under the row and inside the sheet, because it must be read *before* the confirm.
 */
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
 * The menu cursor slot: an arrow on the selected item and an empty slot of the same width on the
 * rest, so menu columns do not drift. Replaces three copy-pastes.
 */
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
