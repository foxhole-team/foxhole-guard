package com.foxhole.guard.ui.cli.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foxhole.core.model.TrafficMapEdge
import com.foxhole.core.model.TrafficMapEdgeRole
import com.foxhole.core.model.TrafficMapPeriod
import com.foxhole.guard.R
import com.foxhole.guard.ui.HomeViewModel
import com.foxhole.guard.ui.cli.CliFormat
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.components.CliButton
import com.foxhole.guard.ui.cli.components.CliFlagIcon
import com.foxhole.guard.ui.cli.components.CliKeyValue
import com.foxhole.guard.ui.cli.components.CliPanel
import com.foxhole.guard.ui.cli.components.CliPixIcon
import com.foxhole.guard.ui.cli.components.CliScreenHeader
import com.foxhole.guard.ui.onTrafficMapEnabledChanged

/**
 * The 16-bit traffic map screen: pixel world map, the active route as a text chain and a
 * per-country traffic table for the selected period.
 */
@Composable
internal fun CliMapScreen(
    viewModel: HomeViewModel,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCliColors.current
    val map by viewModel.trafficMapUiState.collectAsStateWithLifecycle()
    val home by viewModel.homeRouteState.collectAsStateWithLifecycle()
    // The firewall owns a transparent TUN even without a VPN profile. Its live route must stay
    // observable, so the user's optional map visibility switch cannot hide the map while that
    // carrier is active.
    val mapEnabled = home.settings.ui.trafficMapEnabled || home.settings.expert.firewallEnabled

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = CliSpacing.md),
    ) {
        CliScreenHeader(label = stringResource(R.string.cli_dock_map), icon = R.drawable.pix_map)
        if (!mapEnabled) {
            CliPanel(
                icon = R.drawable.pix_map,
                title = stringResource(R.string.cli_map_title),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(R.string.cli_map_disabled),
                    style = CliType.body,
                    color = colors.dim,
                )
                Spacer(modifier = Modifier.height(CliSpacing.sm))
                CliButton(label = stringResource(R.string.cli_common_btn_enable), onClick = {
                    viewModel.onTrafficMapEnabledChanged(true)
                })
            }
            return
        }

        // Session-only by product decision: the map always shows the current connection
        // session; the 5m/24h/7d windows are not part of the CLI frontend.
        val snapshot = map.periodSnapshots.snapshot(TrafficMapPeriod.SESSION)

        // Live edges fan out to LIVE destinations; the map shows the SELECTED period's
        // destinations. Keep the route edges and rebuild the destination fan from the
        // snapshot so lines and markers always agree (mirrors buildTrafficMapEdges).
        val fanSource = map.torExit ?: map.vpnRoute
        val fanRole = when {
            map.torExit != null -> TrafficMapEdgeRole.TOR_DESTINATION
            map.vpnRoute != null -> TrafficMapEdgeRole.VPN_DESTINATION
            else -> TrafficMapEdgeRole.DIRECT
        }
        // The map emits at ~1 Hz; without remember a fresh List each tick forced CliPixelMap into
        // a full redraw even with unchanged edges.
        val periodEdges = remember(map.edges, snapshot.destinations, fanSource, fanRole) {
            map.edges.filterNot { it.role in DESTINATION_ROLES } +
                snapshot.destinations.map { point ->
                    TrafficMapEdge(
                        fromLat = fanSource?.lat ?: map.originLat,
                        fromLon = fanSource?.lon ?: map.originLon,
                        toLat = point.lat,
                        toLon = point.lon,
                        bytes = point.bytes,
                        role = fanRole,
                    )
                }
        }

        // The "you" dot shows whenever device geo is known, not only with a live runtime.
        val originVisible = map.isAvailable || map.originCountryCode != null
        // The map panel has no caption: the map is recognisable on its own.
        CliPanel(modifier = Modifier.fillMaxWidth()) {
            CliPixelMap(
                origin = map.originLat to map.originLon,
                originAvailable = originVisible,
                vpnRoute = map.vpnRoute,
                torExit = map.torExit,
                dnsServer = map.dnsServer,
                destinations = snapshot.destinations,
                edges = periodEdges,
                modifier = Modifier.fillMaxWidth(),
            )
            CliMapLegend(
                originAvailable = originVisible,
                vpn = map.vpnRoute != null,
                tor = map.torExit != null,
                dns = map.dnsServer != null,
                originCountryCode = map.originCountryCode,
                vpnCountryCode = map.vpnRoute?.countryCode,
                torCountryCode = map.torExit?.countryCode,
                dnsCountryCode = map.dnsServer?.countryCode,
            )
            Spacer(modifier = Modifier.height(CliSpacing.sm))
            // The scheme is classified by the selected mode: `home` is not sampled and changes in
            // the same frame as the setting, so switching modes redraws the route at once instead
            // of waiting for a reconnect.
            val routeMode = remember(home.settings, home.connection) {
                cliRouteMode(home.settings, home.connection)
            }
            CliRouteScheme(
                state = map,
                mode = routeMode,
                exitCountryCode = (home.torIpInfo ?: home.ipInfo)?.countryCode,
            )
        }
        if (snapshot.hasTraffic) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = pluralStringResource(
                    R.plurals.cli_map_session_summary,
                    snapshot.countryCount,
                    snapshot.countryCount,
                    CliFormat.bytes(snapshot.totalBytes),
                    snapshot.totalConnections,
                ),
                style = CliType.small,
                color = colors.dim,
                maxLines = 1,
            )
        }
        Spacer(modifier = Modifier.height(CliSpacing.sm))

        CliMapCountriesPanel(snapshot = snapshot)
        Spacer(modifier = Modifier.height(CliSpacing.sm))
    }
}

