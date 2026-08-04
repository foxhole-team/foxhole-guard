package com.foxhole.guard.ui.cli.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foxhole.guard.BuildConfig
import com.foxhole.guard.R
import com.foxhole.guard.guardian.GuardJournalReport
import com.foxhole.guard.guardian.GuardJournalStatus
import com.foxhole.guard.ui.HomeViewModel
import com.foxhole.guard.ui.cli.CliFormat
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.components.CliActionRow
import com.foxhole.guard.ui.cli.components.CliKeyValue
import com.foxhole.guard.ui.cli.components.CliLoadingRow
import com.foxhole.guard.ui.cli.components.CliPanel
import com.foxhole.guard.ui.cli.components.CliScreenHeader
import com.foxhole.guard.ui.loadGuardJournalReport
import kotlinx.coroutines.launch

/** About block (versions as plain terminal facts) + the guard-journal verify action. */
@Composable
internal fun CliAboutSubScreen(
    viewModel: HomeViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.settingsRouteState.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = CliSpacing.md),
    ) {
        CliScreenHeader(label = stringResource(R.string.cli_cfg_more_about), icon = R.drawable.pix_star)
        CliPanel(
            title = stringResource(R.string.cli_about_title),
            icon = R.drawable.pix_info,
            modifier = Modifier.fillMaxWidth(),
        ) {
            CliKeyValue(key = "foxhole guard", value = state.appVersion)
            CliKeyValue(key = "FoxCore", value = BuildConfig.FOXCORE_SOURCE_VERSION)
            CliKeyValue(key = "Arti", value = BuildConfig.ARTI_VERSION)
        }
        Spacer(modifier = Modifier.height(CliSpacing.sm))
        CliPanel(
            icon = R.drawable.pix_info,
            title = stringResource(R.string.about_licenses_title),
            modifier = Modifier.fillMaxWidth(),
        ) {
            ABOUT_LICENSES.forEach { (component, license) ->
                CliKeyValue(key = component, value = license)
            }
        }
        Spacer(modifier = Modifier.height(CliSpacing.sm))
        CliGuardJournalPanel(viewModel = viewModel)
        Spacer(modifier = Modifier.height(CliSpacing.sm))
    }
}

/**
 * The verify action runs the full chain check (donor: loadGuardJournalReport). A clean
 * OK verify re-anchors the checkpoint as a side effect — one manual run per visit, no
 * auto-refresh loops. Null report = guard keys unavailable (no password / locked).
 */
@Composable
private fun CliGuardJournalPanel(viewModel: HomeViewModel) {
    val colors = LocalCliColors.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var checked by remember { mutableStateOf(false) }
    var checkFailed by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<GuardJournalReport?>(null) }

    CliPanel(
        icon = R.drawable.pix_journal,
        title = stringResource(R.string.cli_journal_title),
        modifier = Modifier.fillMaxWidth(),
    ) {
        when {
            checking -> CliLoadingRow(text = stringResource(R.string.cli_journal_checking))
            !checked -> CliActionRow(
                label = stringResource(R.string.cli_journal_verify),
                onTap = {
                    checking = true
                    scope.launch {
                        // verifyGuardJournal() reads files the sentinel rotates and uses
                        // try/finally without a catch: without runCatching a throw took down the
                        // recomposer.
                        val result = runCatching { viewModel.loadGuardJournalReport() }
                        report = result.getOrNull()
                        checkFailed = result.isFailure
                        checking = false
                        checked = true
                    }
                },
            )
            else -> CliGuardJournalReportBody(
                report = report,
                failed = checkFailed,
                onRerun = {
                    checked = false
                    checkFailed = false
                    report = null
                },
            )
        }
    }
}

@Composable
private fun CliGuardJournalReportBody(
    report: GuardJournalReport?,
    failed: Boolean,
    onRerun: () -> Unit,
) {
    val colors = LocalCliColors.current
    if (failed || report == null) {
        // A failed chain read and "no keys" are different: the first is worth retrying.
        Text(
            text = stringResource(
                if (failed) R.string.cli_journal_check_failed else R.string.cli_journal_locked,
            ),
            style = CliType.body,
            color = if (failed) colors.err else colors.dim,
        )
        CliActionRow(label = stringResource(R.string.cli_journal_verify), onTap = onRerun)
        return
    }
    val statusColor = when (report.status) {
        GuardJournalStatus.OK -> colors.ok
        GuardJournalStatus.GAPS -> colors.warn
        GuardJournalStatus.TRUNCATED, GuardJournalStatus.REWRITTEN -> colors.err
    }
    CliKeyValue(
        key = stringResource(R.string.cli_journal_status),
        value = report.status.name.lowercase(),
        valueColor = statusColor,
    )
    CliKeyValue(
        key = stringResource(R.string.cli_journal_entries),
        value = report.entries.size.toString(),
    )
    if (report.anomalies.isNotEmpty()) {
        CliKeyValue(
            key = stringResource(R.string.cli_journal_anomalies),
            value = report.anomalies.size.toString(),
            valueColor = colors.warn,
        )
    }
    report.entries.takeLast(ENTRIES_SHOWN).reversed().forEach { entry ->
        val label = entry.event?.let { event ->
            listOfNotNull(event.type.name.lowercase(), event.packageName).joinToString(" ")
        } ?: "#${entry.record.seq}"
        Text(
            text = "[${CliFormat.clock(entry.record.wallClock)}] $label",
            style = CliType.small,
            color = colors.dim,
            maxLines = 1,
        )
    }
    CliActionRow(label = stringResource(R.string.cli_journal_verify), onTap = onRerun)
}

private const val ENTRIES_SHOWN = 12

// Shipped components and SPDX identifiers are intentionally not localized.
private val ABOUT_LICENSES =
    listOf(
        "FoxHole Guard" to "GPL-3.0-or-later",
        "FoxCore" to "GPL-3.0-or-later",
        // The Tor client linked here is Arti, not the C daemon — and Arti is MIT/Apache-2.0. The
        // BSD-3-Clause line that used to stand here named a component this build does not contain.
        "Arti (Tor)" to "MIT OR Apache-2.0",
        "lyrebird" to "BSD-3-Clause",
        "conjure-client" to "BSD-3-Clause",
        "i2pd (PurpleI2P)" to "BSD-3-Clause",
        "SQLCipher" to "BSD-3-Clause",
        "OkHttp" to "Apache-2.0",
        "AndroidX / Jetpack Compose" to "Apache-2.0",
        "Kotlin / kotlinx" to "Apache-2.0",
        "Protocol Buffers" to "BSD-3-Clause",
        "ZXing Android Embedded" to "Apache-2.0",
        "lazysodium-android" to "MPL-2.0",
        "JNA" to "LGPL-2.1 / Apache-2.0",
        "AdGuard DNS filter" to "GPL-3.0",
        "DB-IP / ip-location-db" to "CC BY 4.0",
        "Stalkerware indicators (Echap)" to "CC BY 4.0",
        "Silkscreen / Press Start 2P / LanaPixel" to "OFL-1.1",
        "1-bit Pixel Icons (Nikoichu)" to "CC0-1.0",
        "Natural Earth" to "public domain",
        "Pixel flags (R74n)" to "free use, no license text",
    )
