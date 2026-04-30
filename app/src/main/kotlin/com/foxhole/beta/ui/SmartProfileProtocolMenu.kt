package com.foxhole.beta.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foxhole.beta.R
import com.foxhole.beta.core.model.LatencyProbeMethod
import com.foxhole.beta.core.model.Profile
import com.foxhole.beta.core.model.ProfileProtocolOption
import com.foxhole.beta.core.model.ProtocolHint
import com.foxhole.beta.core.profile.MultiProtocolProfileSupport
import kotlin.math.max

@Composable
@Suppress("LongParameterList")
internal fun SmartProfileAutoConnectMenu(
    profile: Profile,
    excludedOptionIds: Set<String>,
    onUpdateExcludedOptionIds: (Set<String>) -> Unit,
    latencyByOptionId: Map<String, Long> = emptyMap(),
    unavailableOptionIds: Set<String> = emptySet(),
    latencyUnavailableOptionIds: Set<String> = emptySet(),
    serverPingByOptionId: Map<String, Long> = emptyMap(),
    serverPingUnavailableOptionIds: Set<String> = emptySet(),
    metricsUpdatedAtByOptionId: Map<String, Long> = emptyMap(),
    metricsRefreshing: Boolean = false,
    refreshingOptionId: String? = null,
    recommendedOptionId: String? = null,
    recommendedOptionIds: Set<String> = recommendedOptionId?.let(::setOf).orEmpty(),
    favoriteOptionId: String? = null,
    activeOptionId: String? = profile.selectedProtocolOptionId ?: MultiProtocolProfileSupport.selectedOption(profile)?.id,
    onRefreshMetrics: (() -> Unit)? = null,
    onCancelRefreshMetrics: (() -> Unit)? = null,
    refreshWarningRequired: Boolean = false,
    showLatency: Boolean = false,
    compact: Boolean = false,
    enabled: Boolean = true,
    actionIconSize: Dp? = null,
    showMetricsTable: Boolean = true,
    showStatusHeader: Boolean = false,
    showTransportBadges: Boolean = false,
    latencyProbeMethod: LatencyProbeMethod = LatencyProbeMethod.HTTP,
) {
    val options = MultiProtocolProfileSupport.supportedOptions(profile)
    if (options.size < 2) {
        return
    }
    val menuLayout = resolveSmartStartProtocolMenuLayout(showMetricsTable = showMetricsTable)
    var expanded by rememberSaveable(profile.id) { mutableStateOf(false) }
    var showRefreshWarning by rememberSaveable(profile.id) { mutableStateOf(false) }
    val requestRefreshMetrics: () -> Unit = {
        if (refreshWarningRequired) {
            showRefreshWarning = true
        } else {
            onRefreshMetrics?.invoke()
        }
    }
    Box {
        val density = LocalDensity.current
        val screenWidth = with(density) { LocalWindowInfo.current.containerSize.width.toDp() }
        val menuWidth =
            rememberSmartProfileMenuWidth(
                options = options,
                menuLayout = menuLayout,
                excludedOptionIds = excludedOptionIds,
                latencyByOptionId = latencyByOptionId,
                unavailableOptionIds = unavailableOptionIds,
                latencyUnavailableOptionIds = latencyUnavailableOptionIds,
                serverPingByOptionId = serverPingByOptionId,
                serverPingUnavailableOptionIds = serverPingUnavailableOptionIds,
                recommendedOptionId = recommendedOptionId,
                recommendedOptionIds = recommendedOptionIds,
                favoriteOptionId = favoriteOptionId,
                compact = compact,
                showTransportBadges = showTransportBadges,
                showStatusHeader = showStatusHeader,
                maxWidth = (screenWidth - ScreenHorizontalPadding - ScreenHorizontalPadding).coerceAtLeast(SmartProfileMenuCompactMinWidth),
                showRefreshHeader = menuLayout.showHeader || onRefreshMetrics != null,
                latencyProbeMethod = latencyProbeMethod,
            )
        Surface(
            modifier =
                Modifier
                    .size(if (compact) 30.dp else 36.dp)
                    .clip(CircleShape)
                    .clickable(enabled = enabled) { expanded = true }
                    .testTag("smart_profile_auto_connect_menu_action"),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f),
            border =
                BorderStroke(
                    1.dp,
                    foxholeSystemAwareAccentColor(fallback = FoxholeInfoAccent).copy(alpha = 0.18f),
                ),
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Settings,
                    contentDescription = stringResource(R.string.smart_profile_menu_title),
                    modifier = Modifier.size(actionIconSize ?: if (compact) 16.dp else 20.dp),
                    tint = foxholeSystemAwareAccentColor(fallback = FoxholeInfoAccent),
                )
            }
        }
        FoxholeDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier =
                Modifier
                    .testTag("smart_profile_auto_connect_menu")
                    .width(menuWidth),
            offset = DpOffset(x = 0.dp, y = if (compact) (-6).dp else 0.dp),
        ) {
            SmartProfileProtocolMenuContent(
                options = options,
                menuLayout = menuLayout,
                excludedOptionIds = excludedOptionIds,
                onUpdateExcludedOptionIds = onUpdateExcludedOptionIds,
                latencyByOptionId = latencyByOptionId,
                unavailableOptionIds = unavailableOptionIds,
                latencyUnavailableOptionIds = latencyUnavailableOptionIds,
                serverPingByOptionId = serverPingByOptionId,
                serverPingUnavailableOptionIds = serverPingUnavailableOptionIds,
                metricsUpdatedAtByOptionId = metricsUpdatedAtByOptionId,
                metricsRefreshing = metricsRefreshing,
                refreshingOptionId = refreshingOptionId,
                recommendedOptionId = recommendedOptionId,
                recommendedOptionIds = recommendedOptionIds,
                favoriteOptionId = favoriteOptionId,
                activeOptionId = activeOptionId,
                onRefreshMetrics = onRefreshMetrics?.let { requestRefreshMetrics },
                onCancelRefreshMetrics = onCancelRefreshMetrics,
                compact = compact,
                showTransportBadges = showTransportBadges,
                showStatusHeader = showStatusHeader,
                latencyProbeMethod = latencyProbeMethod,
            )
        }
    }
    if (showRefreshWarning) {
        ConfirmDialog(
            title = stringResource(R.string.smart_profile_metrics_refresh_confirm_title),
            body = stringResource(R.string.smart_profile_metrics_refresh_confirm_body),
            confirmLabel = stringResource(R.string.refresh),
            icon = Icons.Outlined.Refresh,
            dismissLabel = stringResource(R.string.cancel),
            onDismiss = { showRefreshWarning = false },
            onConfirm = {
                showRefreshWarning = false
                onRefreshMetrics?.invoke()
            },
        )
    }
}

