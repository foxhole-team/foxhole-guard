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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
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
import com.foxhole.guard.R
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.cliCaptionSpanStyle

/**
 * Rounded 1dp-border box - the Compose analogue of a `╭─╮ │ ╰─╯` frame. The optional title
 * renders as a clean caption inside the top edge: pixel icon + the word in the display face,
 * dimmed. No decorative stripes — prod polish removed the `░▒ ──` dither so captions stay
 * readable and screens do not blur together.
 *
 * With [collapsible] the caption row becomes tappable with a `▸/▾` glyph (48dp floor,
 * accent glyph per the affordance contract) and the body folds via [expanded] +
 * [onToggleExpanded] — state stays with the caller. Existing non-collapsible call sites
 * keep compiling and rendering as before.
 */
@Composable
internal fun CliPanel(
    modifier: Modifier = Modifier,
    title: String? = null,
    titleColor: Color = Color.Unspecified,
    @DrawableRes icon: Int? = null,
    background: Color = Color.Unspecified,
    collapsible: Boolean = false,
    expanded: Boolean = true,
    onToggleExpanded: (() -> Unit)? = null,
    // Tap covers the whole panel including padding. The press tint is drawn after clip and
    // background, so the ripple stays inside the rounding instead of leaking a rectangle.
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalCliColors.current
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(if (background == Color.Unspecified) colors.panel else background)
            .then(
                if (onClick != null) Modifier.cliPressable(onClick = onClick) else Modifier,
            )
            .border(1.dp, colors.border, shape),
    ) {
        val headerColor = if (titleColor == Color.Unspecified) colors.dim else titleColor
        if (title != null && collapsible) {
            val stateText = stringResource(
                if (expanded) R.string.cli_panel_expanded else R.string.cli_panel_collapsed,
            )
            // A clickable caption spans the full width, with padding inside the pressable, so the
            // press fills the section edge to edge and clips to the panel rounding.
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
                CliPanelCaption(
                    title = title,
                    color = headerColor,
                    icon = icon,
                    modifier = Modifier.weight(1f),
                )
                CliDisclosureGlyph(
                    expanded = expanded,
                    color = if (onToggleExpanded != null) colors.accent else colors.dim,
                )
            }
        }
        if (collapsible) {
            // AnimatedVisibility drops content from the composition when collapsing, which erased
            // the rememberSaveable drafts of custom inputs inside. SaveableStateProvider keeps them
            // across collapse and expand.
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
                    Row(modifier = Modifier.fillMaxWidth()) {
                        CliPanelCaption(title = title, color = headerColor, icon = icon)
                    }
                }
                content()
            }
        }
    }
}

/**
 * Clean panel caption: pixel icon + the title word in the display face. The dither/stripe
 * decoration (`░▒ ── … ── ▒░`) is gone — prod polish: captions are text, separation is the
 * panel border's job.
 */
@Composable
private fun CliPanelCaption(
    title: String,
    color: Color,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
) {
    val colors = LocalCliColors.current
    // The caption word uses the display face at small metrics; see cliCaptionSpanStyle, since
    // Android gives no per-glyph family fallback.
    val captionSpan = cliCaptionSpanStyle(title)
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            // 16dp snaps to whole 1x/2x/3x at any density, and the accent tint lifts the caption
            // without colouring the whole text.
            CliPixIcon(
                id = icon,
                contentDescription = null,
                size = 16.dp,
                tint = color.takeIf { it != Color.Unspecified } ?: colors.accent,
            )
            Spacer(modifier = Modifier.width(CliSpacing.xs))
        }
        Text(
            text = buildAnnotatedString {
                withStyle(captionSpan) { append(title) }
            },
            style = CliType.small,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// SaveableStateProvider key for an unnamed collapsible panel. Each CliPanel owns its holder, so
// panels cannot collide.
private const val CLI_PANEL_STATE_KEY = "cli-panel"
