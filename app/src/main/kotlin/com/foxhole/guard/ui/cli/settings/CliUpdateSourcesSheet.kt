package com.foxhole.guard.ui.cli.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.foxhole.core.model.UpdateSourceSettings
import com.foxhole.guard.R
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.components.CliBottomSheet
import com.foxhole.guard.ui.cli.components.CliChip
import com.foxhole.guard.ui.cli.components.CliElbowLine
import com.foxhole.guard.ui.cli.components.CliInputRow
import com.foxhole.guard.ui.cli.components.CliPixIcon
import com.foxhole.guard.ui.cli.components.CliRowDivider
import com.foxhole.guard.ui.cli.components.CliSheetAction
import com.foxhole.guard.ui.cli.components.CliSheetActionsRow
import com.foxhole.guard.ui.cli.components.cliPressable

/**
 * The updates screen's top-bar control: opens [CliUpdateSourcesSheet].
 *
 * A header button rather than a row on the screen, for the same reason the help button is one —
 * this is about where the screen gets its data, not about the data; putting it among the status
 * rows would read as one more thing to press during an update.
 */
@Composable
internal fun CliUpdateSourcesButton(
    sources: UpdateSourceSettings,
    appChannelEditable: Boolean,
    onApply: (UpdateSourceSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCliColors.current
    var open by rememberSaveable { mutableStateOf(false) }
    Box(
        // requiredSize, like the help button: keeps a 48dp target by overlapping the header
        // instead of inflating its row.
        modifier = modifier
            .requiredSize(SOURCES_BUTTON_SIZE)
            .cliPressable(onClick = { open = true }),
        contentAlignment = Alignment.Center,
    ) {
        CliPixIcon(
            id = R.drawable.pix_settings,
            contentDescription = stringResource(R.string.cli_updates_sources_title),
            size = 16.dp,
            tint = colors.accent,
        )
    }
    if (open) {
        CliUpdateSourcesSheet(
            sources = sources,
            appChannelEditable = appChannelEditable,
            onDismiss = { open = false },
            onApply = { next ->
                onApply(next)
                open = false
            },
        )
    }
}

/**
 * Where updates come from: the FoxHole DB repository and the app's own release feed, each shown as
 * the channel currently in use with a "change" chip that reveals the field.
 *
 * Editing is explicit on purpose. These two fields decide which server the device trusts for data
 * and for its own next version, so they stay folded away behind a chip and are written only on
 * confirm — nothing here applies while the user is still typing.
 *
 * The app feed also carries a token, and only that one does: a private repository answers 404 to an
 * anonymous reader, so a personal build pushed to a private repo is unreachable without it. The
 * data repository needs no token — it is served as static files, and a private one would not be.
 */
@Composable
private fun CliUpdateSourcesSheet(
    sources: UpdateSourceSettings,
    appChannelEditable: Boolean,
    onDismiss: () -> Unit,
    onApply: (UpdateSourceSettings) -> Unit,
) {
    val colors = LocalCliColors.current
    // Seeded from the stored values: what is on screen is what would be saved, so "change" opens
    // an editable copy of the current channel rather than an empty field.
    var database by rememberSaveable { mutableStateOf(sources.databaseBaseUrl) }
    var releases by rememberSaveable { mutableStateOf(sources.appReleasesUrl) }
    var token by rememberSaveable { mutableStateOf(sources.appReleasesToken) }
    var databaseOpen by rememberSaveable { mutableStateOf(false) }
    var releasesOpen by rememberSaveable { mutableStateOf(false) }
    val officialLabel = stringResource(R.string.cli_updates_sources_official)
    CliBottomSheet(
        onDismiss = onDismiss,
        title = stringResource(R.string.cli_updates_sources_title),
        icon = R.drawable.pix_settings,
    ) {
        CliUpdateChannelRow(
            label = stringResource(R.string.cli_foxdb_title),
            value = database.ifBlank { officialLabel },
            editing = databaseOpen,
            onToggleEdit = { databaseOpen = !databaseOpen },
        )
        if (databaseOpen) {
            CliInputRow(
                prompt = "repo",
                value = database,
                onValueChange = { database = it },
                autoFocus = true,
                trailingChipLabel = stringResource(R.string.cli_updates_sources_reset),
                onTrailingChip = { database = "" },
            )
        }
        if (appChannelEditable) {
            CliRowDivider()
            CliUpdateChannelRow(
                label = stringResource(R.string.cli_updates_app),
                value = releases.ifBlank { officialLabel },
                editing = releasesOpen,
                onToggleEdit = { releasesOpen = !releasesOpen },
            )
            if (releasesOpen) {
                CliInputRow(
                    prompt = "repo",
                    value = releases,
                    onValueChange = { releases = it },
                    autoFocus = true,
                    trailingChipLabel = stringResource(R.string.cli_updates_sources_reset),
                    onTrailingChip = {
                        releases = ""
                        token = ""
                    },
                )
                CliInputRow(
                    prompt = "token",
                    value = token,
                    onValueChange = { token = it },
                    password = true,
                )
                CliElbowLine(
                    text = stringResource(R.string.cli_updates_sources_token_note),
                    color = colors.dim,
                )
            }
        }
        Spacer(modifier = Modifier.height(CliSpacing.xs))
        CliElbowLine(text = stringResource(R.string.cli_updates_sources_note))
        Spacer(modifier = Modifier.height(CliSpacing.md))
        CliSheetActionsRow(
            onCancel = onDismiss,
            actions = listOf(
                CliSheetAction(
                    label = stringResource(R.string.cli_common_yes_confirm),
                    onClick = {
                        onApply(
                            UpdateSourceSettings(
                                databaseBaseUrl = database,
                                appReleasesUrl = releases,
                                appReleasesToken = token,
                            ),
                        )
                    },
                ),
            ),
        )
    }
}

/** One channel: what it points at now, and the chip that folds the field open. */
@Composable
private fun CliUpdateChannelRow(
    label: String,
    value: String,
    editing: Boolean,
    onToggleEdit: () -> Unit,
) {
    val colors = LocalCliColors.current
    Row(
        modifier = Modifier.height(CHANNEL_ROW_HEIGHT),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = CliType.body, color = colors.dim, maxLines = 1)
        Spacer(modifier = Modifier.width(CliSpacing.sm))
        Text(
            text = value,
            style = CliType.body,
            color = colors.fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(CliSpacing.sm))
        CliChip(
            label = stringResource(R.string.cli_updates_sources_change),
            selected = editing,
            onClick = onToggleEdit,
        )
    }
}

private val SOURCES_BUTTON_SIZE = 48.dp

private val CHANNEL_ROW_HEIGHT = 48.dp
