package com.foxhole.guard.router.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.text.input.maxLength
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay
import com.foxhole.guard.router.routerPinCooldownRemaining
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.foxhole.guard.ui.cli.components.CliButton
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.foxhole.guard.R
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.components.CliBottomSheet
import com.foxhole.guard.router.GuardRouterProfileSummary
import com.foxhole.guard.router.RouterInstallCatalog
import com.foxhole.guard.router.RouterInstallProgress
import com.foxhole.guard.router.RouterInstallProgressStage
import com.foxhole.guard.router.RouterInstallProtocol
import com.foxhole.guard.router.RouterStorageMode
import com.foxhole.guard.router.RouterUpdateFailureReason
import com.foxhole.guard.router.RouterWizardError
import com.foxhole.guard.router.RouterWizardOptions
import com.foxhole.guard.router.RouterWizardPath
import com.foxhole.guard.router.RouterWizardStage
import com.foxhole.guard.router.RouterWizardState
import com.foxhole.guard.router.ROUTER_PIN_DIGITS
import java.util.Locale

internal data class RouterWizardActions(
    val inspect: (String) -> Unit,
    val trust: () -> Unit,
    val installProtocols: (Set<RouterInstallProtocol>) -> Unit,
    val confirmInstallProtocols: () -> Unit,
    val login: suspend (CharArray) -> Unit,
    val newPin: (CharArray) -> Unit,
    val install: () -> Unit,
    val options: (RouterWizardOptions) -> Unit,
    val finish: (CharArray?) -> Unit,
    val failure: () -> Unit,
    val openRouterSetup: (() -> Unit)? = null,
    val installCompactSelection: ((Set<RouterInstallProtocol>, Boolean) -> Unit)? = null,
    val loginKey: (suspend (ByteArray, CharArray) -> Unit)? = null,
    val setupAddress: ((String) -> Unit)? = null,
)

