package com.foxhole.guard.ui.cli.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import com.foxhole.core.model.PanelAppearance
import com.foxhole.guard.R
import com.foxhole.guard.ui.cli.CliRadius
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.LocalCliPanelAppearance
import com.foxhole.guard.ui.cli.LocalCliPixelArtEnabled
import com.foxhole.guard.ui.cli.cliHeadingGlyphOpticalOffsetFor
import com.foxhole.guard.ui.cli.cliHeadingText
import com.foxhole.guard.ui.cli.cliPanelHeadingOpticalOffsetFor
import com.foxhole.guard.ui.cli.cliPanelTitleStyle
import com.foxhole.guard.ui.cli.cliVerticalEnter
import com.foxhole.guard.ui.cli.cliVerticalExit

@Composable
@Suppress("LongParameterList")
internal fun CliPanel(
    modifier: Modifier = Modifier,
    title: String? = null,
    titleModifier: Modifier = Modifier,
    titleColor: Color = Color.Unspecified,
    @DrawableRes icon: Int? = null,
    iconColor: Color = Color.Unspecified,
    background: Color = Color.Unspecified,
    collapsible: Boolean = false,
    expanded: Boolean = true,
    onToggleExpanded: (() -> Unit)? = null,
    attention: Boolean = false,
    attentionColor: Color = Color.Unspecified,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    infoText: String? = null,
    accentBorderColor: Color = Color.Unspecified,
    contentPadding: CliPanelContentPadding = CliPanelDefaultContentPadding,
    contentVerticalPadding: Dp = CLI_PANEL_VERTICAL_PADDING,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalCliColors.current
    val panelAppearance = LocalCliPanelAppearance.current
    val shape = RoundedCornerShape(CliRadius.panel)
    var infoOpen by rememberSaveable { mutableStateOf(false) }
    if (infoOpen && infoText != null) {
        CliInfoSheet(
            text = infoText,
            onDismiss = { infoOpen = false },
        )
    }
    val accentEdge = if (accentBorderColor != Color.Unspecified) {
        Modifier.border(1.dp, accentBorderColor.copy(alpha = 0.65f), shape)
    } else {
        Modifier
    }
    Column(
        modifier = modifier
            .clip(shape)
            .background(cliPanelBackground(background, colors.panel, panelAppearance))
            .cliPanelInteraction(onClick = onClick, onLongClick = onLongClick)
            .border(1.dp, colors.border, shape)
            .then(accentEdge),
    ) {
        val headerColor = resolvedPanelColor(titleColor, colors.dim)
        if (title != null && collapsible) {
            CliPanelCollapsibleHeader(
                title = title,
                headerColor = headerColor,
                icon = icon,
                iconColor = iconColor,
                expanded = expanded,
                onToggleExpanded = onToggleExpanded,
                attention = attention,
                attentionColor = attentionColor,
                hasInfo = infoText != null,
                onInfoTap = { infoOpen = true },
            )
        }
        if (collapsible) {
            val stateHolder = rememberSaveableStateHolder()
            AnimatedVisibility(
                visible = expanded,
                enter = cliVerticalEnter(),
                exit = cliVerticalExit(),
            ) {
                stateHolder.SaveableStateProvider(key = title ?: CLI_PANEL_STATE_KEY) {
                    CompositionLocalProvider(
                        LocalCliPanelRowHorizontalPadding provides contentPadding.rowHorizontal,
                    ) {
                        Column(
                            modifier = Modifier.padding(
                                horizontal = contentPadding.outerHorizontal,
                                vertical = contentVerticalPadding,
                            ),
                            content = content,
                        )
                    }
                }
            }
        } else {
            CompositionLocalProvider(
                LocalCliPanelRowHorizontalPadding provides contentPadding.rowHorizontal,
            ) {
                Column(
                    modifier = Modifier.padding(
                        horizontal = contentPadding.outerHorizontal,
                        vertical = contentVerticalPadding,
                    ),
                ) {
                    if (title != null) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = contentPadding.rowHorizontal),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CliPanelTitleGroup(
                                title = title,
                                color = headerColor,
                                icon = icon,
                                iconColor = iconColor,
                                hasInfo = infoText != null,
                                onInfoTap = { infoOpen = true },
                                modifier = Modifier
                                    .weight(1f)
                                    .then(titleModifier),
                            )
                            if (attention) {
                                CliAttentionPixel(
                                    color = resolvedPanelColor(attentionColor, colors.warn),
                                )
                            }
                        }
                    }
                    content()
                }
            }
        }
    }
}

