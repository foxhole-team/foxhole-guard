package com.foxhole.guard.ui.cli.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliColors

/**
 * A [CliDropdownRow] option. [detail] is an optional dimmed `· detail` after the label;
 * [swatch] prefixes the label with a `■` of that colour so palette and accent selects show
 * the candidate in the menu itself.
 */
internal data class CliDropdownOption(
    val id: String,
    val label: String,
    val detail: String? = null,
    val swatch: Color? = null,
    // Pixel glyph (vpn/tor/block lanes, site rules) drawn in the same slot as the swatch.
    @DrawableRes val icon: Int? = null,
    // Optional semantic lane/status colour. Null keeps the ordinary selected/dim menu grammar.
    val iconTint: Color? = null,
)

/**
 * `label ....... value ▸` — a select with an anchored popup instead of inline expansion.
 *
 * Replaces [CliSelectRow] + [CliOptionRow] where inline expansion pushes neighbouring rows
 * off-screen on long lists: options float over the content instead. The menu anchors to the
 * row's *tail* so it opens at the tap point; on narrow screens DropdownMenu clamps itself to
 * the window edge. Openness is internal state — only onSelect(id) escapes.
 *
 * Token values (STATUS, kill switch, enum names like prefer_ipv4, tcp/udp) print verbatim
 * and are *not* localised — they are terminal vocabulary, identical in every locale. The
 * human labels beside them are translated.
 */
@Composable
internal fun CliDropdownRow(
    label: String,
    value: String,
    options: List<CliDropdownOption>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    note: String? = null,
    labelColor: Color = Color.Unspecified,
    valueColor: Color = Color.Unspecified,
    // Pixel glyph before the label: a dropdown is an interactive destination, so it follows the
    // same orange affordance law as sub-screen actions. [leading] overrides it when set.
    @DrawableRes icon: Int? = null,
    // Optional slot before the label (app icon, lane glyph).
    leading: (@Composable () -> Unit)? = null,
    // Scenario/lane selectors keep the selected semantic glyph beside their current value.
    showSelectedOptionIcon: Boolean = false,
) {
    val colors = LocalCliColors.current
    var open by remember { mutableStateOf(false) }
    // Measuring is deferred to the first open: settings screens carry a dozen dropdowns and
    // sizing every closed menu on first composition is pure waste. everOpened stays true after
    // closing so the width does not vanish under the dismiss animation.
    var everOpened by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    // The bound is the window, not a constant: a fixed cap ellipsized exactly the tail that told
    // two options apart, and it did so even on wide screens with room to spare.
    val maxMenuWidth = (LocalConfiguration.current.screenWidthDp - CLI_DROPDOWN_WINDOW_MARGIN).dp
    val menuWidth = rememberCliDropdownMenuWidth(
        options = options,
        everOpened = everOpened,
        maxMenuWidth = maxMenuWidth,
    )
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp)
                .cliPressable(enabled = enabled, role = Role.DropdownList) {
                    everOpened = true
                    open = !open
                }
                .alpha(if (enabled) 1f else 0.4f),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) {
                leading()
                Spacer(modifier = Modifier.width(6.dp))
            } else if (icon != null) {
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
                color = if (labelColor == Color.Unspecified) colors.dim else labelColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // The anchor is the row tail, so the popup opens at the tap point. The anchor is
            // narrow and the menu can be far wider: without an explicit offset the M3 positioner
            // falls back after overflowing the right edge and the menu lands differently per
            // section padding. The negative offset pins the menu's right edge to the anchor's.
            var anchorWidthPx by remember { mutableIntStateOf(0) }
            val menuOffset = if (menuWidth != Dp.Unspecified) {
                DpOffset(x = with(density) { anchorWidthPx.toDp() } - menuWidth, y = 0.dp)
            } else {
                DpOffset.Zero
            }
            Box(modifier = Modifier.onSizeChanged { anchorWidthPx = it.width }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CliSelectedOptionIcon(
                        option = options.firstOrNull { option -> option.id == selectedId },
                        visible = showSelectedOptionIcon,
                    )
                    Text(
                        text = value,
                        style = CliType.body,
                        color = if (valueColor == Color.Unspecified) colors.fg else valueColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    CliDisclosureGlyph(
                        expanded = open,
                        color = if (enabled) colors.accent else colors.dim,
                    )
                }
                DropdownMenu(
                    expanded = open,
                    onDismissRequest = { open = false },
                    offset = menuOffset,
                    // The measured width is a FLOOR, not the width: the menu's own intrinsics take
                    // over when a real line comes out a hair wider than the TextMeasurer estimate
                    // (a hard width ellipsized "10s" into "1…"). The window margin stays the cap.
                    modifier = if (menuWidth != Dp.Unspecified) {
                        Modifier.widthIn(min = menuWidth, max = maxMenuWidth)
                    } else {
                        Modifier
                    },
                    shape = RoundedCornerShape(2.dp),
                    containerColor = colors.bg,
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp,
                    border = BorderStroke(1.dp, colors.border),
                ) {
                    options.forEach { option ->
                        CliDropdownOptionLine(
                            option = option,
                            selected = option.id == selectedId,
                            onClick = {
                                open = false
                                onSelect(option.id)
                            },
                        )
                    }
                }
            }
        }
        note?.let {
            CliElbowLine(
                text = it,
                modifier = Modifier.padding(bottom = CliSpacing.xs),
            )
        }
    }
}

