package com.foxhole.guard.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
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
import com.foxhole.guard.ui.cli.components.CliChip
import com.foxhole.guard.ui.cli.components.CliScreenHeader
import kotlinx.coroutines.launch

/**
 * Shared configuration for both widgets: background black or white plus a transparency step via
 * the CLI stepper — the canon has no sliders. Values live in that widgetId's Glance state, and
 * cancelling before apply means the widget is not added.
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
        setContent {
            CliTheme {
                WidgetConfigScreen(
                    loadInitial = { loadInitial(appWidgetId) },
                    onApply = { isBlack, alphaPercent ->
                        applyAndFinish(appWidgetId, isBlack, alphaPercent)
                    },
                )
            }
        }
    }

    private suspend fun loadInitial(appWidgetId: Int): Pair<Boolean, Int> {
        // The form starts from app-settings defaults, with per-widget state layered over them.
        val defaults =
            (application as FoxholeApplication).appGraph.settingsRepository.settings.value.widgets
        val fallback = defaults.blackBackground to defaults.alphaPercent.coerceIn(0, 100)
        val glanceId =
            runCatching { GlanceAppWidgetManager(this).getGlanceIdBy(appWidgetId) }.getOrNull()
                ?: return fallback
        val prefs =
            runCatching {
                getAppWidgetState(this, PreferencesGlanceStateDefinition, glanceId)
            }.getOrNull() ?: return fallback
        return (prefs[WIDGET_BG_BLACK_KEY] ?: defaults.blackBackground) to
            (prefs[WIDGET_ALPHA_KEY] ?: defaults.alphaPercent).coerceIn(0, 100)
    }

    private fun applyAndFinish(appWidgetId: Int, isBlack: Boolean, alphaPercent: Int) {
        lifecycleScope.launch {
            val glanceId =
                runCatching {
                    GlanceAppWidgetManager(this@WidgetConfigActivity).getGlanceIdBy(appWidgetId)
                }.getOrNull()
            if (glanceId != null) {
                updateAppWidgetState(this@WidgetConfigActivity, glanceId) { prefs ->
                    prefs[WIDGET_BG_BLACK_KEY] = isBlack
                    prefs[WIDGET_ALPHA_KEY] = alphaPercent.coerceIn(0, 100)
                }
                val providerClass =
                    AppWidgetManager.getInstance(this@WidgetConfigActivity)
                        .getAppWidgetInfo(appWidgetId)
                        ?.provider
                        ?.className
                val widget =
                    if (providerClass == StatusWidgetReceiver::class.java.name) {
                        StatusWidget()
                    } else {
                        WebAppsWidget()
                    }
                runCatching { widget.update(this@WidgetConfigActivity, glanceId) }
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
    loadInitial: suspend () -> Pair<Boolean, Int>,
    onApply: (Boolean, Int) -> Unit,
) {
    val colors = LocalCliColors.current
    var isBlack by rememberSaveable { mutableStateOf(true) }
    var alphaPercent by rememberSaveable { mutableIntStateOf(100) }
    var loaded by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!loaded) {
            val (initialBlack, initialAlpha) = loadInitial()
            isBlack = initialBlack
            alphaPercent = initialAlpha
            loaded = true
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = CliSpacing.md),
    ) {
        CliScreenHeader(label = stringResource(R.string.cli_widget_config_title), icon = R.drawable.pix_power)
        WidgetConfigPreview(isBlack = isBlack, alphaPercent = alphaPercent)
        Spacer(modifier = Modifier.height(CliSpacing.md))
        Row(horizontalArrangement = Arrangement.spacedBy(CliSpacing.sm)) {
            CliChip(
                label = stringResource(R.string.cli_widget_bg_black),
                selected = isBlack,
                color = if (isBlack) colors.accent else colors.dim,
                onClick = { isBlack = true },
            )
            CliChip(
                label = stringResource(R.string.cli_widget_bg_white),
                selected = !isBlack,
                color = if (!isBlack) colors.accent else colors.dim,
                onClick = { isBlack = false },
            )
        }
        Spacer(modifier = Modifier.height(CliSpacing.sm))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(CliSpacing.sm),
        ) {
            Text(
                text = stringResource(R.string.cli_widget_alpha),
                style = CliType.body,
                color = colors.fg,
            )
            CliChip(
                label = "-",
                onClick = { alphaPercent = (alphaPercent - 10).coerceAtLeast(0) },
            )
            Text(text = "$alphaPercent%", style = CliType.body, color = colors.accent)
            CliChip(
                label = "+",
                onClick = { alphaPercent = (alphaPercent + 10).coerceAtMost(100) },
            )
        }
        Spacer(modifier = Modifier.height(CliSpacing.md))
        CliChip(
            label = stringResource(R.string.cli_widget_apply),
            color = colors.ok,
            onClick = { onApply(isBlack, alphaPercent) },
        )
        Spacer(modifier = Modifier.height(CliSpacing.sm))
    }
}

/**
 * Live preview: the chosen background over a checkerboard, which shows alpha honestly as image
 * editors do, plus a frame and a mock-up of the contents.
 */
@Composable
private fun WidgetConfigPreview(isBlack: Boolean, alphaPercent: Int) {
    val colors = LocalCliColors.current
    val shape = RoundedCornerShape(12.dp)
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
                .border(2.dp, colors.accentDim, shape)
                .padding(CliSpacing.sm + CliSpacing.xs),
        ) {
            Text(
                text = PREVIEW_LABEL,
                style = CliType.small,
                color = colors.accent,
            )
            Spacer(modifier = Modifier.height(CliSpacing.sm))
            Row(horizontalArrangement = Arrangement.spacedBy(CliSpacing.sm)) {
                repeat(4) {
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
    }
}

// The preview mock-up label is widget techno-style and is not localised, like the widgets' own.
private const val PREVIEW_LABEL = "fhg > widget"
