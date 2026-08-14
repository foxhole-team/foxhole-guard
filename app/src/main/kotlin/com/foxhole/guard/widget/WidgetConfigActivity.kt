package com.foxhole.guard.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.lifecycle.lifecycleScope
import com.foxhole.guard.FoxholeApplication
import com.foxhole.guard.R
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.CliTheme
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.cliDisplayStyle
import com.foxhole.guard.ui.cli.components.CliButton
import com.foxhole.guard.ui.cli.components.CliChip
import com.foxhole.guard.ui.cli.components.CliPixIcon
import com.foxhole.guard.ui.cli.components.CliToggleRow
import com.foxhole.guard.ui.cli.components.cliDashedBorder
import com.foxhole.guard.ui.cli.components.cliMarchingBorder
import kotlinx.coroutines.launch

/**
 * Shared configuration for both widgets: background black or white plus a discrete pixel slider.
 * Values live in that widgetId's Glance state, and cancelling before apply means the widget is not
 * added.
 */
class WidgetConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appWidgetId =
            intent?.getIntExtra(
                AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID,
            ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        setResult(
            RESULT_CANCELED,
            Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId),
        )
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        val provider = configuredProvider(appWidgetId)
        if (provider == null) {
            finish()
            return
        }
        setContent {
            CliTheme {
                WidgetConfigScreen(
                    previewKind = provider.previewKind(),
                    loadInitial = { loadInitial(appWidgetId) },
                    onApply = { isBlack, alphaPercent, outlined ->
                        applyAndFinish(appWidgetId, provider, isBlack, alphaPercent, outlined)
                    },
                    // RESULT_CANCELED is already set: leaving through cancel means no widget.
                    onCancel = { finish() },
                )
            }
        }
    }

    private fun configuredProvider(appWidgetId: Int): ConfigurableWidgetProvider? {
        // Reconfigure is launched by a third-party host. Treat a stale/rebound id or a broken
        // launcher binder as cancellation; neither is a reason to crash the FoxHole process.
        val providerClass = runCatching {
            AppWidgetManager.getInstance(this).getAppWidgetInfo(appWidgetId)?.provider?.className
        }.getOrNull()
        return configurableWidgetProvider(providerClass)
    }

    private suspend fun loadInitial(appWidgetId: Int): WidgetInstanceAppearance {
        // The form starts from app-settings defaults, with per-widget state layered over them.
        val defaults =
            (application as FoxholeApplication).appGraph.settingsRepository.settings.value.widgets
        val fallback =
            WidgetInstanceAppearance(
                isBlack = defaults.blackBackground,
                alphaPercent = defaults.alphaPercent.coerceIn(0, 100),
                outlined = true,
            )
        val glanceId =
            runCatching { GlanceAppWidgetManager(this).getGlanceIdBy(appWidgetId) }.getOrNull()
                ?: return fallback
        val prefs =
            runCatching {
                getAppWidgetState(this, PreferencesGlanceStateDefinition, glanceId)
            }.getOrNull() ?: return fallback
        return WidgetInstanceAppearance(
            isBlack = prefs[WIDGET_BG_BLACK_KEY] ?: defaults.blackBackground,
            alphaPercent =
            (prefs[WIDGET_ALPHA_KEY] ?: defaults.alphaPercent).coerceIn(0, 100),
            outlined = prefs[WIDGET_OUTLINE_KEY] ?: true,
        )
    }

    private fun applyAndFinish(
        appWidgetId: Int,
        provider: ConfigurableWidgetProvider,
        isBlack: Boolean,
        alphaPercent: Int,
        outlined: Boolean,
    ) {
        lifecycleScope.launch {
            if (configuredProvider(appWidgetId) != provider) {
                // The widget id was removed or rebound while its configure screen was open.
                // Never render another provider into it.
                finish()
                return@launch
            }
            val glanceId =
                runCatching {
                    GlanceAppWidgetManager(this@WidgetConfigActivity).getGlanceIdBy(appWidgetId)
                }.getOrNull()
            if (glanceId == null) {
                // Keep the initial RESULT_CANCELED. Reporting success without a Glance binding
                // leaves a blank launcher tile that Android can only remove manually.
                finish()
                return@launch
            }
            val rendered = runCatching {
                updateAppWidgetState(this@WidgetConfigActivity, glanceId) { prefs ->
                    prefs[WIDGET_BG_BLACK_KEY] = isBlack
                    prefs[WIDGET_ALPHA_KEY] = alphaPercent.coerceIn(0, 100)
                    prefs[WIDGET_OUTLINE_KEY] = outlined
                }
                provider.createWidget().update(this@WidgetConfigActivity, glanceId)
            }
            if (rendered.isFailure) {
                // A configured widget must have produced its first RemoteViews before the host is
                // told to keep it. RESULT_CANCELED lets the launcher discard this allocation.
                finish()
                return@launch
            }
            setResult(
                RESULT_OK,
                Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId),
            )
            finish()
        }
    }
}