internal data class CliPanelContentPadding(
    val outerHorizontal: Dp,
    val rowHorizontal: Dp,
)

internal val CliPanelDefaultContentPadding = CliPanelContentPadding(
    outerHorizontal = CliSpacing.md,
    rowHorizontal = 0.dp,
)

internal val CliPanelEdgeToEdgeContentPadding = CliPanelContentPadding(
    outerHorizontal = 0.dp,
    rowHorizontal = CliSpacing.md,
)

internal val LocalCliPanelRowHorizontalPadding = staticCompositionLocalOf { 0.dp }

@Composable
private fun CliPanelCollapsibleHeader(
    title: String,
    headerColor: Color,
    @DrawableRes icon: Int?,
    iconColor: Color,
    expanded: Boolean,
    onToggleExpanded: (() -> Unit)?,
    attention: Boolean,
    attentionColor: Color,
    hasInfo: Boolean,
    onInfoTap: () -> Unit,
) {
    val colors = LocalCliColors.current
    val stateText = stringResource(
        if (expanded) R.string.cli_panel_expanded else R.string.cli_panel_collapsed,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = CLI_MENU_ROW_MIN_HEIGHT)
            .cliPressable(enabled = onToggleExpanded != null) {
                onToggleExpanded?.invoke()
            }
            .semantics {
                stateDescription = stateText
                onToggleExpanded?.let { toggle ->
                    if (expanded) {
                        collapse {
                            toggle()
                            true
                        }
                    } else {
                        expand {
                            toggle()
                            true
                        }
                    }
                }
            }
            .padding(horizontal = CliSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CliPanelTitleGroup(
            title = title,
            color = headerColor,
            icon = icon,
            iconColor = iconColor,
            hasInfo = hasInfo,
            onInfoTap = onInfoTap,
            modifier = Modifier.weight(1f),
        )
        if (attention) {
            CliAttentionPixel(
                color = resolvedPanelColor(attentionColor, colors.warn),
            )
            Spacer(modifier = Modifier.width(CliSpacing.sm))
        }
        CliDisclosureGlyph(
            expanded = expanded,
            color = if (onToggleExpanded != null) colors.accent else colors.dim,
        )
    }
}

@Composable
private fun CliPanelTitleGroup(
    title: String,
    color: Color,
    @DrawableRes icon: Int?,
    iconColor: Color,
    hasInfo: Boolean,
    onInfoTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shownTitle = cliHeadingText(title)
    val pixelArtEnabled = LocalCliPixelArtEnabled.current
    val iconMetricOverrides = LocalCliIconMetricOverrides.current
    val headerGlyphLift = iconMetricOverrides.panelHeaderGlyphLift
        ?: cliPanelHeaderGlyphLiftFor(shownTitle, pixelArtEnabled)
    val leadingIconLiftAdjustment = iconMetricOverrides.panelHeaderLeadingIconLiftAdjustment
        ?: cliPanelHeaderLeadingIconLiftFor(pixelArtEnabled)
    val headerIconLift = headerGlyphLift + leadingIconLiftAdjustment
    val headerIconSize = iconMetricOverrides.panelHeaderIconSize
        ?: CLI_PANEL_HEADER_LEADING_ICON_SIZE
    val headerContentDrop = iconMetricOverrides.panelHeaderContentDrop ?: 0.dp
    Row(
        modifier = modifier.offset(y = headerContentDrop),
        verticalAlignment = Alignment.Top,
    ) {
        if (icon != null) {
            CliSectionHeaderIcon(
                icon = icon,
                tint = iconColor.takeIf { it != Color.Unspecified }
                    ?: LocalCliColors.current.err.takeIf { icon == R.drawable.lin_trash }
                    ?: color.takeIf { it != Color.Unspecified }
                    ?: LocalCliColors.current.accent,
                controlWidth = CLI_PANEL_HEADER_LEADING_CONTROL_WIDTH,
                iconSize = headerIconSize,
                modifier = Modifier.offset(
                    y = headerIconLift,
                ),
            )
            Spacer(modifier = Modifier.width(CLI_PANEL_HEADER_LEADING_GAP))
        }
        CliPanelCaption(
            shownTitle = shownTitle,
            color = color,
            modifier = Modifier.weight(
                weight = 1f,
                fill = hasInfo && iconMetricOverrides.panelHeaderInfoAtEnd,
            ),
        )
        if (hasInfo) {
            Spacer(modifier = Modifier.width(CliSpacing.xs))
            CliPanelInfoGlyph(
                title = shownTitle,
                iconSize = headerIconSize,
                controlOffsetY = headerIconLift,
                onTap = onInfoTap,
            )
        }
    }
}

