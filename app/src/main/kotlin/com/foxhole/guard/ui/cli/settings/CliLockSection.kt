package com.foxhole.guard.ui.cli.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.foxhole.core.model.AppLockMode
import com.foxhole.core.model.AppLockTimeout
import com.foxhole.core.model.GuardHostingMode
import com.foxhole.core.model.Settings
import com.foxhole.guard.R
import com.foxhole.guard.ui.HomeViewModel
import com.foxhole.guard.ui.biometricEncryptCipher
import com.foxhole.guard.ui.biometricStrongAvailable
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.components.CliActionRow
import com.foxhole.guard.ui.cli.components.CliChip
import com.foxhole.guard.ui.cli.components.CliDropdownOption
import com.foxhole.guard.ui.cli.components.CliDropdownRow
import com.foxhole.guard.ui.cli.components.CliElbowLine
import com.foxhole.guard.ui.cli.components.CliPanel
import com.foxhole.guard.ui.cli.components.CliToggleRow
import com.foxhole.guard.ui.completeBiometricEnrolment
import com.foxhole.guard.ui.disableBiometricUnlock
import com.foxhole.guard.ui.onAppLockTimeoutSelected
import com.foxhole.guard.ui.onBlockScreenshotsChanged
import com.foxhole.guard.ui.onEventMonitoringChanged
import com.foxhole.guard.ui.onGuardHostingSelected
import com.foxhole.guard.ui.onSystemBiometricToggled
import kotlinx.coroutines.launch

/**
 * The security group: the kill switch (moved here from the old connection section),
 * custom-password lifecycle (enable/change/disable through [CliPinPanel] over the setup
 * coordinator), the relock timeout, the screenshot block and the entry into the anomaly
 * sub-screen. SYSTEM lock stays configurable only in the classic UI in v1 - an existing
 * SYSTEM mode is shown read-only here.
 */
@Composable
internal fun CliSecuritySection(
    viewModel: HomeViewModel,
    settings: Settings,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onOpenAnomaly: () -> Unit,
) {
    // saveable: rotation mid-flow must not reset ENABLE/CHANGE/DISABLE to the start.
    var pinFlow by rememberSaveable { mutableStateOf<CliPinFlow?>(null) }
    val flow = pinFlow
    if (flow != null) {
        CliPinPanel(
            viewModel = viewModel,
            flow = flow,
            onClose = { pinFlow = null },
        )
        return
    }
    val mode = settings.appLock.mode
    CliPanel(
        title = stringResource(R.string.cli_cfg_group_security),
        icon = R.drawable.pix_lock,
        modifier = Modifier.fillMaxWidth(),
        collapsible = true,
        expanded = expanded,
        onToggleExpanded = onToggleExpanded,
    ) {
        // Kill switch left settings: it is an Android feature (VPN lockdown) and the link to it
        // lives in help.
        if (mode == AppLockMode.PASSWORD) {
            // The dropdown carries the two password actions; nothing is "selected".
            CliDropdownRow(
                label = stringResource(R.string.cli_lock_password),
                value = stringResource(R.string.cli_common_on),
                options = listOf(
                    CliDropdownOption(id = LOCK_OPT_CHANGE, label = stringResource(R.string.cli_lock_change)),
                    CliDropdownOption(id = LOCK_OPT_DISABLE, label = stringResource(R.string.cli_lock_disable)),
                ),
                selectedId = null,
                onSelect = { id ->
                    pinFlow = if (id == LOCK_OPT_CHANGE) CliPinFlow.CHANGE else CliPinFlow.DISABLE
                },
            )
        } else {
            // Without a custom password the pin flow opens directly - no menu in between.
            CliActionRow(
                label = stringResource(R.string.cli_lock_password),
                value = when (mode) {
                    AppLockMode.SYSTEM -> "system"
                    else -> stringResource(R.string.cli_common_off)
                },
                onTap = { pinFlow = CliPinFlow.ENABLE },
            )
        }
        if (mode != AppLockMode.OFF) {
            CliDropdownRow(
                label = stringResource(R.string.cli_lock_timeout),
                value = timeoutLabel(settings.appLock.lockTimeout),
                options = AppLockTimeout.entries.map { timeout ->
                    CliDropdownOption(id = timeout.name, label = timeoutLabel(timeout))
                },
                selectedId = settings.appLock.lockTimeout.name,
                onSelect = { id -> viewModel.onAppLockTimeoutSelected(AppLockTimeout.valueOf(id)) },
            )
        }
        if (mode == AppLockMode.PASSWORD) {
            CliToggleRow(
                label = stringResource(R.string.cli_lock_event_monitoring),
                checked = settings.appLock.eventMonitoringEnabled,
                onToggle = viewModel::onEventMonitoringChanged,
            )
            if (settings.appLock.eventMonitoringEnabled) {
                CliDropdownRow(
                    label = stringResource(R.string.cli_lock_guard_hosting),
                    value = guardHostingLabel(settings.appLock.guardHosting),
                    options = GuardHostingMode.entries.map { hosting ->
                        CliDropdownOption(id = hosting.name, label = guardHostingLabel(hosting))
                    },
                    selectedId = settings.appLock.guardHosting.name,
                    onSelect = { id -> viewModel.onGuardHostingSelected(GuardHostingMode.valueOf(id)) },
                )
            }
        }
        CliBiometricRow(viewModel = viewModel, settings = settings)
        CliFirewallRows(viewModel = viewModel, settings = settings)
        CliToggleRow(
            label = stringResource(R.string.cli_cfg_block_screenshots),
            checked = settings.expert.blockScreenshots,
            onToggle = viewModel::onBlockScreenshotsChanged,
        )
        CliActionRow(
            label = stringResource(R.string.cli_cfg_more_anomaly),
            onTap = onOpenAnomaly,
        )
    }
}

