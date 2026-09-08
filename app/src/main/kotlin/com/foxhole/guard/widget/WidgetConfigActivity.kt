package com.foxhole.guard.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.foxhole.guard.ui.cli.cliHeadingText
import com.foxhole.guard.ui.cli.cliTypography
import com.foxhole.guard.ui.cli.components.CliButton
import com.foxhole.guard.ui.cli.components.CliDropdownOption
import com.foxhole.guard.ui.cli.components.CliDropdownRow
import com.foxhole.guard.ui.cli.components.CliIcon
import com.foxhole.guard.ui.cli.components.CliToggleRow
import com.foxhole.guard.ui.cli.components.cliDashedBorder
import com.foxhole.guard.ui.cli.components.cliMarchingBorder
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

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
            val settings by (application as FoxholeApplication)
                .appGraph.settingsRepository.settings.collectAsState()
            CliTheme(
                themeMode = settings.ui.themeMode,
                accentColor = settings.ui.accentColor,
                monochromeEnabled = settings.ui.monochromeEnabled,
                pixelArtEnabled = settings.ui.pixelArtEnabled,
            ) {
                WidgetConfigScreen(
                    previewKind = provider.previewKind(),
                    loadInitial = { loadInitial(appWidgetId) },
                    onApply = { isBlack, alphaPercent, outlined, layoutMode ->
                        applyAndFinish(
                            appWidgetId,
                            provider,
                            isBlack,
                            alphaPercent,
                            outlined,
                            layoutMode,
                        )
                    },

                    onCancel = { finish() },
                )
            }
        }
    }

    private fun configuredProvider(appWidgetId: Int): ConfigurableWidgetProvider? {
        val providerClass = runCatching {
            AppWidgetManager.getInstance(this).getAppWidgetInfo(appWidgetId)?.provider?.className
        }.getOrNull()
        return configurableWidgetProvider(providerClass)
    }

    private suspend fun loadInitial(appWidgetId: Int): WidgetInstanceAppearance {
        val settings =
            (application as FoxholeApplication).appGraph.settingsRepository.settings.value
        val provider = configuredProvider(appWidgetId)
        val defaults =
            when (provider) {
                ConfigurableWidgetProvider.WEB_APPS ->
                    settings.widgets.webAppsAppearanceForWidget(
                        context = this,
                        themeMode = settings.ui.themeMode,
                    )
                else ->
                    settings.widgets.statusAppearanceForWidget(
                        context = this,
                        themeMode = settings.ui.themeMode,
                    )
            }
        val fallback =
            WidgetInstanceAppearance(
                isBlack = defaults.blackBackground,
                alphaPercent = defaults.alphaPercent.coerceIn(0, 100),
                outlined = defaults.outline,
                layoutMode =
                if (provider == ConfigurableWidgetProvider.CONNECTION) {
                    activeStatusWidgetLayoutMode(settings.widgets.statusLayoutMode)
                } else {
                    StatusWidgetLayoutMode.SIMPLE
                },
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
            outlined = prefs[WIDGET_OUTLINE_KEY] ?: defaults.outline,
            layoutMode =
            if (provider == ConfigurableWidgetProvider.CONNECTION) {
                initialStatusWidgetLayoutMode(
                    preferences = prefs,
                    durableDefault = settings.widgets.statusLayoutMode,
                )
            } else {
                StatusWidgetLayoutMode.SIMPLE
            },
        )
    }

    private fun applyAndFinish(
        appWidgetId: Int,
        provider: ConfigurableWidgetProvider,
        isBlack: Boolean,
        alphaPercent: Int,
        outlined: Boolean,
        layoutMode: StatusWidgetLayoutMode,
    ) {
        lifecycleScope.launch {
            if (configuredProvider(appWidgetId) != provider) {
                finish()
                return@launch
            }
            val glanceId =
                runCatching {
                    GlanceAppWidgetManager(this@WidgetConfigActivity).getGlanceIdBy(appWidgetId)
                }.getOrNull()
            if (glanceId == null) {
                finish()
                return@launch
            }
            val rendered = runCatching {
                updateAppWidgetState(this@WidgetConfigActivity, glanceId) { prefs ->
                    prefs[WIDGET_BG_BLACK_KEY] = isBlack
                    prefs[WIDGET_ALPHA_KEY] = alphaPercent.coerceIn(0, 100)
                    prefs[WIDGET_OUTLINE_KEY] = outlined
                    if (provider == ConfigurableWidgetProvider.CONNECTION) {
                        prefs[STATUS_WIDGET_LAYOUT_MODE_KEY] =
                            activeStatusWidgetLayoutMode(layoutMode).persistedValue
                    }
                }
                provider.createWidget().update(this@WidgetConfigActivity, glanceId)
            }
            if (rendered.isFailure) {
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
    onApply: (Boolean, Int, Boolean, StatusWidgetLayoutMode) -> Unit,
    onCancel: () -> Unit,
) {
    val colors = LocalCliColors.current
    var isBlack by rememberSaveable { mutableStateOf(true) }
    var alphaPercent by rememberSaveable { mutableIntStateOf(WIDGET_DEFAULT_OPACITY_PERCENT) }
    var outlined by rememberSaveable { mutableStateOf(false) }
    var layoutMode by rememberSaveable { mutableStateOf(StatusWidgetLayoutMode.SIMPLE) }
    var loaded by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!loaded) {
            val initial = loadInitial()
            isBlack = initial.isBlack
            alphaPercent = initial.alphaPercent
            outlined = initial.outlined
            layoutMode = activeStatusWidgetLayoutMode(initial.layoutMode)
            loaded = true
        }
    }

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
                CliIcon(
                    id = R.drawable.lin_power,
                    contentDescription = null,
                    size = 16.dp,
                    tint = colors.accent,
                )
                Spacer(modifier = Modifier.width(CliSpacing.xs))
                Text(
                    text = cliHeadingText(stringResource(R.string.cli_widget_config_title)),
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
                layoutMode = activeStatusWidgetLayoutMode(layoutMode),
            )
            Spacer(modifier = Modifier.height(CliSpacing.md))
            if (previewKind == WidgetPreviewKind.STATUS) {
                WidgetStatusLayoutModeRow(
                    layoutMode = activeStatusWidgetLayoutMode(layoutMode),
                    onLayoutModeChange = { layoutMode = it },
                )
                Spacer(modifier = Modifier.height(CliSpacing.sm))
            }
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
                    onClick = {
                        onApply(
                            isBlack,
                            alphaPercent,
                            outlined,
                            activeStatusWidgetLayoutMode(layoutMode),
                        )
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

private const val WIDGET_CONFIG_SCRIM_ALPHA = 0.72f

@Composable
private fun WidgetStatusLayoutModeRow(
    layoutMode: StatusWidgetLayoutMode,
    onLayoutModeChange: (StatusWidgetLayoutMode) -> Unit,
) {
    val simple = stringResource(R.string.cli_widget_status_layout_simple)
    CliDropdownRow(
        label = stringResource(R.string.cli_widget_status_layout),
        value = simple,
        options = SELECTABLE_STATUS_WIDGET_LAYOUT_MODES.map { mode ->
            CliDropdownOption(id = mode.persistedValue, label = simple)
        },
        selectedId = layoutMode.persistedValue,
        onSelect = { id ->
            SELECTABLE_STATUS_WIDGET_LAYOUT_MODES.firstOrNull { it.persistedValue == id }
                ?.let(onLayoutModeChange)
        },
    )
}

@Composable
private fun WidgetBackgroundRow(
    isBlack: Boolean,
    onBlackChange: (Boolean) -> Unit,
) {
    val dark = stringResource(R.string.cli_widget_config_bg_dark)
    val light = stringResource(R.string.cli_widget_config_bg_light)
    CliDropdownRow(
        label = stringResource(R.string.cli_widget_bg_label),
        value = if (isBlack) dark else light,
        options = listOf(
            CliDropdownOption(id = true.toString(), label = dark),
            CliDropdownOption(id = false.toString(), label = light),
        ),
        selectedId = isBlack.toString(),
        onSelect = { id -> id.toBooleanStrictOrNull()?.let(onBlackChange) },
    )
}

@Composable
private fun WidgetAlphaRow(
    alphaPercent: Int,
    onAlphaChange: (Int) -> Unit,
) {
    val colors = LocalCliColors.current
    val opacityLabel = stringResource(R.string.cli_widget_alpha)
    val normalizedPercent =
        alphaPercent.coerceIn(WIDGET_OPACITY_MIN_PERCENT, WIDGET_OPACITY_MAX_PERCENT)
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = opacityLabel,
                style = CliType.body,
                color = colors.dim,
            )
            Text(text = "$normalizedPercent%", style = CliType.body, color = colors.accent)
        }
        Spacer(modifier = Modifier.height(CliSpacing.xs))
        Slider(
            value = normalizedPercent.toFloat(),
            onValueChange = { value -> onAlphaChange(value.roundToInt()) },
            modifier = Modifier
                .fillMaxWidth()
                .height(WIDGET_OPACITY_SLIDER_HEIGHT)
                .semantics {
                    contentDescription = opacityLabel
                    stateDescription = "$normalizedPercent%"
                },
            valueRange = WIDGET_OPACITY_MIN_PERCENT.toFloat()..WIDGET_OPACITY_MAX_PERCENT.toFloat(),
            steps = WIDGET_OPACITY_SLIDER_STEPS,
        )
    }
}

