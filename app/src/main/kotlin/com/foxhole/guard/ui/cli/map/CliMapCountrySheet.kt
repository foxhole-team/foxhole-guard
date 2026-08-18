package com.foxhole.guard.ui.cli.map

import android.util.LruCache
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.foxhole.core.model.TrafficMapCountryAppRow
import com.foxhole.core.model.TrafficMapCountryDetail
import com.foxhole.core.model.TrafficMapCountryHostRow
import com.foxhole.guard.R
import com.foxhole.guard.ui.cli.CliFormat
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.cliRowTextStyle
import com.foxhole.guard.ui.cli.cliScaledDp
import com.foxhole.guard.ui.cli.components.CliBottomSheet
import com.foxhole.guard.ui.cli.components.CliDashedInfoNote
import com.foxhole.guard.ui.cli.components.CliElbowLine
import com.foxhole.guard.ui.cli.components.CliFlagIcon
import com.foxhole.guard.ui.cli.components.CliPixIcon
import com.foxhole.guard.ui.cli.components.CliRowDivider
import com.foxhole.guard.ui.cli.settings.rememberCliAppIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
internal fun CliMapCountrySheet(
    countryCode: String,
    label: String,
    bytes: Long,
    connections: Int,
    detail: TrafficMapCountryDetail?,
    journalEnabled: Boolean,
    onDismiss: () -> Unit,
) {
    val colors = LocalCliColors.current
    CliBottomSheet(
        onDismiss = onDismiss,
        title = label,
        icon = R.drawable.pix_globe,
    ) {
        CliMapCountrySummaryRow(
            countryCode = countryCode,
            bytes = bytes,
            connections = connections,
        )
        val appRows = detail?.appRows.orEmpty()
        val hostRows = detail?.hostRows.orEmpty()
        if (appRows.isEmpty() && hostRows.isEmpty()) {
            CliDashedInfoNote(text = stringResource(R.string.cli_map_country_sheet_empty))
            if (!journalEnabled) {
                CliElbowLine(text = stringResource(R.string.cli_map_country_sheet_journal_hint))
            }
        } else {
            if (appRows.isNotEmpty()) {
                CliMapCountrySection(
                    title = stringResource(R.string.cli_stats_apps_title),
                    icon = R.drawable.pix_apps,
                )
                appRows.forEachIndexed { index, row ->
                    if (index > 0) CliRowDivider()
                    CliMapCountryAppRow(row)
                }
            }
            if (hostRows.isNotEmpty()) {
                CliMapCountrySection(
                    title = stringResource(R.string.cli_map_country_sheet_hosts),
                    icon = R.drawable.pix_link,
                )
                hostRows.forEachIndexed { index, row ->
                    if (index > 0) CliRowDivider()
                    CliMapCountryHostRow(row)
                }
            }
        }
    }
}

@Composable
private fun CliMapCountrySummaryRow(
    countryCode: String,
    bytes: Long,
    connections: Int,
) {
    val colors = LocalCliColors.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = SHEET_ROW_PADDING),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.width(SHEET_LEADING_SLOT),
            contentAlignment = Alignment.CenterStart,
        ) {
            CliFlagIcon(countryCode = countryCode)
        }
        Spacer(modifier = Modifier.width(SHEET_LEADING_GAP))
        Text(
            text = countryCode.uppercase(Locale.US),
            style = cliRowTextStyle(),
            color = colors.fg,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        CliMapCountryValueCells(bytes = bytes, connections = connections, color = colors.fg)
    }
    CliRowDivider()
}

@Composable
private fun CliMapCountrySection(
    title: String,
    @DrawableRes icon: Int,
) {
    val colors = LocalCliColors.current
    Spacer(modifier = Modifier.height(CliSpacing.sm))
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = SHEET_ROW_PADDING),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.width(SHEET_LEADING_SLOT),
            contentAlignment = Alignment.CenterStart,
        ) {
            CliPixIcon(id = icon, contentDescription = null, size = SHEET_APP_ICON_SIZE, tint = colors.dim)
        }
        Spacer(modifier = Modifier.width(SHEET_LEADING_GAP))
        Text(
            text = title,
            style = CliType.small,
            color = colors.faint,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(R.string.cli_map_column_traffic),
            style = CliType.small,
            color = colors.faint,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.width(SHEET_TRAFFIC_COLUMN_WIDTH),
        )
        Spacer(modifier = Modifier.width(CliSpacing.sm))
        Text(
            text = stringResource(R.string.cli_map_column_connections),
            style = CliType.small,
            color = colors.faint,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.width(SHEET_CONNECTIONS_COLUMN_WIDTH),
        )
    }
    CliRowDivider()
}

