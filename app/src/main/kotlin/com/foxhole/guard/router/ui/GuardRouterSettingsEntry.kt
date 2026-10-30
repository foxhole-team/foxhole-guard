package com.foxhole.guard.router.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foxhole.guard.R
import com.foxhole.guard.router.ROUTER_LOCAL_NETWORK_PERMISSION
import com.foxhole.guard.router.RouterControlState
import com.foxhole.guard.router.routerLocalNetworkAccessGranted
import com.foxhole.guard.ui.cli.settings.CliModuleBlock

@Composable
internal fun GuardRouterSettingsEntry(onOpen: () -> Unit) {
    val context = LocalContext.current
    val model = rememberGuardRouterViewModel("guard-router-settings")
    val state by model.control.state.collectAsStateWithLifecycle()
    val initialising by model.initialising.collectAsStateWithLifecycle()
    var owner by remember(model) { mutableStateOf<Long?>(null) }
    var permissionDenied by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && routerLocalNetworkAccessGranted(context)) owner?.let { model.setSettingsEnabled(it, true) }
        else permissionDenied = true
    }
    DisposableEffect(model) {
        val generation = model.openSettings()
        owner = generation
        onDispose { model.closeSettings(generation) }
    }
    GuardRouterSettingsBlock(
        state = state,
        initialising = initialising || owner == null,
        permissionDenied = permissionDenied,
        onToggle = { enabled ->
            permissionDenied = false
            if (!enabled || routerLocalNetworkAccessGranted(context)) owner?.let { model.setSettingsEnabled(it, enabled) }
            else try { permission.launch(ROUTER_LOCAL_NETWORK_PERMISSION) }
            catch (_: IllegalStateException) { permissionDenied = true }
        },
        onOpen = { owner?.let(model::closeSettings); onOpen() },
    )
}

@Composable
internal fun GuardRouterSettingsBlock(
    state: RouterControlState,
    onToggle: (Boolean) -> Unit,
    onOpen: () -> Unit,
    initialising: Boolean = false,
    permissionDenied: Boolean = false,
) {
    val note = when {
        initialising -> null
        permissionDenied -> stringResource(R.string.guard_router_vpn_router_error_local_network_permission)
        state.error != null -> stringResource(routerWizardErrorLabel(state.error))
        else -> null
    }
    CliModuleBlock(
        label = stringResource(R.string.guard_router_settings_title),
        icon = R.drawable.lin_router,
        infoText = stringResource(R.string.guard_router_settings_info),
        infoAlwaysActive = true,
        checked = !initialising && state.managementEnabled,
        onToggle = onToggle,
        toggleEnabled = !initialising && state.canToggleManagement,
        note = note,
        onOpenSettings = onOpen,
        settingsLabel = stringResource(R.string.guard_router_settings_subtitle),
        settingsModifier = Modifier.testTag("guard_settings_openwrt_entry"),
    )
}