@Composable
@Suppress("CyclomaticComplexMethod", "LongMethod", "LongParameterList")
private fun rememberSmartProfileMenuWidth(
    options: List<ProfileProtocolOption>,
    menuLayout: SmartStartProtocolMenuLayout,
    excludedOptionIds: Set<String>,
    latencyByOptionId: Map<String, Long>,
    unavailableOptionIds: Set<String>,
    latencyUnavailableOptionIds: Set<String>,
    serverPingByOptionId: Map<String, Long>,
    serverPingUnavailableOptionIds: Set<String>,
    recommendedOptionId: String?,
    recommendedOptionIds: Set<String>,
    favoriteOptionId: String?,
    compact: Boolean,
    showTransportBadges: Boolean,
    showStatusHeader: Boolean,
    maxWidth: Dp,
    showRefreshHeader: Boolean,
    latencyProbeMethod: LatencyProbeMethod,
): Dp {
    val density = LocalDensity.current
    val metricColumnWidthPx = with(density) { SmartProfileMetricColumnWidth.toPx() }
    val textMeasurer = rememberTextMeasurer()
    val title = stringResource(R.string.smart_profile_menu_title)
    val favoriteLegend = stringResource(R.string.smart_profile_legend_favorite)
    val recommendedLegend = stringResource(R.string.smart_profile_legend_reconnect_recommended)
    val protocolLabel = stringResource(R.string.smart_profile_menu_protocol_column)
    val statusLabel = stringResource(R.string.smart_profile_menu_status_column)
    val dashboardOnLabel = stringResource(R.string.smart_profile_menu_dashboard_on_column)
    val serverPingLabel = stringResource(R.string.smart_profile_menu_server_ping_column)
    val vpnLatencyLabel = smartProfileLatencyColumnLabel(latencyProbeMethod)
    val unavailableMetric = stringResource(R.string.smart_profile_metric_unavailable)
    val downMetric = stringResource(R.string.latency_pill_down)
    val neverUpdatedLabel = stringResource(R.string.smart_profile_metrics_never_updated)
    val refreshHint =
        stringResource(
            if (compact) {
                R.string.smart_profile_metrics_refresh_compact_hint
            } else {
                R.string.smart_profile_metrics_refresh_hint
            },
        )
    val updatedSampleLabel =
        stringResource(
            R.string.smart_profile_metrics_last_updated,
            stringResource(R.string.smart_profile_metrics_ago_weeks, 99),
        )
    val availableStatus = stringResource(R.string.smart_start_protocol_status_available)
    val slowStatus = stringResource(R.string.smart_start_protocol_status_slow)
    val noDataStatus = stringResource(R.string.smart_start_protocol_status_no_data)
    val disabledStatus = stringResource(R.string.smart_start_protocol_status_disabled)
    val unavailableStatus = stringResource(R.string.latency_pill_unavailable)
    val fastLabel = stringResource(R.string.latency_quality_fast)
    val normalLabel = stringResource(R.string.latency_quality_normal)
    val slowLabel = stringResource(R.string.latency_quality_slow)
    val verySlowLabel = stringResource(R.string.latency_quality_very_slow)
    val compactStatusRows = menuLayout.showCompactStatusRows
    val headerStyle =
        MaterialTheme.typography.labelSmall.copy(
            fontSize = if (compact) 10.5.sp else 12.sp,
            lineHeight = if (compact) 11.5.sp else 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
    val protocolStyle =
        MaterialTheme.typography.labelSmall.copy(
            fontSize = 13.sp,
            lineHeight = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
    val metricLabelStyle =
        MaterialTheme.typography.labelSmall.copy(
            fontSize = 8.6.sp,
            lineHeight = 9.sp,
            fontWeight = FontWeight.Medium,
        )
    val metricValueStyle =
        MaterialTheme.typography.labelSmall.copy(
            fontSize = 9.sp,
            lineHeight = 10.sp,
            fontWeight = FontWeight.SemiBold,
        )
    val legendStyle =
        MaterialTheme.typography.labelSmall.copy(
            fontSize = if (compactStatusRows) 8.4.sp else 10.sp,
            lineHeight = if (compactStatusRows) 9.sp else 11.sp,
            fontWeight = FontWeight.Medium,
        )
    val hintStyle =
        MaterialTheme.typography.labelSmall.copy(
            fontSize = if (compact) 8.4.sp else 10.sp,
            lineHeight = if (compact) 9.sp else 12.sp,
            fontWeight = FontWeight.Medium,
        )
    val refreshButtonWidth =
        if (compactStatusRows) {
            SmartProfileMenuCompactRefreshButtonWidth
        } else {
            SmartProfileMenuRefreshButtonWidth
        }
    fun textWidth(text: String, style: androidx.compose.ui.text.TextStyle): Float =
        textMeasurer.measure(
            text = AnnotatedString(text),
            style = style,
        ).size.width.toFloat()
    fun metricText(
        latencyMs: Long?,
        down: Boolean = false,
        unavailable: Boolean = false,
    ): String =
        when {
            down -> downMetric
            latencyMs != null -> "${boundedDisplayLatencyMs(latencyMs)} ms"
            unavailable -> unavailableMetric
            else -> unavailableMetric
        }
    fun statusText(
        included: Boolean,
        latencyMs: Long?,
        latencyDown: Boolean,
        latencyUnavailable: Boolean,
    ): String {
        val presentation =
            resolveSmartStartProtocolPresentation(
                included = included,
                latencyMs = latencyMs,
                latencyDown = latencyDown,
                latencyUnavailable = latencyUnavailable,
            )
        return when {
            presentation.status == SmartStartProtocolStatus.DISABLED -> disabledStatus
            latencyDown -> downMetric
            latencyMs != null && !latencyUnavailable ->
                when (classifyVpnLatency(latencyMs = latencyMs, failed = false, unavailable = false)) {
                    LatencyQuality.FAST -> fastLabel
                    LatencyQuality.NORMAL -> normalLabel
                    LatencyQuality.SLOW -> slowLabel
                    LatencyQuality.VERY_SLOW -> verySlowLabel
                    LatencyQuality.UNAVAILABLE,
                    LatencyQuality.FAILED,
                    -> presentation.status.label(
                        available = availableStatus,
                        slow = slowStatus,
                        failed = downMetric,
                        unavailable = unavailableStatus,
                        noData = noDataStatus,
                        disabled = disabledStatus,
                    )
                }
            else ->
                presentation.status.label(
                    available = availableStatus,
                    slow = slowStatus,
                    failed = downMetric,
                    unavailable = unavailableStatus,
                    noData = noDataStatus,
                    disabled = disabledStatus,
                )
        }
    }
    val headerTitleWidthPx =
        if (compactStatusRows) {
            title.split(' ').maxOfOrNull { word -> textWidth(word, headerStyle) } ?: textWidth(title, headerStyle)
        } else {
            textWidth(title, headerStyle)
        }
    val headerWidthPx =
        if (showRefreshHeader) {
            maxOf(
                headerTitleWidthPx + with(density) { refreshButtonWidth.toPx() + 3.dp.toPx() },
                textWidth(neverUpdatedLabel, metricValueStyle),
                textWidth(updatedSampleLabel, metricValueStyle),
            )
        } else {
            0f
        }
    val showLegendFooter = menuLayout.showDetailedMetrics || menuLayout.showCompactStatusRows
    val legendWidthPx =
        if (showLegendFooter) {
            textWidth(favoriteLegend, legendStyle) +
                textWidth(recommendedLegend, legendStyle) +
                with(density) {
                    if (compactStatusRows) {
                        54.dp.toPx()
                    } else {
                        72.dp.toPx()
                    }
                }
        } else {
            0f
        }
    val hintWidthPx =
        if (menuLayout.showHeader) {
            val widestHintLine =
                refreshHint
                    .split('\n')
                    .maxOfOrNull { line -> textWidth(line, hintStyle) }
                    ?: 0f
            minOf(
                widestHintLine + with(density) { (SmartProfileHintIconSize + SmartProfileHintIconGap).toPx() },
                with(density) { SmartProfileHintBasisMaxWidth.toPx() },
            )
        } else {
            0f
        }
    val statusHeaderWidthPx =
        if (showStatusHeader && menuLayout.showCompactStatusRows) {
            textWidth(protocolLabel, metricLabelStyle) +
                maxOf(
                    textWidth(statusLabel, metricLabelStyle),
                    with(density) {
                        if (compactStatusRows) {
                            SmartProfileCompactStatusColumnWidth.toPx()
                        } else {
                            SmartProfileStatusColumnWidth.toPx()
                        }
                    },
                ) +
                maxOf(
                    textWidth(dashboardOnLabel, metricLabelStyle),
                    with(density) { SmartProfileOnColumnWidth.toPx() },
                ) +
                with(density) { 24.dp.toPx() }
        } else {
            0f
        }
    val rowWidthPx =
        options.maxOfOrNull { option ->
            val included = option.id !in excludedOptionIds
            val latencyMs = latencyByOptionId[option.id]
            val latencyDown = option.id in unavailableOptionIds
            val latencyUnavailable = option.id in latencyUnavailableOptionIds
            val recommended = option.id in recommendedOptionIds && !latencyDown
            val favorite = option.id == favoriteOptionId
            val favoriteBadgeWidth = if (favorite) with(density) { 14.dp.toPx() } else 0f
            val recommendedBadgeWidth =
                if (recommended) {
                    with(density) { if (option.id == recommendedOptionId) 24.dp.toPx() else 14.dp.toPx() }
                } else {
                    0f
                }
            val selectionBadgeWidth =
                favoriteBadgeWidth + recommendedBadgeWidth
            val transportBadgeWidth = if (showTransportBadges) with(density) { 31.dp.toPx() } else 0f
            val protocolColumnWidth =
                textWidth(protocolDisplayLabel(option.protocolHint), protocolStyle) +
                    selectionBadgeWidth +
                    transportBadgeWidth +
                    with(density) {
                        if (menuLayout.showCompactStatusRows) {
                            26.dp.toPx()
                        } else {
                            18.dp.toPx()
                        }
                    }
            if (menuLayout.showDetailedMetrics) {
                val serverValue = metricText(
                    latencyMs = serverPingByOptionId[option.id],
                    unavailable =
                        option.id in serverPingUnavailableOptionIds &&
                            option.id !in serverPingByOptionId,
                )
                val vpnValue = metricText(
                    latencyMs = latencyMs,
                    down = latencyDown,
                    unavailable = latencyUnavailable,
                )
                val serverColumnWidth =
                    maxOf(
                        metricColumnWidthPx,
                        textWidth(serverPingLabel, metricLabelStyle),
                        textWidth(serverValue, metricValueStyle),
                    )
                val vpnColumnWidth =
                    maxOf(
                        metricColumnWidthPx,
                        textWidth(vpnLatencyLabel, metricLabelStyle),
                        textWidth(vpnValue, metricValueStyle),
                    )
                protocolColumnWidth +
                    serverColumnWidth +
                    vpnColumnWidth +
                    with(density) { (SmartProfileOnColumnWidth + 12.dp).toPx() }
            } else {
                val statusValueWidth =
                    maxOf(
                        textWidth(
                            statusText(included, latencyMs, latencyDown, latencyUnavailable),
                            metricValueStyle,
                        ),
                        with(density) { SmartProfileCompactStatusColumnWidth.toPx() },
                    )
                protocolColumnWidth +
                    statusValueWidth +
                    with(density) { (SmartProfileOnColumnWidth + 18.dp).toPx() }
            }
        } ?: 0f
    val protocolAndLegendWidthPx = maxOf(legendWidthPx, rowWidthPx)
    val contentBasisWidthPx =
        smartProfileMenuWidthBasisPx(
            protocolAndLegendWidthPx = protocolAndLegendWidthPx,
            hintWidthPx = hintWidthPx,
        )
    val contentWidthPx =
        maxOf(
            contentBasisWidthPx,
            statusHeaderWidthPx,
            if (menuLayout.showDetailedMetrics) 0f else headerWidthPx,
        ) + with(density) {
            (
                SmartProfileMenuHorizontalPadding * 2 +
                    if (compactStatusRows) {
                        4.dp
                    } else {
                        10.dp
                    }
            ).toPx()
        }
    val measuredWidth = with(density) { contentWidthPx.toDp() }
    val minAllowedWidth =
        if (menuLayout.showDetailedMetrics) {
            SmartProfileMenuDetailedMinWidth
        } else {
            SmartProfileMenuCompactMinWidth
        }
    val menuMaxWidth =
        if (menuLayout.showCompactStatusRows) {
            SmartProfileMenuCompactMaxWidth
        } else {
            SmartProfileMenuMaxWidth
        }
    val maxAllowedWidth = maxWidth.coerceAtLeast(minAllowedWidth).coerceAtMost(menuMaxWidth)
    return measuredWidth.coerceIn(minAllowedWidth, maxAllowedWidth)
}

internal fun smartProfileMenuWidthBasisPx(
    protocolAndLegendWidthPx: Float,
    hintWidthPx: Float,
): Float = maxOf(protocolAndLegendWidthPx, hintWidthPx)

private fun SmartStartProtocolStatus.label(
    available: String,
    slow: String,
    failed: String,
    unavailable: String,
    noData: String,
    disabled: String,
): String =
    when (this) {
        SmartStartProtocolStatus.AVAILABLE -> available
        SmartStartProtocolStatus.SLOW -> slow
        SmartStartProtocolStatus.RECENTLY_FAILED -> failed
        SmartStartProtocolStatus.UNAVAILABLE -> unavailable
        SmartStartProtocolStatus.NO_DATA -> noData
        SmartStartProtocolStatus.DISABLED -> disabled
    }

@Composable
@Suppress("CyclomaticComplexMethod", "LongMethod", "LongParameterList")
private fun SmartProfileProtocolMenuContent(
    options: List<ProfileProtocolOption>,
    menuLayout: SmartStartProtocolMenuLayout,
    excludedOptionIds: Set<String>,
    onUpdateExcludedOptionIds: (Set<String>) -> Unit,
    latencyByOptionId: Map<String, Long>,
    unavailableOptionIds: Set<String>,
    latencyUnavailableOptionIds: Set<String>,
    serverPingByOptionId: Map<String, Long>,
    serverPingUnavailableOptionIds: Set<String>,
    metricsUpdatedAtByOptionId: Map<String, Long>,
    metricsRefreshing: Boolean,
    refreshingOptionId: String?,
    recommendedOptionId: String?,
    recommendedOptionIds: Set<String>,
    favoriteOptionId: String?,
    activeOptionId: String?,
    onRefreshMetrics: (() -> Unit)?,
    onCancelRefreshMetrics: (() -> Unit)?,
    compact: Boolean,
    showTransportBadges: Boolean,
    showStatusHeader: Boolean,
    latencyProbeMethod: LatencyProbeMethod,
) {
    Column(
        modifier = Modifier.padding(if (menuLayout.showDetailedMetrics) 2.dp else 0.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        SmartProfileProtocolMenuHeaderContent(
            options = options,
            menuLayout = menuLayout,
            metricsUpdatedAtByOptionId = metricsUpdatedAtByOptionId,
            metricsRefreshing = metricsRefreshing,
            refreshingOptionId = refreshingOptionId,
            onRefreshMetrics = onRefreshMetrics,
            onCancelRefreshMetrics = onCancelRefreshMetrics,
            compact = compact,
        )
        val showCompactStatusHeader = menuLayout.showCompactStatusRows && showStatusHeader
        val hasMenuHeader = menuLayout.showHeader || onRefreshMetrics != null || showCompactStatusHeader
        val hasFooter = menuLayout.showDetailedMetrics || menuLayout.showCompactStatusRows
        if (menuLayout.showDetailedMetrics) {
            SmartProfileProtocolTableHeader(compact = true, latencyProbeMethod = latencyProbeMethod)
        } else if (showCompactStatusHeader) {
            SmartProfileProtocolStatusHeader(compact = true)
        }
        options.forEachIndexed { index, option ->
            val included = option.id !in excludedOptionIds
            val includedCount = options.count { candidate -> candidate.id !in excludedOptionIds }
            val active = option.id == activeOptionId
            val latencyMs = latencyByOptionId[option.id]
            val latencyDown = option.id in unavailableOptionIds
            val latencyUnavailable = option.id in latencyUnavailableOptionIds
            val recommended = option.id in recommendedOptionIds && !latencyDown
            val topRecommended = recommended && option.id == recommendedOptionId
            val favorite = option.id == favoriteOptionId
            val includedSelection = included && active
            val refreshingSelection = metricsRefreshing && option.id == refreshingOptionId
            val selectionTone = foxholeSystemAwareAccentColor(fallback = FoxholeInfoAccent)
            val activeAccent =
                smartProfileCurrentProtocolAccent(
                    latencyMs = latencyMs,
                    down = latencyDown,
                    unavailable = latencyUnavailable,
                )
            FoxholeDropdownItem(
                onClick = {
                    val nextExcluded =
                        if (included) {
                            if (includedCount <= 1) {
                                null
                            } else {
                                excludedOptionIds + option.id
                            }
                        } else {
                            excludedOptionIds - option.id
                        }
                    nextExcluded?.let(onUpdateExcludedOptionIds)
                },
                selected = includedSelection || refreshingSelection,
                highlightSelected = includedSelection || refreshingSelection,
                accentColor =
                    when {
                        refreshingSelection -> selectionTone
                        included && recommended -> FoxholePositiveAccent
                        included && active -> activeAccent
                        else -> FoxholePositiveAccent
                    },
                selectedContainerColor =
                    if (refreshingSelection) {
                        selectionTone.copy(alpha = 0.12f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f)
                    },
                shape =
                    foxholeDropdownItemShape(
                        index = index,
                        lastIndex = options.lastIndex,
                        hasHeader = hasMenuHeader,
                        hasFooter = hasFooter,
                    ),
                showBorder = index != options.lastIndex,
                minHeight =
                    if (menuLayout.showDetailedMetrics) {
                        SmartProfileProtocolAdaptiveCompactRowHeight
                    } else {
                        SmartProfileProtocolSimpleCompactRowHeight
                    },
                contentPadding =
                    PaddingValues(
                        horizontal = SmartProfileMenuHorizontalPadding,
                        vertical = 0.dp,
                    ),
            ) {
                if (menuLayout.showDetailedMetrics) {
                    SmartProfileProtocolMetricsTableRow(
                        option = option,
                        compact = true,
                        included = included,
                        recommended = recommended,
                        topRecommended = topRecommended,
                        favorite = favorite,
                        serverPingMs = serverPingByOptionId[option.id],
                        serverPingUnavailable =
                            option.id in serverPingUnavailableOptionIds &&
                                option.id !in serverPingByOptionId,
                        latencyMs = latencyMs,
                        latencyDown = latencyDown,
                        latencyUnavailable = latencyUnavailable,
                        metricsEnabled = included,
                        metricsRefreshing = refreshingSelection,
                        showTransportBadge = showTransportBadges,
                        transportKnown =
                            option.id in metricsUpdatedAtByOptionId ||
                                option.id in latencyByOptionId ||
                                option.id in serverPingByOptionId ||
                                option.id in serverPingUnavailableOptionIds ||
                                option.id in unavailableOptionIds ||
                                option.id in latencyUnavailableOptionIds,
                    )
                } else {
                    SmartProfileProtocolSimpleMenuRow(
                        option = option,
                        compact = true,
                        included = included,
                        recommended = recommended,
                        topRecommended = topRecommended,
                        favorite = favorite,
                        showSelectionBadge = true,
                        latencyMs = latencyMs,
                        latencyDown = latencyDown,
                        latencyUnavailable = latencyUnavailable,
                        showTransportBadge = showTransportBadges,
                    )
                }
            }
        }
        SmartProfileProtocolMenuFooterContent(
            menuLayout = menuLayout,
        )
    }
}

@Composable
private fun SmartProfileProtocolMenuHeaderContent(
    options: List<ProfileProtocolOption>,
    menuLayout: SmartStartProtocolMenuLayout,
    metricsUpdatedAtByOptionId: Map<String, Long>,
    metricsRefreshing: Boolean,
    refreshingOptionId: String?,
    onRefreshMetrics: (() -> Unit)?,
    onCancelRefreshMetrics: (() -> Unit)?,
    compact: Boolean,
) {
    val refreshingProtocolLabel =
        options.firstOrNull { option -> option.id == refreshingOptionId }?.let { option ->
            protocolDisplayLabel(option.protocolHint)
        }
    when {
        menuLayout.showHeader -> {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SmartProfileProtocolMenuTitleRow(
                    metricsUpdatedAtByOptionId = metricsUpdatedAtByOptionId,
                    metricsRefreshing = metricsRefreshing,
                    refreshingProtocolLabel = refreshingProtocolLabel,
                    onRefreshMetrics = onRefreshMetrics,
                    onCancelRefreshMetrics = onCancelRefreshMetrics,
                    compact = compact,
                )
                SmartProfileMetricsHint(
                    compact = false,
                    showIcon = false,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 10.dp, end = 10.dp, bottom = 6.dp),
                )
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 10.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.34f),
                )
            }
        }
        onRefreshMetrics != null -> {
            SmartProfileProtocolMenuTitleRow(
                metricsUpdatedAtByOptionId = metricsUpdatedAtByOptionId,
                metricsRefreshing = metricsRefreshing,
                refreshingProtocolLabel = refreshingProtocolLabel,
                onRefreshMetrics = onRefreshMetrics,
                onCancelRefreshMetrics = onCancelRefreshMetrics,
                compact = true,
            )
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 8.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.24f),
            )
        }
    }
}

