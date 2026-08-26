package com.foxhole.guard.ui.cli.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foxhole.core.model.InstalledAppOption
import com.foxhole.guard.R
import com.foxhole.guard.ui.HomeViewModel
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.LocalCliBottomChromeClearance
import com.foxhole.guard.ui.cli.components.CliCheckGlyph
import com.foxhole.guard.ui.cli.components.CliInputRow
import com.foxhole.guard.ui.cli.components.CliLoadingRow
import com.foxhole.guard.ui.cli.components.CliPanel
import com.foxhole.guard.ui.cli.components.CliScreenHeader
import com.foxhole.guard.ui.loadInstalledApps
import com.foxhole.guard.ui.onDnsBypassPackagesChanged

@Composable
internal fun CliDnsBypassAppsScreen(
    viewModel: HomeViewModel,
    modifier: Modifier = Modifier,
) {
    val settingsState by viewModel.settingsRouteState.collectAsStateWithLifecycle()
    val appState by viewModel.appPickerRouteState.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.loadInstalledApps() }
    var filter by rememberSaveable { mutableStateOf("") }
    var showSystem by rememberSaveable { mutableStateOf(false) }

    val bypass = settingsState.settings.dns.appBypassPackages.toSet()
    val visible = remember(appState.installedApps, filter, showSystem) {
        appState.installedApps
            .filter { showSystem || !it.isSystemApp }
            .filter { app ->
                filter.isBlank() ||
                    app.label.contains(filter, ignoreCase = true) ||
                    app.packageName.contains(filter, ignoreCase = true)
            }
            .sortedWith(compareBy({ it.isSystemApp }, { it.label.lowercase() }))
    }

    Column(modifier = modifier.fillMaxSize().padding(horizontal = CliSpacing.md)) {
        CliScreenHeader(
            label = stringResource(R.string.cli_cfg_dns_app_bypass),
            icon = R.drawable.lin_dns,
        )
        CliPanel(modifier = Modifier.fillMaxWidth()) {
            CliInputRow(
                prompt = "filter",
                value = filter,
                onValueChange = { filter = it },
                trailingChipLabel = stringResource(
                    if (showSystem) R.string.cli_route_pick_hide_system else R.string.cli_route_pick_show_system,
                ),
                onTrailingChip = { showSystem = !showSystem },
            )
        }
        Spacer(modifier = Modifier.height(CliSpacing.sm))
        if (appState.installedAppsLoading && !appState.installedAppsLoaded) {
            CliLoadingRow(text = stringResource(R.string.cli_common_loading))
            return
        }
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(bottom = LocalCliBottomChromeClearance.current),
        ) {
            items(visible, key = InstalledAppOption::packageName) { app ->
                val enabled = app.packageName in bypass
                CliInstalledAppRow(
                    app = app,
                    trailing = { CliCheckGlyph(checked = enabled) },
                    onTap = {
                        val next =
                            if (enabled) bypass - app.packageName else bypass + app.packageName
                        viewModel.onDnsBypassPackagesChanged(next.toList())
                    },
                )
            }
        }
    }
}
