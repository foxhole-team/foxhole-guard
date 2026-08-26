package com.foxhole.guard.ui.cli.settings

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.foxhole.guard.R
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.components.CliActionRow
import com.foxhole.guard.ui.cli.components.CliToggleRow

@Composable
internal fun CliModuleBlock(
    label: String,
    @DrawableRes icon: Int,
    infoText: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    note: String? = null,
    toggleEnabled: Boolean = true,
    settingsLabel: String? = null,
    settingsAttention: Boolean = false,
    settingsAttentionColor: Color = Color.Unspecified,
) {
    val colors = LocalCliColors.current
    val settingsActionColor = colors.accent
    Column {
        CliToggleRow(
            label = label,
            icon = icon,
            checked = checked,
            onToggle = onToggle,
            note = note,
            infoText = infoText,
            enabled = toggleEnabled,
        )
        Row(modifier = Modifier.offset(y = MODULE_SETTINGS_ROW_OFFSET)) {
            CliModuleSettingsConnector(color = settingsActionColor)
            Spacer(modifier = Modifier.width(MODULE_SETTINGS_CONNECTOR_GAP))
            CliActionRow(
                label = settingsLabel ?: stringResource(R.string.cli_module_settings),
                icon = R.drawable.lin_settings,
                attention = settingsAttention,
                attentionColor = settingsAttentionColor,
                actionColor = settingsActionColor,
                labelColor = colors.dim,
                onTap = onOpenSettings,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun CliModuleSettingsConnector(color: Color) {
    Canvas(
        modifier = Modifier
            .width(MODULE_SETTINGS_CONNECTOR_WIDTH)
            .height(MODULE_SETTINGS_ROW_HEIGHT),
    ) {
        val strokeWidth = 1.5.dp.toPx()
        val x = 8.dp.toPx()
        val y = size.height / 2f
        val arrowLength = 4.dp.toPx()
        val endX = size.width
        val corner = 8.dp.toPx()
        val lane = Path().apply {
            moveTo(x, 0f)
            lineTo(x, y - corner)
            quadraticTo(x, y, x + corner, y)
            lineTo(endX - arrowLength / 2f, y)
        }
        drawPath(
            path = lane,
            color = color.copy(alpha = MODULE_SETTINGS_CONNECTOR_ALPHA),
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
        )
        val tip = Path().apply {
            moveTo(endX, y)
            lineTo(endX - arrowLength, y - arrowLength)
            lineTo(endX - arrowLength, y + arrowLength)
            close()
        }
        drawPath(path = tip, color = color.copy(alpha = MODULE_SETTINGS_CONNECTOR_ALPHA))
    }
}

private val MODULE_SETTINGS_CONNECTOR_WIDTH = 28.dp
private val MODULE_SETTINGS_CONNECTOR_GAP = 4.dp
private val MODULE_SETTINGS_ROW_HEIGHT = 48.dp
private val MODULE_SETTINGS_ROW_OFFSET = (-4).dp
private const val MODULE_SETTINGS_CONNECTOR_ALPHA = 0.65f