@Composable
private fun WidgetConfigPreview(
    kind: WidgetPreviewKind,
    isBlack: Boolean,
    alphaPercent: Int,
    outlined: Boolean,
    layoutMode: StatusWidgetLayoutMode,
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
        contentAlignment = Alignment.Center,
    ) {
        if (kind == WidgetPreviewKind.STATUS && layoutMode == StatusWidgetLayoutMode.SIMPLE) {
            WidgetSimpleStatusConfigPreview(
                fill = fill,
                textTone = textTone,
                outlined = outlined,
            )
        } else {
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
}

@Composable
private fun WidgetSimpleStatusConfigPreview(
    fill: Color,
    textTone: Color,
    outlined: Boolean,
) {
    val colors = LocalCliColors.current
    val smallType = cliTypography().small
    val pixelType = cliTypography().button
    Row(
        modifier =
        Modifier
            .fillMaxWidth()
            .height(WIDGET_SIMPLE_PREVIEW_HEIGHT)
            .background(fill)
            .let { base -> if (outlined) base.cliDashedBorder(colors.accent) else base }
            .padding(horizontal = CliSpacing.sm + CliSpacing.xs, vertical = CliSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .border(2.dp, textTone, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(R.drawable.ic_qs_tile),
                contentDescription = stringResource(R.string.app_name),
                colorFilter = ColorFilter.tint(textTone),
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(modifier = Modifier.width(CliSpacing.sm))
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.cli_home_status_connected_vpn).uppercase(),
                style = pixelType.copy(fontSize = 14.sp, lineHeight = 15.sp),
                color = colors.ok,
                maxLines = 1,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = painterResource(R.drawable.lin_device),
                    contentDescription = stringResource(R.string.cli_rt_device),
                    colorFilter = ColorFilter.tint(colors.vpn),
                    modifier = Modifier.size(14.dp),
                )
                Spacer(modifier = Modifier.width(3.dp))
                Image(
                    painter = painterResource(R.drawable.flag_nl),
                    contentDescription = WIDGET_PREVIEW_COUNTRY_CODE,
                    modifier = Modifier.width(18.dp).height(11.dp),
                )
                Spacer(modifier = Modifier.width(3.dp))
                Text(
                    text = WIDGET_PREVIEW_COUNTRY_CODE,
                    style = smallType.copy(fontSize = 13.sp, lineHeight = 15.sp),
                    color = textTone,
                )
            }
        }
        Spacer(modifier = Modifier.width(CliSpacing.sm))
        Box(
            modifier =
            Modifier
                .size(34.dp)
                .border(2.dp, colors.err, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(R.drawable.lin_power),
                contentDescription = stringResource(R.string.widget_status_turn_off),
                colorFilter = ColorFilter.tint(colors.err),
                modifier = Modifier.size(19.dp),
            )
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
        CliIcon(
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
            CliIcon(
                id = R.drawable.lin_update,
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
            text = stringResource(R.string.widget_status_start).uppercase(),
            style = CliType.button,
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
    val layoutMode: StatusWidgetLayoutMode,
)

private fun ConfigurableWidgetProvider.previewKind(): WidgetPreviewKind =
    when (this) {
        ConfigurableWidgetProvider.CONNECTION -> WidgetPreviewKind.STATUS
        ConfigurableWidgetProvider.WEB_APPS -> WidgetPreviewKind.WEB_APPS
    }

private val WIDGET_OPACITY_SLIDER_HEIGHT = 48.dp
private const val WIDGET_PREVIEW_WEB_APP_COUNT = 4
private const val WIDGET_PREVIEW_COUNTRY_CODE = "NL"
private val WIDGET_SIMPLE_PREVIEW_HEIGHT = 56.dp
