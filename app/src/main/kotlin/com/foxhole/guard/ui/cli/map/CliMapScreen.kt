package com.foxhole.guard.ui.cli.map

import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foxhole.core.model.ConnectionSnapshot
import com.foxhole.core.model.Profile
import com.foxhole.core.model.TrafficMapEdge
import com.foxhole.core.model.TrafficMapEdgeRole
import com.foxhole.core.model.TrafficMapPeriod
import com.foxhole.core.model.TrafficMapPoint
import com.foxhole.core.model.TrafficMapUiState
import com.foxhole.guard.R
import com.foxhole.guard.ui.HomeRouteUiState
import com.foxhole.guard.ui.HomeViewModel
import com.foxhole.guard.ui.cli.CliFormat
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.components.CliButton
import com.foxhole.guard.ui.cli.components.CliFlagIcon
import com.foxhole.guard.ui.cli.components.CliPanel
import com.foxhole.guard.ui.cli.components.CliPixIcon
import com.foxhole.guard.ui.cli.components.CliRowDivider
import com.foxhole.guard.ui.cli.components.CliScreenHeader
import com.foxhole.guard.ui.cli.profiles.profileCountryCode
import com.foxhole.guard.ui.cli.settings.CliFoxholeDbUpdateSheet
import com.foxhole.guard.ui.confirmedTorIdentityOrNull
import com.foxhole.guard.ui.onComponentAutoUpdateChanged
import com.foxhole.guard.ui.onGeoIpDatabaseUpdateRequested
import com.foxhole.guard.ui.onTrafficMapEnabledChanged
import com.foxhole.guard.ui.refreshGeoIpDatabaseInfo
import java.util.Locale

/**
 * The 16-bit traffic map screen: pixel world map, the active route as a text chain and a
 * per-country traffic table for the selected period.
 *
 * Split into this view-model entry point and [CliMapContent] so the layout can be composed on its
 * own — see the `@Preview`s in the debug source set. Everything that needs the view model (the geo
 * database prompt, the enable switch) stays here.
 */
@Composable
internal fun CliMapScreen(
    viewModel: HomeViewModel,
    modifier: Modifier = Modifier,
) {
    val map by viewModel.trafficMapUiState.collectAsStateWithLifecycle()
    val home by viewModel.homeRouteState.collectAsStateWithLifecycle()
    // The firewall owns a transparent TUN even without a VPN profile. Its live route must stay
    // observable, so the user's optional map visibility switch cannot hide the map while that
    // carrier is active.
    val mapEnabled = home.settings.ui.trafficMapEnabled || home.settings.expert.firewallEnabled

    if (mapEnabled) {
        CliMapGeoPrompt(
            viewModel = viewModel,
            autoUpdateEnabled = home.settings.connection.componentAutoUpdateEnabled &&
                home.settings.connection.componentUpdateCheckEnabled,
        )
    }

    CliMapContent(
        map = map,
        home = home,
        mapEnabled = mapEnabled,
        onEnableMap = { viewModel.onTrafficMapEnabledChanged(true) },
        modifier = modifier,
    )
}