@Composable
private fun SmartProfileProtocolMenuTitleRow(
    metricsUpdatedAtByOptionId: Map<String, Long>,
    metricsRefreshing: Boolean,
    refreshingProtocolLabel: String?,
    onRefreshMetrics: (() -> Unit)?,
    onCancelRefreshMetrics: (() -> Unit)?,
    compact: Boolean,
    showRefreshButton: Boolean = true,
) {
    val updatedAt = metricsUpdatedAtByOptionId.values.maxOrNull()
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(
                    start = 8.dp,
                    top = if (compact) 5.dp else 6.dp,
                    end = 8.dp,
                    bottom = if (compact) 5.dp else 6.dp,
                ),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(if (compact) 1.dp else 3.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.smart_profile_menu_title),
                    modifier = Modifier.weight(1f),
                    style =
                        MaterialTheme.typography.labelSmall.copy(
                            fontSize = if (compact) 10.5.sp else 12.sp,
                            lineHeight = if (compact) 11.5.sp else 13.sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (showRefreshButton) {
                    SmartProfileMetricsRefreshButton(
                        refreshing = metricsRefreshing,
                        onRefreshMetrics = onRefreshMetrics,
                        onCancelRefreshMetrics = onCancelRefreshMetrics,
                        compact = compact,
                    )
                }
            }
            SmartProfileMetricsRefreshStatus(
                updatedAt = updatedAt,
                refreshing = metricsRefreshing,
                refreshingProtocolLabel = refreshingProtocolLabel,
                compact = compact,
            )
        }
    }
}