@Composable
private fun CliSelectedOptionIcon(
    option: CliDropdownOption?,
    visible: Boolean,
) {
    val icon = option?.icon ?: return
    if (!visible) return
    val colors = LocalCliColors.current
    CliPixIcon(
        id = icon,
        contentDescription = null,
        size = 12.dp,
        tint = option.iconTint ?: colors.accent,
    )
    Spacer(modifier = Modifier.width(4.dp))
}

/**
 * Menu width follows the longest `label · detail` plus the fixed cursor and swatch slots,
 * bounded only by the window ([CLI_DROPDOWN_WINDOW_MARGIN]) — text length, not a constant,
 * decides the width, which is what keeps long translations whole. CliType.body is a composable
 * getter, so it is read *before* remember and kept in the keys — otherwise a font change never
 * remeasures. Returns [Dp.Unspecified] until the first open.
 */
@Composable
private fun rememberCliDropdownMenuWidth(
    options: List<CliDropdownOption>,
    everOpened: Boolean,
    maxMenuWidth: Dp,
): Dp {
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val bodyStyle = CliType.body
    val menuWidthLazy = remember(options, bodyStyle, density, maxMenuWidth) {
        lazy {
            val hasSwatch = options.any { it.swatch != null }
            val hasIcon = options.any { it.icon != null }
            val menuTextWidthPx = options.maxOfOrNull { option ->
                val line = buildString {
                    append(option.label)
                    option.detail?.let { append(" · ").append(it) }
                }
                textMeasurer.measure(text = line, style = bodyStyle).size.width
            } ?: 0
            with(density) {
                (
                    menuTextWidthPx.toDp() + CliDropdownItemPadding * 2 + 18.dp +
                        (if (hasSwatch) 16.dp else 0.dp) +
                        (if (hasIcon) 18.dp else 0.dp)
                    ).coerceAtMost(maxMenuWidth)
            }
        }
    }
    return if (everOpened) menuWidthLazy.value else Dp.Unspecified
}

/** A menu item: our own row, not the DropdownMenuItem default. */
@Composable
private fun CliDropdownOptionLine(
    option: CliDropdownOption,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalCliColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .cliPressable(onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = CliDropdownItemPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CliMenuCursor(selected = selected)
        option.swatch?.let { swatch ->
            // A real box rather than the ■ glyph, so it survives any font.
            Box(
                modifier = Modifier
                    .width(10.dp)
                    .height(10.dp)
                    .background(swatch),
            )
            Spacer(modifier = Modifier.width(6.dp))
        }
        option.icon?.let { iconRes ->
            CliPixIcon(
                id = iconRes,
                contentDescription = null,
                size = 12.dp,
                tint = option.iconTint ?: if (selected) colors.accent else colors.dim,
            )
            Spacer(modifier = Modifier.width(6.dp))
        }
        Text(
            text = option.label,
            style = CliType.body,
            color = if (selected) colors.accent else colors.fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        option.detail?.let {
            Text(
                text = " · $it",
                style = CliType.body,
                color = colors.dim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private val CliDropdownItemPadding = 12.dp

// Left free beside a full-width menu so it reads as a popup rather than a second screen.
private const val CLI_DROPDOWN_WINDOW_MARGIN = 24
