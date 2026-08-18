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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.foxhole.core.model.PanelAppearance
import com.foxhole.guard.R
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.LocalCliPanelAppearance
import com.foxhole.guard.ui.cli.cliDisplayStyle
import com.foxhole.guard.ui.cli.cliLabelText

/**
 * The body height cap stays below the full window: taller content pushes ModalBottomSheet into its full-height regime, where inset-coupled anchors make the sheet jitter on device.
 * SheetState is kept out of the signature because the module has no global ExperimentalMaterial3Api opt-in.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CliBottomSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    @DrawableRes icon: Int? = null,
    trailing: (@Composable () -> Unit)? = null,
    sheetGesturesEnabled: Boolean = true,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalCliColors.current
    val appearance = LocalCliPanelAppearance.current
    val sheetShape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    val bodyMaxHeight = (LocalConfiguration.current.screenHeightDp * CLI_SHEET_MAX_BODY_FRACTION).dp
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        shape = sheetShape,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        sheetGesturesEnabled = sheetGesturesEnabled,
        containerColor = cliModalSurfaceColor(appearance, colors.panel),
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
                            tint = if (icon == R.drawable.pix_trash) colors.err else colors.accent,
                        )
                        Spacer(modifier = Modifier.width(CliSpacing.xs))
                    }
                    Text(
                        text = cliLabelText(title),
                        style = cliDisplayStyle(title),
                        color = colors.accent,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = trailing != null),
                    )
                    if (trailing != null) {
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

internal fun cliModalSurfaceColor(appearance: PanelAppearance, panelColor: Color): Color =
    if (appearance == PanelAppearance.DARK) Color.Black else panelColor

private const val CLI_SHEET_MAX_BODY_FRACTION = 0.72f
private val CLI_SHEET_EDGE_RIM = 1.dp

@Composable
private fun CliSheetHandle() {
    val colors = LocalCliColors.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Spacer(
            modifier = Modifier
                .fillMaxWidth()
                .height(CLI_SHEET_EDGE_RIM)
                .background(colors.border),
        )
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
}