@Composable
private fun SmartProfileProtocolMenuFooterContent(
    menuLayout: SmartStartProtocolMenuLayout,
) {
    if (!menuLayout.showDetailedMetrics && !menuLayout.showCompactStatusRows) {
        return
    }
    val footerLayout = resolveSmartStartProtocolLegendFooterLayout(menuLayout)
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = if (menuLayout.showDetailedMetrics) 10.dp else 8.dp),
        color =
            MaterialTheme.colorScheme.outlineVariant.copy(
                alpha = if (menuLayout.showDetailedMetrics) 0.34f else 0.24f,
            ),
    )
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(
                    start = if (menuLayout.showDetailedMetrics) 10.dp else 8.dp,
                    top = footerLayout.topPaddingDp.dp,
                    end = if (menuLayout.showDetailedMetrics) 10.dp else 8.dp,
                    bottom = footerLayout.bottomPaddingDp.dp,
                ),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.Start,
    ) {
        SmartProfileLegendLine(
            compact = !menuLayout.showDetailedMetrics,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SmartProfileMetricsHint(
    compact: Boolean,
    modifier: Modifier = Modifier,
    showIcon: Boolean = true,
) {
    val infoTone = foxholeSystemAwareAccentColor(fallback = FoxholeInfoAccent)
    val refreshHint =
        stringResource(
            if (compact) {
                R.string.smart_profile_metrics_refresh_compact_hint
            } else {
                R.string.smart_profile_metrics_refresh_hint
            },
        )
    val hintLines = refreshHint.split('\n', limit = 2)
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(if (showIcon) SmartProfileHintIconGap else 0.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (showIcon) {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = null,
                modifier = Modifier.size(if (compact) 10.dp else SmartProfileHintIconSize),
                tint = infoTone,
            )
        }
        Column(
            modifier = Modifier.weight(1f, fill = false),
            verticalArrangement = Arrangement.spacedBy(if (compact) 1.dp else 2.dp),
        ) {
            Text(
                text = hintLines.firstOrNull().orEmpty(),
                style =
                    MaterialTheme.typography.labelSmall.copy(
                        fontSize = if (compact) 8.4.sp else 10.sp,
                        lineHeight = if (compact) 9.sp else 12.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                color = infoTone,
                maxLines = if (compact) 1 else 2,
                softWrap = !compact,
                overflow = TextOverflow.Clip,
            )
            hintLines.getOrNull(1)?.let { refreshLine ->
                Text(
                    text = refreshLine,
                    style =
                        MaterialTheme.typography.labelSmall.copy(
                            fontSize = if (compact) 8.4.sp else 10.sp,
                            lineHeight = if (compact) 9.sp else 12.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                    color = infoTone,
                    maxLines = if (compact) 1 else 2,
                    softWrap = !compact,
                    overflow = TextOverflow.Clip,
                )
            }
        }
    }
}

@Composable
private fun SmartProfileLegendLine(
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val starColor = foxholeSystemAwareAccentColor()
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SmartProfileLegendRow(
            starCount = 1,
            starColor = starColor,
            label = stringResource(R.string.smart_profile_legend_favorite),
            compact = compact,
        )
        SmartProfileLegendSeparator(compact = compact)
        SmartProfileLegendRow(
            starCount = 2,
            starColor = starColor,
            label = stringResource(R.string.smart_profile_legend_reconnect_recommended),
            compact = compact,
        )
    }
}

@Composable
private fun SmartProfileLegendSeparator(compact: Boolean) {
    Text(
        text = "|",
        modifier = Modifier.padding(horizontal = if (compact) 3.dp else 4.dp),
        style =
            MaterialTheme.typography.labelSmall.copy(
                fontSize = if (compact) 8.2.sp else 9.8.sp,
                lineHeight = if (compact) 9.sp else 11.sp,
                fontWeight = FontWeight.Medium,
            ),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.82f),
        maxLines = 1,
        softWrap = false,
    )
}

@Composable
private fun SmartProfileLegendRow(
    starCount: Int,
    starColor: Color,
    label: String,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(1.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(starCount) { index ->
                Icon(
                    imageVector = Icons.Outlined.Star,
                    contentDescription = null,
                    modifier = Modifier.size(if (compact) 7.dp else 9.dp),
                    tint = starColor,
                )
            }
        }
        Text(
            text = label,
            style =
                MaterialTheme.typography.labelSmall.copy(
                    fontSize = if (compact) 8.4.sp else 10.sp,
                    lineHeight = if (compact) 9.sp else 11.sp,
                    fontWeight = FontWeight.Medium,
                ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SmartProfileMetricsRefreshStatus(
    updatedAt: Long?,
    refreshing: Boolean,
    refreshingProtocolLabel: String?,
    compact: Boolean,
) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = if (compact) 0.dp else 1.dp, bottom = if (compact) 1.dp else 2.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        when {
            refreshing ->
                SmartProfileRefreshingIndicator(
                    protocolLabel = refreshingProtocolLabel ?: stringResource(R.string.auto_connect),
                    compact = compact,
                )
            updatedAt != null ->
                Text(
                    text = stringResource(R.string.smart_profile_metrics_last_updated, formatSmartMetricsUpdatedAgo(updatedAt)),
                    style =
                        MaterialTheme.typography.labelSmall.copy(
                            fontSize = if (compact) 9.sp else 10.sp,
                            lineHeight = if (compact) 10.sp else 11.sp,
                        ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.74f),
                    maxLines = 1,
                    textAlign = TextAlign.Start,
                    overflow = TextOverflow.Ellipsis,
                )
            else ->
                Text(
                    text = stringResource(R.string.smart_profile_metrics_never_updated),
                    style =
                        MaterialTheme.typography.labelSmall.copy(
                            fontSize = if (compact) 9.sp else 10.sp,
                            lineHeight = if (compact) 10.sp else 11.sp,
                        ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.74f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
        }
    }
}

@Composable
private fun SmartProfileRefreshingIndicator(
    protocolLabel: String,
    compact: Boolean,
) {
    val color = foxholeSystemAwareAccentColor(fallback = FoxholeInfoAccent)
    Surface(
        shape = MaterialTheme.shapes.small,
        color = color.copy(alpha = 0.10f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.24f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = if (compact) 5.dp else 6.dp, vertical = if (compact) 2.dp else 3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HomeJellyTriangleLoader(
                color = color,
                indicatorSize = if (compact) 9.dp else 10.dp,
            )
            HomeAnalysisStatusText(
                protocolLabel = protocolLabel,
                tint = color,
                textStyle =
                    MaterialTheme.typography.labelSmall.copy(
                        fontSize = if (compact) 10.5.sp else 11.5.sp,
                        lineHeight = if (compact) 11.sp else 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                modifier =
                    Modifier
                        .offset(y = (-2).dp)
                        .widthIn(max = if (compact) 110.dp else 128.dp),
            )
        }
    }
}

@Composable
private fun SmartProfileMetricsRefreshButton(
    refreshing: Boolean,
    onRefreshMetrics: (() -> Unit)?,
    onCancelRefreshMetrics: (() -> Unit)?,
    compact: Boolean,
) {
    val refreshMetrics = onRefreshMetrics ?: return
    val enabled = !refreshing || onCancelRefreshMetrics != null
    val tone = foxholeSystemAwareAccentColor(fallback = MaterialTheme.colorScheme.primary)
    val label = stringResource(if (refreshing) R.string.disconnect else R.string.refresh)
    val buttonWidth =
        if (refreshing) {
            if (compact) {
                SmartProfileMenuCompactStopButtonWidth
            } else {
                SmartProfileMenuStopButtonWidth
            }
        } else if (compact) {
            SmartProfileMenuCompactRefreshButtonWidth
        } else {
            SmartProfileMenuRefreshButtonWidth
        }
    val buttonHeight =
        if (compact) {
            SmartProfileMenuCompactRefreshButtonHeight
        } else {
            SmartProfileMenuRefreshButtonHeight
        }
    Surface(
        modifier =
            Modifier
                .width(buttonWidth)
                .height(buttonHeight)
                .clip(CircleShape)
                .clickable(enabled = enabled) {
                    if (refreshing) {
                        onCancelRefreshMetrics?.invoke()
                    } else {
                        refreshMetrics()
                    }
                },
        shape = CircleShape,
        color = tone.copy(alpha = if (enabled) 0.14f else 0.06f),
        border = BorderStroke(1.dp, tone.copy(alpha = if (enabled) 0.40f else 0.14f)),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = if (compact) 6.dp else 8.dp),
            horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 5.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (refreshing) Icons.Outlined.Close else Icons.Outlined.Refresh,
                contentDescription =
                    stringResource(
                        if (refreshing) {
                            R.string.smart_profile_metrics_cancel_action
                        } else {
                            R.string.smart_profile_metrics_refresh_action
                        },
                    ),
                modifier = Modifier.size(if (compact) 13.dp else 15.dp),
                tint = tone.copy(alpha = if (enabled) 1f else 0.38f),
            )
            Text(
                text = label,
                style =
                    MaterialTheme.typography.labelSmall.copy(
                        fontSize = if (compact) 9.5.sp else 10.5.sp,
                        lineHeight = if (compact) 10.5.sp else 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                color = tone.copy(alpha = if (enabled) 1f else 0.38f),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun smartProfileCurrentProtocolAccent(
    latencyMs: Long?,
    down: Boolean,
    unavailable: Boolean,
): Color =
    when (classifyVpnLatency(latencyMs = latencyMs, failed = down, unavailable = unavailable || latencyMs == null)) {
        LatencyQuality.FAST,
        LatencyQuality.NORMAL,
        LatencyQuality.SLOW,
        -> SmartProfileCurrentWarningAccent
        LatencyQuality.VERY_SLOW,
        LatencyQuality.FAILED,
        LatencyQuality.UNAVAILABLE,
        -> SmartProfileCurrentDangerAccent
    }

@Composable
private fun SmartProfileProtocolTableHeader(
    compact: Boolean,
    latencyProbeMethod: LatencyProbeMethod,
) {
    Column {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = SmartProfileMenuHorizontalPadding,
                        vertical = if (compact) 5.dp else 6.dp,
                    ),
            horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SmartProfileTableHeaderText(
                text = stringResource(R.string.smart_profile_menu_protocol_column),
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Start,
            )
            SmartProfileTableHeaderText(
                text = stringResource(R.string.smart_profile_menu_server_ping_column),
                modifier = Modifier.width(SmartProfileMetricColumnWidth),
                textAlign = TextAlign.Center,
            )
            SmartProfileTableHeaderText(
                text = smartProfileLatencyColumnLabel(latencyProbeMethod),
                modifier = Modifier.width(SmartProfileMetricColumnWidth),
                textAlign = TextAlign.Center,
            )
            SmartProfileTableHeaderText(
                text = stringResource(R.string.smart_profile_menu_on_column),
                modifier = Modifier.width(SmartProfileOnColumnWidth),
                textAlign = TextAlign.Center,
            )
        }
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = SmartProfileMenuHorizontalPadding),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.34f),
        )
    }
}

@Composable
private fun SmartProfileProtocolStatusHeader(compact: Boolean) {
    Column {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = SmartProfileMenuHorizontalPadding, vertical = if (compact) 5.dp else 6.dp),
            horizontalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SmartProfileTableHeaderText(
                text = stringResource(R.string.smart_profile_menu_protocol_column),
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Start,
            )
            SmartProfileTableHeaderText(
                text = stringResource(R.string.smart_profile_menu_status_column),
                modifier =
                    Modifier.width(
                        if (compact) {
                            SmartProfileCompactStatusColumnWidth
                        } else {
                            SmartProfileStatusColumnWidth
                        },
                    ),
                textAlign = TextAlign.Center,
            )
            SmartProfileTableHeaderText(
                text = stringResource(R.string.smart_profile_menu_dashboard_on_column),
                modifier = Modifier.width(SmartProfileOnColumnWidth),
                textAlign = TextAlign.Center,
            )
        }
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = SmartProfileMenuHorizontalPadding),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.34f),
        )
    }
}

