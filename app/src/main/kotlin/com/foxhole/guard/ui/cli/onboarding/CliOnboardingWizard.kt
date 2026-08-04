package com.foxhole.guard.ui.cli.onboarding

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foxhole.guard.R
import com.foxhole.guard.runtime.RemoteUpdatePhase
import com.foxhole.guard.ui.HomeViewModel
import com.foxhole.guard.ui.OnboardingDownload
import com.foxhole.guard.ui.OnboardingDownloadState
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.cliDisplayStyle
import com.foxhole.guard.ui.cli.components.CliButton
import com.foxhole.guard.ui.cli.components.CliPanel
import com.foxhole.guard.ui.cli.components.CliPixIcon
import com.foxhole.guard.ui.cli.components.CliToggleRow
import com.foxhole.guard.ui.cli.fox.CliFoxHero
import com.foxhole.guard.ui.onOnboardingDownload
import com.foxhole.guard.ui.onOnboardingFinished
import com.foxhole.guard.ui.onOnboardingSkipped
import com.foxhole.guard.ui.onboardingProgress

/**
 * First-run wizard, composed instead of the app until the user finishes or skips it.
 *
 * Three steps: the licence, the components, and — only when something was chosen that needs a remote
 * list — the download. Nothing is written until the last step's action, so backing out or killing the
 * app leaves a fresh install untouched.
 */
@Composable
internal fun CliOnboardingWizard(viewModel: HomeViewModel) {
    val colors = LocalCliColors.current
    var step by rememberSaveable { mutableStateOf(0) }
    var licenseAccepted by rememberSaveable { mutableStateOf(false) }
    var torEnabled by rememberSaveable { mutableStateOf(false) }
    var i2pEnabled by rememberSaveable { mutableStateOf(false) }
    var i2pRelay by rememberSaveable { mutableStateOf(false) }
    var dnsFilterEnabled by rememberSaveable { mutableStateOf(false) }
    var autoUpdate by rememberSaveable { mutableStateOf(true) }

    // Bridges are only worth fetching for Tor, lists only for the filter; with neither the third
    // step has nothing to do and is skipped entirely.
    val needsDownload = torEnabled || dnsFilterEnabled

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(CliSpacing.md),
    ) {
        Box(modifier = Modifier.weight(1f)) {
            when (step) {
                0 -> WizardLicenseStep(
                    accepted = licenseAccepted,
                    onAcceptedChange = { licenseAccepted = it },
                )

                1 -> WizardComponentsStep(
                    torEnabled = torEnabled,
                    onTorChange = { torEnabled = it },
                    i2pEnabled = i2pEnabled,
                    onI2pChange = { value ->
                        i2pEnabled = value
                        if (!value) {
                            i2pRelay = false
                        }
                    },
                    i2pRelay = i2pRelay,
                    onI2pRelayChange = { i2pRelay = it },
                    dnsFilterEnabled = dnsFilterEnabled,
                    onDnsFilterChange = { dnsFilterEnabled = it },
                )

                else -> WizardDownloadStep(
                    viewModel = viewModel,
                    dnsFilter = dnsFilterEnabled,
                    torBridges = torEnabled,
                    autoUpdate = autoUpdate,
                    onAutoUpdateChange = { autoUpdate = it },
                )
            }
        }

        Spacer(modifier = Modifier.height(CliSpacing.sm))
        WizardFooter(
            step = step,
            canContinue = step != 0 || licenseAccepted,
            isLast = step == 2 || (step == 1 && !needsDownload),
            onBack = { step -= 1 },
            onContinue = {
                if (step == 1 && !needsDownload || step == 2) {
                    viewModel.onOnboardingFinished(
                        torEnabled = torEnabled,
                        i2pEnabled = i2pEnabled,
                        i2pRelay = i2pRelay,
                        dnsFilterEnabled = dnsFilterEnabled,
                        autoUpdate = autoUpdate,
                    )
                } else {
                    step += 1
                }
            },
            onSkip = { viewModel.onOnboardingSkipped() },
        )
    }
}

