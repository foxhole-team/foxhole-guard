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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foxhole.core.model.I2P_TRANSIT_TUNNELS_MAX
import com.foxhole.core.model.I2P_TRANSIT_TUNNELS_MIN
import com.foxhole.core.model.I2pAddressBookEntry
import com.foxhole.core.model.I2pSettings
import com.foxhole.core.model.I2pTransitBandwidth
import com.foxhole.guard.R
import com.foxhole.guard.ui.HomeViewModel
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.components.CliActionRow
import com.foxhole.guard.ui.cli.components.CliDropdownOption
import com.foxhole.guard.ui.cli.components.CliDropdownRow
import com.foxhole.guard.ui.cli.components.CliInfoNote
import com.foxhole.guard.ui.cli.components.CliInputRow
import com.foxhole.guard.ui.cli.components.CliPanel
import com.foxhole.guard.ui.cli.components.CliRoutingChangeConfirmSheet
import com.foxhole.guard.ui.cli.components.CliScreenHeader
import com.foxhole.guard.ui.cli.components.CliToggleRow
import com.foxhole.guard.ui.confirmPendingRoutingScenario
import com.foxhole.guard.ui.dismissPendingRoutingScenario
import com.foxhole.guard.ui.i2pDestinationValidationErrorRes
import com.foxhole.guard.ui.i2pHostValidationErrorRes
import com.foxhole.guard.ui.normalizedI2pHostInput
import com.foxhole.guard.ui.onI2pAddressBookEntryDeleted
import com.foxhole.guard.ui.onI2pAddressBookEntrySaved
import com.foxhole.guard.ui.onI2pAllowRelayOnCellularChanged
import com.foxhole.guard.ui.onI2pAutoReconnectChanged
import com.foxhole.guard.ui.onI2pEngagedChanged
import com.foxhole.guard.ui.onI2pRelayTransitTrafficChanged
import com.foxhole.guard.ui.onI2pTransitBandwidthSelected
import com.foxhole.guard.ui.onI2pTransitTunnelsLimitSelected

private val I2P_TUNNEL_LIMIT_PRESETS = listOf(50, 100, 250, 500, 1_000)

/** I2P settings in the terminal grammar; every control is wired to the existing i2pd runtime. */
@Composable
internal fun CliI2pSubScreen(
    viewModel: HomeViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.settingsRouteState.collectAsStateWithLifecycle()
    val pendingRoutingChange by
        viewModel.pendingRoutingScenarioConfirmation.collectAsStateWithLifecycle()
    val settings = state.settings.i2p

    pendingRoutingChange?.let { change ->
        CliRoutingChangeConfirmSheet(
            change = change,
            onConfirm = viewModel::confirmPendingRoutingScenario,
            onDismiss = viewModel::dismissPendingRoutingScenario,
        )
    }

    Column(
        modifier =
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = CliSpacing.md),
    ) {
        // Back-ряда нет по канону суб-экранов настроек: путь назад — системный back.
        CliScreenHeader(label = "I2P", icon = R.drawable.pix_incognito)
        CliI2pRuntimePanel(viewModel = viewModel, settings = settings)
        Spacer(modifier = Modifier.height(CliSpacing.sm))
        CliI2pAddressBookPanel(viewModel = viewModel, settings = settings)
        Spacer(modifier = Modifier.height(CliSpacing.sm))
    }
}

@Composable
private fun CliI2pRuntimePanel(
    viewModel: HomeViewModel,
    settings: I2pSettings,
) {
    CliPanel(
        title = stringResource(R.string.privacy_route_i2p_group),
        icon = R.drawable.pix_link,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (settings.enabled) {
            CliToggleRow(
                label = stringResource(R.string.cli_i2p_runtime),
                icon = R.drawable.pix_power,
                checked = settings.engaged,
                onToggle = { value -> viewModel.onI2pEngagedChanged(value) },
                note = stringResource(R.string.cli_i2p_runtime_note),
            )
            CliToggleRow(
                label = stringResource(R.string.cli_i2p_auto_reconnect),
                icon = R.drawable.pix_restart,
                checked = settings.autoReconnectAfterVpnDisconnect,
                onToggle = viewModel::onI2pAutoReconnectChanged,
                note = stringResource(R.string.cli_i2p_auto_reconnect_note),
            )
            CliInfoNote(
                text = stringResource(R.string.cli_i2p_direct_in_development_note),
                modifier = Modifier.padding(vertical = CliSpacing.xs),
            )
            CliToggleRow(
                label = stringResource(R.string.i2p_relay_transit_title),
                icon = R.drawable.pix_link,
                checked = settings.relayTransitTraffic,
                onToggle = viewModel::onI2pRelayTransitTrafficChanged,
            )
            if (settings.relayTransitTraffic) {
                CliI2pRelayControls(viewModel = viewModel, settings = settings)
            }
        }
    }
}

