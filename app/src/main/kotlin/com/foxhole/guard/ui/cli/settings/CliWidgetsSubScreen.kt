package com.foxhole.guard.ui.cli.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foxhole.core.model.WidgetKindAppearance
import com.foxhole.guard.R
import com.foxhole.guard.ui.HomeViewModel
import com.foxhole.guard.ui.HomeWidgetKind
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliBottomChromeClearance
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.LocalCliPanelAppearance
import com.foxhole.guard.ui.cli.components.CliActionRow
import com.foxhole.guard.ui.cli.components.CliChip
import com.foxhole.guard.ui.cli.components.CliPanel
import com.foxhole.guard.ui.cli.components.CliPixIcon
import com.foxhole.guard.ui.cli.components.CliRowDivider
import com.foxhole.guard.ui.cli.components.CliScreenHeader
import com.foxhole.guard.ui.cli.components.CliToggleRow
import com.foxhole.guard.ui.cli.components.cliDashedBorder
import com.foxhole.guard.ui.cli.home.isRouteConnection
import com.foxhole.guard.ui.cli.home.isRouteTransition
import com.foxhole.guard.ui.onAddHomeWidget
import com.foxhole.guard.ui.onFoxWidgetAnimationChanged
import com.foxhole.guard.ui.onWidgetAlphaPercentChanged
import com.foxhole.guard.ui.onWidgetBlackBackgroundChanged
import com.foxhole.guard.ui.onWidgetOutlineChanged
import com.foxhole.guard.widget.statusAppearanceForWidget
import com.foxhole.guard.widget.webAppsAppearanceForWidget

/**
 * Home-screen widgets: one panel per widget kind, each carrying an honest preview — the same
 * frame, header and state the widget itself renders right now — followed by that kind's own
 * appearance settings. The old screen kept ONE shared background/opacity pair for all widgets;
 * the pair is now per kind, and a per-instance override from the launcher's reconfigure flow
 * still wins over these.
 */
@Composable
internal fun CliWidgetsSubScreen(
    viewModel: HomeViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.settingsRouteState.collectAsStateWithLifecycle()
    val home by viewModel.homeRouteState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val panelAppearance = LocalCliPanelAppearance.current
    val widgets = state.settings.widgets
    val connected = home.connection.isRouteConnection()
    val transition = home.connection.isRouteTransition()
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = CliSpacing.md),
    ) {
        CliScreenHeader(
            label = stringResource(R.string.cli_cfg_widgets),
            icon = R.drawable.pix_home,
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = LocalCliBottomChromeClearance.current),
        ) {
            CliWidgetKindPanel(
                viewModel = viewModel,
                kind = HomeWidgetKind.CONNECTION,
                title = stringResource(R.string.cli_status_widget_label),
                icon = R.drawable.pix_status,
                description = stringResource(R.string.cli_status_widget_description),
                appearance = widgets.statusAppearanceForWidget(context, panelAppearance),
            ) { appearance ->
                CliStatusWidgetPreview(
                    appearance = appearance,
                    connected = connected,
                    transition = transition,
                )
            }
            Spacer(modifier = Modifier.height(CliSpacing.sm))
            CliWidgetKindPanel(
                viewModel = viewModel,
                kind = HomeWidgetKind.WEB_APPS,
                title = stringResource(R.string.cli_webapps_widget_label),
                icon = R.drawable.pix_webapps,
                description = stringResource(R.string.cli_webapps_widget_description),
                appearance = widgets.webAppsAppearanceForWidget(context, panelAppearance),
            ) { appearance ->
                CliWebAppsWidgetPreview(viewModel = viewModel, appearance = appearance)
            }
            Spacer(modifier = Modifier.height(CliSpacing.sm))
            CliFoxWidgetPanel(
                viewModel = viewModel,
                connected = connected,
                animationEnabled = widgets.foxAnimationEnabled,
            )
            Spacer(modifier = Modifier.height(CliSpacing.sm))
        }
    }
}

@Composable
private fun CliWidgetKindPanel(
    viewModel: HomeViewModel,
    kind: HomeWidgetKind,
    title: String,
    icon: Int,
    description: String,
    appearance: WidgetKindAppearance,
    preview: @Composable (WidgetKindAppearance) -> Unit,
) {
    CliPanel(
        title = title,
        icon = icon,
        modifier = Modifier.fillMaxWidth(),
        infoText = description,
    ) {
        preview(appearance)
        CliRowDivider()
        CliActionRow(
            label = stringResource(R.string.cli_widget_add_home),
            icon = R.drawable.pix_home,
            onTap = { viewModel.onAddHomeWidget(kind) },
        )
        CliRowDivider()
        CliWidgetBackgroundRow(
            isBlack = appearance.blackBackground,
            onBlackChange = { viewModel.onWidgetBlackBackgroundChanged(kind, it) },
        )
        CliRowDivider()
        CliWidgetAlphaRow(
            alphaPercent = appearance.alphaPercent,
            onAlphaChange = { viewModel.onWidgetAlphaPercentChanged(kind, it) },
        )
        CliRowDivider()
        CliToggleRow(
            label = stringResource(R.string.cli_widget_outline),
            checked = appearance.outline,
            onToggle = { viewModel.onWidgetOutlineChanged(kind, it) },
        )
    }
}

