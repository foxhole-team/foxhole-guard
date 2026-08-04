package com.foxhole.guard.ui.cli.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.foxhole.guard.R
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.cliDisplayStyle

/**
 * Terminal prompt header of a top-level screen: `fox > stats`. One dim small-type line
 * with a 6dp gap below - just enough to name the tab. An optional [suffix] is rendered
 * dim after the label (` · selected: 3` while profiles marks rows for export); the label
 * ellipsizes first so the suffix stays readable. The home screen keeps its own brand
 * header with the fox sprite and never uses this.
 *
 * [icon] is the 16dp screen glyph before the prompt, accent-tinted; every header carries one.
 *
 * [trailing] is the screen's own control pinned to the right edge (the statistics gear) — the CLI
 * has no top app bar, so this header is where a screen-level action belongs. Passing one makes the
 * label expand to push the control right; without it the header still wraps its content.
 */
@Composable
internal fun CliScreenHeader(
    label: String,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    suffix: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = LocalCliColors.current
    Row(
        modifier = modifier
            .then(if (trailing != null) Modifier.fillMaxWidth() else Modifier)
            .padding(bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            CliPixIcon(id = icon, contentDescription = null, size = 16.dp, tint = colors.accent)
            Spacer(modifier = Modifier.width(CliSpacing.xs))
        }
        val headerText = stringResource(R.string.cli_screen_header, label)
        Text(
            text = headerText,
            // A Cyrillic title moves wholesale to PS2P, since Silkscreen is Latin-only.
            style = cliDisplayStyle(headerText),
            color = colors.accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = trailing != null),
        )
        if (suffix != null) {
            Text(
                text = " · $suffix",
                style = CliType.small,
                color = colors.dim,
                maxLines = 1,
            )
        }
        if (trailing != null) {
            // A fixed-height slot: the 48dp button overlaps neighbouring padding instead of
            // inflating the header, so screens with and without a trailing control match.
            Box(
                modifier = Modifier.height(CliHeaderControlSlotHeight),
                contentAlignment = Alignment.Center,
            ) {
                trailing()
            }
        }
    }
}

// The canon display line height; the trailing control centres on it.
internal val CliHeaderControlSlotHeight = 21.dp

/**
 * The back row for full-screen push screens. Settings sub-screens with [CliScreenHeader] live
 * without it — their only way back is system back. There is no third pattern.
 */
@Composable
internal fun CliBackRow(
    label: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCliColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .cliPressable(onClick = onBack),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = "← ", style = CliType.body, color = colors.accent)
        Text(
            text = label,
            style = CliType.body,
            color = colors.dim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
