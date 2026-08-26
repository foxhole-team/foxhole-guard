package com.foxhole.guard.ui.cli.onboarding

import androidx.compose.animation.AnimatedContent
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foxhole.core.model.AppLocale
import com.foxhole.core.model.ThemeMode
import com.foxhole.guard.R
import com.foxhole.guard.runtime.RemoteUpdatePhase
import com.foxhole.guard.ui.HomeViewModel
import com.foxhole.guard.ui.OnboardingDownload
import com.foxhole.guard.ui.OnboardingDownloadState
import com.foxhole.guard.ui.OnboardingProgress
import com.foxhole.guard.ui.cli.CliRadius
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.cliDisplayStyle
import com.foxhole.guard.ui.cli.cliSlide
import com.foxhole.guard.ui.cli.components.CliButton
import com.foxhole.guard.ui.cli.components.CliConfirmSheet
import com.foxhole.guard.ui.cli.components.CliDropdownOption
import com.foxhole.guard.ui.cli.components.CliDropdownRow
import com.foxhole.guard.ui.cli.components.CliIcon
import com.foxhole.guard.ui.cli.components.CliPanel
import com.foxhole.guard.ui.cli.components.CliPixelProgressBar
import com.foxhole.guard.ui.cli.components.CliRejectFeedback
import com.foxhole.guard.ui.cli.components.CliRowDivider
import com.foxhole.guard.ui.cli.components.CliToggleRow
import com.foxhole.guard.ui.cli.components.LocalCliInfoSheetBodyIconVisible
import com.foxhole.guard.ui.cli.components.cliDashedBorder
import com.foxhole.guard.ui.cli.components.cliMarchingBorder
import com.foxhole.guard.ui.cli.components.cliRejectShake
import com.foxhole.guard.ui.cli.components.rememberCliRejectFeedback
import com.foxhole.guard.ui.cli.fox.CliFoxHero
import com.foxhole.guard.ui.onLocaleSelected
import com.foxhole.guard.ui.onOnboardingDownload
import com.foxhole.guard.ui.onOnboardingFinished
import com.foxhole.guard.ui.onOnboardingSkipped
import com.foxhole.guard.ui.onPixelArtEnabledChanged
import com.foxhole.guard.ui.onThemeSelected
import com.foxhole.guard.ui.onboardingProgress
import kotlinx.coroutines.launch

@Composable
internal fun CliOnboardingWizard(viewModel: HomeViewModel) {
    val colors = LocalCliColors.current
    val progress by viewModel.onboardingProgress.collectAsStateWithLifecycle()
    var step by rememberSaveable { mutableIntStateOf(LICENSE_STEP) }
    var choices by rememberSaveable(stateSaver = OnboardingWizardChoicesSaver) {
        mutableStateOf(OnboardingWizardChoices())
    }
    val licenseRejectFeedback = rememberCliRejectFeedback()
    val feedbackScope = rememberCoroutineScope()
    var licenseValidationError by rememberSaveable { mutableStateOf(false) }
    var skipSetupConfirmationOpen by rememberSaveable { mutableStateOf(false) }

    CompositionLocalProvider(LocalCliInfoSheetBodyIconVisible provides false) {
        if (skipSetupConfirmationOpen) {
            CliConfirmSheet(
                title = stringResource(R.string.cli_wizard_skip),
                question = stringResource(R.string.cli_wizard_skip_note),
                icon = R.drawable.lin_info,
                confirmLabel = stringResource(R.string.cli_wizard_skip),
                onConfirm = {
                    skipSetupConfirmationOpen = false
                    viewModel.onOnboardingSkipped()
                },
                onDismiss = { skipSetupConfirmationOpen = false },
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.bg)
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(CliSpacing.md),
        ) {
            WizardHeader()
            Spacer(modifier = Modifier.height(CliSpacing.sm))
            WizardStepContent(
                step = step,
                viewModel = viewModel,
                progress = progress,
                choices = choices,
                licenseValidationError = licenseValidationError,
                licenseRejectFeedback = licenseRejectFeedback,
                onChoicesChange = { next ->
                    choices = next
                    if (next.licenseAccepted) licenseValidationError = false
                },
                modifier = Modifier.weight(1f),
            )

            Spacer(modifier = Modifier.height(CliSpacing.sm))
            WizardFooter(
                step = step,
                canContinue = wizardCanContinue(step, choices, progress),
                onBack = { step -= 1 },
                onContinue = {
                    when (step) {
                        LICENSE_STEP -> {
                            if (choices.licenseAccepted) {
                                licenseValidationError = false
                                step = COMPONENTS_STEP
                            } else {
                                licenseValidationError = true
                                feedbackScope.launch { licenseRejectFeedback.play() }
                            }
                        }
                        DATA_STEP -> {
                            viewModel.startOnboardingDownloads(choices)
                            step = DOWNLOAD_STEP
                        }
                        DOWNLOAD_STEP -> viewModel.finishOnboarding(choices)
                        else -> step += 1
                    }
                },
                onSkipSetup = { skipSetupConfirmationOpen = true },
                onSkipData = {
                    val skippedChoices = choices.withoutDownloads()
                    choices = skippedChoices
                    viewModel.finishOnboarding(skippedChoices)
                },
            )
        }
    }
}