@Composable
private fun WidgetConfigScreen(
    previewKind: WidgetPreviewKind,
    loadInitial: suspend () -> WidgetInstanceAppearance,
    onApply: (Boolean, Int, Boolean) -> Unit,
    onCancel: () -> Unit,
) {
    val colors = LocalCliColors.current
    var isBlack by rememberSaveable { mutableStateOf(true) }
    var alphaPercent by rememberSaveable { mutableIntStateOf(100) }
    var outlined by rememberSaveable { mutableStateOf(true) }
    var loaded by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!loaded) {
            val initial = loadInitial()
            isBlack = initial.isBlack
            alphaPercent = initial.alphaPercent
            outlined = initial.outlined
            loaded = true
        }
    }
    // Диалог-модалка поверх лаунчера: полупрозрачный скрим (тап по нему — отмена) и карточка
    // с маршевым пунктиром — тем же, каким апп помечает свои модальные панели.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg.copy(alpha = WIDGET_CONFIG_SCRIM_ALPHA))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onCancel,
            )
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(CliSpacing.lg),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(colors.panel)
                .cliMarchingBorder(colors.accent)
                // Consume taps so a press inside the card can never fall through to the scrim.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                )
                .padding(CliSpacing.md),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CliPixIcon(
                    id = R.drawable.pix_power,
                    contentDescription = null,
                    size = 16.dp,
                    tint = colors.accent,
                )
                Spacer(modifier = Modifier.width(CliSpacing.xs))
                Text(
                    text = stringResource(R.string.cli_widget_config_title),
                    style = cliDisplayStyle(stringResource(R.string.cli_widget_config_title)),
                    color = colors.accent,
                    maxLines = 1,
                )
            }
            Spacer(modifier = Modifier.height(CliSpacing.sm))
            WidgetConfigPreview(
                kind = previewKind,
                isBlack = isBlack,
                alphaPercent = alphaPercent,
                outlined = outlined,
            )
            Spacer(modifier = Modifier.height(CliSpacing.md))
            WidgetBackgroundRow(isBlack = isBlack, onBlackChange = { isBlack = it })
            Spacer(modifier = Modifier.height(CliSpacing.sm))
            WidgetAlphaRow(
                alphaPercent = alphaPercent,
                onAlphaChange = { alphaPercent = it },
            )
            CliToggleRow(
                label = stringResource(R.string.cli_widget_outline),
                checked = outlined,
                onToggle = { outlined = it },
            )
            Spacer(modifier = Modifier.height(CliSpacing.md))
            Row(horizontalArrangement = Arrangement.spacedBy(CliSpacing.sm)) {
                CliButton(
                    label = stringResource(R.string.cli_common_no_cancel),
                    color = colors.err,
                    onClick = onCancel,
                    modifier = Modifier.weight(1f),
                )
                CliButton(
                    label = stringResource(R.string.cli_common_yes_confirm),
                    filled = true,
                    color = colors.ok,
                    onClick = { onApply(isBlack, alphaPercent, outlined) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

private const val WIDGET_CONFIG_SCRIM_ALPHA = 0.72f

@Composable
private fun WidgetBackgroundRow(
    isBlack: Boolean,
    onBlackChange: (Boolean) -> Unit,
) {
    val colors = LocalCliColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CliSpacing.sm),
    ) {
        Text(
            text = stringResource(R.string.cli_widget_bg_label),
            style = CliType.body,
            color = colors.dim,
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
private fun WidgetAlphaRow(
    alphaPercent: Int,
    onAlphaChange: (Int) -> Unit,
) {
    val colors = LocalCliColors.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.cli_widget_alpha),
                style = CliType.body,
                color = colors.dim,
            )
            Text(text = "$alphaPercent%", style = CliType.body, color = colors.accent)
        }
        Spacer(modifier = Modifier.height(CliSpacing.xs))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            val selectedStep = alphaPercent.coerceIn(0, 100) / WIDGET_ALPHA_STEP_PERCENT
            repeat(WIDGET_ALPHA_SEGMENTS) { index ->
                val segmentColor = when {
                    index == selectedStep -> colors.fg
                    index < selectedStep -> colors.accent
                    else -> colors.panel
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(WIDGET_ALPHA_SEGMENT_HEIGHT)
                        .background(segmentColor)
                        .border(
                            width = 1.dp,
                            color = if (index == selectedStep) colors.fg else colors.border,
                        )
                        .clickable { onAlphaChange(index * WIDGET_ALPHA_STEP_PERCENT) },
                )
            }
        }
    }
}

