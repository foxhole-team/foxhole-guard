package com.foxhole.guard.ui.cli.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foxhole.guard.R
import com.foxhole.guard.ui.cli.CliRadius
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.components.CliIcon
import com.foxhole.guard.ui.cli.components.CliIconTextItem
import com.foxhole.guard.ui.cli.components.CliPanel
import com.foxhole.guard.ui.cli.components.CliRowDivider
import com.foxhole.guard.ui.cli.components.cliIconTextItems
import com.foxhole.guard.ui.cli.settings.CliSmartHelpBody

internal typealias CliQuickStartItem = CliIconTextItem

internal fun quickStartItems(body: String): List<CliQuickStartItem> =
    cliIconTextItems(body = body, icons = QUICK_START_ICONS)

internal data class CliQuickStartTable(
    val areaHeader: String,
    val tapHeader: String,
    val holdHeader: String,
    val rows: List<CliQuickStartTableRow>,
)

internal data class CliQuickStartTableRow(
    val area: String,
    val tap: String,
    val hold: String,
    val icon: Int,
)

internal fun quickStartTable(body: String): CliQuickStartTable? {
    val rows = body.lineSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .map { line -> line.split(QUICK_START_TABLE_SEPARATOR, limit = QUICK_START_TABLE_COLUMNS) }
        .toList()
    if (rows.size < 2 || rows.any { columns -> columns.size != QUICK_START_TABLE_COLUMNS }) return null
    val headers = rows.first().map(String::trim)
    if (headers.any(String::isBlank)) return null
    val tableRows = rows.drop(1).mapIndexed { index, columns ->
        CliQuickStartTableRow(
            area = columns[0].trim(),
            tap = columns[1].trim(),
            hold = columns[2].trim().ifBlank { QUICK_START_NO_ACTION },
            icon = QUICK_START_TABLE_ICONS.getOrElse(index) { R.drawable.lin_info },
        )
    }
    if (tableRows.any { row -> row.area.isBlank() || row.tap.isBlank() || row.hold.isBlank() }) return null
    return CliQuickStartTable(
        areaHeader = headers[0],
        tapHeader = headers[1],
        holdHeader = headers[2],
        rows = tableRows,
    )
}

@Composable
internal fun CliQuickStartItems(
    body: String,
    framed: Boolean,
    modifier: Modifier = Modifier,
    iconColor: Color = Color.Unspecified,
    smartBody: String? = null,
    detailsBody: String? = null,
) {
    val table = remember(body) { quickStartTable(body) }
    Column(modifier = modifier) {
        if (table == null) {
            val items = remember(body) { quickStartItems(body) }
            CliIconTextItems(items = items, framed = framed, iconColor = iconColor)
        } else {
            CliQuickStartActionTable(table = table, framed = framed, iconColor = iconColor)
        }
        smartBody?.let { bodyText ->
            Spacer(modifier = Modifier.height(CliSpacing.md))
            CliSmartHelpBody(
                body = bodyText,
                icon = R.drawable.lin_star,
                framed = framed,
            )
        }
        detailsBody?.let { bodyText ->
            val details = remember(bodyText) { quickStartItems(bodyText) }
            Spacer(modifier = Modifier.height(CliSpacing.md))
            CliIconTextItems(items = details, framed = framed, iconColor = iconColor)
        }
    }
}

@Composable
private fun CliQuickStartActionTable(
    table: CliQuickStartTable,
    framed: Boolean,
    iconColor: Color,
) {
    if (framed) {
        CliPanel(modifier = Modifier.fillMaxWidth()) {
            CliQuickStartTableBody(table = table, iconColor = iconColor)
        }
    } else {
        CliQuickStartTableBody(table = table, iconColor = iconColor)
    }
}

