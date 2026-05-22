package com.foxhole.beta.ui

import androidx.compose.animation.core.InfiniteTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.StartOffsetType
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.LocationCity
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.QueryStats
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.core.os.ConfigurationCompat
import com.foxhole.beta.R
import com.foxhole.beta.core.model.ConnectionSnapshot
import com.foxhole.beta.core.model.ConnectionState
import com.foxhole.beta.core.model.InstalledAppOption
import com.foxhole.beta.core.model.IpInfo
import com.foxhole.beta.core.model.LocalAuthSettings
import com.foxhole.beta.core.model.PrivacyRouteMode
import com.foxhole.beta.core.model.PrivacyRouteScope
import com.foxhole.beta.core.model.Profile
import com.foxhole.beta.core.model.ProtocolHint
import com.foxhole.beta.core.model.TrafficMode
import com.foxhole.beta.core.model.isUdpTransport
import com.foxhole.beta.vpn.ACTIVE_CONNECTION_STATES
import com.foxhole.beta.vpn.FoxholeVpnService
import com.foxhole.beta.vpn.LocalGuardMode
import com.foxhole.beta.vpn.localGuardModeOrNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.Locale

internal val HomePrimaryActionHeight = 56.dp
internal val HomeTriangleIndicatorSize = 15.dp
private val HomeTriangleIndicatorCanvasBleed = 2.dp
internal val HomeDashboardBannerTopPadding = 74.dp
internal val HomeConnectingStatusSignalOffset = 2.dp
internal val HomeNetworkContentHeight = 96.dp
internal val HomeDashboardProfileContentHeight = 62.dp
private val HomeNetworkValueLoadingWidth = 68.dp
private val HomeNetworkMetricValueLoadingWidth = 54.dp
private val HomeModeSelectorMinWidth = 58.dp
private val HomeModeSelectorMaxWidth = 104.dp
private val HomeNetworkMetricValueLoadingHeight = 12.dp
internal const val HOME_PROFILE_LOADING_TAG = "home_profile_loading"

@Composable
internal fun HomeCardHeader(
    icon: ImageVector,
    title: String,
    titleContent: (@Composable RowScope.() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.56f),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier =
                    Modifier
                        .padding(5.dp)
                        .size(16.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        if (titleContent != null) {
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = titleContent,
            )
        } else {
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        trailing()
    }
}

@Composable
internal fun HomeHeaderActionButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.primary,
) {
    Surface(
        modifier =
            modifier
                .size(32.dp)
                .clip(CircleShape)
                .clickable(enabled = enabled) { onClick() },
        shape = CircleShape,
        color = tint.copy(alpha = if (enabled) 0.14f else 0.06f),
        border = BorderStroke(1.dp, tint.copy(alpha = if (enabled) 0.34f else 0.14f)),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(18.dp),
                tint = tint.copy(alpha = if (enabled) 1f else 0.38f),
            )
        }
    }
}

@Composable
internal fun HomeNetworkDetailLine(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    valueMonospace: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon?.let {
                Icon(
                    imageVector = it,
                    contentDescription = null,
                    modifier = Modifier.size(13.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
                )
            }
            Text(
                text = label,
                modifier = Modifier.weight(1f),
                style =
                    MaterialTheme.typography.labelSmall.copy(
                        fontSize = 11.sp,
                        lineHeight = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = value,
            modifier = modifier.weight(1f),
            style =
                MaterialTheme.typography.labelSmall.copy(
                    fontSize = 11.sp,
                    lineHeight = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = if (valueMonospace) FontFamily.Monospace else FontFamily.Default,
                ),
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun HomeNetworkSubtleDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.18f))
}

@Composable
internal fun HomeNetworkVerticalDivider(modifier: Modifier = Modifier) {
    Box(
        modifier =
            modifier
                .width(1.dp)
                .height(HomeNetworkContentHeight)
                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.14f)),
    )
}

@Composable
internal fun HomeNetworkColumnTitle(text: String) {
    Text(
        text = text,
        style =
            MaterialTheme.typography.labelSmall.copy(
                fontSize = 10.sp,
                lineHeight = 12.sp,
                fontWeight = FontWeight.Bold,
            ),
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
internal fun HomeModeDropdown(
    selected: HomeModeOption,
    onSelect: (HomeModeOption) -> Unit,
    modifier: Modifier = Modifier,
    values: List<HomeModeOption> = HomeModeOption.entries,
) {
    val selectorTint = MaterialTheme.colorScheme.primary
    var expanded by rememberSaveable(selected) { mutableStateOf(false) }
    val menuLabels = values.map { option -> homeModeMenuLabel(option) }
    val selectedLabel = homeModeChipLabel(selected)
    val selectorWidth = rememberHomeModeSelectorWidth(selectedLabel)
    val menuWidth =
        rememberFoxholeDropdownMenuWidth(
            labels = menuLabels,
            textStyle = MaterialTheme.typography.bodyMedium,
            hasIcons = true,
            minWidth = 112.dp,
            maxWidth = 240.dp,
        )
    Box(modifier = modifier) {
        Surface(
            modifier =
                Modifier
                    .testTag("home_mode_selector")
                    .width(selectorWidth)
                    .clip(MaterialTheme.shapes.small)
                    .clickable { expanded = true },
            shape = MaterialTheme.shapes.small,
            color = selectorTint.copy(alpha = 0.16f),
            border = BorderStroke(1.dp, selectorTint.copy(alpha = 0.28f)),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = homeModeOptionIcon(selected),
                    contentDescription = null,
                    modifier = Modifier.size(11.dp),
                    tint = selectorTint,
                )
                Text(
                    text = selectedLabel,
                    style =
                        MaterialTheme.typography.labelSmall.copy(
                            fontSize = 10.sp,
                            lineHeight = 10.sp,
                        ),
                    fontWeight = FontWeight.SemiBold,
                    color = selectorTint,
                )
                Icon(
                    imageVector = Icons.Outlined.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = selectorTint.copy(alpha = 0.84f),
                )
            }
        }
        FoxholeDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            popupGap = 0.dp,
            modifier = Modifier.width(menuWidth),
        ) {
            values.forEachIndexed { index, option ->
                val selectedOption = option == selected
                FoxholeDropdownItem(
                    onClick = {
                        expanded = false
                        if (option != selected) {
                            onSelect(option)
                        }
                    },
                    selected = selectedOption,
                    extendSelectedToMenuTop = index == 0,
                    extendSelectedToMenuBottom = index == values.lastIndex,
                    contentPadding = PaddingValues(start = 12.dp, end = 8.dp, top = 9.dp, bottom = 9.dp),
                ) {
                    Icon(
                        imageVector = homeModeOptionIcon(option),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint =
                            if (selectedOption) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                    )
                    Text(
                        text = homeModeMenuLabel(option),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (selectedOption) FontWeight.SemiBold else FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun rememberHomeModeSelectorWidth(label: String): Dp {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val textStyle =
        MaterialTheme.typography.labelSmall.copy(
            fontSize = 10.sp,
            lineHeight = 10.sp,
            fontWeight = FontWeight.SemiBold,
        )
    val targetWidth =
        remember(label, textStyle, density, textMeasurer) {
            val labelWidth =
                textMeasurer
                    .measure(
                        text = AnnotatedString(label),
                        style = textStyle,
                        maxLines = 1,
                    ).size
                    .width
            with(density) {
                (labelWidth.toDp() + 11.dp + 14.dp + 10.dp + 16.dp)
                    .coerceIn(HomeModeSelectorMinWidth, HomeModeSelectorMaxWidth)
            }
        }
    val animatedWidth by animateDpAsState(
        targetValue = targetWidth,
        label = "home_mode_selector_width",
    )
    return animatedWidth
}

@Composable
internal fun HomeLanProxyChip(onClick: () -> Unit) {
    val color = foxholeSystemAwareAccentColor(fallback = MaterialTheme.colorScheme.primary)
    Surface(
        modifier =
            Modifier
                .clip(MaterialTheme.shapes.small)
                .clickable { onClick() },
        shape = MaterialTheme.shapes.small,
        color = color.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.30f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Public,
                contentDescription = null,
                modifier = Modifier.size(10.dp),
                tint = color,
            )
            Text(
                text = stringResource(R.string.home_lan_proxy_tag),
                style =
                    MaterialTheme.typography.labelSmall.copy(
                        fontSize = 10.sp,
                        lineHeight = 10.sp,
                    ),
                fontWeight = FontWeight.SemiBold,
                color = color,
            )
        }
    }
}

@Composable
internal fun HomeStatusBadge(
    state: ConnectionState,
    label: String,
    textStyle: TextStyle = MaterialTheme.typography.titleLarge,
    accentColor: Color? = null,
    loading: Boolean = false,
    smartMarker: Boolean = false,
) {
    val color = accentColor ?: homeStatusTone(state)
    Row(
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) {
            HomeAnalysisSignal(
                tint = color,
                modifier = Modifier.offset(x = HomeConnectingStatusSignalOffset),
            )
        } else {
            HomeStatusSignal(
                tint = color,
                settled = state == ConnectionState.CONNECTED,
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = textStyle,
                fontWeight = FontWeight.SemiBold,
                color = color,
            )
            if (smartMarker) {
                SmartConnectionBadge(color = color)
            }
        }
    }
}

@Composable
internal fun HomeTopStatusLoadingBlock(
    accentColor: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HomeAnalysisSignal(
            tint = accentColor,
            modifier = Modifier.offset(x = HomeConnectingStatusSignalOffset),
        )
        FoxholeSkeletonBlock(
            modifier =
                Modifier
                    .width(118.dp)
                    .height(18.dp),
        )
    }
}

@Composable
private fun SmartConnectionBadge(color: Color) {
    Surface(
        modifier = Modifier.offset(y = (-3).dp),
        shape = MaterialTheme.shapes.small,
        color = color.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.34f)),
    ) {
        Text(
            text = stringResource(R.string.smart_profile_tag),
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
            style =
                MaterialTheme.typography.labelSmall.copy(
                    fontSize = 9.sp,
                    lineHeight = 10.sp,
                    fontWeight = FontWeight.Black,
                ),
            color = color,
            maxLines = 1,
        )
    }
}