@Composable
private fun CliI2pRelayControls(
    viewModel: HomeViewModel,
    settings: I2pSettings,
) {
    var customLimit by rememberSaveable { mutableStateOf("") }
    CliToggleRow(
        label = stringResource(R.string.i2p_relay_cellular_title),
        icon = R.drawable.pix_device,
        checked = settings.allowRelayOnCellular,
        onToggle = viewModel::onI2pAllowRelayOnCellularChanged,
    )
    CliDropdownRow(
        label = stringResource(R.string.i2p_transit_bandwidth_title),
        icon = R.drawable.pix_stats,
        value = i2pBandwidthLabel(settings.transitBandwidth),
        options = I2pTransitBandwidth.entries.map { bandwidth ->
            CliDropdownOption(id = bandwidth.name, label = i2pBandwidthLabel(bandwidth))
        },
        selectedId = settings.transitBandwidth.name,
        onSelect = { id -> viewModel.onI2pTransitBandwidthSelected(I2pTransitBandwidth.valueOf(id)) },
    )
    CliDropdownRow(
        label = stringResource(R.string.i2p_transit_tunnels_title),
        icon = R.drawable.pix_up,
        value = settings.transitTunnelsLimit.toString(),
        options = I2P_TUNNEL_LIMIT_PRESETS.map { limit ->
            CliDropdownOption(id = limit.toString(), label = limit.toString())
        } + CliDropdownOption(CLI_OPT_CUSTOM, stringResource(R.string.cli_common_custom)),
        selectedId = settings.transitTunnelsLimit.toString(),
        onSelect = { id ->
            if (id == CLI_OPT_CUSTOM) {
                customLimit = settings.transitTunnelsLimit.toString()
            } else {
                id.toIntOrNull()?.let(viewModel::onI2pTransitTunnelsLimitSelected)
            }
        },
    )
    if (customLimit.isNotEmpty()) {
        CliInputRow(
            prompt = "limit",
            value = customLimit,
            onValueChange = { value -> customLimit = value.filter(Char::isDigit).take(5) },
            onSubmit = {
                customLimit.toIntOrNull()?.let { value ->
                    viewModel.onI2pTransitTunnelsLimitSelected(
                        value.coerceIn(I2P_TRANSIT_TUNNELS_MIN, I2P_TRANSIT_TUNNELS_MAX),
                    )
                    customLimit = ""
                }
            },
            modifier = Modifier.padding(start = CliSpacing.md),
        )
    }
}

@Composable
private fun CliI2pAddressBookPanel(
    viewModel: HomeViewModel,
    settings: I2pSettings,
) {
    val colors = LocalCliColors.current
    var host by rememberSaveable { mutableStateOf("") }
    var destination by rememberSaveable { mutableStateOf("") }
    var validationError by rememberSaveable { mutableStateOf<Int?>(null) }
    CliPanel(
        icon = R.drawable.pix_incognito,
        title = stringResource(R.string.privacy_route_i2p_addresses_title),
        modifier = Modifier.fillMaxWidth(),
    ) {
        settings.addressBook.forEach { entry ->
            CliActionRow(
                label = entry.host,
                icon = R.drawable.pix_link,
                value = "[x]",
                onTap = { viewModel.onI2pAddressBookEntryDeleted(entry.host) },
            )
        }
        CliInputRow(
            prompt = "host",
            value = host,
            onValueChange = { value ->
                host = value.take(253)
                validationError = null
            },
        )
        CliInputRow(
            prompt = "destination",
            value = destination,
            onValueChange = { value ->
                destination = value.take(616)
                validationError = null
            },
        )
        CliActionRow(
            label = stringResource(R.string.i2p_address_add_title),
            icon = R.drawable.pix_add,
            onTap = {
                val error = i2pHostValidationErrorRes(host) ?: i2pDestinationValidationErrorRes(destination)
                validationError = error
                val normalizedHost = normalizedI2pHostInput(host)
                if (error == null && normalizedHost != null) {
                    viewModel.onI2pAddressBookEntrySaved(
                        originalHost = null,
                        entry = I2pAddressBookEntry(normalizedHost, destination.trim()),
                    )
                    host = ""
                    destination = ""
                }
            },
        )
        validationError?.let { error ->
            Text(text = stringResource(error), style = CliType.small, color = colors.warn)
        }
    }
}

@Composable
private fun i2pBandwidthLabel(value: I2pTransitBandwidth): String =
    stringResource(
        when (value) {
            I2pTransitBandwidth.LOW -> R.string.i2p_transit_bandwidth_low
            I2pTransitBandwidth.STANDARD -> R.string.i2p_transit_bandwidth_standard
            I2pTransitBandwidth.HIGH -> R.string.i2p_transit_bandwidth_high
            I2pTransitBandwidth.UNLIMITED -> R.string.i2p_transit_bandwidth_unlimited
        },
    )
