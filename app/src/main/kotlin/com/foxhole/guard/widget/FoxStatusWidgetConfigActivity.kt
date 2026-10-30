package com.foxhole.guard.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.lifecycle.lifecycleScope
import com.foxhole.guard.FoxholeApplication
import com.foxhole.guard.R
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.CliTheme
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.cliDisplayStyle
import com.foxhole.guard.ui.cli.cliHeadingText
import com.foxhole.guard.ui.cli.components.CliButton
import com.foxhole.guard.ui.cli.components.CliPanel
import kotlinx.coroutines.launch

class FoxStatusWidgetConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val widgetId =
            intent?.getIntExtra(
                AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID,
            ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        setResult(
            RESULT_CANCELED,
            Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId),
        )
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        setContent {
            val settings by (application as FoxholeApplication)
                .appGraph.settingsRepository.settings.collectAsState()
            CliTheme(
                themeMode = settings.ui.themeMode,
                accentColor = settings.ui.accentColor,
                monochromeEnabled = settings.ui.monochromeEnabled,
                pixelArtEnabled = settings.ui.pixelArtEnabled,
            ) {
                FoxStatusWidgetConfigScreen(
                    onCancel = ::finish,
                    onApply = { applyAndFinish(widgetId) },
                )
            }
        }
    }

    private fun applyAndFinish(widgetId: Int) {
        lifecycleScope.launch {
            val glanceId =
                runCatching {
                    GlanceAppWidgetManager(this@FoxStatusWidgetConfigActivity).getGlanceIdBy(widgetId)
                }.getOrNull()
            if (glanceId != null) {
                runCatching { FoxStatusWidget().update(this@FoxStatusWidgetConfigActivity, glanceId) }
            }
            setResult(
                RESULT_OK,
                Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId),
            )
            finish()
        }
    }
}

@Composable
private fun FoxStatusWidgetConfigScreen(
    onCancel: () -> Unit,
    onApply: () -> Unit,
) {
    val colors = LocalCliColors.current
    Box(
        modifier =
        Modifier
            .fillMaxSize()
            .background(colors.bg.copy(alpha = 0.72f))
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
        CliPanel(
            title = stringResource(R.string.fox_status_widget_config_title),
            modifier =
            Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
        ) {
            Text(
                text = cliHeadingText(stringResource(R.string.fox_status_widget_label)),
                style = cliDisplayStyle(stringResource(R.string.fox_status_widget_label)),
                color = colors.info,
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
                    onClick = onApply,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