/**
 * Live preview: the chosen background over a checkerboard, which shows alpha honestly as image
 * editors do, plus a frame and a mock-up of the contents.
 */
@Composable
private fun WidgetConfigPreview(
    kind: WidgetPreviewKind,
    isBlack: Boolean,
    alphaPercent: Int,
    outlined: Boolean,
) {
    val colors = LocalCliColors.current
    val shape = RoundedCornerShape(8.dp)
    val fill = (if (isBlack) Color.Black else Color.White).copy(alpha = alphaPercent / 100f)
    val textTone = if (isBlack) Color.White else Color.Black
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(112.dp)
            .clip(shape)
            .drawBehind {
                val cell = 8.dp.toPx()
                var y = 0f
                var rowIndex = 0
                while (y < size.height) {
                    var x = 0f
                    var columnIndex = 0
                    while (x < size.width) {
                        drawRect(
                            color = if ((rowIndex + columnIndex) % 2 == 0) {
                                Color(0xFF2A2A2A)
                            } else {
                                Color(0xFF3A3A3A)
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
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(fill)
                .let { base -> if (outlined) base.cliDashedBorder(colors.accent) else base }
                .padding(CliSpacing.sm + CliSpacing.xs),
        ) {
            WidgetConfigPreviewHeader(kind = kind, textTone = textTone)
            Spacer(modifier = Modifier.height(CliSpacing.xs))
            when (kind) {
                WidgetPreviewKind.STATUS -> WidgetStatusConfigPreview(textTone = textTone)
                WidgetPreviewKind.WEB_APPS -> WidgetWebAppsConfigPreview(textTone = textTone)
            }
        }
    }
}

@Composable
private fun WidgetConfigPreviewHeader(
    kind: WidgetPreviewKind,
    textTone: Color,
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
        if (kind == WidgetPreviewKind.STATUS) {
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
private fun WidgetStatusConfigPreview(textTone: Color) {
    val colors = LocalCliColors.current
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            text = stringResource(R.string.widget_status_state),
            style = CliType.small,
            color = textTone.copy(alpha = 0.7f),
        )
        Text(
            text = stringResource(R.string.widget_status_disconnected),
            style = CliType.small,
            color = colors.err,
        )
    }
    Spacer(modifier = Modifier.height(CliSpacing.xs))
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(24.dp)
            .border(1.dp, colors.ok, RoundedCornerShape(6.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.widget_status_start),
            style = CliType.small,
            color = colors.ok,
        )
    }
}

@Composable
private fun WidgetWebAppsConfigPreview(textTone: Color) {
    val colors = LocalCliColors.current
    Row(horizontalArrangement = Arrangement.spacedBy(CliSpacing.sm)) {
        repeat(WIDGET_PREVIEW_WEB_APP_COUNT) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(colors.accent.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "·", style = CliType.body, color = textTone.copy(alpha = 0.8f))
            }
        }
    }
}

private enum class WidgetPreviewKind { STATUS, WEB_APPS }

private data class WidgetInstanceAppearance(
    val isBlack: Boolean,
    val alphaPercent: Int,
    val outlined: Boolean,
)

private fun ConfigurableWidgetProvider.previewKind(): WidgetPreviewKind =
    when (this) {
        ConfigurableWidgetProvider.CONNECTION -> WidgetPreviewKind.STATUS
        ConfigurableWidgetProvider.WEB_APPS -> WidgetPreviewKind.WEB_APPS
    }

private const val WIDGET_ALPHA_STEP_PERCENT = 10
private const val WIDGET_ALPHA_SEGMENTS = 11
private val WIDGET_ALPHA_SEGMENT_HEIGHT = 12.dp
private const val WIDGET_PREVIEW_WEB_APP_COUNT = 4
