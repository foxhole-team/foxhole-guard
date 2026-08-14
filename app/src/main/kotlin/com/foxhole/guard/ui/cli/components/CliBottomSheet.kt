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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
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
 * The body is height-capped and scrolls — that is the component's job, not each caller's. Two
 * sheets used to do it themselves and the rest did not, so a long one (profile details with their
 * metrics table) simply ran off the bottom of a short screen. The cap stays BELOW the full window
 * for the reason the statistics sheet first documented: content taller than the window pushes
 * `ModalBottomSheet` into its full-height regime, where the anchors depend on inset-coupled content
 * height, and on device that feedback loop made the sheet visibly jitter. A body that fits is laid
 * out exactly as before — `heightIn` only caps, it does not stretch.
 *
 * [footer] is the escape hatch for a pinned action row that must stay reachable without scrolling
 * (the first-run quick start): it sits below the scroll area, inside the same gutters.
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
    // Long, internally scrollable reading sheets can hand every vertical gesture to their body.
    // This prevents the modal anchor and the inner scroll from alternately consuming one fling.
    sheetGesturesEnabled: Boolean = true,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalCliColors.current
    val bodyMaxHeight = (LocalConfiguration.current.screenHeightDp * CLI_SHEET_MAX_BODY_FRACTION).dp
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        sheetGesturesEnabled = sheetGesturesEnabled,
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
            Column(
                // fillMaxWidth keeps the body exactly as wide as the sheet was before it gained a
                // scroll container, so ColumnScope alignments inside callers still resolve against
                // the full gutter-to-gutter width rather than the widest row.
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = bodyMaxHeight)
                    .verticalScroll(rememberScrollState()),
                content = content,
            )
            footer?.invoke(this)
        }
    }
}

/**
 * How much of the screen the scrollable body may claim. The title row and an optional footer sit
 * outside it, so the sheet as a whole still stops short of the full window — see [CliBottomSheet].
 */
private const val CLI_SHEET_MAX_BODY_FRACTION = 0.72f

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