@Composable
private fun CliFoxWidgetPanel(
    viewModel: HomeViewModel,
    connected: Boolean,
    animationEnabled: Boolean,
) {
    CliPanel(
        title = stringResource(R.string.fox_status_widget_label),
        icon = R.drawable.pix_star,
        modifier = Modifier.fillMaxWidth(),
        infoText = stringResource(R.string.fox_status_widget_description),
    ) {
        CliWidgetPreviewSurface(fill = Color.Transparent, outlined = false) {
            Image(
                painter = painterResource(
                    if (connected) R.drawable.fhg_status_frame_1 else R.drawable.fhg_status_frame_4,
                ),
                contentDescription = null,
                modifier = Modifier.size(FOX_PREVIEW_SIZE),
            )
        }
        CliRowDivider()
        CliActionRow(
            label = stringResource(R.string.cli_widget_add_home),
            icon = R.drawable.pix_home,
            onTap = { viewModel.onAddHomeWidget(HomeWidgetKind.STATUS) },
        )
        CliRowDivider()
        CliToggleRow(
            label = stringResource(R.string.fox_status_widget_animate),
            checked = animationEnabled,
            onToggle = viewModel::onFoxWidgetAnimationChanged,
        )
    }
}

@Composable
private fun CliStatusWidgetPreview(
    appearance: WidgetKindAppearance,
    connected: Boolean,
    transition: Boolean,
) {
    val colors = LocalCliColors.current
    val fill = widgetPreviewFill(appearance)
    val textTone = if (appearance.blackBackground) Color.White else Color.Black
    CliWidgetPreviewSurface(fill = fill, outlined = appearance.outline) {
        Column(modifier = Modifier.fillMaxWidth().padding(CliSpacing.sm)) {
            CliWidgetPreviewHeader(textTone = textTone, withRefresh = true)
            Spacer(modifier = Modifier.height(CliSpacing.xs))
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.widget_status_state),
                    style = CliType.small,
                    color = textTone.copy(alpha = PREVIEW_SECONDARY_ALPHA),
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(
                        when {
                            connected -> R.string.widget_status_connected
                            transition -> R.string.widget_status_connecting
                            else -> R.string.widget_status_disconnected
                        },
                    ),
                    style = CliType.small,
                    color = when {
                        connected -> colors.ok
                        transition -> colors.info
                        else -> colors.err
                    },
                )
            }
            Spacer(modifier = Modifier.height(CliSpacing.xs))
            if (connected) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    CliWidgetPreviewButton(
                        label = stringResource(R.string.widget_status_stop),
                        color = colors.err,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(modifier = Modifier.width(CliSpacing.xs))
                    CliWidgetPreviewButton(
                        label = stringResource(R.string.widget_status_restart),
                        color = colors.accent,
                        modifier = Modifier.weight(1f),
                    )
                }
            } else {
                CliWidgetPreviewButton(
                    label = stringResource(R.string.widget_status_start),
                    color = colors.ok,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun CliWebAppsWidgetPreview(
    viewModel: HomeViewModel,
    appearance: WidgetKindAppearance,
) {
    val colors = LocalCliColors.current
    val apps by viewModel.webAppsState.collectAsStateWithLifecycle()
    val fill = widgetPreviewFill(appearance)
    val textTone = if (appearance.blackBackground) Color.White else Color.Black
    CliWidgetPreviewSurface(fill = fill, outlined = appearance.outline) {
        Column(modifier = Modifier.fillMaxWidth().padding(CliSpacing.sm)) {
            CliWidgetPreviewHeader(textTone = textTone, withRefresh = false)
            Spacer(modifier = Modifier.height(CliSpacing.xs))
            if (apps.isEmpty()) {
                Text(
                    text = stringResource(R.string.cli_webapps_empty),
                    style = CliType.small,
                    color = textTone.copy(alpha = PREVIEW_SECONDARY_ALPHA),
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(CliSpacing.sm)) {
                    apps.take(PREVIEW_WEB_APP_COUNT).forEach { app ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                modifier = Modifier
                                    .size(PREVIEW_TILE_SIZE)
                                    .background(colors.accent.copy(alpha = PREVIEW_TILE_ALPHA)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = app.name.take(1).uppercase(),
                                    style = CliType.body,
                                    color = colors.accent,
                                )
                            }
                            Text(
                                text = app.name.lowercase(),
                                style = CliType.small,
                                color = textTone,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CliWidgetPreviewHeader(
    textTone: Color,
    withRefresh: Boolean,
) {
    val colors = LocalCliColors.current
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        CliPixIcon(
            id = R.drawable.ic_qs_tile,
            contentDescription = null,
            size = 18.dp,
            tint = textTone,
        )
        Spacer(modifier = Modifier.width(CliSpacing.xs))
        Text(
            text = stringResource(R.string.app_name),
            style = CliType.small,
            color = colors.accent,
            modifier = Modifier.weight(1f),
        )
        if (withRefresh) {
            CliPixIcon(
                id = R.drawable.widget_refresh_pixel,
                contentDescription = null,
                size = 16.dp,
                tint = textTone,
            )
        }
    }
}

@Composable
private fun CliWidgetPreviewButton(
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .height(PREVIEW_BUTTON_HEIGHT)
            .cliDashedBorder(color),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, style = CliType.small, color = color)
    }
}

@Composable
private fun CliWidgetPreviewSurface(
    fill: Color,
    outlined: Boolean,
    content: @Composable () -> Unit,
) {
    val colors = LocalCliColors.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = CliSpacing.xs)
            .clip(RoundedCornerShape(PREVIEW_CORNER_RADIUS))
            .drawBehind {
                val cell = PREVIEW_CHECKER_CELL.toPx()
                var y = 0f
                var rowIndex = 0
                while (y < size.height) {
                    var x = 0f
                    var columnIndex = 0
                    while (x < size.width) {
                        drawRect(
                            color = if ((rowIndex + columnIndex) % 2 == 0) {
                                PREVIEW_CHECKER_DARK
                            } else {
                                PREVIEW_CHECKER_LIGHT
                            },
                            topLeft = Offset(x, y),
                            size = Size(
                                minOf(cell, size.width - x),
                                minOf(cell, size.height - y),
                            ),
                        )
                        x += cell
                        columnIndex++
                    }
                    y += cell
                    rowIndex++
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(fill)
                .let { base -> if (outlined) base.cliDashedBorder(colors.accent) else base },
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
    }
}

@Composable
private fun CliWidgetBackgroundRow(
    isBlack: Boolean,
    onBlackChange: (Boolean) -> Unit,
) {
    val colors = LocalCliColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CliSpacing.sm),
    ) {
        Text(
            text = stringResource(R.string.cli_widget_bg_label),
            style = CliType.body,
            color = colors.fg,
            modifier = Modifier.weight(1f),
        )
        CliChip(
            label = stringResource(R.string.cli_widget_bg_black),
            selected = isBlack,
            color = if (isBlack) colors.accent else colors.dim,
            onClick = { onBlackChange(true) },
        )
        CliChip(
            label = stringResource(R.string.cli_widget_bg_white),
            selected = !isBlack,
            color = if (!isBlack) colors.accent else colors.dim,
            onClick = { onBlackChange(false) },
        )
    }
}

@Composable
private fun CliWidgetAlphaRow(
    alphaPercent: Int,
    onAlphaChange: (Int) -> Unit,
) {
    val colors = LocalCliColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CliSpacing.sm),
    ) {
        Text(
            text = stringResource(R.string.cli_widget_alpha),
            style = CliType.body,
            color = colors.fg,
            modifier = Modifier.weight(1f),
        )
        CliChip(
            label = "-",
            onClick = { onAlphaChange((alphaPercent - ALPHA_STEP).coerceAtLeast(0)) },
        )
        Text(text = "$alphaPercent%", style = CliType.body, color = colors.accent)
        CliChip(
            label = "+",
            onClick = { onAlphaChange((alphaPercent + ALPHA_STEP).coerceAtMost(100)) },
        )
    }
}

private fun widgetPreviewFill(appearance: WidgetKindAppearance): Color =
    (if (appearance.blackBackground) Color.Black else Color.White)
        .copy(alpha = appearance.alphaPercent.coerceIn(0, 100) / 100f)

private const val ALPHA_STEP = 10
private const val PREVIEW_WEB_APP_COUNT = 4
private const val PREVIEW_SECONDARY_ALPHA = 0.7f
private const val PREVIEW_TILE_ALPHA = 0.2f
private val PREVIEW_TILE_SIZE = 36.dp
private val PREVIEW_BUTTON_HEIGHT = 24.dp
private val PREVIEW_CORNER_RADIUS = 8.dp
private val PREVIEW_CHECKER_CELL = 8.dp
private val PREVIEW_CHECKER_DARK = Color(0xFF2A2A2A)
private val PREVIEW_CHECKER_LIGHT = Color(0xFF3A3A3A)
private val FOX_PREVIEW_SIZE = 96.dp