@Composable
private fun SmartProfileTableHeaderText(
    text: String,
    modifier: Modifier,
    textAlign: TextAlign,
) {
    Text(
        text = text,
        modifier = modifier,
        style =
            MaterialTheme.typography.labelSmall.copy(
                fontSize = 8.8.sp,
                lineHeight = 9.6.sp,
                fontWeight = FontWeight.SemiBold,
            ),
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.82f),
        textAlign = textAlign,
        maxLines = 2,
        softWrap = true,
        overflow = TextOverflow.Clip,
    )
}

@Composable
private fun smartProfileLatencyColumnLabel(latencyProbeMethod: LatencyProbeMethod): String =
    stringResource(R.string.smart_profile_menu_latency_column, latencyProbeMethodLabel(latencyProbeMethod))

@Composable
private fun latencyProbeMethodLabel(latencyProbeMethod: LatencyProbeMethod): String =
    stringResource(
        when (latencyProbeMethod) {
            LatencyProbeMethod.HTTP -> R.string.latency_probe_method_http
            LatencyProbeMethod.ICMP -> R.string.latency_probe_method_icmp
            LatencyProbeMethod.TCP -> R.string.latency_probe_method_tcp
        },
    )

@Composable
private fun SmartProfileProtocolMetricsTableRow(
    option: ProfileProtocolOption,
    compact: Boolean,
    included: Boolean,
    recommended: Boolean,
    topRecommended: Boolean,
    favorite: Boolean,
    serverPingMs: Long?,
    serverPingUnavailable: Boolean,
    latencyMs: Long?,
    latencyDown: Boolean,
    latencyUnavailable: Boolean,
    metricsEnabled: Boolean,
    metricsRefreshing: Boolean,
    showTransportBadge: Boolean,
    transportKnown: Boolean,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = if (compact) SmartProfileProtocolAdaptiveCompactRowHeight else SmartProfileProtocolAdaptiveRowHeight)
                .padding(vertical = if (compact) 4.dp else 5.dp),
        horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SmartProfileProtocolCell(
            option = option,
            compact = compact,
            enabled = included,
            recommended = recommended,
            topRecommended = topRecommended,
            favorite = favorite,
            showSelectionBadge = true,
            showTransportBadge = showTransportBadge,
            transportKnown = transportKnown,
            latencyMs = latencyMs,
            latencyDown = latencyDown,
            latencyUnavailable = latencyUnavailable,
            modifier = Modifier.weight(1f),
        )
        if (metricsRefreshing) {
            SmartProfileMetricLoadingCell(
                enabled = metricsEnabled,
                modifier = Modifier.width(SmartProfileMetricColumnWidth),
            )
            SmartProfileMetricLoadingCell(
                enabled = metricsEnabled,
                modifier = Modifier.width(SmartProfileMetricColumnWidth),
            )
        } else {
            SmartProfileMetricCell(
                latencyMs = serverPingMs,
                unavailable = serverPingUnavailable || serverPingMs == null,
                enabled = metricsEnabled,
                modifier = Modifier.width(SmartProfileMetricColumnWidth),
            )
            SmartProfileMetricCell(
                latencyMs = latencyMs,
                down = latencyDown,
                unavailable = latencyUnavailable || latencyMs == null,
                enabled = metricsEnabled,
                modifier = Modifier.width(SmartProfileMetricColumnWidth),
            )
        }
        Box(
            modifier = Modifier.width(SmartProfileOnColumnWidth),
            contentAlignment = Alignment.Center,
        ) {
            SmartProfileOnToggle(included = included, compact = compact)
        }
    }
}

