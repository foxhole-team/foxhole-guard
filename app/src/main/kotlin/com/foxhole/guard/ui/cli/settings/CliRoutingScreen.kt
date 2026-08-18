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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.foxhole.guard.ui.cli.CliTopContentGap
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.cliSlide
import com.foxhole.guard.ui.cli.components.CliBackRow
import com.foxhole.guard.ui.cli.components.CliChromeTailSpacer
import com.foxhole.guard.ui.cli.components.CliGlassHeaderScreen
import com.foxhole.guard.ui.cli.components.CliRoutingChangeConfirmSheet
import com.foxhole.guard.ui.cli.components.CliScreenHeader
import com.foxhole.guard.ui.cli.components.CliTopBarHelpButton
import com.foxhole.guard.ui.cli.components.cliPressable
import com.foxhole.guard.ui.cli.components.rememberCliRejectFeedback
import com.foxhole.guard.ui.confirmPendingRoutingScenario
import com.foxhole.guard.ui.dismissPendingRoutingScenario
import com.foxhole.guard.ui.loadInstalledApps
import kotlinx.coroutines.launch

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

    pendingRoutingScenario?.let { change ->
        CliRoutingChangeConfirmSheet(
            change = change,
            onConfirm = viewModel::confirmPendingRoutingScenario,
            onDismiss = viewModel::dismissPendingRoutingScenario,
        )
    }

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
                modifier = Modifier.statusBarsPadding().padding(top = CliTopContentGap),
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
    CliGlassHeaderScreen(
        header = {
            if (onBack != null) {
                CliBackRow(label = stringResource(R.string.cli_route_back), onBack = onBack)
            } else {
                CliScreenHeader(
                    label = stringResource(R.string.cli_dock_apps),
                    icon = R.drawable.pix_apps,
                    trailing = { CliTopBarHelpButton(bodyRes = R.string.cli_help_routing_body) },
                )
            }
        },
    ) { topInset ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = CliSpacing.md),
        ) {
            Spacer(modifier = Modifier.height(topInset))
            CliRoutingRootSections(
                viewModel = viewModel,
                state = state,
                appState = appState,
                onAddApps = onAddApps,
            )
            CliChromeTailSpacer()
        }
    }
}

@Composable
private fun CliRoutingRootSections(
    viewModel: HomeViewModel,
    state: SettingsRouteUiState,
    appState: RoutingRouteUiState,
    onAddApps: () -> Unit,
) {
    val missingAppsFeedback = rememberCliRejectFeedback()
    val scope = rememberCoroutineScope()
    var missingAppsTarget by remember { mutableStateOf<CliMissingAppsTarget?>(null) }
    var rejectionAttempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(state.settings.expert.appAssignments) {
        rejectionAttempt += 1
        missingAppsTarget = null
    }
    val onMissingAppsRejected: (CliMissingAppsTarget) -> Unit = { target ->
        rejectionAttempt += 1
        val attempt = rejectionAttempt
        missingAppsTarget = target
        scope.launch {
            missingAppsFeedback.play()
            if (rejectionAttempt == attempt) missingAppsTarget = null
        }
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        CliVpnModeSection(
            viewModel = viewModel,
            settings = state.settings,
            onMissingAppsRejected = onMissingAppsRejected,
        )
        Spacer(modifier = Modifier.height(CliSpacing.sm))
        CliAssignedAppsSection(
            viewModel = viewModel,
            state = state,
            appState = appState,
            onAddApps = onAddApps,
            missingAppsTarget = missingAppsTarget,
            missingAppsFeedback = missingAppsFeedback,
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