@Composable
private fun CliQuickStartTableBody(
    table: CliQuickStartTable,
    iconColor: Color,
) {
    val colors = LocalCliColors.current
    val shape = RoundedCornerShape(CliRadius.panel)
    val textStyle = CliType.small.copy(lineHeight = 17.sp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.panelAlt, shape)
            .border(1.dp, colors.border, shape),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = CliSpacing.sm, vertical = CliSpacing.xs),
            verticalAlignment = Alignment.Top,
        ) {
            CliQuickStartTableCell(
                text = table.areaHeader,
                style = textStyle,
                color = colors.info,
                modifier = Modifier.weight(QUICK_START_AREA_COLUMN_WEIGHT),
            )
            Spacer(modifier = Modifier.width(QUICK_START_TABLE_COLUMN_GAP))
            CliQuickStartTableCell(
                text = table.tapHeader,
                style = textStyle,
                color = colors.info,
                modifier = Modifier.weight(QUICK_START_ACTION_COLUMN_WEIGHT),
            )
            Spacer(modifier = Modifier.width(QUICK_START_TABLE_COLUMN_GAP))
            CliQuickStartTableCell(
                text = table.holdHeader,
                style = textStyle,
                color = colors.info,
                modifier = Modifier.weight(QUICK_START_ACTION_COLUMN_WEIGHT),
            )
        }
        table.rows.forEach { row ->
            CliRowDivider()
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = CliSpacing.sm, vertical = CliSpacing.xs),
                verticalAlignment = Alignment.Top,
            ) {
                Row(
                    modifier = Modifier.weight(QUICK_START_AREA_COLUMN_WEIGHT),
                    verticalAlignment = Alignment.Top,
                ) {
                    CliIcon(
                        id = row.icon,
                        contentDescription = null,
                        modifier = Modifier.offset(y = QUICK_START_TABLE_ICON_DROP),
                        size = QUICK_START_TABLE_ICON_SIZE,
                        tint = if (iconColor == Color.Unspecified) colors.info else iconColor,
                    )
                    Spacer(modifier = Modifier.width(CliSpacing.xs))
                    CliQuickStartTableCell(
                        text = row.area,
                        style = textStyle,
                        color = colors.fg,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(modifier = Modifier.width(QUICK_START_TABLE_COLUMN_GAP))
                CliQuickStartTableCell(
                    text = row.tap,
                    style = textStyle,
                    color = colors.fg,
                    modifier = Modifier.weight(QUICK_START_ACTION_COLUMN_WEIGHT),
                )
                Spacer(modifier = Modifier.width(QUICK_START_TABLE_COLUMN_GAP))
                CliQuickStartTableCell(
                    text = row.hold,
                    style = textStyle,
                    color = if (row.hold == QUICK_START_NO_ACTION) colors.dim else colors.fg,
                    modifier = Modifier.weight(QUICK_START_ACTION_COLUMN_WEIGHT),
                )
            }
        }
    }
}

@Composable
private fun CliQuickStartTableCell(
    text: String,
    style: androidx.compose.ui.text.TextStyle,
    color: Color,
    modifier: Modifier,
) {
    Text(
        text = text,
        style = style,
        color = color,
        maxLines = QUICK_START_TABLE_MAX_LINES,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

@Composable
internal fun CliIconTextItems(
    items: List<CliQuickStartItem>,
    framed: Boolean,
    modifier: Modifier = Modifier,
    iconColor: Color = Color.Unspecified,
) {
    com.foxhole.guard.ui.cli.components.CliIconTextItems(
        items = items,
        framed = framed,
        modifier = modifier,
        iconColor = iconColor,
    )
}

private val QUICK_START_ICONS = listOf(
    R.drawable.lin_shield,
    R.drawable.lin_profiles,
    R.drawable.lin_power,
    R.drawable.lin_trash,
    R.drawable.lin_status,
    R.drawable.lin_link,
    R.drawable.lin_settings,
    R.drawable.lin_check,
    R.drawable.lin_globe,
    R.drawable.lin_update,
    R.drawable.lin_lock,
    R.drawable.lin_incognito,
)

private val QUICK_START_TABLE_ICONS = listOf(
    R.drawable.lin_shield,
    R.drawable.lin_terminal,
    R.drawable.lin_power,
    R.drawable.lin_status,
)

private const val QUICK_START_TABLE_SEPARATOR = '|'
private const val QUICK_START_TABLE_COLUMNS = 3
private const val QUICK_START_NO_ACTION = "—"
private const val QUICK_START_TABLE_MAX_LINES = 5
private const val QUICK_START_AREA_COLUMN_WEIGHT = 0.8f
private const val QUICK_START_ACTION_COLUMN_WEIGHT = 1.2f
private val QUICK_START_TABLE_ICON_SIZE = 14.dp
private val QUICK_START_TABLE_COLUMN_GAP = CliSpacing.sm
internal val QUICK_START_TABLE_ICON_DROP = 1.dp
