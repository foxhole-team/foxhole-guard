package com.foxhole.guard.ui.cli.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.components.CliActionRow
import com.foxhole.guard.ui.cli.components.CliDropdownOption
import com.foxhole.guard.ui.cli.components.CliDropdownRow
import com.foxhole.guard.ui.cli.components.CliPanel
import com.foxhole.guard.ui.cli.components.CliPanelEdgeToEdgeContentPadding
import com.foxhole.guard.ui.cli.components.CliRowDivider
import com.foxhole.guard.ui.cli.components.CliToggleRow
import com.foxhole.guard.ui.completeBiometricEnrolment
import com.foxhole.guard.ui.disableBiometricUnlock
import com.foxhole.guard.ui.onAppLockTimeoutSelected
import com.foxhole.guard.ui.onBlockScreenshotsChanged
import com.foxhole.guard.ui.onEventMonitoringChanged
import com.foxhole.guard.ui.onGuardHostingSelected
import com.foxhole.guard.ui.onSystemBiometricToggled
import kotlinx.coroutines.launch

@Composable
@Suppress("LongMethod")
internal fun CliSecuritySection(
    viewModel: HomeViewModel,
    settings: Settings,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
) {
    val colors = LocalCliColors.current
    // saveable: rotation mid-flow must not reset ENABLE/CHANGE/DISABLE to the start.
    var pinFlow by rememberSaveable { mutableStateOf<CliPinFlow?>(null) }
    var consentFlow by rememberSaveable { mutableStateOf<CliPinFlow?>(null) }
    var eventMonitoring by rememberSaveable { mutableStateOf(true) }
    var appJournal by rememberSaveable { mutableStateOf(true) }
    val flow = pinFlow
    if (flow != null) {
        CliPinPanel(
            viewModel = viewModel,
            flow = flow,
            onClose = { pinFlow = null },
            eventMonitoring = eventMonitoring,
            appJournal = appJournal,
        )
        return
    }
    val mode = settings.appLock.mode
    CliPanel(
        title = stringResource(R.string.cli_cfg_group_security),
        icon = R.drawable.lin_lock,
        iconColor = colors.accent,
        modifier = Modifier.fillMaxWidth(),
        collapsible = true,
        expanded = expanded,
        onToggleExpanded = onToggleExpanded,
        contentPadding = CliPanelEdgeToEdgeContentPadding,
    ) {
        if (mode == AppLockMode.PASSWORD) {
            CliDropdownRow(
                label = stringResource(R.string.cli_lock_password),
                icon = R.drawable.lin_lock,
                value = stringResource(R.string.cli_common_on),
                options = listOf(
                    CliDropdownOption(id = LOCK_OPT_CHANGE, label = stringResource(R.string.cli_lock_change)),
                    CliDropdownOption(id = LOCK_OPT_DISABLE, label = stringResource(R.string.cli_lock_disable)),
                ),
                selectedId = null,
                onSelect = { id ->
                    if (id == LOCK_OPT_CHANGE) {
                        pinFlow = CliPinFlow.CHANGE
                    } else {
                        consentFlow = CliPinFlow.DISABLE
                    }
                },
            )
        } else {
            CliActionRow(
                label = stringResource(R.string.cli_lock_password),
                icon = R.drawable.lin_lock,
                value = when (mode) {
                    AppLockMode.SYSTEM -> "system"
                    else -> stringResource(R.string.cli_common_off)
                },
                onTap = { consentFlow = CliPinFlow.ENABLE },
            )
        }
        CliRowDivider()
        CliSettingsAnimatedRows(visible = mode != AppLockMode.OFF) {
            CliDropdownRow(
                label = stringResource(R.string.cli_lock_timeout),
                icon = R.drawable.lin_clock,
                value = timeoutLabel(settings.appLock.lockTimeout),
                options = AppLockTimeout.entries.map { timeout ->
                    CliDropdownOption(id = timeout.name, label = timeoutLabel(timeout))
                },
                selectedId = settings.appLock.lockTimeout.name,
                onSelect = { id -> viewModel.onAppLockTimeoutSelected(AppLockTimeout.valueOf(id)) },
            )
            CliRowDivider()
        }
        CliSettingsAnimatedRows(visible = mode == AppLockMode.PASSWORD) {
            val eventMonitoringActive = settings.anomaly.enabled && settings.appLock.eventMonitoringEnabled
            CliToggleRow(
                label = stringResource(R.string.cli_lock_event_monitoring),
                icon = R.drawable.lin_shield,
                checked = eventMonitoringActive,
                onToggle = viewModel::onEventMonitoringChanged,
                enabled = settings.anomaly.enabled,
            )
            CliSettingsAnimatedRows(visible = eventMonitoringActive) {
                CliRowDivider()
                CliDropdownRow(
                    label = stringResource(R.string.cli_lock_guard_hosting),
                    icon = R.drawable.lin_status,
                    value = guardHostingLabel(settings.appLock.guardHosting),
                    options = GuardHostingMode.entries.map { hosting ->
                        CliDropdownOption(id = hosting.name, label = guardHostingLabel(hosting))
                    },
                    selectedId = settings.appLock.guardHosting.name,
                    onSelect = { id -> viewModel.onGuardHostingSelected(GuardHostingMode.valueOf(id)) },
                )
            }
            CliRowDivider()
        }
        CliSettingsAnimatedRows(
            visible = mode != AppLockMode.OFF && viewModel.biometricStrongAvailable(),
        ) {
            CliBiometricRow(viewModel = viewModel, settings = settings)
            CliRowDivider()
        }
        CliToggleRow(
            label = stringResource(R.string.cli_cfg_block_screenshots),
            icon = R.drawable.lin_forbidden,
            checked = settings.expert.blockScreenshots,
            onToggle = viewModel::onBlockScreenshotsChanged,
        )
    }
    consentFlow?.let { pending ->
        CliEncryptionConsentSheet(
            flow = pending,
            eventMonitoring = eventMonitoring,
            appJournal = appJournal,
            onEventMonitoring = { eventMonitoring = it },
            onAppJournal = { appJournal = it },
            onConfirm = {
                consentFlow = null
                pinFlow = pending
            },
            onDismiss = { consentFlow = null },
        )
    }
}

@Composable
private fun CliBiometricRow(
    viewModel: HomeViewModel,
    settings: Settings,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    CliToggleRow(
        label = stringResource(R.string.cli_lock_biometric),
        icon = R.drawable.lin_check,
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