/**
 * Biometrics on top of either lock mode. SYSTEM = plain setting behind a confirm prompt;
 * PASSWORD = the prompt's cipher seals a hardware-bound master-key copy (donor contract).
 */
@Composable
private fun CliBiometricRow(
    viewModel: HomeViewModel,
    settings: Settings,
) {
    if (settings.appLock.mode == AppLockMode.OFF || !viewModel.biometricStrongAvailable()) {
        return
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    CliToggleRow(
        label = stringResource(R.string.cli_lock_biometric),
        checked = settings.appLock.biometricEnabled,
        onToggle = { enabled ->
            when {
                settings.appLock.mode != AppLockMode.PASSWORD && !enabled ->
                    viewModel.onSystemBiometricToggled(false)
                settings.appLock.mode != AppLockMode.PASSWORD ->
                    showCliBiometricConfirmPrompt(context) {
                        viewModel.onSystemBiometricToggled(true)
                    }
                !enabled -> viewModel.disableBiometricUnlock()
                else -> viewModel.biometricEncryptCipher()?.let { cipher ->
                    showCliBiometricEnrolPrompt(context, cipher) { authorized ->
                        scope.launch { viewModel.completeBiometricEnrolment(authorized) }
                    }
                }
            }
        },
    )
}

/** Firewall toggle with the one-time enable consent (donor contract, terminal y/n form). */
@Composable
private fun CliFirewallRows(
    viewModel: HomeViewModel,
    settings: Settings,
) {
    val colors = LocalCliColors.current
    var consentOpen by remember { mutableStateOf(false) }
    CliToggleRow(
        label = stringResource(R.string.cli_cfg_firewall),
        checked = settings.expert.firewallEnabled,
        onToggle = { enable ->
            if (enable && !settings.ui.suppressFirewallEnableWarning) {
                consentOpen = true
            } else {
                consentOpen = false
                viewModel.onFirewallEnabledChanged(enable)
            }
        },
    )
    if (consentOpen) {
        CliElbowLine(
            text = stringResource(R.string.cli_cfg_firewall_consent_body),
            color = colors.warn,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(CliSpacing.sm)) {
            // "Do not ask again" was removed by product decision: the firewall confirmation is
            // always asked and there is no silent path.
            CliChip(
                label = stringResource(R.string.cli_cfg_firewall_yes),
                color = colors.ok,
                onClick = {
                    consentOpen = false
                    viewModel.onFirewallEnabledChanged(true)
                },
            )
            CliChip(
                label = stringResource(R.string.cli_common_no_cancel),
                color = colors.err,
                onClick = { consentOpen = false },
            )
        }
    }
}

@Composable
private fun timeoutLabel(timeout: AppLockTimeout): String = when (timeout) {
    AppLockTimeout.AFTER_REBOOT -> stringResource(R.string.cli_lock_timeout_reboot)
    AppLockTimeout.IMMEDIATE -> stringResource(R.string.cli_lock_timeout_instant)
    AppLockTimeout.MIN_15 -> "15m"
    AppLockTimeout.MIN_30 -> "30m"
    AppLockTimeout.HOUR_1 -> "1h"
}

@Composable
private fun guardHostingLabel(mode: GuardHostingMode): String = when (mode) {
    GuardHostingMode.ECONOMY -> stringResource(R.string.cli_lock_guard_economy)
    GuardHostingMode.REINFORCED -> stringResource(R.string.cli_lock_guard_reinforced)
}

private const val LOCK_OPT_CHANGE = "change"
private const val LOCK_OPT_DISABLE = "disable"