@Composable
internal fun HomeStatusSignal(
    tint: Color,
    settled: Boolean,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier =
            modifier
                .offset(x = HomeConnectingStatusSignalOffset)
                .width(HomeTriangleIndicatorSize + (HomeTriangleIndicatorCanvasBleed * 2))
                .height(HomeTriangleIndicatorSize),
    ) {
        val canvasSize = size.height
        val dotRadius = canvasSize * 0.16f
        val anchors = homeTriangleAnchors(canvasSize).withHorizontalOffset((size.width - canvasSize) / 2f)
        anchors.forEach { center ->
            drawCircle(
                color = tint.copy(alpha = if (settled) 0.96f else 0.82f),
                radius = dotRadius,
                center = center,
            )
        }
    }
}

internal enum class HomeConnectionFeature {
    KILL_SWITCH,
    FIREWALL,
    TOR,
    LAN_PROXY,
}

internal enum class HomeConnectionFeatureStatus(
    val label: String,
) {
    ON("ON"),
    PENDING("PENDING"),
    OFF("OFF"),
}

internal data class HomeConnectionFeatureIndicator(
    val feature: HomeConnectionFeature,
    val titleRes: Int,
    val status: HomeConnectionFeatureStatus,
)

internal fun homeConnectionFeatureIndicators(state: HomeRouteUiState): List<HomeConnectionFeatureIndicator> =
    buildList {
        if (state.settings.ui.showFirewallStatus) {
            add(
                HomeConnectionFeatureIndicator(
                    feature = HomeConnectionFeature.FIREWALL,
                    titleRes = R.string.home_connection_feature_firewall,
                    status = homeFirewallFeatureStatus(state),
                ),
            )
        }
        add(
            HomeConnectionFeatureIndicator(
                feature = HomeConnectionFeature.TOR,
                titleRes = R.string.tor_badge,
                status = homeTorFeatureStatus(state),
            ),
        )
        if (state.settings.expert.localSurfaces.allowLanAccess) {
            add(
                HomeConnectionFeatureIndicator(
                    feature = HomeConnectionFeature.LAN_PROXY,
                    titleRes = R.string.home_lan_proxy_title,
                    status = HomeConnectionFeatureStatus.ON,
                ),
            )
        }
    }

internal fun homeConnectionFeatureIndicator(
    feature: HomeConnectionFeature,
    state: HomeRouteUiState,
): HomeConnectionFeatureIndicator? =
    homeConnectionFeatureIndicators(state).firstOrNull { it.feature == feature }

internal fun homeFirewallFeatureStatus(state: HomeRouteUiState): HomeConnectionFeatureStatus =
    when {
        !state.settings.expert.firewallEnabled -> HomeConnectionFeatureStatus.OFF
        state.connection.profileId == FoxholeVpnService.LOCAL_GUARD_PROFILE_ID -> HomeConnectionFeatureStatus.ON
        state.connection.state in ACTIVE_CONNECTION_STATES -> HomeConnectionFeatureStatus.ON
        else -> HomeConnectionFeatureStatus.PENDING
    }

internal fun homeTorFeatureStatus(state: HomeRouteUiState): HomeConnectionFeatureStatus {
    if (!state.settings.privacyRoute.enabled) {
        return HomeConnectionFeatureStatus.OFF
    }
    if (state.activeProfile == null && !homeTorRouteHasRunnableScope(state)) {
        return HomeConnectionFeatureStatus.PENDING
    }
    val torCanRun =
        (
            state.activeProfile == null ||
                state.settings.privacyRoute.directTorEnabled ||
                state.settings.traffic.mode == TrafficMode.TUNNEL
            ) &&
            homeTorRouteHasRunnableScope(state) &&
            !homeTorSelectedProtocolIsUdp(state)
    return if (torCanRun && state.connection.state == ConnectionState.CONNECTED) {
        HomeConnectionFeatureStatus.ON
    } else {
        HomeConnectionFeatureStatus.PENDING
    }
}

internal fun homeTorSelectedProtocolIsUdp(state: HomeRouteUiState): Boolean {
    val profile = state.activeProfile
    val protocolHint =
        if (state.settings.privacyRoute.bypassVpnTunnel || profile == null) {
            null
        } else {
            state.connection.protocolHint ?: profile.selectedRuntimeProtocolHint()
        }
    return protocolHint?.isUdpTransport() == true
}

private fun homeTorRouteHasRunnableScope(state: HomeRouteUiState): Boolean =
    when (state.settings.privacyRoute.scope) {
        PrivacyRouteScope.ALL_APPS -> true
        PrivacyRouteScope.SELECTED_APPS -> state.settings.privacyRoute.selectedPackages.any(String::isNotBlank)
    }

internal fun homeTorOnlyStartAvailable(state: HomeRouteUiState): Boolean =
    state.activeProfile == null &&
        state.settings.privacyRoute.enabled &&
        homeTorRouteHasRunnableScope(state)

private fun Profile.selectedRuntimeProtocolHint(): ProtocolHint? {
    val selectedId = selectedProtocolOptionId?.takeIf(String::isNotBlank)
    return selectedId
        ?.let { optionId -> protocolOptions.firstOrNull { it.id == optionId }?.protocolHint }
        ?: protocolOptions.firstOrNull()?.protocolHint
        ?: protocolHint
}

@Composable
internal fun HomeConnectionFeatureIndicators(
    indicators: List<HomeConnectionFeatureIndicator>,
    onIndicatorClick: (HomeConnectionFeature) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (indicators.isEmpty()) {
        return
    }
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .testTag("home_connection_feature_indicators"),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            indicators.forEach { indicator ->
                HomeConnectionFeatureIndicatorItem(
                    indicator = indicator,
                    onClick = { onIndicatorClick(indicator.feature) },
                )
            }
        }
    }
}

