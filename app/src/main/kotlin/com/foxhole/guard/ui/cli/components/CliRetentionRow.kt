package com.foxhole.guard.ui.cli.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.foxhole.core.model.RetentionPolicy
import com.foxhole.core.model.RetentionPreset
import com.foxhole.guard.R
import com.foxhole.guard.ui.cli.settings.CLI_OPT_CUSTOM

/**
 * The «how long do we keep this» dropdown, shared by every store that has a [RetentionPolicy]:
 * the statistics settings sheet and the app journal both use it, so the preset list, the labels and
 * the custom-days modal exist once. Presets render as bare `1d/7d/30d` in the CLI grammar; CUSTOM is
 * a separate `custom…` entry that opens a numeric modal rather than a preset of its own.
 */
@Composable
internal fun CliRetentionRow(
    label: String,
    policy: RetentionPolicy,
    onSelect: (RetentionPolicy) -> Unit,
    enabled: Boolean = true,
    icon: Int? = null,
) {
    // "Forever" is the one label that cannot be built from digits — resolve it before the options
    // are mapped, so no stringResource() call happens inside the map lambda.
    val foreverLabel = stringResource(R.string.cli_stats_retention_forever)
    var customOpen by rememberSaveable { mutableStateOf(false) }
    var customDays by rememberSaveable { mutableStateOf("") }
    CliDropdownRow(
        label = label,
        icon = icon,
        value = cliRetentionLabel(policy, foreverLabel),
        options = RETENTION_PRESETS.map { preset ->
            CliDropdownOption(
                id = preset.name,
                label = cliRetentionLabel(RetentionPolicy(preset = preset), foreverLabel),
            )
        } + CliDropdownOption(id = CLI_OPT_CUSTOM, label = stringResource(R.string.cli_common_custom)),
        selectedId = if (policy.preset == RetentionPreset.CUSTOM) CLI_OPT_CUSTOM else policy.preset.name,
        onSelect = { id ->
            if (id == CLI_OPT_CUSTOM) {
                customOpen = true
            } else {
                customOpen = false
                onSelect(policy.copy(preset = RetentionPreset.valueOf(id)))
            }
        },
        enabled = enabled,
    )
    if (customOpen) {
        CliInputModal(
            title = stringResource(R.string.cli_input_value_title),
            prompt = "d",
            value = customDays,
            onValueChange = { raw -> customDays = raw.filter(Char::isDigit).take(CUSTOM_DAYS_DIGITS) },
            onSubmit = {
                customDays.toIntOrNull()?.let { days ->
                    onSelect(
                        RetentionPolicy(
                            preset = RetentionPreset.CUSTOM,
                            customDays = days.coerceIn(1, RetentionPolicy.MAX_CUSTOM_DAYS),
                        ),
                    )
                    customOpen = false
                }
            },
            onDismiss = { customOpen = false },
            numeric = true,
        )
    }
}

internal fun cliRetentionLabel(
    policy: RetentionPolicy,
    foreverLabel: String,
): String =
    when (policy.preset) {
        RetentionPreset.DAY -> "1d"
        RetentionPreset.WEEK -> "7d"
        RetentionPreset.MONTH -> "30d"
        RetentionPreset.FOREVER -> foreverLabel
        RetentionPreset.CUSTOM -> "${policy.normalizedCustomDays()}d"
    }

// CUSTOM lives as its own «custom…» entry — it is not in the preset list.
private val RETENTION_PRESETS =
    listOf(
        RetentionPreset.DAY,
        RetentionPreset.WEEK,
        RetentionPreset.MONTH,
        RetentionPreset.FOREVER,
    )

private const val CUSTOM_DAYS_DIGITS = 3