@Composable
private fun WizardStepContent(
    step: Int,
    viewModel: HomeViewModel,
    progress: OnboardingProgress,
    choices: OnboardingWizardChoices,
    licenseValidationError: Boolean,
    licenseRejectFeedback: CliRejectFeedback,
    onChoicesChange: (OnboardingWizardChoices) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier, contentAlignment = Alignment.TopStart) {
        AnimatedContent(
            targetState = step,
            transitionSpec = {
                cliSlide(forward = targetState > initialState)
            },
            contentAlignment = Alignment.TopStart,
            label = "onboarding-step",
            modifier = Modifier.fillMaxSize().clipToBounds(),
        ) { currentStep ->
            when (currentStep) {
                LICENSE_STEP -> WizardLicenseStep(
                    viewModel = viewModel,
                    accepted = choices.licenseAccepted,
                    validationError = licenseValidationError,
                    rejectFeedback = licenseRejectFeedback,
                    onAcceptedChange = { onChoicesChange(choices.copy(licenseAccepted = it)) },
                )

                COMPONENTS_STEP -> WizardComponentsStep(
                    torEnabled = choices.torEnabled,
                    onTorChange = { enabled ->
                        onChoicesChange(
                            choices.copy(
                                torEnabled = enabled,
                                torBridges = choices.torBridges && enabled,
                                torBridgesDownload = choices.torBridgesDownload && enabled,
                            ),
                        )
                    },
                    torBridges = choices.torBridges,
                    onTorBridgesChange = { enabled ->
                        onChoicesChange(
                            choices.copy(
                                torBridges = enabled,
                                torBridgesDownload = enabled && choices.bridgesMirror,
                            ),
                        )
                    },
                    bridgesMirror = choices.bridgesMirror,
                    onBridgesMirrorChange = { useFoxholeSource ->
                        onChoicesChange(
                            choices.copy(
                                bridgesMirror = useFoxholeSource,
                                torBridgesDownload = choices.torBridges && useFoxholeSource,
                            ),
                        )
                    },
                    i2pEnabled = choices.i2pEnabled,
                    onI2pChange = { enabled ->
                        onChoicesChange(
                            choices.copy(
                                i2pEnabled = enabled,
                                i2pRelay = choices.i2pRelay && enabled,
                            ),
                        )
                    },
                    i2pRelay = choices.i2pRelay,
                    onI2pRelayChange = { onChoicesChange(choices.copy(i2pRelay = it)) },
                )

                DATA_STEP -> WizardDataSetsStep(
                    geoIp = choices.geoDownload,
                    onGeoIpChange = { onChoicesChange(choices.copy(geoDownload = it)) },
                    tlsFingerprints = choices.tlsFingerprintDownload,
                    onTlsFingerprintsChange = { onChoicesChange(choices.copy(tlsFingerprintDownload = it)) },
                    dnsFilter = choices.dnsDownload,
                    onDnsFilterChange = { onChoicesChange(choices.copy(dnsDownload = it)) },
                    torBridges = choices.torBridgesDownload,
                    onTorBridgesChange = { onChoicesChange(choices.copy(torBridgesDownload = it)) },
                    threatIntel = choices.threatIntelDownload,
                    onThreatIntelChange = { onChoicesChange(choices.copy(threatIntelDownload = it)) },
                    autoUpdate = choices.autoUpdate,
                    onAutoUpdateChange = { onChoicesChange(choices.copy(autoUpdate = it)) },
                )

                else -> WizardDownloadStep(
                    viewModel = viewModel,
                    progress = progress,
                    dnsFilter = choices.dnsDownload,
                    torBridges = choices.torBridgesDownload,
                    geoIp = choices.geoDownload,
                    tlsFingerprints = choices.tlsFingerprintDownload,
                    threatIntel = choices.threatIntelDownload,
                )
            }
        }
    }
}

