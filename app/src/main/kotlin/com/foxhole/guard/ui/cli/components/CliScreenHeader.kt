package com.foxhole.guard.ui.cli.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
 * [icon] is the letter-height screen glyph before the `fhg >` brand and section title,
 * accent-tinted.
 * [iconGlyph] is the pixel-font alternative for a glyph such as the Help screen's question mark.
 *
 * [trailing] is the screen's own control pinned to the right edge (the statistics gear) — the CLI
 * has no top app bar, so this header is where a screen-level action belongs. The full-width outer
 * row owns that alignment centrally; individual screens must not nudge their controls sideways.
 */
@Composable
internal fun CliScreenHeader(
    label: String,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    iconGlyph: String? = null,
    suffix: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = LocalCliColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                CliPixIcon(
                    id = icon,
                    contentDescription = null,
                    size = CliHeaderIconSize,
                    tint = colors.accent,
                )
                Spacer(modifier = Modifier.width(CliSpacing.xs))
            } else if (iconGlyph != null) {
                Box(modifier = Modifier.size(CliHeaderIconSize), contentAlignment = Alignment.Center) {
                    Text(
                        text = iconGlyph,
                        style = CliType.small.copy(fontSize = 12.sp, lineHeight = 12.sp),
                        color = colors.accent,
                        maxLines = 1,
                    )
                }
                Spacer(modifier = Modifier.width(CliSpacing.xs))
            }
            val brandText = stringResource(R.string.cli_screen_header)
            Text(
                text = brandText,
                style = cliDisplayStyle(brandText),
                color = colors.accent,
                maxLines = 1,
            )
            Spacer(modifier = Modifier.width(CliSpacing.xs))
            Text(
                text = label,
                // A Cyrillic title moves wholesale to PS2P, since Silkscreen is Latin-only.
                style = cliDisplayStyle(label),
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
        }
        if (trailing != null) {
            // A fixed-height slot: the 48dp button overlaps neighbouring padding instead of
            // inflating the header, so screens with and without a trailing control match.
            Box(
                modifier = Modifier.height(CliHeaderControlSlotHeight),
                contentAlignment = Alignment.CenterEnd,
            ) {
                trailing()
            }
        }
    }
}

// The canon display line height; the trailing control centres on it.
internal val CliHeaderControlSlotHeight = 21.dp
internal val CliHeaderIconSize = 12.dp

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
