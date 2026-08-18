package com.foxhole.guard.ui.cli.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.foxhole.core.model.PanelAppearance
import com.foxhole.core.model.VisualStyle
import com.foxhole.guard.R
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.LocalCliPanelAppearance
import com.foxhole.guard.ui.cli.LocalCliVisualStyle
import com.foxhole.guard.ui.cli.cliCaptionSpanStyle
import com.foxhole.guard.ui.cli.cliCaptionTextStyle
import com.foxhole.guard.ui.cli.cliLabelText

@Composable
@Suppress("LongParameterList")
internal fun CliPanel(
    modifier: Modifier = Modifier,
    title: String? = null,
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
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalCliColors.current
    val panelAppearance = LocalCliPanelAppearance.current
    val shape = RoundedCornerShape(8.dp)
    var infoOpen by rememberSaveable { mutableStateOf(false) }
    if (infoOpen && infoText != null) {
        CliInfoSheet(
            text = infoText,
            onDismiss = { infoOpen = false },
        )
    }
    val animatedEdge = if (accentBorderColor != Color.Unspecified) {
        if (LocalCliVisualStyle.current == VisualStyle.PLAIN) {
            Modifier.cliAccentSweepBorder(accentBorderColor)
        } else {
            Modifier.cliMarchingBorder(accentBorderColor)
        }
    } else {
        Modifier
    }
    Column(
        modifier = modifier
            .clip(shape)
            .background(cliPanelBackground(background, colors.panel, panelAppearance))
            .cliPanelInteraction(onClick = onClick, onLongClick = onLongClick)
            .border(1.dp, colors.border, shape)
            .then(animatedEdge),
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
                enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
            ) {
                stateHolder.SaveableStateProvider(key = title ?: CLI_PANEL_STATE_KEY) {
                    Column(
                        modifier = Modifier.padding(
                            start = CliSpacing.md,
                            end = CliSpacing.md,
                            bottom = 10.dp,
                        ),
                        content = content,
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier.padding(horizontal = CliSpacing.md, vertical = 10.dp),
            ) {
                if (title != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CliPanelTitleGroup(
                            title = title,
                            color = headerColor,
                            icon = icon,
                            iconColor = iconColor,
                            hasInfo = infoText != null,
                            onInfoTap = { infoOpen = true },
                            modifier = Modifier.weight(1f),
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
            .defaultMinSize(minHeight = 48.dp)
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
    Row(modifier = modifier, verticalAlignment = Alignment.Top) {
        CliPanelCaption(
            title = title,
            color = color,
            icon = icon,
            iconColor = iconColor,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (hasInfo) {
            CliPanelInfoGlyph(onTap = onInfoTap)
        }
    }
}

@Composable
private fun CliPanelInfoGlyph(onTap: () -> Unit) {
    CliHeaderHelpButton(
        contentDescription = stringResource(R.string.cli_common_information),
        onClick = onTap,
    )
}

private val PANEL_HEADER_ICON_SIZE = 16.dp

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
    title: String,
    color: Color,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    iconColor: Color = Color.Unspecified,
) {
    val colors = LocalCliColors.current
    val captionSpan = cliCaptionSpanStyle(title)
    val shownTitle = cliLabelText(title)
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            CliPixIcon(
                id = icon,
                contentDescription = null,
                size = PANEL_HEADER_ICON_SIZE,
                tint = iconColor.takeIf { it != Color.Unspecified }
                    ?: colors.err.takeIf { icon == R.drawable.pix_trash }
                    ?: color.takeIf { it != Color.Unspecified }
                    ?: colors.accent,
            )
            Spacer(modifier = Modifier.width(CliSpacing.xs))
        }
        Text(
            text = buildAnnotatedString {
                withStyle(captionSpan) { append(shownTitle) }
            },
            style = cliCaptionTextStyle(),
            color = color,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private const val CLI_PANEL_STATE_KEY = "cli-panel"