@Composable
private fun HomeConnectionFeatureIndicatorItem(
    indicator: HomeConnectionFeatureIndicator,
    onClick: () -> Unit,
) {
    val icon = homeConnectionFeatureIcon(indicator.feature)
    val iconEnabled = indicator.status != HomeConnectionFeatureStatus.OFF
    val iconColor = if (iconEnabled) homeConnectionFeatureStatusColor(HomeConnectionFeatureStatus.ON) else null
    val neutralIconColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.82f)
    val shape = RoundedCornerShape(999.dp)
    Row(
        modifier =
            Modifier
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.50f))
                .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.38f)), shape)
                .clickable(onClick = onClick)
                .heightIn(min = 30.dp)
                .padding(horizontal = 9.dp, vertical = 4.dp)
                .testTag("home_connection_feature_indicator_${indicator.feature.name.lowercase(Locale.US)}"),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = iconColor ?: neutralIconColor,
        )
        Text(
            text = stringResource(indicator.titleRes),
            style =
                MaterialTheme.typography.labelMedium.copy(
                    fontSize = 12.sp,
                    lineHeight = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun homeConnectionFeatureStatusColor(status: HomeConnectionFeatureStatus): Color =
    when (status) {
        HomeConnectionFeatureStatus.ON -> Color(0xFF7BD69D)
        HomeConnectionFeatureStatus.PENDING -> MaterialTheme.colorScheme.primary
        HomeConnectionFeatureStatus.OFF -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.58f)
    }

@Composable
internal fun HomeConnectionFeatureDialog(
    feature: HomeConnectionFeature,
    state: HomeRouteUiState,
    wifiLanAddress: String?,
    onDismiss: () -> Unit,
    onKillSwitchChanged: (Boolean) -> Unit,
    onFirewallEnabledChanged: (Boolean) -> Unit,
    onPrivacyRouteModeSelected: (PrivacyRouteMode) -> Unit,
    onOpenPrivacyRoute: () -> Unit,
    onLocalProxyLanAccessChanged: (Boolean) -> Unit,
    onRenewTorIp: () -> Unit,
    onRestart: () -> Unit,
) {
    val indicator = homeConnectionFeatureIndicator(feature, state) ?: return
    val title = "${stringResource(indicator.titleRes)}: ${indicator.status.label}"
    val icon = homeConnectionFeatureIcon(feature)
    val restartAvailable = state.homeConnectionFeatureRestartAvailable()
    val enabled = feature.enabledIn(state)
    val torSelectedProtocolIsUdp = feature == HomeConnectionFeature.TOR && homeTorSelectedProtocolIsUdp(state)
    val torRouteNeedsSetup = feature == HomeConnectionFeature.TOR && !enabled && homeTorRouteNeedsSetup(state)
    val torOperationActive = feature == HomeConnectionFeature.TOR && state.torOperation.active
    val torOnlyRuntimeActive = feature == HomeConnectionFeature.TOR && state.hasTorOnlyRuntime()
    val confirmLabel =
        homeConnectionFeatureConfirmLabel(
            torOperationActive = torOperationActive,
            torOnlyRuntimeActive = torOnlyRuntimeActive,
            enabled = enabled,
            restartAvailable = restartAvailable,
        )
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier =
            Modifier
                .foxholeDialogChrome()
                .testTag("home_connection_feature_dialog_${feature.name.lowercase(Locale.US)}"),
        shape = FoxholeDialogShape,
        properties = DialogProperties(dismissOnClickOutside = false),
        title = {
            FoxholeDialogTitle(
                title = title,
                icon = icon,
                iconTint = homeConnectionFeatureStatusColor(indicator.status),
            )
        },
        text =
            homeConnectionFeatureDialogContent(
                feature = feature,
                state = state,
                indicator = indicator,
                wifiLanAddress = wifiLanAddress,
                torSelectedProtocolIsUdp = torSelectedProtocolIsUdp,
                onRenewTorIp = onRenewTorIp,
            ),
        confirmButton = {
            FoxholeDialogConfirmButton(
                onClick = {
                    handleHomeConnectionFeatureConfirm(
                        feature = feature,
                        enabled = enabled,
                        restartAvailable = restartAvailable,
                        onKillSwitchChanged = onKillSwitchChanged,
                        onFirewallEnabledChanged = onFirewallEnabledChanged,
                        onPrivacyRouteModeSelected = onPrivacyRouteModeSelected,
                        onOpenPrivacyRoute = onOpenPrivacyRoute,
                        onLocalProxyLanAccessChanged = onLocalProxyLanAccessChanged,
                        onRestart = onRestart,
                        onDismiss = onDismiss,
                        torRouteNeedsSetup = torRouteNeedsSetup,
                        torOnlyRuntimeActive = torOnlyRuntimeActive,
                        torSelectedProtocolIsUdp = torSelectedProtocolIsUdp,
                    )
                },
                label = confirmLabel,
            )
        },
        dismissButton = {
            FoxholeDialogDismissButton(
                onClick = onDismiss,
                label = stringResource(R.string.close),
            )
        },
    )
}

@Composable
internal fun TorTransitionPromptDialog(
    prompt: TorTransitionPrompt,
    onDismiss: () -> Unit,
    onConfirmDisableTorForUdpProtocol: (TorTransitionPrompt.DisableTorForUdpProtocol) -> Unit,
    onConfirmMoveTorIntoVpn: (TorTransitionPrompt.StartTcpVpnWhileTorOnlyActive) -> Unit,
    onConfirmKeepTorOnDeviceAndStartVpn: (TorTransitionPrompt.StartTcpVpnWhileTorOnlyActive) -> Unit,
) {
    when (prompt) {
        is TorTransitionPrompt.DisableTorForUdpProtocol ->
            TorTransitionAlertDialog(
                title = stringResource(R.string.privacy_route_udp_switch_warning_title),
                body = stringResource(R.string.privacy_route_udp_switch_warning_body),
                onDismiss = onDismiss,
                confirmLabel = stringResource(R.string.privacy_route_continue_tor_over_vpn),
                onConfirm = { onConfirmDisableTorForUdpProtocol(prompt) },
            )
        is TorTransitionPrompt.StartTcpVpnWhileTorOnlyActive ->
            TorTransitionAlertDialog(
                title = stringResource(R.string.privacy_route_tcp_vpn_from_tor_title),
                body = stringResource(R.string.privacy_route_tcp_vpn_from_tor_body),
                onDismiss = onDismiss,
                secondaryLabel = stringResource(R.string.privacy_route_keep_tor_on_device),
                onSecondary = { onConfirmKeepTorOnDeviceAndStartVpn(prompt) },
                confirmLabel = stringResource(R.string.privacy_route_continue_tor_over_vpn),
                onConfirm = { onConfirmMoveTorIntoVpn(prompt) },
            )
        is TorTransitionPrompt.UdpVpnProtocolNotSupported ->
            TorTransitionAlertDialog(
                title = stringResource(R.string.privacy_route_udp_protocol_not_supported_title),
                body = stringResource(R.string.privacy_route_udp_protocol_not_supported_body),
                onDismiss = onDismiss,
                confirmLabel = stringResource(R.string.close),
                onConfirm = onDismiss,
                showDismissButton = false,
            )
    }
}

@Composable
private fun TorTransitionAlertDialog(
    title: String,
    body: String,
    onDismiss: () -> Unit,
    confirmLabel: String,
    onConfirm: () -> Unit,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
    showDismissButton: Boolean = true,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier =
            Modifier
                .foxholeDialogChrome()
                .testTag("tor_transition_prompt_dialog"),
        shape = FoxholeDialogShape,
        properties = DialogProperties(dismissOnClickOutside = false),
        title = {
            FoxholeDialogTitle(
                title = title,
                icon = Icons.Outlined.Shield,
                iconTint = MaterialTheme.colorScheme.primary,
            )
        },
        text = {
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        confirmButton = {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (secondaryLabel != null && onSecondary != null) {
                    FoxholeDialogSecondaryButton(
                        label = secondaryLabel,
                        onClick = onSecondary,
                    )
                }
                if (showDismissButton) {
                    FoxholeDialogDismissButton(
                        onClick = onDismiss,
                        label = stringResource(R.string.cancel),
                    )
                }
                FoxholeDialogConfirmButton(
                    onClick = onConfirm,
                    label = confirmLabel,
                )
            }
        },
    )
}

private fun HomeRouteUiState.homeConnectionFeatureRestartAvailable(): Boolean {
    val connectionActive =
        connection.state in setOf(
            ConnectionState.CONNECTED,
            ConnectionState.CONNECTING,
            ConnectionState.RECONNECTING,
        )
    return reconnectRequired && connectionActive
}

internal fun homeProtocolMetricsAnalysisState(
    state: HomeRouteUiState,
    presentation: HomeDashboardProtocolPresentation,
): AutoConnectUiState {
    val refreshingOption =
        presentation.protocolOptions.firstOrNull { option -> option.id == state.protocolMetricsRefreshingOptionId }
            ?: presentation.protocolOptions.firstOrNull { option -> option.id == presentation.selectedProtocolOptionId }
            ?: presentation.protocolOptions.firstOrNull()
    return AutoConnectUiState(
        running = true,
        currentOptionId = refreshingOption?.id,
        currentProtocolHint = refreshingOption?.protocolHint ?: presentation.protocolHint,
        currentDisplayName = refreshingOption?.displayName,
        options =
            refreshingOption
                ?.let { option ->
                    listOf(
                        AutoConnectProbeOptionUiState(
                            optionId = option.id,
                            displayName = option.displayName,
                            protocolHint = option.protocolHint,
                            status = AutoConnectProbeStatus.TESTING,
                        ),
                    )
                }.orEmpty(),
    )
}

private fun HomeConnectionFeature.enabledIn(state: HomeRouteUiState): Boolean =
    when (this) {
        HomeConnectionFeature.KILL_SWITCH -> state.settings.expert.killSwitchEnabled
        HomeConnectionFeature.FIREWALL -> state.settings.expert.firewallEnabled
        HomeConnectionFeature.TOR -> state.settings.privacyRoute.enabled
        HomeConnectionFeature.LAN_PROXY -> state.settings.expert.localSurfaces.allowLanAccess
    }

private fun homeTorRouteNeedsSetup(state: HomeRouteUiState): Boolean =
    !homeTorRouteHasRunnableScope(state)

@Composable
private fun homeConnectionFeatureConfirmLabel(
    torOperationActive: Boolean,
    torOnlyRuntimeActive: Boolean,
    enabled: Boolean,
    restartAvailable: Boolean,
): String =
    if (torOnlyRuntimeActive) {
        stringResource(R.string.disconnect_tor)
    } else if (torOperationActive && enabled) {
        stringResource(R.string.cancel)
    } else if (restartAvailable) {
        stringResource(R.string.reconnect)
    } else {
        stringResource(
            if (enabled) {
                R.string.home_feature_turn_off
            } else {
                R.string.home_feature_turn_on
            },
        )
    }