/** The screen's whole layout, over plain state and one callback. */
@Composable
internal fun CliMapContent(
    map: TrafficMapUiState,
    home: HomeRouteUiState,
    mapEnabled: Boolean,
    onEnableMap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCliColors.current
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
                CliButton(label = stringResource(R.string.cli_common_btn_enable), onClick = onEnableMap)
            }
            return
        }

        // Session-only by product decision: the map always shows the current connection
        // session; the 5m/24h/7d windows are not part of the CLI frontend.
        val snapshot = map.periodSnapshots.snapshot(TrafficMapPeriod.SESSION)
        // ConnectionSnapshot classifies the applied route. Geo points arrive asynchronously and
        // may outlive a stopped session, so they provide flags/coordinates but never revive lanes.
        val routeMode = remember(home.settings, home.connection) {
            cliRouteMode(home.settings, home.connection)
        }
        // Smart profiles can switch protocol options without changing Profile.selectedOptionId.
        // The applied snapshot is therefore first, asynchronous runtime geo second, and the stored
        // option/name only the final fallback.
        val vpnCountry =
            remember(home.activeProfile, home.connection, map.vpnRoute?.countryCode) {
                cliMapVpnCountry(
                    profile = home.activeProfile,
                    connection = home.connection,
                    runtimeCountryCode = map.vpnRoute?.countryCode,
                )
            }
        val confirmedTorCountryCode = home.torIpInfo?.confirmedTorIdentityOrNull()?.countryCode
        val liveRoute = remember(
            map.vpnRoute,
            map.torExit,
            map.dnsServer,
            routeMode,
            confirmedTorCountryCode,
        ) {
            cliLiveMapRoute(map, routeMode, confirmedTorCountryCode)
        }

        // Live edges fan out to LIVE destinations; the map shows the SELECTED period's
        // destinations. Keep the route edges and rebuild the destination fan from the
        // snapshot so lines and markers always agree (mirrors buildTrafficMapEdges).
        val fanSource = liveRoute.torExit ?: liveRoute.vpnRoute
        val fanRole = when {
            liveRoute.torExit != null -> TrafficMapEdgeRole.TOR_DESTINATION
            liveRoute.vpnRoute != null -> TrafficMapEdgeRole.VPN_DESTINATION
            else -> TrafficMapEdgeRole.DIRECT
        }
        // The map emits at ~1 Hz; without remember a fresh List each tick forced CliPixelMap into
        // a full redraw even with unchanged edges.
        val periodEdges = remember(map.edges, snapshot.destinations, fanSource, fanRole) {
            map.edges.filter { edge -> edge.visibleFor(liveRoute) } +
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
                vpnRoute = liveRoute.vpnRoute,
                torExit = liveRoute.torExit,
                dnsServer = liveRoute.dnsServer,
                destinations = snapshot.destinations,
                edges = periodEdges,
                modifier = Modifier.fillMaxWidth(),
            )
            CliMapLegend(
                originAvailable = originVisible,
                vpn = routeMode.vpn || routeMode.proxy,
                tor = routeMode.tor,
                dns = liveRoute.dnsServer != null,
                originCountryCode = map.originCountryCode,
                vpnCountryCode = vpnCountry,
                torCountryCode = liveRoute.torExit?.countryCode,
                dnsCountryCode = liveRoute.dnsServer?.countryCode,
            )
            Spacer(modifier = Modifier.height(CliSpacing.sm))
            // The scheme is classified by the applied connection snapshot, not retained map geo.
            // The identities stay split: mixing them (torIpInfo ?: ipInfo) fed every lane the
            // same country, and the VPN lane wore the Tor exit flag whenever Tor was engaged.
            CliRouteScheme(
                state = map,
                mode = routeMode,
                // The traffic-map sampler may not have emitted an origin yet (notably when
                // VPN+Tor and I2P become active together). The controller's physical-network
                // identity is already generation-fenced and is the honest source for both the
                // device and the detached, unprotected branch.
                deviceCountryCode = home.deviceIpInfo?.countryCode,
                vpnHopCountryCode = vpnCountry,
                vpnExitCountryCode = home.ipInfo?.countryCode.takeIf { routeMode.engaged },
                torExitCountryCode = confirmedTorCountryCode.takeIf { routeMode.tor },
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
 * Countries need the FoxHole DB geo group, which is not bundled: entering the map without it
 * offers the download once per visit — declined stays declined until the next entry.
 */
@Composable
private fun CliMapGeoPrompt(
    viewModel: HomeViewModel,
    autoUpdateEnabled: Boolean,
) {
    val geoIp by viewModel.geoIpDatabaseUiState.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refreshGeoIpDatabaseInfo() }
    var dismissed by rememberSaveable { mutableStateOf(false) }
    val missing = geoIp.info != null && geoIp.info?.installed == null
    if (!missing || dismissed) return
    CliFoxholeDbUpdateSheet(
        groupLabel = stringResource(R.string.cli_foxdb_group_geo),
        autoUpdateEnabled = autoUpdateEnabled,
        onAutoUpdateChange = viewModel::onComponentAutoUpdateChanged,
        onDownload = viewModel::onGeoIpDatabaseUpdateRequested,
        onDismiss = { dismissed = true },
        phase = geoIp.phase,
    )
}