@Composable
private fun WizardHeader() {
    val colors = LocalCliColors.current
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CliFoxHero(size = WIZARD_HERO_SIZE)
        Text(
            text = buildAnnotatedString {
                append("FOXHOLE ")
                pushStyle(SpanStyle(color = colors.info))
                append("GUARD")
                pop()
            },
            style = cliDisplayStyle(stringResource(R.string.cli_wizard_welcome_title)),
            color = colors.accent,
        )
        Text(
            text = stringResource(R.string.cli_wizard_welcome_subtitle),
            style = CliType.small,
            color = colors.dim,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun WizardLicenseStep(
    viewModel: HomeViewModel,
    accepted: Boolean,
    validationError: Boolean,
    rejectFeedback: CliRejectFeedback,
    onAcceptedChange: (Boolean) -> Unit,
) {
    val colors = LocalCliColors.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        CliPanel(title = stringResource(R.string.cli_wizard_license_caption), icon = R.drawable.lin_info) {
            Text(
                text = stringResource(R.string.cli_wizard_license_body),
                style = CliType.small,
                color = colors.fg,
            )
            Spacer(modifier = Modifier.height(CliSpacing.sm))
            CliToggleRow(
                label = stringResource(R.string.cli_wizard_license_accept),
                checked = accepted,
                onToggle = onAcceptedChange,
            )
        }
        Spacer(modifier = Modifier.height(CliSpacing.sm))
        WizardAppearancePanel(viewModel = viewModel)
        if (validationError) {
            Spacer(modifier = Modifier.height(CliSpacing.sm))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .cliRejectShake(rejectFeedback)
                    .cliDashedBorder(colors.err)
                    .padding(CliSpacing.sm),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CliIcon(
                    id = R.drawable.lin_info,
                    contentDescription = null,
                    size = 16.dp,
                    tint = colors.err,
                )
                Spacer(modifier = Modifier.size(CliSpacing.xs))
                Text(
                    text = stringResource(R.string.cli_wizard_license_required),
                    style = CliType.small,
                    color = colors.err,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun WizardComponentsStep(
    torEnabled: Boolean,
    onTorChange: (Boolean) -> Unit,
    torBridges: Boolean,
    onTorBridgesChange: (Boolean) -> Unit,
    bridgesMirror: Boolean,
    onBridgesMirrorChange: (Boolean) -> Unit,
    i2pEnabled: Boolean,
    onI2pChange: (Boolean) -> Unit,
    i2pRelay: Boolean,
    onI2pRelayChange: (Boolean) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        CliPanel(
            title = stringResource(R.string.cli_wizard_components_caption),
            icon = R.drawable.lin_shield,
            infoText = stringResource(R.string.cli_wizard_components_intro),
        ) {
            CliToggleRow(
                label = stringResource(R.string.cli_wizard_component_tor),
                checked = torEnabled,
                onToggle = onTorChange,
                infoText = stringResource(R.string.cli_wizard_component_tor_note),
            )
            if (torEnabled) {
                CliToggleRow(
                    label = stringResource(R.string.cli_wizard_component_tor_bridges),
                    checked = torBridges,
                    onToggle = onTorBridgesChange,
                    infoText = stringResource(R.string.cli_wizard_component_tor_bridges_note),
                    modifier = Modifier.padding(start = CliSpacing.md),
                )
                if (torBridges) {
                    CliDropdownRow(
                        label = stringResource(R.string.cli_dataset_source),
                        icon = R.drawable.lin_globe,
                        value = stringResource(
                            if (bridgesMirror) {
                                R.string.cli_dataset_source_foxhole_db
                            } else {
                                R.string.cli_dataset_source_tor_project
                            },
                        ),
                        options = listOf(
                            CliDropdownOption(
                                id = WIZARD_SOURCE_TOR_PROJECT,
                                label = stringResource(R.string.cli_dataset_source_tor_project),
                            ),
                            CliDropdownOption(
                                id = WIZARD_SOURCE_FOXHOLE_DB,
                                label = stringResource(R.string.cli_dataset_source_foxhole_db),
                            ),
                        ),
                        selectedId =
                        if (bridgesMirror) WIZARD_SOURCE_FOXHOLE_DB else WIZARD_SOURCE_TOR_PROJECT,
                        onSelect = { id -> onBridgesMirrorChange(id == WIZARD_SOURCE_FOXHOLE_DB) },
                        modifier = Modifier.padding(start = CliSpacing.md),
                    )
                }
            }
            CliToggleRow(
                label = stringResource(R.string.cli_wizard_component_i2p),
                checked = i2pEnabled,
                onToggle = onI2pChange,
                infoText = stringResource(R.string.cli_wizard_component_i2p_note),
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

@Composable
private fun WizardAppearancePanel(viewModel: HomeViewModel) {
    val appearance by viewModel.appearanceUiState.collectAsStateWithLifecycle()
    val settingsState by viewModel.settingsRouteState.collectAsStateWithLifecycle()
    CliPanel(title = stringResource(R.string.cli_wizard_appearance_caption), icon = R.drawable.lin_star) {
        val systemLocaleLabel = stringResource(R.string.cli_cfg_locale_system)
        val russianLocaleLabel = stringResource(R.string.cli_cfg_locale_ru)
        val englishLocaleLabel = stringResource(R.string.cli_cfg_locale_en)
        val localeLabel = { locale: AppLocale ->
            when (locale) {
                AppLocale.SYSTEM -> systemLocaleLabel
                AppLocale.RU -> russianLocaleLabel
                AppLocale.EN -> englishLocaleLabel
            }
        }
        val selectedLocale = settingsState.settings.ui.locale
        CliDropdownRow(
            label = stringResource(R.string.cli_cfg_language),
            icon = R.drawable.lin_globe,
            value = localeLabel(selectedLocale),
            options = AppLocale.entries.map { locale ->
                CliDropdownOption(
                    id = locale.name,
                    label = localeLabel(locale),
                    flagCountry = wizardLocaleFlagCountry(locale),
                )
            },
            selectedId = selectedLocale.name,
            onSelect = { id -> viewModel.onLocaleSelected(AppLocale.valueOf(id)) },
            showSelectedOptionIcon = true,
        )
        CliRowDivider()
        val systemLabel = stringResource(R.string.cli_cfg_appearance_system)
        val darkLabel = stringResource(R.string.cli_cfg_appearance_dark)
        val oledLabel = stringResource(R.string.cli_cfg_appearance_oled)
        val lightLabel = stringResource(R.string.cli_cfg_appearance_light)
        val paletteLabel = { selectedThemeMode: ThemeMode ->
            when (selectedThemeMode) {
                ThemeMode.SYSTEM -> systemLabel
                ThemeMode.DARK -> darkLabel
                ThemeMode.OLED -> oledLabel
                ThemeMode.LIGHT -> lightLabel
            }
        }
        CliDropdownRow(
            label = stringResource(R.string.cli_cfg_appearance),
            icon = R.drawable.lin_star,
            value = paletteLabel(appearance.themeMode),
            options = ThemeMode.entries.map { availableThemeMode ->
                CliDropdownOption(id = availableThemeMode.name, label = paletteLabel(availableThemeMode))
            },
            selectedId = appearance.themeMode.name,
            onSelect = { id -> viewModel.onThemeSelected(ThemeMode.valueOf(id)) },
            showSelectedOptionIcon = true,
        )
        CliRowDivider()
        CliToggleRow(
            label = stringResource(R.string.cli_cfg_pixel_art),
            checked = settingsState.settings.ui.pixelArtEnabled,
            onToggle = viewModel::onPixelArtEnabledChanged,
            icon = R.drawable.lin_terminal,
        )
    }
}

private fun wizardLocaleFlagCountry(locale: AppLocale): String? = when (locale) {
    AppLocale.SYSTEM -> null
    AppLocale.RU -> "ru"
    AppLocale.EN -> "gb"
}

@Composable
private fun I2pRelayBanner(i2pEnabled: Boolean, relay: Boolean, onRelayChange: (Boolean) -> Unit) {
    val colors = LocalCliColors.current
    val frame = if (i2pEnabled) {
        Modifier.border(
            1.dp,
            colors.accent,
            androidx.compose.foundation.shape.RoundedCornerShape(CliRadius.pixel),
        )
    } else {
        Modifier.cliMarchingBorder(colors.accent)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(frame)
            .background(colors.panelAlt)
            .padding(CliSpacing.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CliIcon(id = R.drawable.lin_globe, contentDescription = null, size = 16.dp, tint = colors.accent)
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
            enabled = i2pEnabled,
        )
    }
}

@Composable
private fun WizardDataSetsStep(
    geoIp: Boolean,
    onGeoIpChange: (Boolean) -> Unit,
    tlsFingerprints: Boolean,
    onTlsFingerprintsChange: (Boolean) -> Unit,
    dnsFilter: Boolean,
    onDnsFilterChange: (Boolean) -> Unit,
    torBridges: Boolean,
    onTorBridgesChange: (Boolean) -> Unit,
    threatIntel: Boolean,
    onThreatIntelChange: (Boolean) -> Unit,
    autoUpdate: Boolean,
    onAutoUpdateChange: (Boolean) -> Unit,
) {
    val colors = LocalCliColors.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        CliPanel(title = stringResource(R.string.cli_wizard_geo_caption), icon = R.drawable.lin_globe) {
            Text(
                text = stringResource(R.string.cli_wizard_geo_body),
                style = CliType.small,
                color = colors.dim,
            )
            Spacer(modifier = Modifier.height(CliSpacing.sm))
            CliToggleRow(
                label = stringResource(R.string.cli_wizard_download_geoip),
                checked = geoIp,
                onToggle = onGeoIpChange,
                infoText = stringResource(R.string.cli_wizard_geo_later_note),
            )
            CliToggleRow(
                label = stringResource(R.string.cli_wizard_download_tls),
                checked = tlsFingerprints,
                onToggle = onTlsFingerprintsChange,
                infoText = stringResource(R.string.cli_wizard_download_tls_note),
            )
            CliToggleRow(
                label = stringResource(R.string.cli_wizard_download_dns),
                checked = dnsFilter,
                onToggle = onDnsFilterChange,
            )
            CliToggleRow(
                label = stringResource(R.string.cli_wizard_download_bridges),
                checked = torBridges,
                onToggle = onTorBridgesChange,
            )
            CliToggleRow(
                label = stringResource(R.string.cli_wizard_download_sentinel),
                checked = threatIntel,
                onToggle = onThreatIntelChange,
            )
            Spacer(modifier = Modifier.height(CliSpacing.xs))
            CliToggleRow(
                label = stringResource(R.string.cli_wizard_download_auto),
                checked = autoUpdate,
                onToggle = onAutoUpdateChange,
                infoText = stringResource(R.string.cli_wizard_download_auto_note),
            )
            Spacer(modifier = Modifier.height(CliSpacing.xs))
            Text(
                text = stringResource(R.string.cli_wizard_download_skip_note),
                style = CliType.small,
                color = colors.faint,
            )
        }
    }
}

@Composable
private fun WizardDownloadStep(
    viewModel: HomeViewModel,
    progress: OnboardingProgress,
    dnsFilter: Boolean,
    torBridges: Boolean,
    geoIp: Boolean,
    threatIntel: Boolean,
    tlsFingerprints: Boolean,
) {
    val colors = LocalCliColors.current
    LaunchedEffect(progress.running, progress.finished, progress.items) {
        if (!progress.running && !progress.finished && progress.items.isEmpty()) {
            viewModel.onOnboardingDownload(
                dnsFilter = dnsFilter,
                torBridges = torBridges,
                geoIp = geoIp,
                threatIntel = threatIntel,
                tlsFingerprints = tlsFingerprints,
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        CliPanel(title = stringResource(R.string.cli_wizard_download_caption), icon = R.drawable.lin_dns) {
            Text(
                text = stringResource(R.string.cli_wizard_download_intro),
                style = CliType.small,
                color = colors.dim,
            )
            Spacer(modifier = Modifier.height(CliSpacing.sm))
            WizardProgressBar(
                fraction = progress.fraction,
                verified = progress.finished && progress.items.isNotEmpty() && progress.items.none { it.failed },
            )
            Spacer(modifier = Modifier.height(CliSpacing.xs))
            progress.items.forEach { item -> WizardDownloadRow(item) }
            if (progress.items.isEmpty()) {
                if (dnsFilter) {
                    WizardPlannedRow(stringResource(R.string.cli_wizard_download_dns))
                }
                if (torBridges) {
                    WizardPlannedRow(stringResource(R.string.cli_wizard_download_bridges))
                }
                if (geoIp) {
                    WizardPlannedRow(stringResource(R.string.cli_wizard_download_geoip))
                }
                if (tlsFingerprints) {
                    WizardPlannedRow(stringResource(R.string.cli_wizard_download_tls))
                }
                if (threatIntel) {
                    WizardPlannedRow(stringResource(R.string.cli_wizard_download_sentinel))
                }
                if (listOf(dnsFilter, torBridges, geoIp, threatIntel, tlsFingerprints).none()) {
                    Text(
                        text = stringResource(R.string.cli_wizard_download_none),
                        style = CliType.small,
                        color = colors.dim,
                    )
                }
            }
        }
    }
}

@Composable
private fun WizardProgressBar(
    fraction: Float,
    verified: Boolean,
) {
    val colors = LocalCliColors.current
    CliPixelProgressBar(
        fraction = fraction,
        color = if (verified) colors.ok else colors.accent,
    )
}

@Composable
private fun WizardDownloadRow(state: OnboardingDownloadState) {
    val colors = LocalCliColors.current
    val label = when (state.item) {
        OnboardingDownload.DNS_FILTER -> stringResource(R.string.cli_wizard_download_dns)
        OnboardingDownload.TOR_BRIDGES -> stringResource(R.string.cli_wizard_download_bridges)
        OnboardingDownload.GEOIP -> stringResource(R.string.cli_wizard_download_geoip)
        OnboardingDownload.THREAT_INTEL -> stringResource(R.string.cli_wizard_download_sentinel)
        OnboardingDownload.TLS_FINGERPRINTS -> stringResource(R.string.cli_wizard_download_tls)
    }
    val status = wizardDownloadStatus(state)
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
private fun wizardDownloadStatus(state: OnboardingDownloadState): String =
    when {
        state.failed -> stringResource(R.string.cli_wizard_phase_failed)
        state.done -> stringResource(R.string.cli_wizard_phase_done)
        state.phase == RemoteUpdatePhase.CHECKING -> stringResource(R.string.cli_wizard_phase_checking)
        state.phase == RemoteUpdatePhase.DOWNLOADING && state.totalBytes > 0L ->
            stringResource(
                R.string.cli_wizard_phase_downloading_percent,
                (state.downloadFraction * 100f).toInt().coerceIn(0, 100),
            )
        state.phase == RemoteUpdatePhase.DOWNLOADING -> stringResource(R.string.cli_wizard_phase_downloading)
        state.phase == RemoteUpdatePhase.VERIFYING -> stringResource(R.string.cli_wizard_phase_verifying)
        else -> "—"
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
    onBack: () -> Unit,
    onContinue: () -> Unit,
    onSkipSetup: () -> Unit,
    onSkipData: () -> Unit,
) {
    val colors = LocalCliColors.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(CliSpacing.sm),
        ) {
            if (step == LICENSE_STEP) {
                CliButton(
                    label = stringResource(R.string.cli_wizard_skip),
                    onClick = onSkipSetup,
                    modifier = Modifier.weight(1f),
                    color = colors.dim,
                    enabled = true,
                    dimWhenDisabled = false,
                )
            } else if (step in 1..2) {
                CliButton(
                    label = stringResource(R.string.cli_wizard_back),
                    onClick = onBack,
                    modifier = Modifier.weight(1f),
                )
            }
            CliButton(
                label = stringResource(
                    if (step == DOWNLOAD_STEP) R.string.cli_wizard_finish else R.string.cli_wizard_continue,
                ),
                onClick = onContinue,
                modifier = Modifier.weight(1f),
                enabled = step == LICENSE_STEP || canContinue,
                dimWhenDisabled = step != LICENSE_STEP,
                color = colors.ok,
            )
        }
        Spacer(modifier = Modifier.height(CliSpacing.xs))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(WIZARD_SECOND_ACTION_HEIGHT),
        ) {
            if (step == DATA_STEP) {
                CliButton(
                    label = stringResource(R.string.cli_wizard_geo_skip),
                    onClick = onSkipData,
                    modifier = Modifier.fillMaxSize(),
                    color = colors.dim,
                    enabled = canContinue,
                    dimWhenDisabled = false,
                )
            }
        }
    }
}

private data class OnboardingWizardChoices(
    val licenseAccepted: Boolean = false,
    val torEnabled: Boolean = false,
    val torBridges: Boolean = false,
    val bridgesMirror: Boolean = false,
    val sentinelEnabled: Boolean = false,
    val i2pEnabled: Boolean = false,
    val i2pRelay: Boolean = false,
    val dnsFilterEnabled: Boolean = false,
    val geoDownload: Boolean = true,
    val tlsFingerprintDownload: Boolean = true,
    val dnsDownload: Boolean = false,
    val torBridgesDownload: Boolean = false,
    val threatIntelDownload: Boolean = false,
    val autoUpdate: Boolean = true,
) {
    fun withoutDownloads(): OnboardingWizardChoices = copy(
        geoDownload = false,
        tlsFingerprintDownload = false,
        dnsDownload = false,
        torBridgesDownload = false,
        threatIntelDownload = false,
    )
}

private val OnboardingWizardChoicesSaver: Saver<OnboardingWizardChoices, Any> =
    listSaver(
        save = { value ->
            listOf(
                value.licenseAccepted,
                value.torEnabled,
                value.torBridges,
                value.bridgesMirror,
                value.i2pEnabled,
                value.i2pRelay,
                value.dnsFilterEnabled,
                value.geoDownload,
                value.dnsDownload,
                value.torBridgesDownload,
                value.threatIntelDownload,
                value.autoUpdate,
                value.sentinelEnabled,
                value.tlsFingerprintDownload,
            )
        },
        restore = { saved ->
            val defaults = OnboardingWizardChoices()
            OnboardingWizardChoices(
                licenseAccepted = saved.booleanAt(0, defaults.licenseAccepted),
                torEnabled = saved.booleanAt(1, defaults.torEnabled),
                torBridges = saved.booleanAt(2, defaults.torBridges),
                bridgesMirror = saved.booleanAt(3, defaults.bridgesMirror),
                i2pEnabled = saved.booleanAt(4, defaults.i2pEnabled),
                i2pRelay = saved.booleanAt(5, defaults.i2pRelay),
                dnsFilterEnabled = saved.booleanAt(6, defaults.dnsFilterEnabled),
                geoDownload = saved.booleanAt(7, defaults.geoDownload),
                dnsDownload = saved.booleanAt(8, defaults.dnsDownload),
                torBridgesDownload = saved.booleanAt(9, defaults.torBridgesDownload),
                threatIntelDownload = saved.booleanAt(10, defaults.threatIntelDownload),
                autoUpdate = saved.booleanAt(11, defaults.autoUpdate),
                sentinelEnabled = saved.booleanAt(12, defaults.sentinelEnabled),
                tlsFingerprintDownload = saved.booleanAt(13, defaults.tlsFingerprintDownload),
            )
        },
    )

private fun List<Any>.booleanAt(index: Int, default: Boolean): Boolean =
    getOrNull(index) as? Boolean ?: default

private fun wizardCanContinue(
    step: Int,
    choices: OnboardingWizardChoices,
    progress: OnboardingProgress,
): Boolean = when (step) {
    LICENSE_STEP -> choices.licenseAccepted
    DOWNLOAD_STEP -> progress.finished
    else -> true
}

private fun HomeViewModel.startOnboardingDownloads(choices: OnboardingWizardChoices) {
    onOnboardingDownload(
        dnsFilter = choices.dnsDownload,
        torBridges = choices.torBridgesDownload,
        geoIp = choices.geoDownload,
        threatIntel = choices.threatIntelDownload,
        tlsFingerprints = choices.tlsFingerprintDownload,
    )
}

private fun HomeViewModel.finishOnboarding(choices: OnboardingWizardChoices) {
    onOnboardingFinished(
        torEnabled = choices.torEnabled,
        torBridges = choices.torBridges,
        bridgesFoxholeMirror = choices.bridgesMirror,
        sentinelEnabled = choices.sentinelEnabled,
        i2pEnabled = choices.i2pEnabled,
        i2pRelay = choices.i2pRelay,
        dnsFilterEnabled = choices.dnsFilterEnabled,
        autoUpdate = choices.autoUpdate,
    )
}

private val WIZARD_HERO_SIZE = 72.dp
private const val LICENSE_STEP = 0
private const val COMPONENTS_STEP = 1
private const val DATA_STEP = 2
private const val DOWNLOAD_STEP = 3
private const val WIZARD_SOURCE_TOR_PROJECT = "tor-project"
private const val WIZARD_SOURCE_FOXHOLE_DB = "foxhole-db"
private val WIZARD_SECOND_ACTION_HEIGHT = 48.dp