@Composable
private fun homeConnectionFeatureDialogContent(
    feature: HomeConnectionFeature,
    state: HomeRouteUiState,
    indicator: HomeConnectionFeatureIndicator,
    wifiLanAddress: String?,
    torSelectedProtocolIsUdp: Boolean,
    onRenewTorIp: () -> Unit,
): (@Composable () -> Unit)? =
    when (feature) {
        HomeConnectionFeature.TOR -> {
            {
                HomeConnectionFeatureDialogContent(
                    state = state,
                    indicator = indicator,
                    torSelectedProtocolIsUdp = torSelectedProtocolIsUdp,
                    onRenewTorIp = onRenewTorIp,
                )
            }
        }
        HomeConnectionFeature.FIREWALL -> {
            {
                HomeFirewallFeatureDialogContent(state = state)
            }
        }
        HomeConnectionFeature.LAN_PROXY -> {
            {
                HomeLanProxyFeatureDialogContent(
                    state = state,
                    wifiLanAddress = wifiLanAddress,
                )
            }
        }
        else -> null
    }

private fun handleHomeConnectionFeatureConfirm(
    feature: HomeConnectionFeature,
    enabled: Boolean,
    restartAvailable: Boolean,
    onKillSwitchChanged: (Boolean) -> Unit,
    onFirewallEnabledChanged: (Boolean) -> Unit,
    onPrivacyRouteModeSelected: (PrivacyRouteMode) -> Unit,
    onOpenPrivacyRoute: () -> Unit,
    onLocalProxyLanAccessChanged: (Boolean) -> Unit,
    onRestart: () -> Unit,
    onDismiss: () -> Unit,
    torRouteNeedsSetup: Boolean,
    torOnlyRuntimeActive: Boolean,
    torSelectedProtocolIsUdp: Boolean,
) {
    when {
        feature == HomeConnectionFeature.TOR -> {
            if (torOnlyRuntimeActive) {
                onRestart()
                onDismiss()
                return
            }
            if (!enabled && torRouteNeedsSetup) {
                onDismiss()
                onOpenPrivacyRoute()
                return
            }
            onPrivacyRouteModeSelected(
                if (enabled) {
                    PrivacyRouteMode.OFF
                } else {
                    PrivacyRouteMode.TOR_OVER_VPN
                },
            )
            if (enabled || torSelectedProtocolIsUdp) {
                onDismiss()
            }
        }
        restartAvailable -> {
            onRestart()
            onDismiss()
        }
        else -> {
            toggleHomeConnectionFeature(
                feature = feature,
                enabled = enabled,
                onKillSwitchChanged = onKillSwitchChanged,
                onFirewallEnabledChanged = onFirewallEnabledChanged,
                onLocalProxyLanAccessChanged = onLocalProxyLanAccessChanged,
            )
            onDismiss()
        }
    }
}

private fun toggleHomeConnectionFeature(
    feature: HomeConnectionFeature,
    enabled: Boolean,
    onKillSwitchChanged: (Boolean) -> Unit,
    onFirewallEnabledChanged: (Boolean) -> Unit,
    onLocalProxyLanAccessChanged: (Boolean) -> Unit,
) {
    when (feature) {
        HomeConnectionFeature.KILL_SWITCH -> onKillSwitchChanged(!enabled)
        HomeConnectionFeature.FIREWALL -> onFirewallEnabledChanged(!enabled)
        HomeConnectionFeature.TOR -> Unit
        HomeConnectionFeature.LAN_PROXY -> onLocalProxyLanAccessChanged(!enabled)
    }
}

@Composable
private fun HomeConnectionFeatureDialogContent(
    state: HomeRouteUiState,
    indicator: HomeConnectionFeatureIndicator,
    torSelectedProtocolIsUdp: Boolean,
    onRenewTorIp: () -> Unit,
) {
    val torRouteRunnable = homeTorRouteHasRunnableScope(state)
    val torRouteLoading =
        state.torOperation.active ||
            (state.settings.privacyRoute.enabled && torRouteRunnable && indicator.status != HomeConnectionFeatureStatus.ON)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (torSelectedProtocolIsUdp) {
            HomeTorWarningBlock()
        }
        if (torRouteLoading || indicator.status == HomeConnectionFeatureStatus.ON) {
            HomeTorOperationLog(state)
        }
        if (indicator.status == HomeConnectionFeatureStatus.ON) {
            HomeTorConnectedTable(
                state = state,
                loading = state.torOperation.active,
                onRenewTorIp = onRenewTorIp,
            )
            if (state.settings.privacyRoute.bypassVpnTunnel && state.connection.profileId != FoxholeVpnService.TOR_ONLY_PROFILE_ID) {
                HomeTorParallelBatteryNotice()
            }
        } else if (!torSelectedProtocolIsUdp) {
            if (torRouteLoading) {
                HomeTorConnectedTable(
                    state = state,
                    loading = true,
                    onRenewTorIp = onRenewTorIp,
                )
            } else if (!torRouteRunnable) {
                Text(
                    text = stringResource(R.string.privacy_route_selected_apps_summary),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = stringResource(R.string.privacy_route_modal_ready_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun HomeTorOperationLog(state: HomeRouteUiState) {
    val text =
        when (state.torOperation.kind) {
            HomeTorOperationKind.CHANGING_LOCATION -> stringResource(R.string.privacy_route_modal_log_changing_ip)
            HomeTorOperationKind.CONNECTING -> stringResource(R.string.privacy_route_modal_log_connecting)
            HomeTorOperationKind.NONE ->
                if (state.connection.profileId == FoxholeVpnService.TOR_ONLY_PROFILE_ID) {
                    stringResource(R.string.privacy_route_modal_log_tor_only_connected)
                } else {
                    stringResource(R.string.privacy_route_modal_log_connected)
                }
        }
    val activeTone = Color(0xFFE89B3C)
    val pulseAlpha by rememberTorActionButtonPulse(active = state.torOperation.active)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.Start),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.size(8.dp),
            shape = CircleShape,
            color =
                if (state.torOperation.active) {
                    activeTone.copy(alpha = pulseAlpha.coerceAtLeast(0.12f))
                } else {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
                },
        ) {
        }
        Text(
            text = text,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color =
                if (state.torOperation.active) {
                    activeTone
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            textAlign = TextAlign.Start,
        )
    }
}

@Composable
private fun HomeTorWarningBlock() {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.34f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.32f)),
    ) {
        Text(
            text = stringResource(R.string.privacy_route_udp_protocol_not_supported_body),
            modifier = Modifier.padding(10.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

@Composable
private fun HomeTorParallelBatteryNotice() {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.26f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)),
    ) {
        Text(
            text = stringResource(R.string.privacy_route_parallel_battery_notice),
            modifier = Modifier.padding(10.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HomeTorConnectedTable(
    state: HomeRouteUiState,
    loading: Boolean,
    onRenewTorIp: () -> Unit,
) {
    val durationText = rememberTorConnectionDurationText(state) ?: "-"
    val torIpPresentation = resolveHomeTorIpPresentation(state = state, loading = loading)
    val selectedApps = remember(state.installedApps, state.settings.privacyRoute.selectedPackages) {
        resolveSelectedApps(
            installedApps = state.installedApps,
            selectedPackages = state.settings.privacyRoute.selectedPackages,
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
                HomeTorInfoRow(
                    icon = Icons.Outlined.Public,
                    label = stringResource(R.string.privacy_route_modal_current_ip),
                    value = torIpPresentation.ipText,
                    valueMonospace = torIpPresentation.hasIp,
                    loading = torIpPresentation.loading,
                )
                HomeNetworkSubtleDivider()
                HomeTorInfoRow(
                    icon = Icons.Outlined.Language,
                    label = stringResource(R.string.home_network_country_label),
                    value = torIpPresentation.countryText,
                    loading = torIpPresentation.loading,
                )
                HomeNetworkSubtleDivider()
                HomeTorInfoRow(
                    icon = Icons.Outlined.LocationCity,
                    label = stringResource(R.string.home_network_city_label),
                    value = torIpPresentation.cityText,
                    loading = torIpPresentation.loading,
                )
                HomeNetworkSubtleDivider()
                HomeTorInfoRow(
                    icon = Icons.Outlined.AccessTime,
                    label = stringResource(R.string.privacy_route_modal_connection_time),
                    value = durationText,
                    valueMonospace = durationText != "-",
                    loading = loading,
                )
                HomeNetworkSubtleDivider()
                HomeTorRouteRow(state = state, selectedApps = selectedApps)
            }
        }
        val changeIpInProgress = state.torOperation.kind == HomeTorOperationKind.CHANGING_LOCATION
        val pulseAlpha by rememberTorActionButtonPulse(active = changeIpInProgress)
        OutlinedButton(
            onClick = onRenewTorIp,
            enabled = state.connection.state == ConnectionState.CONNECTED && !state.reconnectInProgress && !loading,
            modifier = Modifier.fillMaxWidth().testTag("home_tor_renew_ip_action"),
            border =
                BorderStroke(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = if (changeIpInProgress) 0.58f else 0.34f),
                ),
            colors =
                ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.primary,
                    containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.06f),
                    disabledContainerColor =
                        if (changeIpInProgress) {
                            MaterialTheme.colorScheme.primary.copy(alpha = pulseAlpha)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f)
                        },
                    disabledContentColor =
                        if (changeIpInProgress) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                ),
        ) {
            Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            if (changeIpInProgress) {
                Text(stringResource(R.string.privacy_route_modal_in_progress))
            } else {
                Text(stringResource(R.string.privacy_route_modal_change_ip))
            }
        }
    }
}

