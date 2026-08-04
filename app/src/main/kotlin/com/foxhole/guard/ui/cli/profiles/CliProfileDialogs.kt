package com.foxhole.guard.ui.cli.profiles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.foxhole.core.model.Profile
import com.foxhole.core.model.ProfileSourceType
import com.foxhole.guard.R
import com.foxhole.guard.ui.HomeViewModel
import com.foxhole.guard.ui.ProfileImportConfirmationState
import com.foxhole.guard.ui.ProfilesRouteUiState
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.components.CliBottomSheet
import com.foxhole.guard.ui.cli.components.CliButton
import com.foxhole.guard.ui.cli.components.CliChip
import com.foxhole.guard.ui.cli.components.CliElbowLine
import com.foxhole.guard.ui.cli.components.CliFlagIcon
import com.foxhole.guard.ui.cli.components.CliKeyValue
import com.foxhole.guard.ui.cli.components.CliPanel
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
    CliPanel(
        icon = R.drawable.pix_import,
        title = stringResource(R.string.cli_prof_import_title),
        titleColor = colors.accent,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = confirmation.preview.displayName,
            style = CliType.body,
            color = colors.fg,
        )
        val protocols = confirmation.preview.protocolHints
            .joinToString(",") { it.name.lowercase() }
            .ifEmpty { "—" }
        val kind = if (confirmation.preview.subscription) {
            stringResource(R.string.cli_prof_import_kind_subscription)
        } else {
            stringResource(R.string.cli_prof_import_kind_profile)
        }
        CliElbowLine(
            text = stringResource(
                R.string.cli_prof_import_summary,
                kind,
                protocols,
                confirmation.preview.nodesCount,
            ),
        )
        if (confirmation.duplicateProfileId != null) {
            CliElbowLine(
                text = stringResource(
                    R.string.cli_prof_import_dup,
                    confirmation.duplicateProfileName.orEmpty(),
                ),
                color = colors.warn,
            )
        }
        if (confirmation.insecureTls) {
            CliElbowLine(
                text = stringResource(
                    R.string.cli_prof_import_insecure,
                    confirmation.insecureTlsProtocolLabels.joinToString(","),
                ),
                color = colors.err,
            )
        }
        Spacer(modifier = Modifier.height(CliSpacing.sm))
        Row(horizontalArrangement = Arrangement.spacedBy(CliSpacing.sm)) {
            if (confirmation.duplicateProfileId != null) {
                CliChip(
                    label = stringResource(R.string.cli_prof_import_yes_update),
                    color = colors.ok,
                    onClick = { viewModel.confirmProfileImportDuplicateUpdate() },
                )
            } else {
                CliChip(
                    label = stringResource(R.string.cli_prof_import_yes_add),
                    color = colors.ok,
                    onClick = { viewModel.confirmProfileImport() },
                )
                if (confirmation.insecureTls && confirmation.canExcludeInsecureTls) {
                    CliChip(
                        label = stringResource(R.string.cli_prof_import_no_insecure),
                        color = colors.warn,
                        onClick = { viewModel.confirmProfileImportExcludingInsecureTls() },
                    )
                }
            }
            CliChip(
                label = stringResource(R.string.cli_common_no_cancel),
                onClick = { viewModel.dismissProfileImportConfirmation() },
            )
        }
    }
}

// ISO date (yyyy-MM-dd) for the subscription-expiry row; the exact time is not useful here.
// Shared by the profile list and the detail sheet: the one place expiry dates are formatted.
internal fun formatExpiryDate(epochMs: Long): String =
    java.time.Instant.ofEpochMilli(epochMs)
        .atZone(java.time.ZoneId.systemDefault())
        .toLocalDate()
        .toString()

/**
 * The profile detail sheet: every fact laid out as a `key ....... value` table, the selected
 * option's live metrics, and a highlighted TEST button that probes the protocols. [onActivate]
 * makes it the current profile (a live switch is confirmed upstream via B2); the sheet slides up
 * from the bottom edge on a plain tap of the row, sharing the look of every other panel via
 * [CliBottomSheet] with the profile name as its caption.
 *
 * The body deliberately has no scroll of its own: the content is short enough for compact screens.
 * The sheet rule is that a short body does not scroll, so drag-to-dismiss owns the whole gesture,
 * while a long one scrolls and M3 nested scroll arbitrates the drag.
 */
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
    CliBottomSheet(onDismiss = onDismiss, title = profile.name, icon = R.drawable.pix_profiles) {
        val selected = profile.protocolOptions.firstOrNull { it.id == profile.selectedProtocolOptionId }
        CliKeyValue(
            key = stringResource(R.string.cli_prof_detail_protocol),
            value = profile.protocolHint.name.lowercase(),
            valueColor = colors.fg,
            icon = R.drawable.pix_shield,
        )
        // Geo from the node name; shown only when the name revealed it.
        profileCountryCode(selected?.displayName, profile.name)?.let { country ->
            CliKeyValue(
                key = stringResource(R.string.cli_prof_detail_geo),
                value = country.uppercase(),
                valueColor = colors.fg,
                icon = R.drawable.pix_map,
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
                R.drawable.pix_import
            } else {
                R.drawable.pix_edit
            },
        )
        if (profile.protocolOptions.size > 1) {
            CliKeyValue(
                key = stringResource(R.string.cli_prof_detail_options),
                value = profile.protocolOptions.size.toString(),
                valueColor = colors.fg,
                icon = R.drawable.pix_apps,
            )
            if (selected != null) {
                CliKeyValue(
                    key = stringResource(R.string.cli_prof_detail_selected),
                    value = selected.displayName,
                    valueColor = colors.info,
                    icon = R.drawable.pix_check,
                )
            }
        }
        profile.subscriptionExpiresAt?.let { expiresAt ->
            CliKeyValue(
                key = stringResource(R.string.cli_prof_detail_expires),
                value = formatExpiryDate(expiresAt),
                valueColor = colors.fg,
                icon = R.drawable.pix_clock,
            )
        }
        if (profile.requiresInsecureTls) {
            CliKeyValue(
                key = stringResource(R.string.cli_prof_detail_insecure),
                value = "!",
                valueColor = colors.warn,
                icon = R.drawable.pix_forbidden,
            )
        }
        // The selected option's last measured connect · ping · latency.
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
                icon = R.drawable.pix_stats,
            )
        }

        Spacer(modifier = Modifier.height(CliSpacing.md))
        Row(horizontalArrangement = Arrangement.spacedBy(CliSpacing.sm)) {
            // The highlighted primary action — the TEST button.
            CliSmartTestButton(
                viewModel = viewModel,
                profileId = profile.id,
                testing = testing,
                modifier = Modifier.weight(1f),
            )
            CliButton(
                label = stringResource(R.string.cli_prof_detail_activate),
                color = colors.vpn,
                enabled = !isActive,
                onClick = onActivate,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(modifier = Modifier.height(CliSpacing.xs))
        CliButton(
            label = stringResource(R.string.cli_common_no_cancel),
            color = colors.dim,
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
