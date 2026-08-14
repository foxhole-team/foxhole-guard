package com.foxhole.guard.ui.cli.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foxhole.core.model.InstalledAppOption
import com.foxhole.guard.R
import com.foxhole.guard.ui.HomeViewModel
import com.foxhole.guard.ui.RoutingRouteUiState
import com.foxhole.guard.ui.SettingsRouteUiState
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.cliSlide
import com.foxhole.guard.ui.cli.components.CliBackRow
import com.foxhole.guard.ui.cli.components.CliContextHelpButton
import com.foxhole.guard.ui.cli.components.CliRoutingChangeConfirmSheet
import com.foxhole.guard.ui.cli.components.CliScreenHeader
import com.foxhole.guard.ui.cli.components.cliPressable
import com.foxhole.guard.ui.confirmPendingRoutingScenario
import com.foxhole.guard.ui.dismissPendingRoutingScenario
import com.foxhole.guard.ui.loadInstalledApps

/**
 * The APPS tab: one selected-app set shared by VPN/Split/Tor, a real always-block lane,
 * LAN proxy surfaces inside the same TUN, and provisional local site rules.
 *
 * Sections split into siblings to keep files small — see [CliVpnModeSection]
 * (CliRoutingModeSection.kt), [CliAssignedAppsSection] (CliRoutingAppLanesSection.kt) and
 * [CliSiteRulesSection] (CliRoutingSiteRulesSection.kt).
 */
@Composable
internal fun CliRoutingScreen(
    viewModel: HomeViewModel,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
) {
    val state by viewModel.settingsRouteState.collectAsStateWithLifecycle()
    val appState by viewModel.appPickerRouteState.collectAsStateWithLifecycle()
    val pendingRoutingScenario by viewModel.pendingRoutingScenarioConfirmation.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.loadInstalledApps() }
    var showPicker by rememberSaveable { mutableStateOf(false) }

    // With atomic scenario application off, every Home mode or VPN/Tor scenario change is parked
    // before this shared bottom sheet. Per-app and per-domain rules never reach it: those are
    // always atomic.
    pendingRoutingScenario?.let { change ->
        CliRoutingChangeConfirmSheet(
            change = change,
            onConfirm = viewModel::confirmPendingRoutingScenario,
            onDismiss = viewModel::dismissPendingRoutingScenario,
        )
    }

    // An open picker is nested state: system back closes it rather than the tab. Entry and exit use
    // the shared push/pop slide.
    BackHandler(enabled = showPicker) { showPicker = false }
    AnimatedContent(
        targetState = showPicker,
        transitionSpec = { cliSlide(forward = targetState) },
        modifier = modifier,
        label = "appsPicker",
    ) { picker ->
        if (picker) {
            CliAppPickerScreen(
                viewModel = viewModel,
                state = appState,
                onBack = { showPicker = false },
                modifier = Modifier,
            )
        } else {
            CliRoutingRootColumn(
                viewModel = viewModel,
                state = state,
                appState = appState,
                onBack = onBack,
                onAddApps = { showPicker = true },
            )
        }
    }
}

@Composable
private fun CliRoutingRootColumn(
    viewModel: HomeViewModel,
    state: SettingsRouteUiState,
    appState: RoutingRouteUiState,
    onBack: (() -> Unit)?,
    onAddApps: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = CliSpacing.md),
    ) {
        // As the APPS tab (onBack == null) the dock is the way out - the prompt header
        // names the screen instead of a back row.
        if (onBack != null) {
            CliBackRow(label = stringResource(R.string.cli_route_back), onBack = onBack)
        } else {
            CliScreenHeader(
                label = stringResource(R.string.cli_dock_apps),
                icon = R.drawable.pix_apps,
                trailing = { CliContextHelpButton(bodyRes = R.string.cli_help_routing_body) },
            )
        }
        CliVpnModeSection(viewModel = viewModel, settings = state.settings)
        Spacer(modifier = Modifier.height(CliSpacing.sm))
        CliAssignedAppsSection(
            viewModel = viewModel,
            state = state,
            appState = appState,
            onAddApps = onAddApps,
        )
        Spacer(modifier = Modifier.height(CliSpacing.sm))
        CliSiteRulesSection(viewModel = viewModel, activePreset = appState.activePreset)
        Spacer(modifier = Modifier.height(CliSpacing.sm))
    }
}

@Composable
internal fun CliInstalledAppRow(
    app: InstalledAppOption,
    trailing: @Composable () -> Unit,
    onTap: () -> Unit,
) {
    val colors = LocalCliColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .cliPressable(onClick = onTap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val icon = rememberCliAppIcon(
            packageName = app.packageName,
            versionCode = app.versionCode,
            lastUpdateTime = app.lastUpdateTime,
            bitmapSize = 18.dp,
        )
        // An empty placeholder box keeps the name aligned while the icon loads.
        if (icon != null) {
            Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(18.dp))
        } else {
            Box(modifier = Modifier.size(18.dp))
        }
        Spacer(modifier = Modifier.width(CliSpacing.sm))
        Text(
            text = app.label.ifEmpty { app.packageName },
            style = CliType.body,
            color = colors.dim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        trailing()
    }
}