@Composable
private fun WizardLicenseStep(accepted: Boolean, onAcceptedChange: (Boolean) -> Unit) {
    val colors = LocalCliColors.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CliFoxHero()
        Spacer(modifier = Modifier.height(CliSpacing.sm))
        Text(
            text = stringResource(R.string.cli_wizard_welcome_title),
            style = cliDisplayStyle(stringResource(R.string.cli_wizard_welcome_title)),
            color = colors.accent,
        )
        Text(
            text = stringResource(R.string.cli_wizard_welcome_subtitle),
            style = CliType.small,
            color = colors.dim,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(CliSpacing.md))
        CliPanel(title = stringResource(R.string.cli_wizard_license_caption), icon = R.drawable.pix_info) {
            Text(
                text = stringResource(R.string.cli_wizard_license_body),
                style = CliType.small,
                color = colors.fg,
            )
        }
        Spacer(modifier = Modifier.height(CliSpacing.sm))
        CliToggleRow(
            label = stringResource(R.string.cli_wizard_license_accept),
            checked = accepted,
            onToggle = onAcceptedChange,
        )
    }
}

@Composable
private fun WizardComponentsStep(
    torEnabled: Boolean,
    onTorChange: (Boolean) -> Unit,
    i2pEnabled: Boolean,
    onI2pChange: (Boolean) -> Unit,
    i2pRelay: Boolean,
    onI2pRelayChange: (Boolean) -> Unit,
    dnsFilterEnabled: Boolean,
    onDnsFilterChange: (Boolean) -> Unit,
) {
    val colors = LocalCliColors.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        CliPanel(title = stringResource(R.string.cli_wizard_components_caption), icon = R.drawable.pix_shield) {
            Text(
                text = stringResource(R.string.cli_wizard_components_intro),
                style = CliType.small,
                color = colors.dim,
            )
            Spacer(modifier = Modifier.height(CliSpacing.sm))
            CliToggleRow(
                label = stringResource(R.string.cli_wizard_component_tor),
                checked = torEnabled,
                onToggle = onTorChange,
                note = stringResource(R.string.cli_wizard_component_tor_note),
            )
            CliToggleRow(
                label = stringResource(R.string.cli_wizard_component_i2p),
                checked = i2pEnabled,
                onToggle = onI2pChange,
                note = stringResource(R.string.cli_wizard_component_i2p_note),
            )
            CliToggleRow(
                label = stringResource(R.string.cli_wizard_component_dns),
                checked = dnsFilterEnabled,
                onToggle = onDnsFilterChange,
                note = stringResource(R.string.cli_wizard_component_dns_note),
            )
        }
        Spacer(modifier = Modifier.height(CliSpacing.sm))
        I2pRelayBanner(
            i2pEnabled = i2pEnabled,
            relay = i2pRelay,
            onRelayChange = onI2pRelayChange,
        )
    }
}

/**
 * The call to relay for I2P. It breathes while I2P is off so the offer is noticed at all, and holds
 * still once the user has engaged with it — a banner that keeps blinking under a made decision reads
 * as an unfixable warning.
 */
@Composable
private fun I2pRelayBanner(i2pEnabled: Boolean, relay: Boolean, onRelayChange: (Boolean) -> Unit) {
    val colors = LocalCliColors.current
    val transition = rememberInfiniteTransition(label = "i2p-banner")
    val pulse by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.45f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "i2p-banner-alpha",
    )
    val alpha = if (i2pEnabled) 1f else pulse

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(alpha)
            .border(1.dp, colors.accent, androidx.compose.foundation.shape.RoundedCornerShape(2.dp))
            .background(colors.panelAlt)
            .padding(CliSpacing.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CliPixIcon(id = R.drawable.pix_globe, contentDescription = null, size = 16.dp, tint = colors.accent)
            Spacer(modifier = Modifier.size(6.dp))
            Text(
                text = stringResource(R.string.cli_wizard_i2p_banner_title),
                style = CliType.small,
                color = colors.accent,
            )
        }
        Spacer(modifier = Modifier.height(CliSpacing.xs))
        Text(
            text = stringResource(R.string.cli_wizard_i2p_banner_body),
            style = CliType.small,
            color = colors.fg,
        )
        Spacer(modifier = Modifier.height(CliSpacing.xs))
        CliToggleRow(
            label = stringResource(R.string.cli_wizard_i2p_banner_toggle),
            checked = relay,
            onToggle = onRelayChange,
            // Relaying is meaningless without the router; the row stays visible so the offer is not
            // hidden, but it cannot be armed before I2P itself is on.
            enabled = i2pEnabled,
        )
    }
}

