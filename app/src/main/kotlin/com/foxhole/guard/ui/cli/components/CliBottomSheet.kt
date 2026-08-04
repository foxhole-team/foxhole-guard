package com.foxhole.guard.ui.cli.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.cliDisplayStyle

/**
 * The one bottom sheet of the app — every panel that slides up from the bottom edge goes through
 * here so they share a single look: panel background, terminal foreground, a 0.72 scrim over the
 * black, and the flat pixel-bar handle instead of Material's pill.
 *
 * [title] is the optional terminal caption printed in accent display type above the body, with a
 * mandatory-style 16dp pixel [icon] before it — screen headers, panel captions and sheet titles
 * all speak the same language of glyph plus word. The body runs in a [ColumnScope] already padded to the
 * screen gutters, so callers only lay out their rows.
 *
 * Deliberately keeps `SheetState` out of the signature: the module has no global opt-in for
 * `ExperimentalMaterial3Api`, so the experimental type must not leak into callers.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CliBottomSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    @DrawableRes icon: Int? = null,
    // A sheet control at the right of the title row, mirroring the header's trailing slot.
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalCliColors.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.panel,
        contentColor = colors.fg,
        scrimColor = colors.bg.copy(alpha = 0.72f),
        dragHandle = { CliSheetHandle() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = CliSpacing.md)
                .padding(bottom = CliSpacing.lg),
        ) {
            if (title != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (icon != null) {
                        CliPixIcon(
                            id = icon,
                            contentDescription = null,
                            size = 16.dp,
                            tint = colors.accent,
                        )
                        Spacer(modifier = Modifier.width(CliSpacing.xs))
                    }
                    Text(
                        text = title,
                        // A Cyrillic title goes wholly to PS2P; Silkscreen is Latin-only.
                        style = cliDisplayStyle(title),
                        color = colors.accent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = trailing != null),
                    )
                    if (trailing != null) {
                        // The same fixed slot as CliScreenHeader: the 48dp button overlaps rather
                        // than inflating the sheet's title row.
                        Box(
                            modifier = Modifier.height(CliHeaderControlSlotHeight),
                            contentAlignment = Alignment.Center,
                        ) {
                            trailing()
                        }
                    }
                }
                Spacer(modifier = Modifier.height(CliSpacing.sm))
            }
            content()
        }
    }
}

// A flat pixel-bar handle in the CLI grammar instead of Material's pill — dim, hard-edged, centred.
@Composable
private fun CliSheetHandle() {
    val colors = LocalCliColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(PaddingValues(vertical = CliSpacing.sm)),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(
            modifier = Modifier
                .width(32.dp)
                .height(3.dp)
                .background(colors.faint, RoundedCornerShape(1.dp)),
        )
    }
}