/**
 * Marker legend, in the markers' own colours. Only nodes actually present are shown.
 *
 * The row is laid out even while empty, on the same reasoning as the reserved flag slot in
 * [CliRouteScheme]: geo arrives after the first frame (the state starts as an all-null
 * `TrafficMapUiState` and the map flow is sampled at ~1 Hz), and materialising the whole row at
 * that moment shoved the route scheme and the country table down under the user's finger.
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
    Spacer(modifier = Modifier.height(6.dp))
    Row(
        // heightIn, not height: the slot must still grow with the system font scale rather than
        // clip the labels on a device that runs large text.
        modifier = Modifier.heightIn(min = LEGEND_ROW_HEIGHT),
        horizontalArrangement = Arrangement.spacedBy(CliSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (originAvailable) {
            CliMapLegendItem(
                R.drawable.pix_home,
                rememberDeviceLegendLabel(),
                colors.fg,
                originCountryCode,
            )
        }
        if (vpn) CliMapLegendItem(R.drawable.pix_shield, "vpn", colors.vpn, vpnCountryCode)
        if (tor) CliMapLegendItem(R.drawable.pix_tor, "tor", colors.tor, torCountryCode)
        if (dns) CliMapLegendItem(R.drawable.pix_dns, "dns", colors.info, dnsCountryCode)
    }
}

/**
 * The origin marker is labelled with the device's own name (the user-set one from system
 * settings, model as the fallback), lowercased into the legend's vocabulary and capped so a
 * verbose name cannot push the other legend items off the row.
 */
@Composable
private fun rememberDeviceLegendLabel(): String {
    val context = LocalContext.current
    return remember {
        val configured = Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
        (configured?.takeIf(String::isNotBlank) ?: Build.MODEL)
            .lowercase()
            .take(DEVICE_LEGEND_MAX_CHARS)
    }
}

private const val DEVICE_LEGEND_MAX_CHARS = 24

// One legend item: a 12.dp pixel icon beside a CliType.small line (16.sp).
private val LEGEND_ROW_HEIGHT = 16.dp

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
        Text(
            text = label,
            style = CliType.small,
            color = colors.dim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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
    // LocalConfiguration is app-locale aware and invalidates composition on an in-app language
    // switch. The repository's geo labels may have been cached under the previous locale, so the
    // visible table resolves ISO names here from the current configuration instead of trusting a
    // stale label stored in the traffic snapshot.
    val configuration = LocalConfiguration.current
    // Android guarantees at least one locale in Configuration; reading it through
    // LocalConfiguration keeps the table observable when the in-app language changes.
    val locale = configuration.locales[0]
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
            CliMapCountryTableHeader()
            CliRowDivider(modifier = Modifier.padding(vertical = CliSpacing.xs))
            CliMapCountryTableRow(
                countryCode = null,
                label = stringResource(R.string.cli_map_key_total),
                bytes = snapshot.totalBytes,
                connections = snapshot.totalConnections,
                valueColor = colors.info,
            )
            val topCountries = snapshot.destinations
                .sortedByDescending { it.bytes }
                .take(8)
            topCountries.forEach { point ->
                CliRowDivider(modifier = Modifier.padding(vertical = CliSpacing.xs))
                CliMapCountryTableRow(
                    countryCode = point.countryCode,
                    label = localizedTrafficMapCountryLabel(
                        countryCode = point.countryCode,
                        locale = locale,
                        fallback = point.label,
                    ),
                    bytes = point.bytes,
                    connections = point.connections,
                )
            }
            if (snapshot.hiddenCountryCount > 0) {
                CliRowDivider(modifier = Modifier.padding(vertical = CliSpacing.xs))
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

/** Canonical three-column caption: country identity, traffic volume and connection count. */
@Composable
private fun CliMapCountryTableHeader() {
    val colors = LocalCliColors.current
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(R.string.cli_map_column_country),
            style = CliType.small,
            color = colors.dim,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(R.string.cli_map_column_traffic),
            style = CliType.small,
            color = colors.dim,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.width(COUNTRY_TRAFFIC_COLUMN_WIDTH),
        )
        Spacer(modifier = Modifier.width(CliSpacing.sm))
        Text(
            text = stringResource(R.string.cli_map_column_connections),
            style = CliType.small,
            color = colors.dim,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.width(COUNTRY_CONNECTIONS_COLUMN_WIDTH),
        )
    }
}