@Composable
internal fun GuardRouterWizardSheet(
    state: RouterWizardState,
    profiles: List<GuardRouterProfileSummary>,
    supportsHomeAutodetection: Boolean,
    supportsRemoteProfile: Boolean,
    installationCatalog: RouterInstallCatalog?,
    actions: RouterWizardActions,
    onClose: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
) {
    val initialPin = remember(state.options.licenseGeneration, state.address) { TextFieldState() }
    val repeatedPin = remember(state.options.licenseGeneration, state.address) { TextFieldState() }
    DisposableEffect(initialPin, repeatedPin) {
        onDispose { initialPin.edit { replace(0, length, "") }; repeatedPin.edit { replace(0, length, "") } }
    }
    val pinCooldownSeconds by produceState(routerPinCooldownRemaining(state.pinCooldownUntilMs), state.pinCooldownUntilMs) {
        value = routerPinCooldownRemaining(state.pinCooldownUntilMs)
        while (value > 0) { delay(1000); value = routerPinCooldownRemaining(state.pinCooldownUntilMs) }
    }
    val initialPinReady = initialPin.text.length == ROUTER_PIN_DIGITS && initialPin.text.all { it in '0'..'9' } &&
        repeatedPin.text.length == ROUTER_PIN_DIGITS && initialPin.text.indices.all { initialPin.text[it] == repeatedPin.text[it] }
    var address by rememberSaveable(state.options.licenseGeneration, state.address) { mutableStateOf(state.address) }
    var protocols by remember(installationCatalog?.sha256, state.installSelection) {
        mutableStateOf(state.installSelection?.protocols ?: installationCatalog?.defaultSelection?.protocols.orEmpty())
    }
    var includeServer by remember(installationCatalog?.sha256, state.installSelection) {
        mutableStateOf(state.installSelection?.includeServer ?: false)
    }
    val discoveredPackageVersion = state.discovery?.takeIf { it.address == state.address &&
                    it.kind == com.foxhole.guard.router.RouterDiscoveryKind.INSTALLED }?.packageVersion
    AnimatedContent(targetState = state.stage, modifier = Modifier.fillMaxSize(), transitionSpec = {
        GuardRouterSheetMotion.pageTransition(animateSize = false)
    }, label = "router-wizard") { stage ->
        val enabled = stage == state.stage && !state.busy
        when (stage) {
            RouterWizardStage.LOGIN -> GuardRouterLoginSheet(
                installed = state.path == RouterWizardPath.PAIR,
                packageVersion = discoveredPackageVersion,
                active = stage == state.stage,
                pinCooldownUntilMs = state.pinCooldownUntilMs,
                pinError = state.error,
                gateway = state.address,
                busy = !enabled,
                onSubmit = { _, credential -> actions.login(credential) },
                onFailure = actions.failure,
                onKeySubmit = actions.loginKey?.let { login -> { _, key, passphrase -> login(key, passphrase) } },
                addressEditable = false,
                onClose = onClose,
                onBack = onBack,
                layout = { working, headerActions, footer, content ->
                    RouterWizardContent(
                        R.string.guard_router_vpn_router, R.drawable.lin_router,
                        state.address, discoveredPackageVersion, working,
                        actions = headerActions, footer = footer, content = content,
                    )
                },
            )
            else -> RouterWizardContent(
                R.string.guard_router_vpn_router,
                R.drawable.lin_router,
                address = if (stage == RouterWizardStage.ADDRESS) address else state.address,
                packageVersion = discoveredPackageVersion,
                busy = state.busy,
                footer = {
                    when (stage) {
                        RouterWizardStage.ADDRESS -> RouterFullButton(R.string.guard_router_vpn_router_check, enabled && address.isNotBlank()) {
                            actions.inspect(address)
                        }
                        RouterWizardStage.IDENTITY -> Unit
                        RouterWizardStage.PIN_SETUP_REQUIRED -> actions.openRouterSetup?.let { open ->
                            RouterFullButton(R.string.guard_router_router_review_open_router_setup, enabled, open)
                        }
                        RouterWizardStage.PROTOCOLS -> RouterFullButton(
                            R.string.guard_router_vpn_continue,
                            enabled && installationCatalog?.accepts(protocols, includeServer) == true &&
                                (installationCatalog?.schema != 2 || actions.installCompactSelection != null),
                        ) {
                            if (installationCatalog?.schema == 2) actions.installCompactSelection?.invoke(protocols, includeServer)
                            else actions.installProtocols(protocols)
                            actions.confirmInstallProtocols()
                        }
                        RouterWizardStage.INSTALL -> RouterFullButton(R.string.guard_router_vpn_router_install,
                            enabled && (state.path != RouterWizardPath.SETUP || state.options.licenseAccepted), actions.install)
                        RouterWizardStage.OPTIONS, RouterWizardStage.NEW_PIN -> RouterFullButton(
                            if (state.autoUpdateUnconfirmed) R.string.guard_router_router_updates_status else R.string.guard_router_vpn_router_done,
                            enabled && state.options.valid && (state.options.countryAuto || knownRouterHomeCountry(state.options.countryCode) != null) &&
                                (!state.initialPinRequired || initialPinReady && pinCooldownSeconds == 0 &&
                                    (!(state.licenseRequired || state.installedInThisWizard) || state.options.licenseAccepted)),
                        ) {
                            val secret = if (state.initialPinRequired) CharArray(initialPin.text.length) { initialPin.text[it] } else null
                            initialPin.edit { replace(0, length, "") }; repeatedPin.edit { replace(0, length, "") }
                            actions.finish(secret)
                        }
                        else -> Unit
                    }
                    GuardRouterWizardNavigationFooter(enabled, onBack, onClose)
                },
            ) {
                when (stage) {
                    RouterWizardStage.ADDRESS -> {
                        Text(stringResource(R.string.guard_router_vpn_router_standard_network), style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(
                            address,
                            onValueChange = { if (it.length <= 15) address = it },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = enabled,
                            singleLine = true,
                            label = { Text(stringResource(R.string.guard_router_vpn_router_address)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        )
                        if (state.path == RouterWizardPath.PAIR) actions.setupAddress?.let { setup ->
                            CliButton(
                                label = stringResource(R.string.guard_router_router_ssh_manual_setup),
                                onClick = { setup(address) },
                                modifier = Modifier.align(Alignment.CenterHorizontally),
                                enabled = enabled && address.isNotBlank(),
                                filled = false,
                            )
                        }
                    }
                    RouterWizardStage.IDENTITY -> Text(stringResource(R.string.guard_router_vpn_router_checking))
                    RouterWizardStage.PIN_SETUP_REQUIRED -> Text(stringResource(R.string.guard_router_router_review_owner_setup_required),
                        style = MaterialTheme.typography.bodyMedium)
                    RouterWizardStage.CHECKING -> if (state.installProgress == null) Text(stringResource(R.string.guard_router_router_ssh_checking_openwrt))
                    RouterWizardStage.PROTOCOLS -> {
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                            GuardRouterTableRow(Modifier.fillMaxWidth()) {
                                Icon(painterResource(R.drawable.lin_power), null, Modifier.size(20.dp))
                                Text(stringResource(R.string.guard_router_vpn_router_install_protocols), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                GuardRouterHelpButton(R.string.guard_router_vpn_router_install_protocols, R.string.guard_router_router_help_protocols)
                            }
                            GuardRouterProtocolDropdown(installationCatalog, protocols, enabled) { protocols = it }
                        }
                        if (installationCatalog?.schema == 2) {
                            RouterCheckRow(R.string.guard_router_vpn_router_install_server, R.string.guard_router_router_help_install_server, includeServer, enabled) {
                                includeServer = it
                            }
                        }
                        if (state.path == RouterWizardPath.SETUP) {
                            RouterAutoUpdateChoice(state.options, enabled) {
                                actions.options(state.options.copy(autoUpdateEnabled = it))
                            }
                        }
                        if (protocols.isNotEmpty() && installationCatalog?.accepts(protocols, includeServer) != true) {
                            Text(stringResource(R.string.guard_router_vpn_router_protocol_combination_unavailable), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    RouterWizardStage.INSPECTED -> {
                        GuardRouterReviewTable(listOf(
                            stringResource(R.string.guard_router_router_review_address) to state.address,
                            "OpenWrt" to state.openWrtVersion,
                            stringResource(R.string.guard_router_router_review_architecture) to state.architecture,
                        ), icons = listOf(R.drawable.lin_router, R.drawable.lin_settings, R.drawable.lin_settings))
                        Text(stringResource(R.string.guard_router_setup_readonly_complete), style = MaterialTheme.typography.bodyMedium)
                    }
                    RouterWizardStage.INSTALL -> {
                        if (!state.busy || state.installProgress == null) {
                            Text(stringResource(R.string.guard_router_router_review_install_summary), style = MaterialTheme.typography.titleMedium)
                            GuardRouterReviewTable(listOf(
                                stringResource(R.string.guard_router_router_review_address) to state.address,
                                "OpenWrt" to state.openWrtVersion,
                                stringResource(R.string.guard_router_router_review_architecture) to state.architecture,
                                stringResource(R.string.guard_router_router_review_protocols) to RouterInstallProtocol.entries
                                    .filter { it in state.installSelection?.protocols.orEmpty() }.joinToString(" · ") { it.label },
                                *(if (installationCatalog?.schema == 2) arrayOf(stringResource(R.string.guard_router_vpn_router_install_server) to
                                    stringResource(if (state.installSelection?.includeServer == true) R.string.guard_router_vpn_router_install_server_yes else R.string.guard_router_vpn_router_install_server_no)) else emptyArray()),
                                stringResource(R.string.guard_router_router_review_storage) to stringResource(
                                    if (state.storageMode == RouterStorageMode.RAM) R.string.guard_router_vpn_router_ram else R.string.guard_router_vpn_router_flash),
                                *(if (state.path == RouterWizardPath.SETUP) arrayOf(stringResource(R.string.guard_router_vpn_router_setup_auto_updates) to
                                    stringResource(if (state.options.autoUpdateEnabled) R.string.guard_router_vpn_router_setup_auto_on else R.string.guard_router_vpn_router_setup_auto_off)) else emptyArray()),
                            ), icons = listOf(
                                R.drawable.lin_router, R.drawable.lin_settings, R.drawable.lin_settings, R.drawable.lin_power,
                                *(if (installationCatalog?.schema == 2) arrayOf(R.drawable.lin_profiles) else emptyArray()),
                                R.drawable.lin_update,
                                *(if (state.path == RouterWizardPath.SETUP) arrayOf(R.drawable.lin_update) else emptyArray()),
                            ))
                            if (state.path == RouterWizardPath.SETUP) RouterSetupLicenseChoice(state.options.licenseAccepted, enabled, state.options.licenseGeneration) {
                                actions.options(state.options.copy(licenseAccepted = it))
                            }
                            if (state.storageMode == RouterStorageMode.RAM) Text(stringResource(R.string.guard_router_vpn_router_ram_note),
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    RouterWizardStage.OPTIONS, RouterWizardStage.NEW_PIN -> {
                        if (state.installedInThisWizard && state.options.localRules != null)
                            RouterLocalRoutingChoice(state.options.localRules, enabled) { actions.options(state.options.copy(localRules = it)) }
                        RouterOptionsContent(
                            state.options, profiles, enabled && !state.autoUpdateUnconfirmed, supportsHomeAutodetection, supportsRemoteProfile,
                            state.installedInThisWizard, state.autoUpdateUnconfirmed, state.autoUpdateFailure, actions.options,
                        )
                        if (state.initialPinRequired && state.installedInThisWizard) {
                            RouterInitialPinFields(initialPin, repeatedPin, enabled && pinCooldownSeconds == 0)
                            if (pinCooldownSeconds > 0) Text(stringResource(R.string.guard_router_router_pin_cooldown, pinCooldownSeconds),
                                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            if (state.licenseRequired && !state.options.licenseAccepted)
                                RouterSetupLicenseChoice(state.options.licenseAccepted, enabled, state.options.licenseGeneration) {
                                    actions.options(state.options.copy(licenseAccepted = it))
                                }
                        }
                    }
                    RouterWizardStage.FINISHING -> Text(stringResource(R.string.guard_router_vpn_router_finishing))
                    RouterWizardStage.COMPLETE -> {
                        Text(stringResource(R.string.guard_router_vpn_router_connected), style = MaterialTheme.typography.titleMedium)
                        if (state.remoteProfileCreated) Text(stringResource(
                            if (state.remoteReady) R.string.guard_router_vpn_router_remote_ready else R.string.guard_router_vpn_router_remote_check,
                        ))
                    }
                    else -> Unit
                }
                state.installProgress?.let { RouterInstallationProgress(it) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RouterInitialPinFields(pin: TextFieldState, repeat: TextFieldState, enabled: Boolean) {
    Text(stringResource(R.string.guard_router_vpn_router_change_pin_note), style = MaterialTheme.typography.bodySmall)
    for ((field, label) in listOf(pin to R.string.guard_router_router_setup_pin, repeat to R.string.guard_router_router_setup_pin_repeat)) {
        OutlinedSecureTextField(
            state = field, modifier = Modifier.fillMaxWidth(), enabled = enabled,
            inputTransformation = InputTransformation.maxLength(ROUTER_PIN_DIGITS),
            textObfuscationMode = TextObfuscationMode.Hidden,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            label = { Text(stringResource(label)) },
        )
    }
}

@Composable
private fun RouterOptionsContent(
    options: RouterWizardOptions,
    profiles: List<GuardRouterProfileSummary>,
    enabled: Boolean,
    supportsHome: Boolean,
    supportsRemote: Boolean,
    setupCompleted: Boolean,
    autoUpdateUnconfirmed: Boolean,
    autoUpdateFailure: RouterUpdateFailureReason?,
    onChange: (RouterWizardOptions) -> Unit,
) {
    if (setupCompleted) {
        RouterAutoUpdateChoice(options, enabled && !autoUpdateUnconfirmed) { onChange(options.copy(autoUpdateEnabled = it)) }
        autoUpdateFailure?.let { Text(stringResource(wizardAutoUpdateFailureLabel(it)), color = MaterialTheme.colorScheme.error) }
        if (autoUpdateUnconfirmed) Text(stringResource(R.string.guard_router_vpn_router_setup_auto_unknown), style = MaterialTheme.typography.bodySmall)
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        GuardRouterTableRow(Modifier.fillMaxWidth()) {
            Icon(painterResource(R.drawable.lin_profiles), null, Modifier.size(20.dp))
            Text(stringResource(R.string.guard_router_vpn_router_profile_title), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            GuardRouterHelpButton(R.string.guard_router_vpn_router_profile_title, R.string.guard_router_router_help_profile)
        }
        GuardRouterProfileDropdown(profiles, options.profileId, enabled) { onChange(options.copy(profileId = it)) }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        GuardRouterTableRow(Modifier.fillMaxWidth()) {
            Icon(painterResource(R.drawable.lin_globe), null, Modifier.size(20.dp))
            Text(stringResource(R.string.guard_router_router_review_local_traffic), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            GuardRouterCountryFlag(options.countryCode, enabled,
                onSelect = { onChange(options.copy(countryCode = it, countryAuto = false)) },
                autoSelected = options.countryAuto,
                onAuto = { onChange(options.copy(countryAuto = true)) })
            GuardRouterHelpButton(R.string.guard_router_router_review_local_traffic, R.string.guard_router_router_help_country_source)
            GuardRouterSwitch(options.countrySitesDirect, onCheckedChange = { onChange(options.copy(countrySitesDirect = it)) }, enabled = enabled)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (supportsHome) RouterCheckRow(R.string.guard_router_vpn_router_auto_home, R.string.guard_router_router_help_home, options.homeAutodetection, enabled) {
            onChange(options.copy(homeAutodetection = it))
        }
        if (supportsRemote) {
            RouterCheckRow(R.string.guard_router_router_review_direct_ip, R.string.guard_router_router_help_public_ip, options.publicIp, enabled) {
                onChange(options.copy(publicIp = it, createRemoteProfile = it && options.createRemoteProfile))
            }
            if (options.publicIp) {
                RouterHelpLabel(R.string.guard_router_router_review_remote_settings, R.string.guard_router_router_review_remote_help)
                RouterCheckRow(R.string.guard_router_router_review_phone_profile, R.string.guard_router_router_review_remote_help,
                    options.createRemoteProfile, enabled, showHelp = false) { onChange(options.copy(createRemoteProfile = it)) }
            }
        }
    }
}

@Composable
private fun RouterSetupLicenseChoice(accepted: Boolean, enabled: Boolean, licenseGeneration: Long,
    hideAcceptedChoice: Boolean = false, onChange: (Boolean) -> Unit) {
    var expanded by remember(licenseGeneration) { mutableStateOf(false) }
    if (!hideAcceptedChoice || !accepted) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        CliButton(
            label = stringResource(R.string.guard_router_setup_license_title),
            onClick = { expanded = true },
            modifier = Modifier,
            enabled = enabled,
            filled = false,
        )
    }
    if (expanded) {
        CliBottomSheet(onDismiss = { expanded = false },
            title = stringResource(R.string.guard_router_setup_license_title), icon = R.drawable.lin_info,
            footerContent = { RouterLicenseAcceptedRow(accepted, enabled, onChange) }) {
            Text(stringResource(R.string.guard_router_setup_license_body), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun RouterLicenseAcceptedRow(accepted: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    GuardRouterTableRow(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.toggleable(
        value = accepted, enabled = enabled, role = Role.Checkbox, onValueChange = onChange)) {
        Icon(painterResource(R.drawable.lin_info), null, Modifier.size(20.dp))
        Text(stringResource(R.string.guard_router_setup_license_accept), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Checkbox(checked = accepted, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun RouterLocalRoutingChoice(checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    GuardRouterTableRow(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.toggleable(
        value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange)) {
        Icon(painterResource(R.drawable.lin_power), null, Modifier.size(20.dp))
        Text(stringResource(R.string.guard_router_router_setup_all_lan_vpn), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        GuardRouterHelpButton(R.string.guard_router_router_setup_all_lan_vpn, R.string.guard_router_router_setup_all_lan_vpn_help)
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun RouterAutoUpdateChoice(options: RouterWizardOptions, enabled: Boolean, onChange: (Boolean) -> Unit) {
    GuardRouterTableRow(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.toggleable(
        value = options.autoUpdateEnabled, enabled = enabled, role = Role.Checkbox, onValueChange = onChange)) {
        Icon(painterResource(R.drawable.lin_update), null, Modifier.size(20.dp))
        Text(stringResource(R.string.guard_router_vpn_router_setup_auto_updates), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Checkbox(checked = options.autoUpdateEnabled, onCheckedChange = null, enabled = enabled)
    }
    Text(stringResource(R.string.guard_router_router_updates_scope), style = MaterialTheme.typography.bodySmall)
    Text(stringResource(R.string.guard_router_router_updates_auto_help), style = MaterialTheme.typography.bodySmall)
}

internal fun knownRouterHomeCountry(code: String?): String? = code?.uppercase(Locale.ROOT)?.takeIf { it in Locale.getISOCountries() }

@Composable
private fun RouterHelpLabel(title: Int, help: Int) {
    GuardRouterTableRow(Modifier.fillMaxWidth()) {
        Icon(painterResource(R.drawable.lin_globe), null, Modifier.size(20.dp))
        Text(stringResource(title), Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleMedium)
        GuardRouterHelpButton(title, help)
    }
}

@Composable
private fun RouterCheckRow(label: Int, help: Int, checked: Boolean, enabled: Boolean,
    showHelp: Boolean = true, onChange: (Boolean) -> Unit) {
    GuardRouterTableRow(Modifier.fillMaxWidth()) {
        val icon = when (label) {
            R.string.guard_router_vpn_router_auto_home -> R.drawable.lin_router
            R.string.guard_router_router_review_direct_ip -> R.drawable.lin_globe
            R.string.guard_router_router_review_phone_profile -> R.drawable.lin_profiles
            else -> R.drawable.lin_power
        }
        Icon(painterResource(icon), null, Modifier.size(20.dp))
        Text(stringResource(label), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        if (showHelp) GuardRouterHelpButton(label, help)
        GuardRouterSwitch(checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun RouterFullButton(label: Int, enabled: Boolean, onClick: () -> Unit) {
    CliButton(
        label = stringResource(label),
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        enabled = enabled,
        color = routerConnectColor(),
        filled = false,
    )
}

internal fun routerWizardErrorLabel(error: RouterWizardError): Int = when (error) {
    RouterWizardError.WIFI -> R.string.guard_router_vpn_router_error_wifi
    RouterWizardError.VPN_ACTIVE -> R.string.guard_router_vpn_router_error_active_vpn
    RouterWizardError.HOME_VPN -> R.string.guard_router_vpn_router_error_home_vpn
    RouterWizardError.LOCAL_NETWORK_PERMISSION -> R.string.guard_router_vpn_router_error_local_network_permission
    RouterWizardError.SSH_UNAVAILABLE -> R.string.guard_router_vpn_router_error_ssh
    RouterWizardError.ADDRESS -> R.string.guard_router_vpn_router_error_address
    RouterWizardError.IDENTITY -> R.string.guard_router_vpn_router_error_identity
    RouterWizardError.PASSWORD -> R.string.guard_router_vpn_router_error_password
    RouterWizardError.PIN -> R.string.guard_router_vpn_router_error_pin
    RouterWizardError.LOCKED -> R.string.guard_router_vpn_router_error_locked
    RouterWizardError.UNSUPPORTED -> R.string.guard_router_vpn_router_error_unsupported
    RouterWizardError.STORAGE -> R.string.guard_router_vpn_router_error_storage
    RouterWizardError.INSTALL -> R.string.guard_router_vpn_router_error_install
    RouterWizardError.NETWORK -> R.string.guard_router_vpn_router_error_network
    RouterWizardError.SAVE -> R.string.guard_router_vpn_router_error_save
    RouterWizardError.PROFILE -> R.string.guard_router_vpn_router_error_profile
    RouterWizardError.PROFILE_EXPORT -> R.string.guard_router_vpn_error_export_profile
    RouterWizardError.UNAVAILABLE -> R.string.guard_router_vpn_router_error_unavailable
    RouterWizardError.LICENSE -> R.string.guard_router_setup_license_required
    RouterWizardError.LIMIT -> R.string.guard_router_vpn_router_error_limit
}

@Composable
private fun RouterInstallationProgress(progress: RouterInstallProgress) {
    val percent = progress.percent
    if (percent == null) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    else key(progress.stage, progress.totalBytes) {
        val displayed by animateFloatAsState(percent / 100f, GuardRouterSheetMotion.fastEffectsSpec(), label = "router-install-progress")
        LinearProgressIndicator(progress = { displayed }, modifier = Modifier.fillMaxWidth())
        Text(stringResource(R.string.guard_router_router_review_progress_percent, percent), style = MaterialTheme.typography.labelLarge)
    }
    Text(stringResource(when (progress.stage) {
        RouterInstallProgressStage.DOWNLOAD -> if (progress.viaDevice) R.string.guard_router_router_updates_device_download else R.string.guard_router_router_review_progress_download
        RouterInstallProgressStage.UPLOAD -> R.string.guard_router_router_review_progress_upload
        RouterInstallProgressStage.INSTALL -> R.string.guard_router_router_review_progress_install
        RouterInstallProgressStage.CHECK -> R.string.guard_router_router_review_progress_check
    }), style = MaterialTheme.typography.bodyMedium)
}

private fun wizardAutoUpdateFailureLabel(reason: RouterUpdateFailureReason): Int = when (reason) {
    RouterUpdateFailureReason.UNSUPPORTED_TARGET -> R.string.guard_router_router_updates_unsupported
    RouterUpdateFailureReason.NOT_CHECKED -> R.string.guard_router_router_updates_not_checked
    RouterUpdateFailureReason.RELEASE_UNAVAILABLE -> R.string.guard_router_router_updates_release_unavailable
    RouterUpdateFailureReason.AUTHENTICATION_FAILED -> R.string.guard_router_router_updates_auth_failed
    RouterUpdateFailureReason.STALE_PLAN, RouterUpdateFailureReason.STALE_SESSION -> R.string.guard_router_router_updates_stale
    RouterUpdateFailureReason.BUSY -> R.string.guard_router_router_engines_busy
    RouterUpdateFailureReason.NO_SPACE -> R.string.guard_router_router_updates_no_space
    RouterUpdateFailureReason.DOWNLOAD_FAILED -> R.string.guard_router_router_updates_download_failed
    RouterUpdateFailureReason.PACKAGE_VERIFICATION_FAILED, RouterUpdateFailureReason.DOWNGRADE_REJECTED,
    RouterUpdateFailureReason.VERSION_IDENTITY_CONFLICT -> R.string.guard_router_router_updates_verification_failed
    RouterUpdateFailureReason.ROLLBACK_UNAVAILABLE -> R.string.guard_router_router_updates_rollback_unavailable
    RouterUpdateFailureReason.RAM_STAGE_UNAVAILABLE -> R.string.guard_router_router_updates_ram_unavailable
    RouterUpdateFailureReason.ROUTER_UNAVAILABLE -> R.string.guard_router_router_updates_unreachable
    RouterUpdateFailureReason.RECOVERY_REQUIRED -> R.string.guard_router_router_engines_recovery
    RouterUpdateFailureReason.LEGACY_CONNECTION_ACTIVE -> R.string.guard_router_router_updates_disconnect_before_update
    RouterUpdateFailureReason.INVALID_REQUEST -> R.string.guard_router_router_updates_invalid_response
}


@Composable
private fun RouterWizardContent(
    title: Int, icon: Int, address: String, packageVersion: String?, busy: Boolean,
    actions: @Composable RowScope.() -> Unit = {},
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize().imePadding().padding(horizontal = CliSpacing.md, vertical = CliSpacing.xs)) {
        val compact = maxHeight < 420.dp
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End) {
                if (busy) {
                    val working = stringResource(R.string.guard_router_vpn_working)
                    CircularProgressIndicator(Modifier.size(20.dp).semantics { contentDescription = working }, strokeWidth = 2.dp)
                }
                GuardRouterAddonSettingsButton()
                actions()
            }
            Column(Modifier.weight(1f).fillMaxWidth().then(LocalGuardRouterSheetBodyGestures.current)
                .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 14.dp)) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Image(painterResource(R.drawable.ic_launcher_openwrt), null,
                        Modifier.size(if (compact) 48.dp else 68.dp), contentScale = ContentScale.Fit)
                    Text(stringResource(R.string.guard_router_router_login_brand), Modifier.fillMaxWidth(),
                        style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Center, maxLines = 2)
                    Text(if (packageVersion == null) stringResource(R.string.guard_router_router_login_version_unknown)
                        else stringResource(R.string.guard_router_router_login_version, packageVersion), Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center)
                    Text(address, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp).semantics(mergeDescendants = true) { heading() },
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                        Icon(painterResource(icon), null, Modifier.size(20.dp))
                        Text(stringResource(title), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                    }
                }
                content()
            }
            if (footer != null) Column(Modifier.fillMaxWidth().padding(top = 8.dp).then(LocalGuardRouterSheetBodyGestures.current),
                verticalArrangement = Arrangement.spacedBy(8.dp), content = footer)
        }
    }
}

@Composable
@Suppress("UNUSED_PARAMETER")
internal fun GuardRouterWizardNavigationFooter(backEnabled: Boolean, onBack: (() -> Unit)?, onClose: (() -> Unit)?) {
    if (onClose != null) CliButton(
        label = stringResource(R.string.guard_router_vpn_cancel),
        onClick = onClose,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        enabled = true,
        color = com.foxhole.guard.ui.cli.LocalCliColors.current.err,
        filled = false,
    )
}