@Composable
private fun SmartProfileProtocolSimpleMenuRow(
    option: ProfileProtocolOption,
    compact: Boolean,
    included: Boolean,
    recommended: Boolean,
    topRecommended: Boolean,
    favorite: Boolean,
    showSelectionBadge: Boolean,
    latencyMs: Long?,
    latencyDown: Boolean,
    latencyUnavailable: Boolean,
    showTransportBadge: Boolean,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(if (compact) SmartProfileProtocolSimpleCompactRowHeight else SmartProfileProtocolSimpleRowHeight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 8.dp),
    ) {
        SmartProfileProtocolCell(
            option = option,
            compact = compact,
            enabled = included,
            recommended = recommended,
            topRecommended = topRecommended,
            favorite = favorite,
            showSelectionBadge = showSelectionBadge,
            showTransportBadge = showTransportBadge,
            transportKnown = false,
            latencyMs = latencyMs,
            latencyDown = latencyDown,
            latencyUnavailable = latencyUnavailable,
            modifier = Modifier.weight(1f),
        )
        SmartProfileStatusPill(
            modifier =
                Modifier.width(
                    if (compact) {
                        SmartProfileCompactStatusColumnWidth
                    } else {
                        SmartProfileStatusColumnWidth
                    },
                ),
            presentation =
                resolveSmartStartProtocolPresentation(
                    included = included,
                    latencyMs = latencyMs,
                    latencyDown = latencyDown,
                    latencyUnavailable = latencyUnavailable,
                ),
            latencyMs = latencyMs,
            latencyDown = latencyDown,
            latencyUnavailable = latencyUnavailable,
            showLatencyDetails = true,
            compact = compact,
        )
        Box(
            modifier = Modifier.width(SmartProfileOnColumnWidth),
            contentAlignment = Alignment.Center,
        ) {
            SmartProfileOnToggle(included = included, compact = compact)
        }
    }
}