@Composable
private fun CliMapCountryAppRow(row: TrafficMapCountryAppRow) {
    val colors = LocalCliColors.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = SHEET_ROW_PADDING),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val icon = rememberCliAppIcon(
            packageName = row.packageName,
            versionCode = null,
            lastUpdateTime = null,
            bitmapSize = SHEET_APP_ICON_SIZE,
        )
        Box(
            modifier = Modifier.width(SHEET_LEADING_SLOT),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (icon != null) {
                Image(
                    bitmap = icon,
                    contentDescription = null,
                    modifier = Modifier.size(SHEET_APP_ICON_SIZE),
                )
            } else {
                CliPixIcon(
                    id = R.drawable.pix_apps,
                    contentDescription = null,
                    size = SHEET_APP_ICON_SIZE,
                    tint = colors.dim,
                )
            }
        }
        Spacer(modifier = Modifier.width(SHEET_LEADING_GAP))
        Text(
            text = rememberCliAppLabel(row.packageName) ?: row.packageName,
            style = cliRowTextStyle(),
            color = colors.fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        CliMapCountryValueCells(bytes = row.bytes, connections = row.connections)
    }
}

@Composable
private fun CliMapCountryHostRow(row: TrafficMapCountryHostRow) {
    val colors = LocalCliColors.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = SHEET_ROW_PADDING),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.width(SHEET_LEADING_SLOT),
            contentAlignment = Alignment.CenterStart,
        ) {
            CliPixIcon(
                id = R.drawable.pix_globe,
                contentDescription = null,
                size = SHEET_APP_ICON_SIZE,
                tint = colors.dim,
            )
        }
        Spacer(modifier = Modifier.width(SHEET_LEADING_GAP))
        val host = row.remotePort?.let { port -> "${row.remoteHost}:$port" } ?: row.remoteHost
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = host,
                style = cliRowTextStyle(),
                color = colors.fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            row.protocol.takeIf(String::isNotBlank)?.let { protocol ->
                Spacer(modifier = Modifier.width(CliSpacing.xs))
                Text(
                    text = protocol.lowercase(Locale.US),
                    style = CliType.small,
                    color = colors.dim,
                    maxLines = 1,
                )
            }
        }
        CliMapCountryValueCells(bytes = row.bytes, connections = row.connections)
    }
}

@Composable
private fun CliMapCountryValueCells(
    bytes: Long,
    connections: Int,
    color: Color = Color.Unspecified,
) {
    val colors = LocalCliColors.current
    val resolved = if (color == Color.Unspecified) colors.dim else color
    Text(
        text = CliFormat.bytes(bytes),
        style = cliRowTextStyle(),
        color = resolved,
        textAlign = TextAlign.End,
        maxLines = 1,
        modifier = Modifier.width(SHEET_TRAFFIC_COLUMN_WIDTH),
    )
    Spacer(modifier = Modifier.width(CliSpacing.sm))
    Text(
        text = connections.toString(),
        style = cliRowTextStyle(),
        color = resolved,
        textAlign = TextAlign.End,
        maxLines = 1,
        modifier = Modifier.width(SHEET_CONNECTIONS_COLUMN_WIDTH),
    )
}

private val appLabelCache = LruCache<String, String>(APP_LABEL_CACHE_ENTRIES)

@Composable
private fun rememberCliAppLabel(packageName: String): String? {
    val context = LocalContext.current.applicationContext
    val label by produceState(initialValue = appLabelCache.get(packageName), packageName) {
        value = appLabelCache.get(packageName) ?: withContext(Dispatchers.IO) {
            runCatching {
                val packageManager = context.packageManager
                packageManager
                    .getApplicationLabel(packageManager.getApplicationInfo(packageName, 0))
                    .toString()
            }.getOrNull()
        }?.also { resolved -> appLabelCache.put(packageName, resolved) }
    }
    return label
}

private const val APP_LABEL_CACHE_ENTRIES = 128

private val SHEET_APP_ICON_SIZE = 16.dp
private val SHEET_LEADING_SLOT = SHEET_APP_ICON_SIZE
private val SHEET_LEADING_GAP = 6.dp
private val SHEET_ROW_PADDING = 3.dp
private val SHEET_TRAFFIC_COLUMN_WIDTH = cliScaledDp(74f)
private val SHEET_CONNECTIONS_COLUMN_WIDTH = cliScaledDp(44f)