@Composable
private fun CliMapCountryTableRow(
    countryCode: String?,
    label: String,
    bytes: Long,
    connections: Int,
    valueColor: Color? = null,
) {
    val colors = LocalCliColors.current
    val resolvedValueColor = valueColor ?: colors.dim
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (countryCode != null) {
            CliFlagIcon(countryCode = countryCode)
            Spacer(modifier = Modifier.width(CliSpacing.xs))
        } else {
            // Same footprint as a flag: the total row and country rows share exact columns.
            Spacer(modifier = Modifier.width(COUNTRY_FLAG_SLOT_WIDTH))
        }
        Text(
            text = if (countryCode == null) label else "${countryCode.uppercase(Locale.US)} · $label",
            style = CliType.body,
            color = if (countryCode == null) colors.info else colors.fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = CliFormat.bytes(bytes),
            style = CliType.body,
            color = resolvedValueColor,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.width(COUNTRY_TRAFFIC_COLUMN_WIDTH),
        )
        Spacer(modifier = Modifier.width(CliSpacing.sm))
        Text(
            text = connections.toString(),
            style = CliType.body,
            color = resolvedValueColor,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.width(COUNTRY_CONNECTIONS_COLUMN_WIDTH),
        )
    }
}

/** Locale-safe ISO display name, resolved at render time so RU↔EN changes cannot stay cached. */
internal fun localizedTrafficMapCountryLabel(
    countryCode: String,
    locale: Locale,
    fallback: String,
): String {
    val normalized = countryCode.trim().uppercase(Locale.US)
    if (normalized.length != 2 || normalized.any { character -> character !in 'A'..'Z' }) {
        return fallback.ifBlank { normalized }
    }
    val localized = runCatching {
        Locale.Builder()
            .setRegion(normalized)
            .build()
            .getDisplayCountry(locale)
            .trim()
    }.getOrNull()
    return localized
        ?.takeIf { label -> label.isNotEmpty() && !label.equals(normalized, ignoreCase = true) }
        ?: fallback.ifBlank { normalized }
}

internal data class CliLiveMapRoute(
    val vpnRoute: TrafficMapPoint?,
    val torExit: TrafficMapPoint?,
    val dnsServer: TrafficMapPoint?,
)

/** Historical geo samples stay available to statistics, but cannot resurrect a stopped route. */
internal fun cliLiveMapRoute(
    map: TrafficMapUiState,
    mode: CliRouteMode,
    confirmedTorCountryCode: String? = null,
): CliLiveMapRoute =
    CliLiveMapRoute(
        vpnRoute = map.vpnRoute.takeIf { mode.engaged && (mode.vpn || mode.proxy) },
        // A retained traffic-map point cannot prove Tor identity by itself. Show it only while the
        // dedicated authenticated Tor probe confirms the same country for the current route.
        torExit = map.torExit.takeIf { point ->
            mode.engaged &&
                mode.tor &&
                confirmedTorCountryCode != null &&
                point?.countryCode?.equals(confirmedTorCountryCode, ignoreCase = true) == true
        },
        dnsServer = map.dnsServer.takeIf { mode.engaged },
    )

/** Country of the actually running smart-profile option, with stable fallbacks while geo loads. */
internal fun cliMapVpnCountry(
    profile: Profile?,
    connection: ConnectionSnapshot,
    runtimeCountryCode: String?,
): String? {
    val liveOptionName =
        profile
            ?.takeIf { active -> active.id == connection.profileId }
            ?.protocolOptions
            ?.firstOrNull { option -> option.id == connection.protocolOptionId }
            ?.displayName
    val storedOption =
        profile?.protocolOptions
            ?.firstOrNull { option -> option.id == profile.selectedProtocolOptionId }
            ?: profile?.protocolOptions?.firstOrNull { option -> option.isSelected }
            ?: profile?.protocolOptions?.firstOrNull()
    return cliVpnHopCountry(
        profileCountryCode(liveOptionName),
        runtimeCountryCode,
        profileCountryCode(storedOption?.displayName, profile?.name),
    )
}

private fun TrafficMapEdge.visibleFor(route: CliLiveMapRoute): Boolean =
    when (role) {
        TrafficMapEdgeRole.VPN_ROUTE -> route.vpnRoute != null
        TrafficMapEdgeRole.TOR_ROUTE -> route.torExit != null
        TrafficMapEdgeRole.VPN_DESTINATION,
        TrafficMapEdgeRole.TOR_DESTINATION,
        TrafficMapEdgeRole.DIRECT,
        -> false
    }

private val COUNTRY_TRAFFIC_COLUMN_WIDTH = 74.dp
private val COUNTRY_CONNECTIONS_COLUMN_WIDTH = 44.dp
private val COUNTRY_FLAG_SLOT_WIDTH = 20.dp
