package com.foxhole.guard.ui.cli.sentinel

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.foxhole.core.model.AnomalyEvent
import com.foxhole.core.model.InstalledAppOption
import com.foxhole.guard.R
import com.foxhole.guard.core.sentinel.anomaly.userFacingMessage
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.components.CliBottomSheet
import com.foxhole.guard.ui.cli.components.CliPanel
import com.foxhole.guard.ui.cli.components.CliRowDivider
import com.foxhole.guard.ui.cli.components.CliActionRow
import java.text.DateFormat
import java.util.Date

@Composable
internal fun SentinelObservationCards(events: List<AnomalyEvent>, installedApps: List<InstalledAppOption>, onOpenReport: ((String) -> Unit)? = null) {
    val context = LocalContext.current
    val details = stringResource(R.string.sentinel_assessment_details)
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val installed = installedApps.associateBy { it.packageName }
    events.firstOrNull { eventKey(it) == selected }?.let { event ->
        CliBottomSheet(title = details, onDismiss = { selected = null }, icon = R.drawable.lin_status) {
            Text(installed[event.packageName]?.label ?: event.packageName.orEmpty(), style = CliType.body)
            event.packageName?.let { SentinelTechnicalValue(it) }
            Text(event.userFacingMessage(context), style = CliType.small)
            Text(eventDate(event.createdAtMs), style = CliType.small)
            Text(stringResource(R.string.sentinel_assessment_match_note), style = CliType.small)
            event.evidence["scan_report_id"]?.takeIf { it.matches(Regex("[A-Za-z0-9_.-]{1,128}")) }?.let { id ->
                if (onOpenReport != null) CliActionRow(stringResource(R.string.sentinel_report_open), onTap = { selected = null; onOpenReport(id) })
            }
            for ((key, value) in event.evidence.entries.take(32)) {
                Text(key, style = CliType.body)
                SentinelTechnicalValue(value)
            }
            if (event.evidence.size > 32) Text(stringResource(R.string.sentinel_report_display_limited), style = CliType.small)
        }
    }
    CliPanel(title = stringResource(R.string.sentinel_assessment_observations), modifier = Modifier.fillMaxWidth(), icon = R.drawable.lin_status) {
        if (events.isEmpty()) {
            Text(stringResource(R.string.cli_stats_anomalies_empty), style = CliType.small, color = LocalCliColors.current.dim)
        } else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
            itemsIndexed(events, key = { index, event -> "${eventKey(event)}:$index" }) { _, event ->
                Column(Modifier.fillMaxWidth().heightIn(min = 56.dp)
                    .clickable(role = Role.Button, onClickLabel = details) { selected = eventKey(event) }
                    .semantics(mergeDescendants = true) {}
                    .padding(vertical = CliSpacing.sm)) {
                    Text(installed[event.packageName]?.label ?: event.packageName ?: stringResource(R.string.sentinel_assessment_unknown), style = CliType.body)
                    event.packageName?.let { Text(it, style = CliType.small, color = LocalCliColors.current.dim) }
                    Text(event.userFacingMessage(context), style = CliType.small)
                    Text(eventDate(event.createdAtMs), style = CliType.small, color = LocalCliColors.current.dim)
                    Text(details, style = CliType.small)
                }
                CliRowDivider()
            }
        }
    }
}

private fun eventKey(event: AnomalyEvent): String = "${event.id}:${event.createdAtMs}:${event.packageName}:${event.type.name}"
private fun eventDate(value: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(value))