@Composable
private fun CliPanelInfoGlyph(
    title: String,
    iconSize: Dp,
    controlOffsetY: Dp,
    onTap: () -> Unit,
) {
    CliHeaderHelpButton(
        contentDescription = stringResource(R.string.cli_common_information),
        modifier = Modifier.offset(y = controlOffsetY),
        alignIconToFirstLine = true,
        firstLineText = title,
        iconSize = iconSize,
        iconOffsetY = 0.dp,
        onClick = onTap,
    )
}

internal fun cliPanelHeaderGlyphLiftFor(
    text: String,
    pixelArtEnabled: Boolean = true,
): Dp = cliHeadingGlyphOpticalOffsetFor(text, pixelArtEnabled)

internal fun cliPanelHeaderLeadingIconLiftFor(pixelArtEnabled: Boolean = true): Dp =
    CLI_HEADER_ICON_LIFT + if (pixelArtEnabled) 0.dp else CLI_MONO_PANEL_HEADER_ICON_LIFT

internal val CLI_PANEL_HEADER_LEADING_CONTROL_WIDTH = 16.dp

internal val CLI_PANEL_HEADER_LEADING_ICON_SIZE = 18.dp

internal val CLI_PANEL_HEADER_LEADING_GAP = 3.dp

private val CLI_MONO_PANEL_HEADER_ICON_LIFT = (-1).dp

internal fun cliPanelBackground(
    candidate: Color,
    fallback: Color,
    appearance: PanelAppearance,
): Color = when {
    candidate != Color.Unspecified -> candidate
    appearance == PanelAppearance.DARK -> Color.Transparent
    else -> fallback
}

private fun resolvedPanelColor(candidate: Color, fallback: Color): Color = when (candidate) {
    Color.Unspecified -> fallback
    else -> candidate
}

@Composable
private fun Modifier.cliPanelInteraction(
    onClick: (() -> Unit)?,
    onLongClick: (() -> Unit)?,
): Modifier = then(
    when {
        onLongClick != null -> Modifier.cliCombinedPressable(
            onLongClick = onLongClick,
            onClick = { onClick?.invoke() },
        )
        onClick != null -> Modifier.cliPressable(onClick = onClick)
        else -> Modifier
    },
)

@Composable
private fun CliPanelCaption(
    shownTitle: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val pixelArtEnabled = LocalCliPixelArtEnabled.current
    val iconMetricOverrides = LocalCliIconMetricOverrides.current
    val baseStyle = cliPanelTitleStyle(shownTitle)
    val overriddenFontSize = iconMetricOverrides.panelHeaderFontSize.takeIf { it.isSpecified }
    val overriddenLineHeight = iconMetricOverrides.panelHeaderLineHeight.takeIf { it.isSpecified }
    val headingStyle = baseStyle.copy(
        fontSize = overriddenFontSize ?: baseStyle.fontSize,
        lineHeight = overriddenLineHeight ?: baseStyle.lineHeight,
    )
    Text(
        text = shownTitle,
        style = headingStyle,
        color = color,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.offset(
            y = cliPanelHeadingOpticalOffsetFor(shownTitle, pixelArtEnabled),
        ),
    )
}

private const val CLI_PANEL_STATE_KEY = "cli-panel"

internal val CLI_PANEL_VERTICAL_PADDING = 10.dp
