package com.foxhole.guard.ui.cli.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
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
import com.foxhole.guard.ui.cli.components.CliBadge
import com.foxhole.guard.ui.cli.components.CliBottomSheet
import com.foxhole.guard.ui.cli.components.CliChip
import com.foxhole.guard.ui.cli.components.CliElbowLine
import com.foxhole.guard.ui.cli.components.CliInfoSheet
import com.foxhole.guard.ui.cli.components.CliInputRow
import com.foxhole.guard.ui.cli.components.CliRowDivider
import com.foxhole.guard.ui.cli.components.CliRowInfoGlyph
import com.foxhole.guard.ui.cli.components.CliSheetAction
import com.foxhole.guard.ui.cli.components.CliSheetActionsRow
import com.foxhole.guard.ui.cli.components.CliSheetHeaderIconRole
import com.foxhole.guard.ui.cli.components.CliTopBarSettingsButton
import com.foxhole.guard.ui.cli.components.cliModalHeaderIconSizeFor

@Composable
internal fun CliUpdateSourcesButton(
    sources: UpdateSourceSettings,
    appChannelEditable: Boolean,
    onApply: (UpdateSourceSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCliColors.current
    var open by rememberSaveable { mutableStateOf(false) }
    CliTopBarSettingsButton(
        contentDescription = stringResource(R.string.cli_updates_sources_title),
        onClick = { open = true },
        modifier = modifier,
        tint = colors.accent,
    )
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

@Composable
private fun CliUpdateSourcesSheet(
    sources: UpdateSourceSettings,
    appChannelEditable: Boolean,
    onDismiss: () -> Unit,
    onApply: (UpdateSourceSettings) -> Unit,
) {
    val colors = LocalCliColors.current
    var database by rememberSaveable { mutableStateOf(sources.databaseBaseUrl) }
    var releases by rememberSaveable { mutableStateOf(sources.appReleasesUrl) }
    var token by rememberSaveable { mutableStateOf(sources.appReleasesToken) }
    var databaseOpen by rememberSaveable { mutableStateOf(false) }
    var releasesOpen by rememberSaveable { mutableStateOf(false) }
    var noteOpen by rememberSaveable { mutableStateOf(false) }
    val officialLabel = stringResource(R.string.cli_updates_sources_official)
    val databaseRow = cliUpdateSourceRowPresentation(database)
    val releasesRow = cliUpdateSourceRowPresentation(releases)
    if (noteOpen) {
        CliInfoSheet(
            text = stringResource(R.string.cli_updates_sources_note),
            onDismiss = { noteOpen = false },
        )
    }
    CliBottomSheet(
        onDismiss = onDismiss,
        title = stringResource(R.string.cli_updates_sources_title),
        icon = R.drawable.lin_settings,
        trailing = {
            CliRowInfoGlyph(
                onTap = { noteOpen = true },
                iconSize = cliModalHeaderIconSizeFor(CliSheetHeaderIconRole.DEFAULT),
            )
        },
    ) {
        CliUpdateChannelRow(
            label = stringResource(R.string.cli_foxdb_title),
            value = databaseRow.value,
            official = databaseRow.official,
            officialLabel = officialLabel,
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
                value = releasesRow.value,
                official = releasesRow.official,
                officialLabel = officialLabel,
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
        Spacer(modifier = Modifier.height(CliSpacing.md))
        CliSheetActionsRow(
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
                    dismissAfterClick = true,
                ),
            ),
        )
    }
}

@Composable
private fun CliUpdateChannelRow(
    label: String,
    value: String,
    official: Boolean,
    officialLabel: String,
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
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.Top,
        ) {
            if (value.isNotBlank()) {
                Text(
                    text = value,
                    style = CliType.body,
                    color = colors.fg,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            if (official) {
                if (value.isNotBlank()) Spacer(modifier = Modifier.width(BADGE_GAP))
                CliBadge(text = officialLabel, color = colors.ok)
            }
        }
        Spacer(modifier = Modifier.width(CliSpacing.sm))
        CliChip(
            label = stringResource(R.string.cli_updates_sources_change),
            selected = editing,
            onClick = onToggleEdit,
        )
    }
}

internal data class CliUpdateSourceRowPresentation(
    val value: String,
    val official: Boolean,
)

internal fun cliUpdateSourceRowPresentation(configuredValue: String): CliUpdateSourceRowPresentation =
    CliUpdateSourceRowPresentation(
        value = configuredValue.takeUnless(String::isBlank).orEmpty(),
        official = configuredValue.isBlank(),
    )

private val BADGE_GAP = 3.dp

private val CHANNEL_ROW_HEIGHT = 48.dp
