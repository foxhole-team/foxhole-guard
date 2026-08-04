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

/** `label ............ [x]` - instant toggle, no confirmation. */
@Composable
internal fun CliToggleRow(
    label: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    note: String? = null,
    noteColor: Color = Color.Unspecified,
    enabled: Boolean = true,
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
            Text(
                text = label,
                style = CliType.body,
                color = if (checked) colors.fg else colors.dim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
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
        Text(
            text = label,
            style = CliType.body,
            color = colors.dim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
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

/** `label ....... value ▸` one-shot action row (opens a flow or a sub-screen). */
@Composable
internal fun CliActionRow(
    label: String,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    value: String? = null,
    enabled: Boolean = true,
    icon: Int? = null,
) {
    val colors = LocalCliColors.current
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
                CliPixIcon(id = icon, contentDescription = null, tint = colors.accent)
                Spacer(modifier = Modifier.width(6.dp))
            }
            Text(
                text = label,
                style = CliType.body,
                color = colors.fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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
                tint = colors.accent,
            )
        }
    }
}

/** Inline y/n confirmation: a warn-toned question line plus the two chips. */
@Composable
internal fun CliYesNoRow(
    question: String,
    onYes: () -> Unit,
    onNo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCliColors.current
    Column(modifier = modifier) {
        Text(
            text = question,
            style = CliType.body,
            color = colors.warn,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(CliSpacing.sm)) {
            CliChip(
                label = stringResource(R.string.cli_common_yes_confirm),
                color = colors.warn,
                onClick = onYes,
            )
            CliChip(label = stringResource(R.string.cli_common_no_cancel), onClick = onNo)
        }
    }
}

/**
 * A destructive action row: first the label of what it erases, then an inline y/n on tap — never a
 * system dialog. The host keeps one armed key per group, so tapping another row re-arms it. [note]
 * survives into the confirmation state, because it must be read *before* "y".
 */
@Composable
internal fun CliDestructiveRow(
    key: String,
    label: String,
    confirmAction: String?,
    onArm: (String?) -> Unit,
    onConfirm: () -> Unit,
    note: String? = null,
) {
    if (confirmAction == key) {
        CliYesNoRow(
            question = stringResource(R.string.cli_data_destructive_confirm, label),
            onYes = {
                onArm(null)
                onConfirm()
            },
            onNo = { onArm(null) },
        )
    } else {
        CliActionRow(label = label, onTap = { onArm(key) })
    }
    note?.let { CliElbowLine(text = it) }
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