internal data class HomeTorIpPresentation(
    val ipText: String,
    val countryText: String,
    val cityText: String,
    val loading: Boolean,
    val hasIp: Boolean,
)

internal fun resolveHomeTorIpPresentation(
    state: HomeRouteUiState,
    loading: Boolean,
): HomeTorIpPresentation {
    val info = state.visibleTorIpInfo()
    val ipText = info?.let(::primaryVisibleIp) ?: "-"
    val countryText =
        info?.let { ipInfo ->
            val country = ipInfo.countryName ?: ipInfo.countryCode
            country?.let { "${countryEmoji(ipInfo.countryCode)} $it" }
        } ?: "-"
    return HomeTorIpPresentation(
        ipText = ipText,
        countryText = countryText,
        cityText = info?.city?.takeIf(String::isNotBlank) ?: "-",
        loading = info == null && (loading || state.connection.state in ACTIVE_CONNECTION_STATES),
        hasIp = ipText != "-",
    )
}

private fun HomeRouteUiState.visibleTorIpInfo(): IpInfo? {
    val info = visibleTorRouteIpInfo()
    val requiredFetchedAt = requiredTorRouteFetchedAt()
    return when {
        info == null -> null
        !torRouteVisible() -> null
        torOperation.active && info == torIpInfo -> info
        info.fetchedAt >= requiredFetchedAt -> info
        connection.state in ACTIVE_CONNECTION_STATES -> info
        else -> null
    }
}

private fun HomeRouteUiState.torRouteVisible(): Boolean =
    connection.profileId == FoxholeVpnService.TOR_ONLY_PROFILE_ID ||
        settings.privacyRoute.enabled

private fun HomeRouteUiState.visibleTorRouteIpInfo(): IpInfo? =
    when {
        connection.profileId == FoxholeVpnService.TOR_ONLY_PROFILE_ID -> torIpInfo ?: ipInfo
        settings.privacyRoute.enabled -> torIpInfo
        else -> null
    }

private fun HomeRouteUiState.requiredTorRouteFetchedAt(): Long =
    when {
        torOperation.active -> torOperation.startedAt
        connection.profileId == FoxholeVpnService.TOR_ONLY_PROFILE_ID -> connection.lastChangeAt
        else -> 0L
    }

@Composable
private fun HomeFirewallFeatureDialogContent(state: HomeRouteUiState) {
    val blockedApps = remember(state.installedApps, state.settings.expert.blockedPackages) {
        resolveSelectedApps(
            installedApps = state.installedApps,
            selectedPackages = state.settings.expert.blockedPackages,
        )
    }
    val blockedAppsEnabled = state.firewallBlockedAppsEnabled(blockedApps)
    val persistentBlockEnabled = state.firewallPersistentBlockEnabled(blockedApps)
    val appStatisticsEnabled = state.firewallAppStatisticsEnabled()
    val countryStatisticsEnabled = state.firewallCountryStatisticsEnabled()
    val anomalyStatisticsEnabled = state.firewallAnomalyStatisticsEnabled()
    val runtimeMode = homeFirewallRuntimeMode(state)
    val blockedAppsText = homeFirewallBlockedAppsText(blockedAppsEnabled, blockedApps.size)
    val persistentBlockText = switchStateLabel(persistentBlockEnabled)
    val appStatisticsText = switchStateLabel(appStatisticsEnabled)
    val countryStatisticsText = switchStateLabel(countryStatisticsEnabled)
    val anomalyStatisticsText = switchStateLabel(anomalyStatisticsEnabled)
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
            HomeTorInfoRow(
                icon = Icons.Outlined.Shield,
                label = stringResource(R.string.firewall_modal_mode),
                value = runtimeMode,
            )
            HomeNetworkSubtleDivider()
            HomeFirewallBlockedAppsRow(
                value = blockedAppsText,
                blockedApps = if (blockedAppsEnabled) blockedApps else emptyList(),
            )
            HomeNetworkSubtleDivider()
            HomeTorInfoRow(
                icon = Icons.Outlined.PowerSettingsNew,
                label = stringResource(R.string.firewall_modal_persistent_block),
                value = persistentBlockText,
                valueColor = switchStateValueColor(persistentBlockEnabled),
            )
            HomeNetworkSubtleDivider()
            HomeTorInfoRow(
                icon = Icons.Outlined.Apps,
                label = stringResource(R.string.firewall_modal_app_statistics),
                value = appStatisticsText,
                valueColor = switchStateValueColor(appStatisticsEnabled),
            )
            HomeNetworkSubtleDivider()
            HomeTorInfoRow(
                icon = Icons.Outlined.Language,
                label = stringResource(R.string.firewall_modal_country_statistics),
                value = countryStatisticsText,
                valueColor = switchStateValueColor(countryStatisticsEnabled),
            )
            HomeNetworkSubtleDivider()
            HomeTorInfoRow(
                icon = Icons.Outlined.QueryStats,
                label = stringResource(R.string.firewall_modal_anomaly_statistics),
                value = anomalyStatisticsText,
                valueColor = switchStateValueColor(anomalyStatisticsEnabled),
            )
        }
    }
}

@Composable
private fun HomeLanProxyFeatureDialogContent(
    state: HomeRouteUiState,
    wifiLanAddress: String?,
) {
    val lanSurface = activeLanProxySurface(state)
    val lanAvailable = wifiLanAddress != null && lanSurface != null
    val endpoint =
        if (lanAvailable) {
            "$wifiLanAddress:${lanSurface.settings.port}"
        } else {
            stringResource(R.string.proxy_surface_lan_waiting_for_wifi)
        }
    val authEnabled = state.settings.expert.localSurfaces.lanAuth.enabled
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
            HomeTorInfoRow(
                icon = Icons.Outlined.Router,
                label = stringResource(R.string.firewall_modal_mode),
                value = lanSurface?.label ?: "-",
            )
            HomeNetworkSubtleDivider()
            HomeTorInfoRow(
                icon = Icons.Outlined.Public,
                label = stringResource(R.string.proxy_surface_endpoint_title),
                value = endpoint,
                valueMonospace = lanAvailable,
                valueColor =
                    if (lanAvailable) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
            HomeNetworkSubtleDivider()
            HomeTorInfoRow(
                icon = Icons.Outlined.Router,
                label = stringResource(R.string.port),
                value = lanSurface?.settings?.port?.toString() ?: "-",
                valueMonospace = true,
            )
            HomeNetworkSubtleDivider()
            HomeTorInfoRow(
                icon = Icons.Outlined.Shield,
                label = stringResource(R.string.lan_proxy_auth_title),
                value =
                    stringResource(
                        if (authEnabled) {
                            R.string.auth_required_label
                        } else {
                            R.string.no_auth_label
                        },
                    ),
                valueColor = switchStateValueColor(authEnabled),
            )
        }
    }
}

@Composable
private fun homeFirewallRuntimeMode(state: HomeRouteUiState): String =
    when {
        state.connection.profileId == FoxholeVpnService.LOCAL_GUARD_PROFILE_ID &&
            state.connection.state in ACTIVE_CONNECTION_STATES ->
            when (state.settings.localGuardModeOrNull()) {
                LocalGuardMode.FIREWALL -> stringResource(R.string.firewall_modal_mode_local_guard)
                LocalGuardMode.JOURNAL -> stringResource(R.string.notification_status_journal)
                LocalGuardMode.DNS -> stringResource(R.string.notification_status_dns_guard)
                null -> stringResource(R.string.firewall_modal_mode_local_guard)
            }
        !state.settings.expert.firewallEnabled -> stringResource(R.string.switch_state_off)
        state.connection.state in ACTIVE_CONNECTION_STATES ->
            stringResource(R.string.firewall_modal_mode_vpn)
        else -> stringResource(R.string.firewall_modal_mode_waiting)
    }

@Composable
private fun homeFirewallBlockedAppsText(
    blockedAppsEnabled: Boolean,
    blockedAppsCount: Int,
): String =
    if (blockedAppsEnabled) {
        pluralStringResource(
            R.plurals.firewall_modal_blocked_apps_count,
            blockedAppsCount,
            blockedAppsCount,
        )
    } else {
        stringResource(R.string.switch_state_off)
    }

@Composable
private fun switchStateLabel(enabled: Boolean): String =
    stringResource(
        if (enabled) {
            R.string.switch_state_on
        } else {
            R.string.switch_state_off
        },
    )

@Composable
private fun switchStateValueColor(enabled: Boolean): Color =
    if (enabled) {
        homeConnectionFeatureStatusColor(HomeConnectionFeatureStatus.ON)
    } else {
        MaterialTheme.colorScheme.onSurface
    }

