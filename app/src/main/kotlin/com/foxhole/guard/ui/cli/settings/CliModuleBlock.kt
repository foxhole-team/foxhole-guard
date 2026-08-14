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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.foxhole.guard.R
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.components.CliActionRow
import com.foxhole.guard.ui.cli.components.CliToggleRow

/**
 * One switchable part of the product — a module or an extra — as a settings block: the switch that
 * permits it and an always-visible way into its own screen.
 *
 * Shared rather than copied because the shape is the promise: everything that can be turned on and
 * configured separately — web apps, the proxy server, TOR, I2P, the firewall, anomaly detection —
 * reads the same way, and one whose settings were reachable only while it was enabled taught users
 * that turning it off also hides how it is configured. The settings row is therefore never gated on
 * the toggle.
 *
 * [icon] is mandatory rather than optional: a module is a thing in the product, and a bare label
 * made the block read as one more preference row. Both rows carry a glyph — the module's own icon,
 * then the settings cog — so the pair reads as one unit.
 *
 * The settings action is styled as an indented secondary row. An orange dashed elbow connects the
 * module heading to that nested row; only the connector is dashed — there is no outlined box.
 *
 * [settingsLabel] lets a module say "module settings" where an extra says "open settings" — the two
 * are not the same kind of thing, and the wording is the only place that shows.
 */
@Composable
internal fun CliModuleBlock(
    label: String,
    @DrawableRes icon: Int,
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
    val settingsActionColor = if (checked) colors.ok else colors.accent
    Column {
        CliToggleRow(
            label = label,
            icon = icon,
            checked = checked,
            onToggle = onToggle,
            note = note,
            enabled = toggleEnabled,
        )
        Row(modifier = Modifier.offset(y = MODULE_SETTINGS_ROW_OFFSET)) {
            CliModuleSettingsConnector(color = settingsActionColor)
            Spacer(modifier = Modifier.width(MODULE_SETTINGS_CONNECTOR_GAP))
            CliActionRow(
                label = settingsLabel ?: stringResource(R.string.cli_module_settings),
                icon = R.drawable.pix_settings,
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

/** Dashed `└──` hierarchy mark from the module heading into its always-visible settings row. */
@Composable
private fun CliModuleSettingsConnector(color: Color) {
    Canvas(
        modifier = Modifier
            .width(MODULE_SETTINGS_CONNECTOR_WIDTH)
            .height(MODULE_SETTINGS_ROW_HEIGHT),
    ) {
        val strokeWidth = 1.dp.toPx()
        val x = 8.dp.toPx()
        val y = size.height / 2f
        val arrowLength = 4.dp.toPx()
        val endX = size.width
        val path = Path().apply {
            moveTo(x, 0f)
            lineTo(x, y)
            lineTo(endX, y)
            moveTo(endX - arrowLength, y - arrowLength)
            lineTo(endX, y)
            lineTo(endX - arrowLength, y + arrowLength)
        }
        drawPath(
            path = path,
            color = color.copy(alpha = MODULE_SETTINGS_CONNECTOR_ALPHA),
            style = Stroke(
                width = strokeWidth,
                pathEffect = PathEffect.dashPathEffect(
                    floatArrayOf(2.dp.toPx(), 2.dp.toPx()),
                ),
            ),
        )
    }
}

private val MODULE_SETTINGS_CONNECTOR_WIDTH = 28.dp
private val MODULE_SETTINGS_CONNECTOR_GAP = 4.dp
private val MODULE_SETTINGS_ROW_HEIGHT = 48.dp
private val MODULE_SETTINGS_ROW_OFFSET = (-4).dp
private const val MODULE_SETTINGS_CONNECTOR_ALPHA = 0.65f
