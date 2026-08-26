package com.foxhole.guard.ui.cli.profiles

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.foxhole.core.model.Profile
import com.foxhole.core.model.ProfileSourceType
import com.foxhole.guard.R
import com.foxhole.guard.ui.HomeViewModel
import com.foxhole.guard.ui.ProfileImportConfirmationState
import com.foxhole.guard.ui.ProfilesRouteUiState
import com.foxhole.guard.ui.cli.CliRadius
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.components.CliBottomSheet
import com.foxhole.guard.ui.cli.components.CliButton
import com.foxhole.guard.ui.cli.components.CliElbowLine
import com.foxhole.guard.ui.cli.components.CliFlagIcon
import com.foxhole.guard.ui.cli.components.CliKeyValue
import com.foxhole.guard.ui.cli.components.CliLoadingRow
import com.foxhole.guard.ui.cli.components.CliToggleRow
import com.foxhole.guard.ui.confirmProfileImport
import com.foxhole.guard.ui.confirmProfileImportDuplicateUpdate
import com.foxhole.guard.ui.confirmProfileImportExcludingInsecureTls
import com.foxhole.guard.ui.dismissProfileImportConfirmation

@Composable
internal fun CliImportConfirmPanel(
    viewModel: HomeViewModel,
    confirmation: ProfileImportConfirmationState,
) {
    val colors = LocalCliColors.current
    CliBottomSheet(
        onDismiss = { viewModel.dismissProfileImportConfirmation() },
        icon = R.drawable.lin_import,
        title = stringResource(R.string.cli_prof_import_title),
    ) {
        Text(
            text = confirmation.preview.displayName,
            style = CliType.body,
            color = colors.fg,
        )
        val protocols = confirmation.preview.protocolHints
            .map { hint -> hint.name.lowercase() }
            .distinct()
        val kind = if (confirmation.preview.subscription) {
            stringResource(R.string.cli_prof_import_kind_subscription)
        } else {
            stringResource(R.string.cli_prof_import_kind_profile)
        }
        CliElbowLine(
            text = stringResource(
                R.string.cli_prof_import_summary,
                kind,
                confirmation.preview.nodesCount,
            ),
        )
        Spacer(modifier = Modifier.height(CliSpacing.xs))
        Text(
            text = stringResource(R.string.cli_prof_import_protocols),
            style = CliType.small,
            color = colors.faint,
        )
        Spacer(modifier = Modifier.height(CliSpacing.xs))
        CliImportProtocolGrid(protocols = protocols)
        if (confirmation.duplicateProfileId != null) {
            CliElbowLine(
                text = stringResource(
                    R.string.cli_prof_import_dup,
                    confirmation.duplicateProfileName.orEmpty(),
                ),
                color = colors.warn,
            )
        }
        var insecureTlsConsent by remember(confirmation.rawInput) { mutableStateOf(false) }
        if (confirmation.insecureTls) {
            CliElbowLine(
                text = stringResource(
                    R.string.cli_prof_import_insecure,
                    confirmation.insecureTlsProtocolLabels.joinToString(","),
                ),
                color = colors.err,
            )
            CliToggleRow(
                label = stringResource(R.string.cli_prof_import_insecure_consent),
                checked = insecureTlsConsent,
                onToggle = { insecureTlsConsent = it },
                enabled = confirmation.canConfirm,
            )
        }
        when {
            confirmation.subscriptionInspectionInProgress ->
                CliLoadingRow(text = stringResource(R.string.cli_prof_import_inspecting))
            confirmation.subscriptionInspectionFailed ->
                CliElbowLine(
                    text = stringResource(R.string.cli_prof_import_inspection_failed),
                    color = colors.err,
                )
        }
        Spacer(modifier = Modifier.height(CliSpacing.sm))
        if (confirmation.duplicateProfileId != null) {
            CliButton(
                label = stringResource(R.string.cli_prof_import_yes_update),
                color = colors.ok,
                enabled = confirmation.canConfirm && (!confirmation.insecureTls || insecureTlsConsent),
                onClick = { viewModel.confirmProfileImportDuplicateUpdate() },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            CliButton(
                label = stringResource(R.string.cli_prof_import_yes_add),
                color = colors.ok,
                enabled = confirmation.canConfirm && (!confirmation.insecureTls || insecureTlsConsent),
                onClick = { viewModel.confirmProfileImport() },
                modifier = Modifier.fillMaxWidth(),
            )
            if (confirmation.insecureTls && confirmation.canExcludeInsecureTls) {
                Spacer(modifier = Modifier.height(CliSpacing.sm))
                CliButton(
                    label = stringResource(R.string.cli_prof_import_no_insecure),
                    color = colors.warn,
                    enabled = confirmation.canConfirm,
                    onClick = { viewModel.confirmProfileImportExcludingInsecureTls() },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun CliImportProtocolGrid(protocols: List<String>) {
    val colors = LocalCliColors.current
    val labels = protocols.ifEmpty { listOf("—") }
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val columns = cliImportProtocolGridColumns(maxWidth, labels)
        Column(verticalArrangement = Arrangement.spacedBy(CliSpacing.xs)) {
            labels.chunked(columns).forEach { rowLabels ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(CliSpacing.xs),
                ) {
                    rowLabels.forEach { label ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = CLI_IMPORT_PROTOCOL_CELL_MIN_HEIGHT)
                                .clip(RoundedCornerShape(CliRadius.control))
                                .border(
                                    width = 1.dp,
                                    color = colors.borderBright,
                                    shape = RoundedCornerShape(CliRadius.control),
                                )
                                .padding(horizontal = CliSpacing.xs, vertical = CliSpacing.xs),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = label,
                                style = CliType.small,
                                color = colors.info,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                    repeat(columns - rowLabels.size) { Spacer(modifier = Modifier.weight(1f)) }
                }
            }
        }
    }
}

internal fun cliImportProtocolGridColumns(
    availableWidth: Dp,
    labels: List<String>,
): Int {
    if (labels.isEmpty()) return 1
    val longestLabelLength = labels.maxOf(String::length)
    val labelWidth = CLI_IMPORT_PROTOCOL_BASE_WIDTH_DP +
        (longestLabelLength - CLI_IMPORT_PROTOCOL_BASE_LABEL_LENGTH).coerceAtLeast(0) *
        CLI_IMPORT_PROTOCOL_CHAR_WIDTH_DP
    val fit = (
        (availableWidth.value + CLI_IMPORT_PROTOCOL_GAP_DP) /
            (labelWidth + CLI_IMPORT_PROTOCOL_GAP_DP)
        ).toInt()
    return fit
        .coerceIn(CLI_IMPORT_PROTOCOL_MIN_COLUMNS, CLI_IMPORT_PROTOCOL_MAX_COLUMNS)
        .coerceAtMost(labels.size)
}

private val CLI_IMPORT_PROTOCOL_CELL_MIN_HEIGHT = 36.dp
private const val CLI_IMPORT_PROTOCOL_MIN_COLUMNS = 3
private const val CLI_IMPORT_PROTOCOL_MAX_COLUMNS = 5
private const val CLI_IMPORT_PROTOCOL_BASE_WIDTH_DP = 58f
private const val CLI_IMPORT_PROTOCOL_BASE_LABEL_LENGTH = 4
private const val CLI_IMPORT_PROTOCOL_CHAR_WIDTH_DP = 5f
private const val CLI_IMPORT_PROTOCOL_GAP_DP = 8f

internal fun formatExpiryDate(epochMs: Long): String =
    java.time.Instant.ofEpochMilli(epochMs)
        .atZone(java.time.ZoneId.systemDefault())
        .toLocalDate()
        .toString()

@Composable
internal fun CliProfileDetailSheet(
    viewModel: HomeViewModel,
    state: ProfilesRouteUiState,
    profile: Profile,
    isActive: Boolean,
    onActivate: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalCliColors.current
    val testing = profile.id in state.smartProfileMetricsRefreshingProfileIds
    CliBottomSheet(onDismiss = onDismiss, title = profile.name, icon = R.drawable.lin_profiles) {
        val selected = profile.protocolOptions.firstOrNull { it.id == profile.selectedProtocolOptionId }
        CliKeyValue(
            key = stringResource(R.string.cli_prof_detail_protocol),
            value = profile.protocolHint.name.lowercase(),
            valueColor = colors.fg,
            icon = R.drawable.lin_shield,
        )
        profileCountryCode(selected?.displayName, profile.name)?.let { country ->
            CliKeyValue(
                key = stringResource(R.string.cli_prof_detail_geo),
                value = country.uppercase(),
                valueColor = colors.fg,
                icon = R.drawable.lin_map,
                valueLeading = { CliFlagIcon(countryCode = country) },
            )
        }
        CliKeyValue(
            key = stringResource(R.string.cli_prof_detail_source),
            value = stringResource(
                if (profile.sourceType == ProfileSourceType.SUBSCRIPTION_URL) {
                    R.string.cli_prof_detail_source_sub
                } else {
                    R.string.cli_prof_detail_source_user
                },
            ),
            valueColor = colors.fg,
            icon = if (profile.sourceType == ProfileSourceType.SUBSCRIPTION_URL) {
                R.drawable.lin_import
            } else {
                R.drawable.lin_edit
            },
        )
        if (profile.protocolOptions.size > 1) {
            CliKeyValue(
                key = stringResource(R.string.cli_prof_detail_options),
                value = profile.protocolOptions.size.toString(),
                valueColor = colors.fg,
                icon = R.drawable.lin_apps,
            )
            if (selected != null) {
                CliKeyValue(
                    key = stringResource(R.string.cli_prof_detail_selected),
                    value = selected.displayName,
                    valueColor = colors.info,
                    icon = R.drawable.lin_check,
                )
            }
        }
        profile.subscriptionExpiresAt?.let { expiresAt ->
            CliKeyValue(
                key = stringResource(R.string.cli_prof_detail_expires),
                value = formatExpiryDate(expiresAt),
                valueColor = colors.fg,
                icon = R.drawable.lin_clock,
            )
        }
        if (profile.requiresInsecureTls) {
            CliKeyValue(
                key = stringResource(R.string.cli_prof_detail_insecure),
                value = "!",
                valueColor = colors.warn,
                icon = R.drawable.lin_forbidden,
            )
        }
        val optionId = profile.selectedProtocolOptionId
        if (optionId != null) {
            val connect = state.smartProfileConnectDurationsByProfileId[profile.id]?.get(optionId)
            val ping = state.smartProfileServerPingsByProfileId[profile.id]?.get(optionId)
            val latency = state.smartStartRememberedLatenciesByProfileId[profile.id]?.get(optionId)
            CliKeyValue(
                key = stringResource(R.string.cli_prof_detail_metrics),
                value = listOf(connect, ping, latency)
                    .joinToString(" · ") { it?.let { ms -> "${ms}ms" } ?: "—" },
                valueColor = colors.dim,
                icon = R.drawable.lin_stats,
            )
        }

        Spacer(modifier = Modifier.height(CliSpacing.md))
        Row(horizontalArrangement = Arrangement.spacedBy(CliSpacing.sm)) {
            CliSmartTestButton(
                viewModel = viewModel,
                profileId = profile.id,
                testing = testing,
                modifier = Modifier.weight(1f),
            )
            CliButton(
                label = stringResource(R.string.cli_prof_detail_activate),
                color = colors.ok,
                enabled = !isActive,
                onClick = onActivate,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