private fun HomeRouteUiState.firewallBlockedAppsEnabled(blockedApps: List<InstalledAppOption>): Boolean =
    settings.expert.firewallEnabled &&
        settings.expert.blockedPackagesEnabled &&
        blockedApps.isNotEmpty()

private fun HomeRouteUiState.firewallPersistentBlockEnabled(blockedApps: List<InstalledAppOption>): Boolean =
    settings.expert.firewallEnabled &&
        settings.expert.blockAppsAlways &&
        settings.expert.blockedPackagesEnabled &&
        blockedApps.isNotEmpty()

private fun HomeRouteUiState.firewallAppStatisticsEnabled(): Boolean =
    settings.statistics.enabled &&
        settings.statistics.appTrafficEnabled &&
        settings.appTrafficStatsEnabled

private fun HomeRouteUiState.firewallCountryStatisticsEnabled(): Boolean =
    settings.statistics.enabled && settings.statistics.countryTrafficEnabled

private fun HomeRouteUiState.firewallAnomalyStatisticsEnabled(): Boolean =
    settings.statistics.enabled && settings.statistics.anomalyMetricsEnabled

@Composable
private fun HomeFirewallBlockedAppsRow(
    value: String,
    blockedApps: List<InstalledAppOption>,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HomeFeatureInfoLabel(
            icon = Icons.Outlined.Apps,
            label = stringResource(R.string.firewall_modal_app_blocking),
            modifier = Modifier.weight(1f),
        )
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HomeTorRouteIcons(
                scope = PrivacyRouteScope.SELECTED_APPS,
                selectedApps = blockedApps,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.End,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun HomeTorInfoRow(
    icon: ImageVector? = null,
    label: String,
    value: String,
    valueMonospace: Boolean = false,
    loading: Boolean = false,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HomeFeatureInfoLabel(
            icon = icon,
            label = label,
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.CenterEnd,
        ) {
            if (loading) {
                FoxholeSkeletonBlock(
                    modifier =
                        Modifier
                            .width(82.dp)
                            .height(12.dp),
                )
            } else {
                Text(
                    text = value,
                    style =
                        MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = if (valueMonospace) FontFamily.Monospace else FontFamily.Default,
                    ),
                    color = valueColor,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun HomeFeatureInfoLabel(
    icon: ImageVector?,
    label: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let {
            Icon(
                imageVector = it,
                contentDescription = null,
                modifier = Modifier.size(15.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
            )
        }
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun HomeTorRouteRow(
    state: HomeRouteUiState,
    selectedApps: List<InstalledAppOption>,
) {
    val routeText =
        when (state.settings.privacyRoute.scope) {
            PrivacyRouteScope.ALL_APPS -> stringResource(R.string.privacy_route_modal_route_all)
            PrivacyRouteScope.SELECTED_APPS ->
                if (selectedApps.isEmpty()) {
                    stringResource(R.string.privacy_route_selected_apps_empty)
                } else {
                    null
                }
        }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HomeFeatureInfoLabel(
            icon = Icons.Outlined.Apps,
            label = stringResource(R.string.privacy_route_modal_routing),
            modifier = Modifier.weight(1f),
        )
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HomeTorRouteIcons(
                scope = state.settings.privacyRoute.scope,
                selectedApps = selectedApps,
            )
            routeText?.let { text ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun rememberTorActionButtonPulse(active: Boolean): State<Float> {
    if (!active) {
        return remember { mutableFloatStateOf(0.06f) }
    }
    val transition = rememberInfiniteTransition(label = "tor_action_button_pulse")
    return transition.animateFloat(
        initialValue = 0.07f,
        targetValue = 0.20f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(durationMillis = 900),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "tor_action_button_container_alpha",
    )
}

@Composable
private fun HomeTorRouteIcons(
    scope: PrivacyRouteScope,
    selectedApps: List<InstalledAppOption>,
) {
    if (scope == PrivacyRouteScope.ALL_APPS) {
        Icon(
            imageVector = Icons.Outlined.Apps,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        return
    }
    selectedApps.take(4).forEach { app ->
        AppIcon(
            packageName = app.packageName,
            modifier = Modifier.size(24.dp),
            contentPadding = 1.dp,
            fallbackIconSize = 16.dp,
        )
    }
}

@Composable
private fun homeConnectionFeatureIcon(feature: HomeConnectionFeature): ImageVector =
    when (feature) {
        HomeConnectionFeature.KILL_SWITCH -> Icons.Outlined.Shield
        HomeConnectionFeature.FIREWALL -> Icons.Outlined.Shield
        HomeConnectionFeature.TOR -> ImageVector.vectorResource(R.drawable.ic_tor_route)
        HomeConnectionFeature.LAN_PROXY -> Icons.Outlined.Public
    }

@Composable
internal fun HomeTypewriterProtocolText(
    targetText: String,
    prefix: String,
    color: Color,
    textStyle: TextStyle,
    modifier: Modifier = Modifier,
) {
    var shownText by remember { mutableStateOf(targetText) }

    LaunchedEffect(targetText) {
        if (shownText == targetText) {
            return@LaunchedEffect
        }
        while (shownText.isNotEmpty()) {
            shownText = shownText.dropLast(1)
            delay(18)
        }
        for (index in targetText.indices) {
            shownText = targetText.take(index + 1)
            delay(22)
        }
    }

    Text(
        text = "$prefix$shownText",
        modifier = modifier,
        style = textStyle,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        color = color,
    )
}

@Composable
internal fun HomeAnalysisStatusText(
    protocolLabel: String,
    tint: Color,
    textStyle: TextStyle,
    modifier: Modifier = Modifier,
) {
    val reducedTextStyle =
        if (textStyle.fontSize != TextUnit.Unspecified) {
            textStyle.copy(fontSize = (textStyle.fontSize.value - 2f).coerceAtLeast(1f).sp)
        } else {
            textStyle
        }
    HomeTypewriterProtocolText(
        targetText = protocolLabel,
        prefix = "${stringResource(R.string.home_status_analysis)}: ",
        color = tint,
        textStyle = reducedTextStyle,
        modifier = modifier.offset(y = 2.dp),
    )
}

@Composable
internal fun TrafficStatBlock(
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    labelColor: Color = MaterialTheme.colorScheme.primary,
    leadingContent: (@Composable RowScope.() -> Unit)? = null,
    headerSpacing: Dp = 3.dp,
    leadingContentSpacing: Dp = 1.dp,
    label: String,
    value: String,
    secondary: String,
    valueTag: String? = null,
    secondaryTag: String? = null,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    loading: Boolean = false,
) {
    val textAlign =
        when (horizontalAlignment) {
            Alignment.CenterHorizontally -> TextAlign.Center
            Alignment.End -> TextAlign.End
            else -> TextAlign.Start
        }
    val rowArrangement =
        when (horizontalAlignment) {
            Alignment.CenterHorizontally -> Arrangement.spacedBy(headerSpacing, Alignment.CenterHorizontally)
            Alignment.End -> Arrangement.spacedBy(headerSpacing, Alignment.End)
            else -> Arrangement.spacedBy(headerSpacing, Alignment.Start)
        }
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = labelColor.copy(alpha = 0.11f),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            horizontalAlignment = horizontalAlignment,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = rowArrangement,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (leadingContent != null) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(leadingContentSpacing),
                        verticalAlignment = Alignment.CenterVertically,
                        content = leadingContent,
                    )
                } else if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(11.dp),
                        tint = iconTint,
                    )
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = labelColor,
                    textAlign = textAlign,
                )
            }
            if (loading) {
                FoxholeSkeletonBlock(
                    modifier =
                        (valueTag?.let(Modifier::testTag) ?: Modifier)
                            .fillMaxWidth(0.72f)
                            .height(15.dp),
                    color = labelColor,
                )
                FoxholeSkeletonBlock(
                    modifier =
                        (secondaryTag?.let(Modifier::testTag) ?: Modifier)
                            .fillMaxWidth(0.56f)
                            .height(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                )
            } else {
                Text(
                    text = value,
                    modifier =
                        (valueTag?.let(Modifier::testTag) ?: Modifier)
                            .fillMaxWidth(),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = textAlign,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = secondary,
                    modifier =
                        (secondaryTag?.let(Modifier::testTag) ?: Modifier)
                            .fillMaxWidth(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = textAlign,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
internal fun HomeProfileLoadingBlock() {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(HomeDashboardProfileContentHeight)
                .testTag(HOME_PROFILE_LOADING_TAG),
        verticalArrangement = Arrangement.Center,
    ) {
        HomeProfileLoadingLine(width = 156.dp, height = 18.dp)
        Spacer(modifier = Modifier.height(4.dp))
        HomeProfileLoadingLine(width = 206.dp, height = 12.dp)
        Spacer(modifier = Modifier.height(4.dp))
        HomeProfileLoadingLine(width = 126.dp, height = 12.dp)
    }
}

@Composable
private fun HomeProfileLoadingLine(
    width: Dp,
    height: Dp,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FoxholeSkeletonBlock(
            modifier =
                Modifier
                    .width(width)
                    .height(height),
        )
    }
}

@Composable
internal fun HomeNetworkLoadingBlock(
    title: String,
    labels: List<String>,
    icons: List<ImageVector?> = emptyList(),
    modifier: Modifier = Modifier,
    valueWidth: Dp = HomeNetworkValueLoadingWidth,
    loadingColor: Color = MaterialTheme.colorScheme.surfaceVariant,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        HomeNetworkColumnTitle(title)
        labels.forEachIndexed { index, label ->
            HomeNetworkLoadingLine(
                label = label,
                icon = icons.getOrNull(index),
                valueWidth = valueWidth,
                loadingColor = loadingColor,
            )
            if (index < labels.lastIndex) {
                HomeNetworkSubtleDivider()
            }
        }
    }
}

@Composable
internal fun HomeConnectionStatusLoadingBlock(
    modifier: Modifier = Modifier,
    loadingColor: Color = MaterialTheme.colorScheme.surfaceVariant,
) {
    HomeNetworkLoadingBlock(
        title = stringResource(R.string.home_network_profile_info_title),
        labels =
            listOf(
                stringResource(R.string.home_network_server_ping_label),
                stringResource(R.string.home_network_dns_label),
                stringResource(R.string.home_network_transport_type_label),
                stringResource(R.string.home_network_connect_time_label),
        ),
        icons =
            listOf(
                Icons.Outlined.Speed,
                Icons.Outlined.Dns,
                Icons.Outlined.SwapVert,
                Icons.Outlined.AccessTime,
            ),
        modifier = modifier,
        valueWidth = HomeNetworkMetricValueLoadingWidth,
        loadingColor = loadingColor,
    )
}

@Composable
private fun HomeNetworkLoadingLine(
    label: String,
    icon: ImageVector?,
    valueWidth: Dp,
    loadingColor: Color,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon?.let {
                Icon(
                    imageVector = it,
                    contentDescription = null,
                    modifier = Modifier.size(13.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
                )
            }
            Text(
                text = label,
                modifier = Modifier.weight(1f),
                style =
                    MaterialTheme.typography.labelSmall.copy(
                        fontSize = 11.sp,
                        lineHeight = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.CenterEnd,
        ) {
            FoxholeSkeletonBlock(
                modifier =
                    Modifier
                        .width(valueWidth)
                        .height(HomeNetworkMetricValueLoadingHeight),
                color = loadingColor,
            )
        }
    }
}

@Composable
internal fun rememberConnectionDurationText(snapshot: ConnectionSnapshot): String? {
    if (!shouldShowConnectionDuration(snapshot)) {
        return null
    }
    return rememberElapsedDurationText(startedAt = snapshot.lastChangeAt, key = snapshot.state)
}

@Composable
private fun rememberTorConnectionDurationText(state: HomeRouteUiState): String? {
    val startedAt =
        when {
            state.torOperation.active -> state.torOperation.startedAt
            state.connection.profileId == FoxholeVpnService.TOR_ONLY_PROFILE_ID -> state.connection.lastChangeAt
            state.torIpInfo != null -> state.torIpInfo.fetchedAt
            else -> 0L
        }
    return rememberElapsedDurationText(startedAt = startedAt, key = state.torOperation.kind)
}

@Composable
private fun rememberElapsedDurationText(
    startedAt: Long,
    key: Any?,
): String? {
    if (startedAt <= 0L) {
        return null
    }
    val configuration = LocalConfiguration.current
    val locale =
        remember(configuration) {
            ConfigurationCompat.getLocales(configuration)[0] ?: Locale.getDefault()
        }
    var now by remember(startedAt, key) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startedAt, key) {
        now = System.currentTimeMillis()
        while (isActive) {
            val elapsedMs = (now - startedAt).coerceAtLeast(0L)
            delay(connectionDurationTickDelayMillis(elapsedMs))
            now = System.currentTimeMillis()
        }
    }
    return formatConnectionDuration(
        elapsedMs = (now - startedAt).coerceAtLeast(0L),
        locale = locale,
    )
}

internal fun shouldShowConnectionDuration(snapshot: ConnectionSnapshot): Boolean =
    snapshot.state in setOf(ConnectionState.CONNECTING, ConnectionState.CONNECTED, ConnectionState.RECONNECTING)

internal fun formatConnectionDuration(
    elapsedMs: Long,
    locale: Locale,
): String {
    val totalSeconds = (elapsedMs / 1000L).coerceAtLeast(0L)
    val days = totalSeconds / 86_400L
    val hours = (totalSeconds % 86_400L) / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    val isRussian = locale.language.equals("ru", ignoreCase = true)
    return when {
        days > 0L ->
            if (isRussian) {
                String.format(locale, "%d д %02d ч", days, hours)
            } else {
                String.format(locale, "%d d %02d h", days, hours)
            }
        hours > 0L ->
            if (isRussian) {
                String.format(locale, "%d ч %02d мин", hours, minutes)
            } else {
                String.format(locale, "%d h %02d m", hours, minutes)
            }
        minutes > 0L ->
            if (isRussian) {
                String.format(locale, "%d мин %02d с", minutes, seconds)
            } else {
                String.format(locale, "%d m %02d s", minutes, seconds)
            }
        else ->
            if (isRussian) {
                String.format(locale, "%02d с", seconds)
            } else {
                String.format(locale, "%02d s", seconds)
            }
    }
}

private fun connectionDurationTickDelayMillis(elapsedMs: Long): Long =
    when {
        elapsedMs >= 3_600_000L -> 30_000L
        else -> 1_000L
    }

internal fun dashboardProfileTitle(title: String): String =
    if (title.length <= 25) {
        title
    } else {
        "${title.take(23)}.."
    }

@Suppress("CyclomaticComplexMethod", "LongMethod")
@Composable
internal fun HomeConnectionActions(
    state: HomeRouteUiState,
    onToggleConnection: () -> Unit,
    onAutoConnect: () -> Unit,
    smartStartControlsEnabled: Boolean = true,
) {
    val activeProfile = state.activeProfile
    val torOnlyStartAvailable = homeTorOnlyStartAvailable(state)
    val torOnlyRuntimeActive = state.hasTorOnlyRuntime()
    val showAutoConnectAction = smartStartControlsEnabled && shouldShowAutoConnectAction(activeProfile)
    val autoConnectRunning = state.autoConnect.running
    val protocolRefreshRunning = state.protocolMetricsRefreshing
    val autoConnectEnabled =
        activeProfile != null &&
            !autoConnectRunning &&
            !protocolRefreshRunning &&
            state.connection.state !in setOf(ConnectionState.CONNECTING, ConnectionState.RECONNECTING)
    val smartStartAccent = MaterialTheme.colorScheme.primary
    val autoConnectColor =
        if (autoConnectEnabled) {
            smartStartAccent
        } else {
            smartStartAccent.copy(alpha = 0.46f)
        }
    val primaryAction =
        if (autoConnectRunning || protocolRefreshRunning || state.reconnectInProgress) {
            HomePrimaryAction.STOP
        } else {
            homePrimaryAction(state)
        }
    val primaryButtonColor =
        smartStartAccent
    val primaryButtonColors =
        ButtonDefaults.outlinedButtonColors(
            contentColor = primaryButtonColor,
            containerColor = primaryButtonColor.copy(alpha = 0.06f),
            disabledContentColor = primaryButtonColor.copy(alpha = 0.52f),
            disabledContainerColor = primaryButtonColor.copy(alpha = 0.04f),
        )
    val primaryButtonBorder = BorderStroke(1.dp, primaryButtonColor.copy(alpha = 0.25f))
    val primaryIcon =
        when (primaryAction) {
            HomePrimaryAction.RECONNECT -> Icons.Outlined.Refresh
            else -> Icons.Outlined.PowerSettingsNew
        }
    val primaryButtonInteractionSource = remember(primaryAction) { MutableInteractionSource() }
    val primaryButtonShape = RoundedCornerShape(28.dp)
    val reconnectExpiresAtElapsedMs =
        state.profileReconnectPromptUntilElapsedMs.takeIf {
            primaryAction == HomePrimaryAction.RECONNECT && it > 0L
        }
    val reconnectProgress =
        rememberDeadlineProgress(
            expiresAtElapsedMs = reconnectExpiresAtElapsedMs,
            totalDurationMs = HomeViewModel.PROFILE_RECONNECT_PROMPT_WINDOW_MS,
        )
    val reconnectCountdownColor = primaryButtonColor.copy(alpha = 0.08f)
    val reconnectCountdownModifier =
        if (reconnectExpiresAtElapsedMs != null) {
            Modifier
                .clip(primaryButtonShape)
                .drawBehind {
                    drawRect(
                        color = reconnectCountdownColor,
                        size =
                            Size(
                                width = size.width * reconnectProgress.coerceIn(0f, 1f),
                                height = size.height,
                            ),
                    )
                }
        } else {
            Modifier
        }
    if (!showAutoConnectAction) {
        ClippedOutlinedButton(
            onClick = onToggleConnection,
            enabled = activeProfile != null || torOnlyStartAvailable || torOnlyRuntimeActive,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(HomePrimaryActionHeight)
                    .testTag("home_connect_button")
                    .then(reconnectCountdownModifier),
            interactionSource = primaryButtonInteractionSource,
            shape = primaryButtonShape,
            border = primaryButtonBorder,
            colors = primaryButtonColors,
        ) {
            Icon(primaryIcon, contentDescription = null)
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                if (autoConnectRunning || protocolRefreshRunning || state.reconnectInProgress) {
                    stringResource(R.string.disconnect)
                } else if (torOnlyRuntimeActive) {
                    stringResource(R.string.disconnect_tor)
                } else if (torOnlyStartAvailable && !state.hasPrimaryConnectionRuntime()) {
                    stringResource(R.string.connect_tor)
                } else {
                    homeConnectionLabel(state)
                },
            )
        }
        return
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ClippedOutlinedButton(
            onClick = onToggleConnection,
            enabled = activeProfile != null || torOnlyStartAvailable || torOnlyRuntimeActive,
            modifier =
                Modifier
                    .weight(1f)
                    .height(HomePrimaryActionHeight)
                    .testTag("home_connect_button")
                    .then(reconnectCountdownModifier),
            interactionSource = primaryButtonInteractionSource,
            shape = primaryButtonShape,
            border = primaryButtonBorder,
            colors = primaryButtonColors,
        ) {
            Icon(primaryIcon, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                if (autoConnectRunning || protocolRefreshRunning || state.reconnectInProgress) {
                    stringResource(R.string.disconnect)
                } else if (torOnlyRuntimeActive) {
                    stringResource(R.string.disconnect_tor)
                } else if (torOnlyStartAvailable && !state.hasPrimaryConnectionRuntime()) {
                    stringResource(R.string.connect_tor)
                } else {
                    homeConnectionLabel(state)
                },
            )
        }
        OutlinedButton(
            onClick = onAutoConnect,
            enabled = autoConnectEnabled,
            modifier =
                Modifier
                    .weight(1f)
                    .height(HomePrimaryActionHeight)
                    .testTag("home_auto_connect_button"),
            border = BorderStroke(1.dp, autoConnectColor.copy(alpha = 0.25f)),
            colors =
                ButtonDefaults.outlinedButtonColors(
                    contentColor = autoConnectColor,
                    containerColor = autoConnectColor.copy(alpha = 0.06f),
                    disabledContentColor = autoConnectColor,
                    disabledContainerColor = autoConnectColor.copy(alpha = 0.04f),
                ),
        ) {
            Icon(
                Icons.Outlined.Speed,
                contentDescription = null,
                tint = autoConnectColor,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.auto_connect),
                color = autoConnectColor,
            )
        }
    }
}

@Composable
private fun ClippedOutlinedButton(
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier,
    interactionSource: MutableInteractionSource,
    shape: RoundedCornerShape,
    border: BorderStroke,
    colors: androidx.compose.material3.ButtonColors,
    content: @Composable RowScope.() -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.clip(shape),
        interactionSource = interactionSource,
        shape = shape,
        border = border,
        colors = colors,
        content = content,
    )
}

@Composable
internal fun HomeAutoConnectStatusLine(
    state: AutoConnectUiState,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = MaterialTheme.typography.titleMedium,
) {
    val protocolLabel = autoConnectAnalysisProtocolLabel(state) ?: stringResource(R.string.auto_connect)
    val tone = FoxholeAnalysisAccent
    Row(
        modifier =
            modifier
                .offset(x = HomeConnectingStatusSignalOffset)
                .testTag("home_auto_connect_status_line"),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HomeAnalysisSignal(
            tint = tone,
            modifier = Modifier,
        )
        HomeAnalysisStatusText(
            protocolLabel = protocolLabel,
            tint = tone,
            textStyle = textStyle,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

internal fun autoConnectAnalysisProtocolLabel(state: AutoConnectUiState): String? {
    val option =
        state.options.firstOrNull { it.optionId == state.currentOptionId }
            ?: state.options.firstOrNull()
    return state.currentProtocolHint
        ?.let(::protocolHintChipLabel)
        ?: option?.let { protocolHintChipLabel(it.protocolHint) }
}

@Composable
internal fun HomeAnalysisSignal(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    HomeJellyTriangleLoader(
        modifier = modifier,
        color = tint,
        indicatorSize = HomeTriangleIndicatorSize,
    )
}

@Composable
internal fun HomeJellyTriangleLoader(
    modifier: Modifier = Modifier,
    color: Color,
    indicatorSize: Dp = 15.dp,
) {
    val infinite = rememberInfiniteTransition(label = "home_jelly_triangle")
    val travelerProgress =
        infinite.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec =
                infiniteRepeatable(
                    animation =
                        keyframes {
                            durationMillis = 1750
                            0f at 0
                            1f / 3f at 583
                            2f / 3f at 1166
                            1f at 1750
                        },
                    repeatMode = RepeatMode.Restart,
                ),
            label = "home_jelly_triangle_progress",
        )
    val topScale =
        animateJellyDotScale(
            infinite = infinite,
            label = "home_jelly_triangle_top",
            fastForwardMillis = 0,
        )
    val rightScale =
        animateJellyDotScale(
            infinite = infinite,
            label = "home_jelly_triangle_right",
            fastForwardMillis = 1167,
        )
    val leftScale =
        animateJellyDotScale(
            infinite = infinite,
            label = "home_jelly_triangle_left",
            fastForwardMillis = 583,
        )
    Canvas(
        modifier =
            modifier
                .width(indicatorSize + (HomeTriangleIndicatorCanvasBleed * 2))
                .height(indicatorSize),
    ) {
        val canvasSize = size.height
        val anchors = homeTriangleAnchors(canvasSize).withHorizontalOffset((size.width - canvasSize) / 2f)
        val dotRadius = canvasSize * 0.16f
        drawCircle(
            color = color,
            radius = dotRadius * topScale,
            center = anchors[0],
        )
        drawCircle(
            color = color,
            radius = dotRadius * rightScale,
            center = anchors[2],
        )
        drawCircle(
            color = color,
            radius = dotRadius * leftScale,
            center = anchors[1],
        )
        drawCircle(
            color = color,
            radius = dotRadius,
            center = triangleTravelerCenter(progress = travelerProgress.value, anchors = anchors),
        )
    }
}

internal fun homeTriangleAnchors(sizePx: Float): List<Offset> =
    listOf(
        Offset(x = sizePx * 0.465f, y = sizePx * 0.225f),
        Offset(x = sizePx * 0.165f, y = sizePx * 0.775f),
        Offset(x = sizePx * 0.835f, y = sizePx * 0.775f),
    )

private fun List<Offset>.withHorizontalOffset(offsetPx: Float): List<Offset> =
    map { anchor -> Offset(x = anchor.x + offsetPx, y = anchor.y) }

internal fun triangleTravelerCenter(
    progress: Float,
    anchors: List<Offset>,
): Offset {
    val normalizedProgress = progress.coerceIn(0f, 1f)
    return when {
        normalizedProgress <= (1f / 3f) ->
            lerpOffset(anchors[0], anchors[2], normalizedProgress * 3f)
        normalizedProgress <= (2f / 3f) ->
            lerpOffset(anchors[2], anchors[1], (normalizedProgress - (1f / 3f)) * 3f)
        else ->
            lerpOffset(anchors[1], anchors[0], (normalizedProgress - (2f / 3f)) * 3f)
    }
}

internal fun lerpOffset(
    start: Offset,
    end: Offset,
    fraction: Float,
): Offset {
    val safeFraction = fraction.coerceIn(0f, 1f)
    return Offset(
        x = start.x + ((end.x - start.x) * safeFraction),
        y = start.y + ((end.y - start.y) * safeFraction),
    )
}

@Composable
internal fun animateJellyDotScale(
    infinite: InfiniteTransition,
    label: String,
    fastForwardMillis: Int,
): Float =
    infinite.animateFloat(
        initialValue = 1.5f,
        targetValue = 1.5f,
        animationSpec =
            infiniteRepeatable(
                animation =
                    keyframes {
                        durationMillis = 1750
                        1.5f at 0
                        1f at 350
                        1f at 1225
                        1.5f at 1750
                    },
                repeatMode = RepeatMode.Restart,
                initialStartOffset = StartOffset(fastForwardMillis, StartOffsetType.FastForward),
            ),
        label = label,
    ).value

@Composable
internal fun ProxyCredentialRow(
    label: String,
    value: String,
    onEdit: (() -> Unit)? = null,
    editContentDescription: String? = null,
    onCopy: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        onEdit?.let { edit ->
            HomeHeaderActionButton(
                icon = Icons.Outlined.Edit,
                contentDescription = editContentDescription,
                onClick = edit,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        HomeHeaderActionButton(
            icon = Icons.Outlined.ContentCopy,
            contentDescription = stringResource(R.string.copy_to_clipboard),
            onClick = onCopy,
            modifier = Modifier.size(32.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}
