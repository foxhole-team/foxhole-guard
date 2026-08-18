package com.foxhole.guard.ui.cli.map

import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import com.foxhole.guard.ui.cli.components.CliChromeTailSpacer
import com.foxhole.guard.ui.cli.components.CliFlagIcon
import com.foxhole.guard.ui.cli.components.CliGlassHeaderScreen
import com.foxhole.guard.ui.cli.components.CliPanel
import com.foxhole.guard.ui.cli.components.CliPixIcon
import com.foxhole.guard.ui.cli.components.CliRowDivider
import com.foxhole.guard.ui.cli.components.CliScreenHeader
import com.foxhole.guard.ui.cli.components.cliPressable
import com.foxhole.guard.ui.cli.profiles.profileCountryCode
import com.foxhole.guard.ui.cli.settings.CliFoxholeDbUpdateSheet
import com.foxhole.guard.ui.confirmedTorIdentityOrNull
import com.foxhole.guard.ui.onComponentAutoUpdateChanged
import com.foxhole.guard.ui.onGeoIpDatabaseUpdateRequested
import com.foxhole.guard.ui.onTrafficMapEnabledChanged
import com.foxhole.guard.ui.refreshGeoIpDatabaseInfo
import java.util.Locale

@Composable
internal fun CliMapScreen(
    viewModel: HomeViewModel,
    modifier: Modifier = Modifier,
) {
    val map by viewModel.trafficMapUiState.collectAsStateWithLifecycle()
    val home by viewModel.homeRouteState.collectAsStateWithLifecycle()
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

@Composable
@Suppress("LongMethod")
internal fun CliMapContent(
    map: TrafficMapUiState,
    home: HomeRouteUiState,
    mapEnabled: Boolean,
    onEnableMap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCliColors.current
    CliGlassHeaderScreen(
        modifier = modifier,
        header = {
            CliScreenHeader(label = stringResource(R.string.cli_dock_map), icon = R.drawable.pix_map)
        },
    ) { topInset ->
        if (!mapEnabled) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = CliSpacing.md, top = topInset, end = CliSpacing.md),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(R.string.cli_map_disabled),
                        style = CliType.body,
                        color = colors.dim,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(modifier = Modifier.height(CliSpacing.sm))
                    CliButton(
                        label = stringResource(R.string.cli_common_btn_enable),
                        onClick = onEnableMap,
                    )
                }
            }
            return@CliGlassHeaderScreen
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = CliSpacing.md),
        ) {
            Spacer(modifier = Modifier.height(topInset))
            val snapshot = map.periodSnapshots.snapshot(TrafficMapPeriod.SESSION)
            val routeMode = remember(home.settings, home.connection) {
                cliRouteMode(home.settings, home.connection)
            }
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

            val fanSource = liveRoute.torExit ?: liveRoute.vpnRoute
            val fanRole = when {
                liveRoute.torExit != null -> TrafficMapEdgeRole.TOR_DESTINATION
                liveRoute.vpnRoute != null -> TrafficMapEdgeRole.VPN_DESTINATION
                else -> TrafficMapEdgeRole.DIRECT
            }
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

            val originVisible = map.isAvailable || map.originCountryCode != null
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
                CliRouteScheme(
                    state = map,
                    mode = routeMode,
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

            CliMapCountriesPanel(snapshot = snapshot, map = map)
            CliChromeTailSpacer()
        }
    }
}

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
        if (!countryCode.isNullOrBlank()) {
            Spacer(modifier = Modifier.width(CliSpacing.xs))
            CliFlagIcon(countryCode = countryCode, style = CliType.small)
        }
    }
}

@Composable
private fun CliMapCountriesPanel(
    snapshot: com.foxhole.core.model.TrafficMapPeriodSnapshot,
    map: TrafficMapUiState,
) {
    val colors = LocalCliColors.current
    val configuration = LocalConfiguration.current
    val locale = configuration.locales[0]
    var selectedCountryCode by rememberSaveable { mutableStateOf<String?>(null) }
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
                    onTap = { selectedCountryCode = point.countryCode },
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
    selectedCountryCode?.let { code ->
        val normalized = code.trim().uppercase(Locale.US)
        val point = snapshot.destinations.firstOrNull { destination ->
            destination.countryCode.equals(code, ignoreCase = true)
        }
        CliMapCountrySheet(
            countryCode = normalized,
            label = localizedTrafficMapCountryLabel(
                countryCode = normalized,
                locale = locale,
                fallback = point?.label.orEmpty(),
            ),
            bytes = point?.bytes ?: 0L,
            connections = point?.connections ?: 0,
            detail = map.countryDetailsByCode[normalized],
            journalEnabled = map.networkJournalEnabled,
            onDismiss = { selectedCountryCode = null },
        )
    }
}

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
    onTap: (() -> Unit)? = null,
) {
    val colors = LocalCliColors.current
    val resolvedValueColor = valueColor ?: colors.dim
    val rowModifier = if (onTap != null) {
        Modifier.fillMaxWidth().cliPressable(onClick = onTap)
    } else {
        Modifier.fillMaxWidth()
    }
    Row(modifier = rowModifier, verticalAlignment = Alignment.CenterVertically) {
        if (countryCode != null) {
            CliFlagIcon(countryCode = countryCode)
            Spacer(modifier = Modifier.width(CliSpacing.xs))
        } else {
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

internal fun cliLiveMapRoute(
    map: TrafficMapUiState,
    mode: CliRouteMode,
    confirmedTorCountryCode: String? = null,
): CliLiveMapRoute =
    CliLiveMapRoute(
        vpnRoute = map.vpnRoute.takeIf { mode.engaged && (mode.vpn || mode.proxy) },
        torExit = map.torExit.takeIf { point ->
            mode.engaged &&
                mode.tor &&
                confirmedTorCountryCode != null &&
                point?.countryCode?.equals(confirmedTorCountryCode, ignoreCase = true) == true
        },
        dnsServer = map.dnsServer.takeIf { mode.engaged },
    )

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