@Composable
private fun WizardDownloadStep(
    viewModel: HomeViewModel,
    dnsFilter: Boolean,
    torBridges: Boolean,
    autoUpdate: Boolean,
    onAutoUpdateChange: (Boolean) -> Unit,
) {
    val colors = LocalCliColors.current
    val progress by viewModel.onboardingProgress.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        CliPanel(title = stringResource(R.string.cli_wizard_download_caption), icon = R.drawable.pix_dns) {
            Text(
                text = stringResource(R.string.cli_wizard_download_intro),
                style = CliType.small,
                color = colors.dim,
            )
            Spacer(modifier = Modifier.height(CliSpacing.sm))

            WizardProgressBar(fraction = progress.fraction)
            Spacer(modifier = Modifier.height(CliSpacing.xs))
            progress.items.forEach { item -> WizardDownloadRow(item) }
            if (progress.items.isEmpty()) {
                if (dnsFilter) {
                    WizardPlannedRow(stringResource(R.string.cli_wizard_download_dns))
                }
                if (torBridges) {
                    WizardPlannedRow(stringResource(R.string.cli_wizard_download_bridges))
                }
            }

            Spacer(modifier = Modifier.height(CliSpacing.sm))
            CliToggleRow(
                label = stringResource(R.string.cli_wizard_download_auto),
                checked = autoUpdate,
                onToggle = onAutoUpdateChange,
                note = stringResource(R.string.cli_wizard_download_auto_note),
            )
            Spacer(modifier = Modifier.height(CliSpacing.sm))
            CliButton(
                label = stringResource(R.string.cli_wizard_download_start),
                onClick = { viewModel.onOnboardingDownload(dnsFilter = dnsFilter, torBridges = torBridges) },
                filled = true,
                enabled = !progress.running && !progress.finished,
            )
            Text(
                text = stringResource(R.string.cli_wizard_download_skip_note),
                style = CliType.small,
                color = colors.faint,
            )
        }
    }
}

@Composable
private fun WizardProgressBar(fraction: Float) {
    val colors = LocalCliColors.current
    // Drawn as two boxes rather than a Material bar: the terminal look has no rounded indicators.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .background(colors.panel)
            .border(1.dp, colors.border),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .fillMaxSize()
                .background(colors.accent),
        )
    }
}

@Composable
private fun WizardDownloadRow(state: OnboardingDownloadState) {
    val colors = LocalCliColors.current
    val label = when (state.item) {
        OnboardingDownload.DNS_FILTER -> stringResource(R.string.cli_wizard_download_dns)
        OnboardingDownload.TOR_BRIDGES -> stringResource(R.string.cli_wizard_download_bridges)
    }
    val status = when {
        state.failed -> stringResource(R.string.cli_wizard_phase_failed)
        state.done -> stringResource(R.string.cli_wizard_phase_done)
        state.phase == RemoteUpdatePhase.CHECKING -> stringResource(R.string.cli_wizard_phase_checking)
        state.phase == RemoteUpdatePhase.DOWNLOADING -> stringResource(R.string.cli_wizard_phase_downloading)
        state.phase == RemoteUpdatePhase.VERIFYING -> stringResource(R.string.cli_wizard_phase_verifying)
        else -> "—"
    }
    val tone = when {
        state.failed -> colors.warn
        state.done -> colors.ok
        else -> colors.dim
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, style = CliType.small, color = colors.fg)
        Text(text = status, style = CliType.small, color = tone)
    }
}

@Composable
private fun WizardPlannedRow(label: String) {
    val colors = LocalCliColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, style = CliType.small, color = colors.fg)
        Text(text = "—", style = CliType.small, color = colors.faint)
    }
}

@Composable
private fun WizardFooter(
    step: Int,
    canContinue: Boolean,
    isLast: Boolean,
    onBack: () -> Unit,
    onContinue: () -> Unit,
    onSkip: () -> Unit,
) {
    val colors = LocalCliColors.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(CliSpacing.sm),
        ) {
            if (step > 0) {
                CliButton(
                    label = stringResource(R.string.cli_wizard_back),
                    onClick = onBack,
                    modifier = Modifier.weight(1f),
                )
            }
            CliButton(
                label = stringResource(
                    if (isLast) R.string.cli_wizard_finish else R.string.cli_wizard_continue,
                ),
                onClick = onContinue,
                modifier = Modifier.weight(1f),
                // Outlined until the step is satisfied: dimming alone still reads as a live primary
                // button, and the licence step must make "not yet" unmistakable.
                filled = canContinue,
                enabled = canContinue,
                color = colors.ok,
            )
        }
        if (step == 0) {
            Spacer(modifier = Modifier.height(CliSpacing.xs))
            CliButton(
                label = stringResource(R.string.cli_wizard_skip),
                onClick = onSkip,
                modifier = Modifier.fillMaxWidth(),
                color = colors.dim,
                enabled = canContinue,
            )
            Text(
                text = stringResource(R.string.cli_wizard_skip_note),
                style = CliType.small,
                color = colors.faint,
            )
        }
    }
}