@Suppress("CyclomaticComplexMethod")
@Composable
private fun SmartProfileProtocolCell(
    option: ProfileProtocolOption,
    compact: Boolean,
    enabled: Boolean,
    recommended: Boolean,
    topRecommended: Boolean,
    favorite: Boolean,
    showSelectionBadge: Boolean,
    showTransportBadge: Boolean,
    transportKnown: Boolean,
    latencyMs: Long?,
    latencyDown: Boolean,
    latencyUnavailable: Boolean,
    modifier: Modifier = Modifier,
) {
    val contentColor =
        if (enabled) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
    Box(modifier = modifier) {
        Row(
            modifier = Modifier.graphicsLayer(alpha = if (enabled) 1f else 0.54f),
            horizontalArrangement = Arrangement.spacedBy(if (compact) 5.dp else 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProtocolMarkIcon(
                protocol = option.protocolHint,
                compact = compact,
                tintOverride =
                    protocolLatencyIconTint(
                        latencyMs = latencyMs,
                        down = latencyDown,
                        unavailable = latencyUnavailable || latencyMs == null,
                    ),
            )
            Text(
                text = protocolDisplayLabel(option.protocolHint),
                style =
                    MaterialTheme.typography.labelSmall.copy(
                        fontSize = if (compact) 13.sp else 14.sp,
                        lineHeight = if (compact) 14.sp else 15.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                color = contentColor,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
            if (showTransportBadge || transportKnown) {
                SmartProfileTransportBadge(
                    transport = protocolTransportBadge(option.protocolHint),
                    modifier = Modifier.offset(y = if (compact) (-3).dp else (-2).dp),
                )
            }
            val showStatusBadge =
                showSelectionBadge &&
                    enabled &&
                    (favorite || recommended)
            if (showStatusBadge) {
                SmartProfileConditionStars(
                    favorite = favorite,
                    recommended = recommended,
                    topRecommended = topRecommended,
                    compact = compact,
                    modifier = Modifier.offset(y = if (compact) (-3).dp else (-2).dp),
                )
            }
        }
    }
}

@Composable
private fun SmartProfileOnToggle(
    included: Boolean,
    compact: Boolean,
) {
    val tone = foxholeSystemAwareAccentColor()
    if (included) {
        Surface(
            modifier = Modifier.size(if (compact) 17.dp else 18.dp),
            shape = CircleShape,
            color = tone.copy(alpha = 0.16f),
            border = BorderStroke(1.dp, tone.copy(alpha = 0.48f)),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Outlined.Check,
                    contentDescription = null,
                    modifier = Modifier.size(if (compact) 12.dp else 13.dp),
                    tint = tone,
                )
            }
        }
    } else {
        Surface(
            modifier = Modifier.size(if (compact) 14.dp else 15.dp),
            shape = CircleShape,
            color = Color.Transparent,
            border = BorderStroke(1.dp, tone.copy(alpha = 0.42f)),
            content = {},
        )
    }
}

@Composable
private fun SmartProfileTransportBadge(
    transport: SmartProfileTransport,
    modifier: Modifier = Modifier,
) {
    val color =
        when (transport) {
            SmartProfileTransport.TCP,
            SmartProfileTransport.UDP,
            -> foxholeTransportBadgeColor()
        }
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = Color.Transparent,
        border = BorderStroke(1.dp, color.copy(alpha = 0.44f)),
    ) {
        Text(
            text = transport.name,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
            style =
                MaterialTheme.typography.labelSmall.copy(
                    fontSize = 7.sp,
                    lineHeight = 8.sp,
                    fontWeight = FontWeight.Bold,
                ),
            color = color,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun SmartProfileMetricLoadingCell(
    modifier: Modifier = Modifier,
    enabled: Boolean,
) {
    Box(
        modifier = modifier.graphicsLayer(alpha = if (enabled) 1f else 0.58f),
        contentAlignment = Alignment.Center,
    ) {
        FoxholeSkeletonBlock(
            modifier =
                Modifier
                    .width(34.dp)
                    .height(14.dp),
        )
    }
}

@Composable
private fun SmartProfileMetricCell(
    modifier: Modifier = Modifier,
    latencyMs: Long?,
    down: Boolean = false,
    unavailable: Boolean = false,
    enabled: Boolean = true,
) {
    val text =
        when {
            down -> stringResource(R.string.latency_pill_down)
            unavailable -> stringResource(R.string.smart_profile_metric_unavailable)
            latencyMs != null -> stringResource(R.string.latency_pill_value, boundedDisplayLatencyMs(latencyMs))
            else -> stringResource(R.string.smart_profile_metric_unavailable)
        }
    val tone =
        if (!enabled) {
            SmartProfileMetricTone.NEUTRAL
        } else {
            when (classifyVpnLatency(latencyMs = latencyMs, failed = down, unavailable = unavailable || latencyMs == null)) {
                LatencyQuality.FAST -> SmartProfileMetricTone.POSITIVE
                LatencyQuality.NORMAL -> SmartProfileMetricTone.NEUTRAL
                LatencyQuality.SLOW -> SmartProfileMetricTone.WARNING
                LatencyQuality.VERY_SLOW -> SmartProfileMetricTone.VERY_SLOW
                LatencyQuality.FAILED -> SmartProfileMetricTone.DANGER
                LatencyQuality.UNAVAILABLE -> SmartProfileMetricTone.NEUTRAL
            }
        }
    SmartProfileMetricPill(
        text = text,
        tone = tone,
        roundUnavailable = latencyMs == null && !down,
        enabled = enabled,
        modifier = modifier,
    )
}

@Composable
private fun SmartProfileStatusPill(
    modifier: Modifier = Modifier,
    presentation: SmartStartProtocolPresentation,
    latencyMs: Long? = null,
    latencyDown: Boolean = false,
    latencyUnavailable: Boolean = false,
    showLatencyDetails: Boolean = false,
    compact: Boolean,
) {
    SmartProfileMetricPill(
        text =
            if (showLatencyDetails) {
                smartStartProtocolCompactText(
                    presentation = presentation,
                    latencyMs = latencyMs,
                    latencyDown = latencyDown,
                    latencyUnavailable = latencyUnavailable,
                )
            } else {
                smartStartProtocolPresentationText(presentation)
            },
        tone =
            if (showLatencyDetails) {
                smartStartProtocolCompactTone(
                    presentation = presentation,
                    latencyMs = latencyMs,
                    latencyDown = latencyDown,
                    latencyUnavailable = latencyUnavailable,
                )
            } else {
                smartStartProtocolPresentationTone(presentation)
            },
        compact = compact,
        modifier = modifier,
    )
}

@Composable
private fun smartStartProtocolCompactText(
    presentation: SmartStartProtocolPresentation,
    latencyMs: Long?,
    latencyDown: Boolean,
    latencyUnavailable: Boolean,
): String {
    val base = smartStartProtocolPresentationText(presentation)
    if (latencyMs == null || latencyDown || latencyUnavailable) {
        return base
    }
    val quality =
        latencyQualityLabel(
            classifyVpnLatency(
                latencyMs = latencyMs,
                failed = false,
                unavailable = false,
            ),
        )
    return when (presentation.status) {
        SmartStartProtocolStatus.AVAILABLE,
        SmartStartProtocolStatus.SLOW,
        -> quality
        else -> base
    }
}

private fun smartStartProtocolCompactTone(
    presentation: SmartStartProtocolPresentation,
    latencyMs: Long?,
    latencyDown: Boolean,
    latencyUnavailable: Boolean,
): SmartProfileMetricTone {
    if (presentation.status == SmartStartProtocolStatus.DISABLED) {
        return SmartProfileMetricTone.NEUTRAL
    }
    if (latencyDown) {
        return SmartProfileMetricTone.DANGER
    }
    if (latencyMs == null || latencyUnavailable) {
        return smartStartProtocolPresentationTone(presentation)
    }
    return when (classifyVpnLatency(latencyMs = latencyMs, failed = false, unavailable = false)) {
        LatencyQuality.FAST -> SmartProfileMetricTone.POSITIVE
        LatencyQuality.NORMAL -> SmartProfileMetricTone.NEUTRAL
        LatencyQuality.SLOW -> SmartProfileMetricTone.WARNING
        LatencyQuality.VERY_SLOW -> SmartProfileMetricTone.VERY_SLOW
        LatencyQuality.UNAVAILABLE,
        LatencyQuality.FAILED,
        -> smartStartProtocolPresentationTone(presentation)
    }
}

@Composable
internal fun latencyQualityLabel(quality: LatencyQuality): String =
    stringResource(
        when (quality) {
            LatencyQuality.FAST -> R.string.latency_quality_fast
            LatencyQuality.NORMAL -> R.string.latency_quality_normal
            LatencyQuality.SLOW -> R.string.latency_quality_slow
            LatencyQuality.VERY_SLOW -> R.string.latency_quality_very_slow
            LatencyQuality.UNAVAILABLE -> R.string.latency_quality_unavailable
            LatencyQuality.FAILED -> R.string.latency_quality_failed
        },
    )

@Composable
private fun smartStartProtocolPresentationText(presentation: SmartStartProtocolPresentation): String =
    when (presentation.status) {
        SmartStartProtocolStatus.AVAILABLE -> stringResource(R.string.smart_start_protocol_status_available)
        SmartStartProtocolStatus.SLOW -> stringResource(R.string.smart_start_protocol_status_slow)
        SmartStartProtocolStatus.RECENTLY_FAILED -> stringResource(R.string.latency_pill_down)
        SmartStartProtocolStatus.UNAVAILABLE -> stringResource(R.string.latency_pill_unavailable)
        SmartStartProtocolStatus.NO_DATA -> stringResource(R.string.smart_start_protocol_status_no_data)
        SmartStartProtocolStatus.DISABLED -> stringResource(R.string.smart_start_protocol_status_disabled)
    }

private fun smartStartProtocolPresentationTone(presentation: SmartStartProtocolPresentation): SmartProfileMetricTone =
    when (presentation.status) {
        SmartStartProtocolStatus.AVAILABLE -> SmartProfileMetricTone.NEUTRAL
        SmartStartProtocolStatus.SLOW -> SmartProfileMetricTone.WARNING
        SmartStartProtocolStatus.RECENTLY_FAILED -> SmartProfileMetricTone.DANGER
        SmartStartProtocolStatus.UNAVAILABLE,
        SmartStartProtocolStatus.NO_DATA,
        SmartStartProtocolStatus.DISABLED,
        -> SmartProfileMetricTone.NEUTRAL
    }

@Composable
internal fun SmartProfileMetricPill(
    text: String,
    tone: SmartProfileMetricTone,
    modifier: Modifier = Modifier,
    roundUnavailable: Boolean = false,
    compact: Boolean = true,
    enabled: Boolean = true,
) {
    val color =
        if (!enabled) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            when (tone) {
                SmartProfileMetricTone.POSITIVE -> FoxholePositiveAccent
                SmartProfileMetricTone.WARNING -> Color(0xFFE0B84A)
                SmartProfileMetricTone.VERY_SLOW -> Color(0xFFE28131)
                SmartProfileMetricTone.DANGER -> Color(0xFFC95353)
                SmartProfileMetricTone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
            }
        }
    val contentAlpha = if (enabled) 1f else 0.58f
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier =
                if (roundUnavailable) {
                    Modifier.size(SmartProfileMetricUnavailableSize)
                } else {
                    Modifier
                },
            shape = if (roundUnavailable) CircleShape else MaterialTheme.shapes.medium,
            color = color.copy(alpha = if (!enabled) 0.06f else if (tone == SmartProfileMetricTone.NEUTRAL) 0.10f else 0.16f),
            border = BorderStroke(1.dp, color.copy(alpha = if (enabled) 0.32f else 0.18f)),
        ) {
            val textStyle =
                MaterialTheme.typography.labelSmall.copy(
                    fontSize = if (compact) 9.sp else 10.sp,
                    lineHeight = if (compact) 10.sp else 11.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            if (roundUnavailable) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = text,
                        style = textStyle,
                        color = color.copy(alpha = contentAlpha),
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
                Text(
                    text = text,
                    modifier = Modifier.padding(horizontal = if (compact) 6.dp else 8.dp, vertical = if (compact) 2.dp else 3.dp),
                    style = textStyle,
                    color = color.copy(alpha = contentAlpha),
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun protocolTransportBadge(protocol: ProtocolHint): SmartProfileTransport =
    when (protocol) {
        ProtocolHint.HYSTERIA2,
        ProtocolHint.WIREGUARD,
        -> SmartProfileTransport.UDP
        ProtocolHint.VLESS,
        ProtocolHint.TROJAN,
        ProtocolHint.SHADOWSOCKS,
        ProtocolHint.VMESS,
        ProtocolHint.OUTLINE,
        ProtocolHint.SING_BOX,
        ProtocolHint.UNKNOWN,
        -> SmartProfileTransport.TCP
    }

private enum class SmartProfileTransport {
    TCP,
    UDP,
}

internal enum class SmartProfileMetricTone {
    POSITIVE,
    WARNING,
    VERY_SLOW,
    DANGER,
    NEUTRAL,
}

private val SmartProfileMenuHorizontalPadding = 8.dp
private val SmartProfileMenuCompactMinWidth = 238.dp
private val SmartProfileMenuCompactMaxWidth = 340.dp
private val SmartProfileMenuDetailedMinWidth = 268.dp
private val SmartProfileMenuMaxWidth = 388.dp
private val SmartProfileMenuRefreshButtonWidth = 96.dp
private val SmartProfileMenuCompactRefreshButtonWidth = 88.dp
private val SmartProfileMenuStopButtonWidth = 78.dp
private val SmartProfileMenuCompactStopButtonWidth = 68.dp
private val SmartProfileMenuRefreshButtonHeight = 34.dp
private val SmartProfileMenuCompactRefreshButtonHeight = 30.dp
private val SmartProfileHintIconSize = 12.dp
private val SmartProfileHintIconGap = 3.dp
private val SmartProfileHintBasisMaxWidth = 246.dp
private val SmartProfileProtocolAdaptiveCompactRowHeight = 44.dp
private val SmartProfileProtocolAdaptiveRowHeight = 56.dp
private val SmartProfileProtocolSimpleCompactRowHeight = 36.dp
private val SmartProfileProtocolSimpleRowHeight = 42.dp
private val SmartProfileMetricUnavailableSize = 18.dp
private val SmartProfileMetricColumnWidth = 52.dp
private val SmartProfileStatusColumnWidth = 88.dp
private val SmartProfileCompactStatusColumnWidth = 78.dp
private val SmartProfileOnColumnWidth = 24.dp
private val SmartProfileCurrentWarningAccent = Color(0xFFE28131)
private val SmartProfileCurrentDangerAccent = Color(0xFFC95353)

@Composable
private fun formatSmartMetricsUpdatedAgo(updatedAt: Long): String {
    val elapsedMs = (System.currentTimeMillis() - updatedAt).coerceAtLeast(0L)
    val minute = 60_000L
    val hour = 60L * minute
    val day = 24L * hour
    val week = 7L * day
    return when {
        elapsedMs < hour ->
            stringResource(R.string.smart_profile_metrics_ago_minutes, max(1L, elapsedMs / minute))
        elapsedMs < day ->
            stringResource(R.string.smart_profile_metrics_ago_hours, max(1L, elapsedMs / hour))
        elapsedMs < week ->
            stringResource(R.string.smart_profile_metrics_ago_days, max(1L, elapsedMs / day))
        else ->
            stringResource(R.string.smart_profile_metrics_ago_weeks, max(1L, elapsedMs / week))
    }
}
