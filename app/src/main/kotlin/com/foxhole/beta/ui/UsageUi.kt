package com.foxhole.beta.ui

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.foxhole.beta.R
import com.foxhole.beta.core.model.Profile
import com.foxhole.beta.core.model.ProfileTrafficTotal
import com.foxhole.beta.core.model.Settings as FoxholeSettings
import com.foxhole.beta.core.model.TrafficSnapshot

@Composable
internal fun UsageTotalsCard(
    state: DiagnosticsRouteUiState,
    modifier: Modifier = Modifier,
    onClear: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val totals = visibleProfileTrafficTotals(state)

    FoxholeCard(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.diagnostics_usage_title),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            onClear?.let {
                IconButton(onClick = it) {
                    Icon(
                        imageVector = Icons.Outlined.DeleteSweep,
                        contentDescription = stringResource(R.string.clear_usage_title),
                    )
                }
            }
        }
        Text(
            text = stringResource(R.string.diagnostics_usage_summary),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (totals.isEmpty()) {
            Text(
                text = stringResource(R.string.diagnostics_usage_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(CardContentSpacing)) {
                totals.forEach { total ->
                    UsageTotalRow(total = total, context = context)
                }
            }
        }
    }
}

internal fun visibleProfileTrafficTotals(state: DiagnosticsRouteUiState): List<ProfileTrafficTotal> =
    visibleProfileTrafficTotals(
        settings = state.settings,
        activeProfile = state.activeProfile,
        traffic = state.traffic,
    )

internal fun visibleProfileTrafficTotals(state: HomeRouteUiState): List<ProfileTrafficTotal> =
    visibleProfileTrafficTotals(
        settings = state.settings,
        activeProfile = state.activeProfile,
        traffic = state.traffic,
        currentProtocolOptionId = state.connection.protocolOptionId,
        currentProtocolHint = state.connection.protocolHint,
    )

private fun visibleProfileTrafficTotals(
    settings: FoxholeSettings,
    activeProfile: Profile?,
    traffic: TrafficSnapshot,
    currentProtocolOptionId: String? = null,
    currentProtocolHint: com.foxhole.beta.core.model.ProtocolHint? = null,
): List<ProfileTrafficTotal> {
    val totals = settings.profileTrafficTotals.associateBy(ProfileTrafficTotal::trafficKey).toMutableMap()
    if (activeProfile != null && (traffic.rxTotalBytes > 0L || traffic.txTotalBytes > 0L)) {
        val activeOptionId =
            currentProtocolOptionId
                ?.takeIf(String::isNotBlank)
                ?.takeIf { optionId -> activeProfile.protocolOptions.any { option -> option.id == optionId } }
                ?: activeProfile.selectedProtocolOptionId?.takeIf(String::isNotBlank)
        val activeOption = activeProfile.protocolOptions.firstOrNull { option -> option.id == activeOptionId }
        val trafficKey = ProfileTrafficKey(activeProfile.id, activeOptionId)
        val stored = totals[trafficKey]
        totals[trafficKey] =
            ProfileTrafficTotal(
                profileId = activeProfile.id,
                profileName = activeProfile.name,
                protocolHint = activeOption?.protocolHint ?: currentProtocolHint ?: activeProfile.protocolHint,
                protocolOptionId = activeOptionId,
                rxTotalBytes = (stored?.rxTotalBytes ?: 0L) + traffic.rxTotalBytes,
                txTotalBytes = (stored?.txTotalBytes ?: 0L) + traffic.txTotalBytes,
                updatedAt = maxOf(stored?.updatedAt ?: 0L, traffic.sampledAt),
            )
    }
    return totals.values.sortedByDescending(ProfileTrafficTotal::updatedAt)
}

private data class ProfileTrafficKey(
    val profileId: Long,
    val protocolOptionId: String?,
)

private val ProfileTrafficTotal.trafficKey: ProfileTrafficKey
    get() = ProfileTrafficKey(profileId, protocolOptionId?.takeIf(String::isNotBlank))

@Composable
private fun UsageTotalRow(
    total: ProfileTrafficTotal,
    context: android.content.Context,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = total.profileName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = protocolLabel(total.protocolHint.name),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = formatUsageBytes(context, total.rxTotalBytes + total.txTotalBytes),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.End,
            )
            Text(
                text =
                    stringResource(
                        R.string.diagnostics_usage_breakdown,
                        formatUsageBytes(context, total.rxTotalBytes),
                        formatUsageBytes(context, total.txTotalBytes),
                    ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
            )
        }
    }
}

private fun formatUsageBytes(
    context: android.content.Context,
    bytes: Long,
): String = Formatter.formatShortFileSize(context, bytes)

internal fun protocolLabel(raw: String): String = raw.lowercase().replace('_', '-')
