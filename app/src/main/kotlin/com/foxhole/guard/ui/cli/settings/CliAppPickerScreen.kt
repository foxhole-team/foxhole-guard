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
import androidx.compose.ui.graphics.Color
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
import com.foxhole.guard.ui.onAppLaneEditsApplied

/**
 * Full installed-app list to add apps to the routing lanes. Default view is user-installed apps
 * only (a [showSystem] chip reveals system apps); a filter narrows by name/package. Tapping a row
 * TOGGLES a checkbox-style selection (`[x]`/`[ ]`) without leaving the screen; the bottom control
 * then applies the whole batch and returns — the per-app dropdown on the Apps screen moves them on
 * to Tor / Block / Exclude afterwards.
 *
 * A row that is ALREADY in a lane toggles too, the other way: tapping it marks the app for
 * removal, so the screen that adds apps is also the screen that takes them back out, and the
 * button then reads "save changes" rather than "add N". Additions and removals leave together in
 * one settings transaction; the runtime never sees half of the edit.
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
    // Apps already in a lane that the user tapped to take out. Kept apart from [selected] so the
    // two never name the same package: a row is either an addition or a removal, never both.
    val removed = rememberSaveable(
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
                CliAppPickerRow(
                    app = app,
                    state = cliAppPickerRowState(
                        alreadyIn = app.packageName in state.settings.expert.appAssignments,
                        picked = app.packageName in selected,
                        dropped = app.packageName in removed,
                    ),
                    onToggle = { rowState ->
                        when (rowState) {
                            CliAppPickerRowState.DROPPED -> removed.remove(app.packageName)
                            CliAppPickerRowState.ASSIGNED -> removed.add(app.packageName)
                            CliAppPickerRowState.PICKED -> selected.remove(app.packageName)
                            CliAppPickerRowState.FREE -> selected.add(app.packageName)
                        }
                    },
                )
            }
        }
        // Batch confirm pinned below the list. Filled only once there is something to apply: a
        // grey button is the screen saying the batch is still empty, which "add 0" did not.
        val hasEdits = selected.isNotEmpty() || removed.isNotEmpty()
        Spacer(modifier = Modifier.height(CliSpacing.sm))
        CliButton(
            label = if (removed.isEmpty()) {
                stringResource(R.string.cli_route_pick_add_count, selected.size)
            } else {
                // Once a removal is in the batch the action is no longer "add": it saves the whole
                // edit, and the count would name only half of what the button is about to do.
                stringResource(R.string.cli_route_pick_save_changes)
            },
            filled = hasEdits,
            color = if (hasEdits) Color.Unspecified else colors.dim,
            enabled = hasEdits,
            onClick = {
                viewModel.onAppLaneEditsApplied(
                    added = selected.toList(),
                    lane = AppTunnelLane.VPN,
                    removed = removed.toList(),
                )
                onBack()
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(CliSpacing.sm))
    }
}

/** What one row of the picker currently is; a tap moves it to the opposite of whatever it says. */
internal enum class CliAppPickerRowState {
    /** In a lane and staying there. */
    ASSIGNED,

    /** In a lane, ticked off for removal. */
    DROPPED,

    /** Not in a lane, picked to be added. */
    PICKED,

    /** Not in a lane and not picked. */
    FREE,
}

internal fun cliAppPickerRowState(
    alreadyIn: Boolean,
    picked: Boolean,
    dropped: Boolean,
): CliAppPickerRowState = when {
    alreadyIn && dropped -> CliAppPickerRowState.DROPPED
    alreadyIn -> CliAppPickerRowState.ASSIGNED
    picked -> CliAppPickerRowState.PICKED
    else -> CliAppPickerRowState.FREE
}

/** One installed app: the checkbox glyph carries the whole state, colour and all. */
@Composable
private fun CliAppPickerRow(
    app: InstalledAppOption,
    state: CliAppPickerRowState,
    onToggle: (CliAppPickerRowState) -> Unit,
) {
    val colors = LocalCliColors.current
    CliInstalledAppRow(
        app = app,
        trailing = {
            Text(
                text = when (state) {
                    CliAppPickerRowState.ASSIGNED, CliAppPickerRowState.PICKED -> "[x]"
                    CliAppPickerRowState.DROPPED, CliAppPickerRowState.FREE -> "[ ]"
                },
                style = CliType.body,
                color = when (state) {
                    CliAppPickerRowState.ASSIGNED -> colors.ok
                    CliAppPickerRowState.DROPPED -> colors.err
                    CliAppPickerRowState.PICKED -> colors.accent
                    CliAppPickerRowState.FREE -> colors.dim
                },
            )
        },
        onTap = { onToggle(state) },
    )
}
