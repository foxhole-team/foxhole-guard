package com.foxhole.guard.ui.cli.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.foxhole.core.model.AppTunnelLane
import com.foxhole.core.model.InstalledAppOption
import com.foxhole.guard.R
import com.foxhole.guard.ui.HomeViewModel
import com.foxhole.guard.ui.RoutingRouteUiState
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.components.CliBackRow
import com.foxhole.guard.ui.cli.components.CliButton
import com.foxhole.guard.ui.cli.components.CliInputRow
import com.foxhole.guard.ui.cli.components.CliLoadingRow
import com.foxhole.guard.ui.cli.components.CliPanel
import com.foxhole.guard.ui.onAppsAddedToLane

/**
 * Full installed-app list to add apps to the routing lanes. Default view is user-installed apps
 * only (a [showSystem] chip reveals system apps); a filter narrows by name/package. Tapping a row
 * TOGGLES a checkbox-style selection (`[x]`/`[ ]`) without leaving the screen; the `add N` control
 * then pins every picked app to the default VPN lane in one batch and returns — the per-app
 * dropdown on the Apps screen moves them on to Tor / Block / Exclude afterwards. Apps already in a
 * lane show a locked `[x]` and cannot be re-picked.
 */
@Composable
internal fun CliAppPickerScreen(
    viewModel: HomeViewModel,
    state: RoutingRouteUiState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCliColors.current
    var filter by rememberSaveable { mutableStateOf("") }
    var showSystem by rememberSaveable { mutableStateOf(false) }
    // Survives rotation and the show-system/filter toggles: the picked set is keyed by package.
    val selected = rememberSaveable(
        saver = listSaver(
            save = { it.toList() },
            restore = { it.toMutableStateList() },
        ),
    ) { mutableStateListOf<String>() }

    val visible = remember(state.installedApps, filter, showSystem) {
        state.installedApps
            .filter { showSystem || !it.isSystemApp }
            .filter { app ->
                filter.isBlank() ||
                    app.label.contains(filter, ignoreCase = true) ||
                    app.packageName.contains(filter, ignoreCase = true)
            }
            .sortedWith(compareBy({ it.isSystemApp }, { it.label.lowercase() }))
    }

    Column(modifier = modifier.fillMaxSize().padding(horizontal = CliSpacing.md)) {
        CliBackRow(
            label = stringResource(R.string.cli_route_pick_apps_title),
            onBack = onBack,
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
        if (state.installedAppsLoading && !state.installedAppsLoaded) {
            CliLoadingRow(text = stringResource(R.string.cli_common_loading))
            return
        }
        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
            items(visible, key = InstalledAppOption::packageName) { app ->
                val alreadyIn = app.packageName in state.settings.expert.appAssignments
                val picked = app.packageName in selected
                CliInstalledAppRow(
                    app = app,
                    trailing = {
                        Text(
                            // Locked `[x]` (ok tone) = already in a lane; `[x]` (accent) = picked
                            // now; `[ ]` (dim) = addable.
                            text = if (alreadyIn || picked) "[x]" else "[ ]",
                            style = CliType.body,
                            color = when {
                                alreadyIn -> colors.ok
                                picked -> colors.accent
                                else -> colors.dim
                            },
                        )
                    },
                    onTap = {
                        if (!alreadyIn) {
                            if (picked) selected.remove(app.packageName) else selected.add(app.packageName)
                        }
                    },
                )
            }
        }
        // Batch confirm, pinned below the list: assigns every picked app to the VPN lane at once.
        Spacer(modifier = Modifier.height(CliSpacing.sm))
        CliButton(
            label = stringResource(R.string.cli_route_pick_add_count, selected.size),
            filled = true,
            enabled = selected.isNotEmpty(),
            onClick = {
                viewModel.onAppsAddedToLane(selected.toList(), AppTunnelLane.VPN)
                onBack()
            },
        )
        Spacer(modifier = Modifier.height(CliSpacing.sm))
    }
}
