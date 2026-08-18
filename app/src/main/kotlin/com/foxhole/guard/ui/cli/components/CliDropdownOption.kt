package com.foxhole.guard.ui.cli.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
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
import com.foxhole.core.model.VisualStyle
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.LocalCliPanelAppearance
import com.foxhole.guard.ui.cli.LocalCliVisualStyle
import com.foxhole.guard.ui.cli.cliLabelText
import com.foxhole.guard.ui.cli.cliRowTextStyle

internal data class CliDropdownOption(
    val id: String,
    val label: String,
    val detail: String? = null,
    val swatch: Color? = null,
    @DrawableRes val icon: Int? = null,
    val iconTint: Color? = null,
    val flagCountry: String? = null,
)

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
    infoText: String? = null,
    labelColor: Color = Color.Unspecified,
    valueColor: Color = Color.Unspecified,
    @DrawableRes icon: Int? = null,
    leading: (@Composable () -> Unit)? = null,
    showSelectedOptionIcon: Boolean = false,
) {
    val colors = LocalCliColors.current
    var infoOpen by rememberSaveable(label) { mutableStateOf(false) }
    if (infoOpen && infoText != null) {
        CliInfoSheet(text = infoText, onDismiss = { infoOpen = false })
    }
    var open by remember { mutableStateOf(false) }
    var everOpened by remember { mutableStateOf(false) }
    val density = LocalDensity.current
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
            CliDropdownLabel(
                label = label,
                labelColor = labelColor,
                hasInfo = infoText != null,
                onInfoTap = { infoOpen = true },
            )
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
                        text = cliLabelText(value),
                        style = cliRowTextStyle(),
                        color = if (valueColor == Color.Unspecified) colors.fg else valueColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.width(ROW_VALUE_GLYPH_GAP))
                    CliDisclosureGlyph(
                        expanded = open,
                        color = if (enabled) colors.accent else colors.dim,
                    )
                }
                DropdownMenu(
                    expanded = open,
                    onDismissRequest = { open = false },
                    offset = menuOffset,
                    modifier = if (menuWidth != Dp.Unspecified) {
                        Modifier.widthIn(min = menuWidth, max = maxMenuWidth)
                    } else {
                        Modifier
                    },
                    shape = RoundedCornerShape(CLI_DROPDOWN_WINDOW_CORNER),
                    containerColor = cliDropdownContainerColor(colors.panel),
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
private fun RowScope.CliDropdownLabel(
    label: String,
    labelColor: Color,
    hasInfo: Boolean,
    onInfoTap: () -> Unit,
) {
    val colors = LocalCliColors.current
    Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = cliLabelText(label),
            style = cliRowTextStyle(),
            color = if (labelColor == Color.Unspecified) colors.dim else labelColor,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (hasInfo) {
            CliRowInfoGlyph(onTap = onInfoTap)
        }
    }
}

@Composable
private fun cliDropdownContainerColor(panel: Color): Color =
    cliModalSurfaceColor(LocalCliPanelAppearance.current, panel)

@Composable
internal fun CliSwatchDot(color: Color) {
    val round = LocalCliVisualStyle.current == VisualStyle.PLAIN
    Box(
        modifier = Modifier
            .width(10.dp)
            .height(10.dp)
            .then(if (round) Modifier.clip(CircleShape) else Modifier)
            .background(color),
    )
}

@Composable
private fun CliSelectedOptionIcon(
    option: CliDropdownOption?,
    visible: Boolean,
) {
    if (option == null || !visible) return
    if (option.flagCountry != null) {
        CliFlagIcon(countryCode = option.flagCountry, style = CliType.body)
        Spacer(modifier = Modifier.width(4.dp))
        return
    }
    if (option.swatch != null) {
        CliSwatchDot(option.swatch)
        Spacer(modifier = Modifier.width(4.dp))
        return
    }
    val icon = option.icon ?: return
    val colors = LocalCliColors.current
    CliPixIcon(
        id = icon,
        contentDescription = null,
        size = 12.dp,
        tint = option.iconTint ?: colors.accent,
    )
    Spacer(modifier = Modifier.width(4.dp))
}

@Composable
private fun rememberCliDropdownMenuWidth(
    options: List<CliDropdownOption>,
    everOpened: Boolean,
    maxMenuWidth: Dp,
): Dp {
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val bodyStyle = cliRowTextStyle()
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
            CliSwatchDot(swatch)
            Spacer(modifier = Modifier.width(6.dp))
        }
        option.flagCountry?.let { country ->
            CliFlagIcon(countryCode = country, style = CliType.body)
            Spacer(modifier = Modifier.width(6.dp))
        }
        if (option.flagCountry == null) {
            option.icon?.let { iconRes ->
                CliPixIcon(
                    id = iconRes,
                    contentDescription = null,
                    size = 12.dp,
                    tint = option.iconTint ?: if (selected) colors.accent else colors.dim,
                )
                Spacer(modifier = Modifier.width(6.dp))
            }
        }
        Text(
            text = cliLabelText(option.label),
            style = cliRowTextStyle(),
            color = if (selected) colors.accent else colors.fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        option.detail?.let {
            Text(
                text = " · $it",
                style = cliRowTextStyle(),
                color = colors.dim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private val CliDropdownItemPadding = 12.dp

private val CLI_DROPDOWN_WINDOW_CORNER = 14.dp

private const val CLI_DROPDOWN_WINDOW_MARGIN = 24