/**
 * Marker legend, in the markers' own colours. Only nodes actually present are shown.
 */
@Composable
private fun CliMapLegend(
    originAvailable: Boolean,
    vpn: Boolean,
    tor: Boolean,
    dns: Boolean,
    originCountryCode: String? = null,
    vpnCountryCode: String? = null,
    torCountryCode: String? = null,
    dnsCountryCode: String? = null,
) {
    val colors = LocalCliColors.current
    if (listOf(originAvailable, vpn, tor, dns).none { it }) return
    Spacer(modifier = Modifier.height(6.dp))
    Row(
        horizontalArrangement = Arrangement.spacedBy(CliSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (originAvailable) {
            CliMapLegendItem(
                R.drawable.pix_home,
                stringResource(R.string.cli_map_legend_you),
                colors.fg,
                originCountryCode,
            )
        }
        if (vpn) CliMapLegendItem(R.drawable.pix_shield, "vpn", colors.vpn, vpnCountryCode)
        if (tor) CliMapLegendItem(R.drawable.pix_tor, "tor", colors.tor, torCountryCode)
        if (dns) CliMapLegendItem(R.drawable.pix_dns, "dns", colors.info, dnsCountryCode)
    }
}

@Composable
private fun CliMapLegendItem(
    iconRes: Int,
    label: String,
    tint: Color,
    countryCode: String? = null,
) {
    val colors = LocalCliColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        CliPixIcon(id = iconRes, contentDescription = null, size = 12.dp, tint = tint)
        Spacer(modifier = Modifier.width(CliSpacing.xs))
        Text(text = label, style = CliType.small, color = colors.dim, maxLines = 1)
        // The flag rides after the label so a node without geo simply keeps the plain legend.
        if (!countryCode.isNullOrBlank()) {
            Spacer(modifier = Modifier.width(CliSpacing.xs))
            CliFlagIcon(countryCode = countryCode, style = CliType.small)
        }
    }
}

@Composable
private fun CliMapCountriesPanel(snapshot: com.foxhole.core.model.TrafficMapPeriodSnapshot) {
    val colors = LocalCliColors.current
    CliPanel(
        icon = R.drawable.pix_globe,
        title = stringResource(R.string.cli_common_countries_title),
        modifier = Modifier.fillMaxWidth()
    ) {
        if (!snapshot.hasTraffic) {
            Text(
                text = stringResource(R.string.cli_common_empty_run_traffic),
                style = CliType.body,
                color = colors.dim,
            )
        } else {
            CliKeyValue(
                key = stringResource(R.string.cli_map_key_total),
                value = "${CliFormat.bytes(snapshot.totalBytes)} · ${snapshot.totalConnections} conn",
                valueColor = colors.accent,
            )
            val topCountries = snapshot.destinations
                .sortedByDescending { it.bytes }
                .take(8)
            topCountries.forEach { point ->
                // Country row: pixel flag in native colours, ISO code, city.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CliFlagIcon(countryCode = point.countryCode)
                    Spacer(modifier = Modifier.width(CliSpacing.xs))
                    Text(
                        text = "${point.countryCode.uppercase()} ${point.label}".trim(),
                        style = CliType.body,
                        color = colors.fg,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "${CliFormat.bytes(point.bytes)} · ${point.connections}",
                        style = CliType.body,
                        color = colors.dim,
                    )
                }
            }
            if (snapshot.hiddenCountryCount > 0) {
                Text(
                    text = pluralStringResource(
                        R.plurals.cli_map_more_countries,
                        snapshot.hiddenCountryCount,
                        snapshot.hiddenCountryCount,
                    ),
                    style = CliType.small,
                    color = colors.faint,
                )
            }
        }
    }
}

// Destination roles: live edges of these roles are replaced by the period's fan of dots.
private val DESTINATION_ROLES = setOf(
    TrafficMapEdgeRole.VPN_DESTINATION,
    TrafficMapEdgeRole.TOR_DESTINATION,
    TrafficMapEdgeRole.DIRECT,
)
